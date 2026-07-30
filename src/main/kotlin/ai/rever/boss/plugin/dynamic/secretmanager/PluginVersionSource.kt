package ai.rever.boss.plugin.dynamic.secretmanager

/**
 * Resolves this plugin's own version from its bundled `plugin.json`.
 *
 * Extracted from [SecretManagerDynamicPlugin] so the *selection* rules are testable without a
 * classloader. They have been wrong twice, both times reporting a plausible-looking value:
 *
 * 1. `javaClass.package?.implementationVersion` returns null under a plain `URLClassLoader`
 *    (what the host's `PluginClassLoader` extends), so the plugin reported `"unknown"` for a
 *    whole release even though `buildPluginJar` does write `Implementation-Version`.
 * 2. `getResourceAsStream` takes the *first* match, and every BOSS plugin ships this resource
 *    at the same path — so a neighbour's manifest could answer instead.
 *
 * Fixing (2) with a `pluginId` filter alone still left a hole: a copy of *our own* manifest
 * bundled or staled on the host classpath matches that filter too, and `getResources` is
 * parent-first. Hence [pickOwnManifest], which prefers the jar the class itself came from and
 * falls back to the `pluginId` match so IDE and test runs still resolve.
 */
internal object PluginVersionSource {
    const val PLUGIN_ID = "ai.rever.boss.plugin.dynamic.secretmanager"

    const val MANIFEST_RESOURCE = "META-INF/boss-plugin/plugin.json"

    /** Reported when no manifest and no manifest attribute can be found (IDE, bare classes). */
    const val UNKNOWN = "unknown"

    /**
     * Matches `"version": "1.2.6"` without pulling a JSON parser into class init.
     *
     * Loose on purpose, and safe because it is only ever applied to a document already
     * confirmed to be ours by [pluginIdPattern].
     */
    private val versionPattern = Regex(""""version"\s*:\s*"([^"]+)"""")

    private val pluginIdPattern = Regex(""""pluginId"\s*:\s*"${Regex.escape(PLUGIN_ID)}"""")

    /**
     * One candidate manifest: where it came from, and what it said.
     *
     * [url] is the resource URL as a string, e.g.
     * `jar:file:/…/boss-plugin-secret-manager-1.2.6.jar!/META-INF/boss-plugin/plugin.json`.
     */
    data class Candidate(
        val url: String,
        val text: String,
    )

    /**
     * Pick the manifest that belongs to this plugin.
     *
     * [ownRoot] is the jar (or classes directory) the plugin class itself was loaded from, as
     * a string prefix. A candidate under that root wins outright — that is the only manifest
     * guaranteed to be ours rather than a copy of ours. Otherwise the first candidate naming
     * our `pluginId` is used, which is what makes IDE and test runs work, where resources come
     * from a directory rather than the jar.
     */
    fun pickOwnManifest(
        candidates: List<Candidate>,
        ownRoot: String?,
    ): Candidate? {
        val ours = candidates.filter { pluginIdPattern.containsMatchIn(it.text) }
        if (ours.isEmpty()) return null
        return ownRoot?.let { root -> ours.firstOrNull { it.url.startsWith(root) } } ?: ours.first()
    }

    /** The version declared in [Candidate.text], or null if it declares none. */
    fun versionIn(candidate: Candidate): String? =
        versionPattern.find(candidate.text)?.groupValues?.get(1)?.takeIf { it.isNotBlank() }

    /**
     * Strips a resource URL back to the archive or directory root it came from, so candidates
     * can be compared against it: `jar:file:/x/y.jar!/A/B.class` -> `jar:file:/x/y.jar!/`, and
     * `file:/x/classes/A/B.class` -> `file:/x/classes/`.
     */
    fun rootOf(
        resourceUrl: String?,
        resourcePath: String,
    ): String? {
        if (resourceUrl == null) return null
        val jarSeparator = resourceUrl.indexOf("!/")
        if (jarSeparator >= 0) return resourceUrl.substring(0, jarSeparator + 2)
        return resourceUrl.removeSuffix(resourcePath).takeIf { it != resourceUrl }
    }

    /**
     * Read the version from the classpath.
     *
     * plugin.json first, the jar manifest second, [UNKNOWN] only when neither is available.
     */
    fun read(): String {
        val fromResource =
            runCatching {
                val loader = SecretManagerDynamicPlugin::class.java.classLoader
                val ownClassPath = "${PLUGIN_ID.replace('.', '/')}/SecretManagerDynamicPlugin.class"
                val ownRoot =
                    rootOf(
                        SecretManagerDynamicPlugin::class.java
                            .getResource("SecretManagerDynamicPlugin.class")
                            ?.toString(),
                        ownClassPath,
                    )

                val candidates =
                    loader
                        ?.getResources(MANIFEST_RESOURCE)
                        ?.asSequence()
                        .orEmpty()
                        .mapNotNull { url ->
                            runCatching { Candidate(url.toString(), url.readText()) }.getOrNull()
                        }.toList()

                pickOwnManifest(candidates, ownRoot)?.let { versionIn(it) }
            }.getOrNull()

        return fromResource
            ?: SecretManagerDynamicPlugin::class.java.`package`?.implementationVersion
            ?: UNKNOWN
    }
}
