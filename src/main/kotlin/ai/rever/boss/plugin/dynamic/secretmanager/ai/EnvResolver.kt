package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import java.io.File

/**
 * Resolves provider API keys supplied through the environment.
 *
 * Ported from the host, which used to own this. Packaged DMG/MSI apps do not inherit
 * a login shell's environment, so a plain `System.getenv` is not enough — hence the
 * `launchctl` and env-file fallbacks.
 *
 * Everything this returns is **read-only**. The previous host implementation seeded
 * its editable key fields from this resolver and then wrote them all back on save,
 * which persisted environment-supplied keys into a plaintext file on disk. Keys from
 * here are surfaced as [CredentialSource.ENVIRONMENT] and never handed to the
 * credential store.
 */
class EnvResolver(
    private val bossRootDir: File = defaultBossRootDir(),
) {
    private val logger = BossLogger.forComponent("AiEnvResolver")

    /**
     * First non-blank value among [names], or null.
     *
     * Providers list more than one variable where both are in common use (Google
     * accepts `GEMINI_API_KEY` and `GOOGLE_API_KEY`), and the order in the registry
     * is the priority order.
     */
    fun resolve(names: List<String>): String? =
        names.firstNotNullOfOrNull { name -> lookup(name)?.takeIf { it.isNotBlank() } }

    /** Which variable actually supplied a value, for display next to the field. */
    fun resolveSourceName(names: List<String>): String? =
        names.firstOrNull { !lookup(it).isNullOrBlank() }

    private fun lookup(name: String): String? =
        fromProcessEnv(name)
            ?: fromSystemProperty(name)
            ?: fromLaunchctl(name)
            ?: fromEnvFile(name)

    private fun fromProcessEnv(name: String): String? = System.getenv(name)?.takeIf { it.isNotBlank() }

    private fun fromSystemProperty(name: String): String? = System.getProperty(name)?.takeIf { it.isNotBlank() }

    /**
     * `launchctl getenv` picks up machine-wide variables set with `launchctl setenv`,
     * which is how some users make keys visible to GUI apps on macOS.
     */
    private fun fromLaunchctl(name: String): String? {
        if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return null
        return runCatching {
            val process = ProcessBuilder("launchctl", "getenv", name).start()
            val output = process.inputStream.bufferedReader().readText().trim()
            process.waitFor()
            if (process.exitValue() == 0) output.takeIf { it.isNotBlank() } else null
        }.getOrNull()
    }

    /**
     * `~/.boss/env_vars` — `KEY=value` lines, `#` comments. The documented workaround
     * for packaged builds that see no shell environment at all.
     */
    private fun fromEnvFile(name: String): String? {
        val file = File(bossRootDir, ENV_FILE_NAME)
        return runCatching {
            if (!file.exists()) return@runCatching null
            file
                .readLines()
                .asSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val parts = line.split("=", limit = 2)
                    if (parts.size == 2) parts[0].trim() to parts[1].trim() else null
                }.firstOrNull { it.first == name }
                ?.second
                ?.takeIf { it.isNotBlank() }
        }.onFailure {
            logger.warn(
                LogCategory.SYSTEM,
                "Could not read env_vars file",
                mapOf("exception" to (it::class.simpleName ?: "Exception")),
            )
        }.getOrNull()
    }

    companion object {
        private const val ENV_FILE_NAME = "env_vars"

        /**
         * Mirrors the host's BossDirectories: `~/.boss`, or `~/.boss_debug` in dev
         * mode. That class lives in a host module that is not on the plugin
         * classpath, so the rule is replicated here — it must stay in step, or the
         * env file and the legacy-settings import would look in the wrong root.
         */
        fun defaultBossRootDir(): File {
            val devMode =
                isTruthy(System.getProperty("boss.dev.mode")) || isTruthy(System.getenv("BOSS_DEV_MODE"))
            val name = if (devMode) ".boss_debug" else ".boss"
            return File(System.getProperty("user.home"), name)
        }

        private fun isTruthy(value: String?): Boolean {
            val v = value?.trim()?.lowercase() ?: return false
            return v == "true" || v == "1" || v == "yes"
        }
    }
}
