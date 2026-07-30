package ai.rever.boss.plugin.dynamic.secretmanager.ai

import ai.rever.boss.plugin.logging.BossLogger
import ai.rever.boss.plugin.logging.LogCategory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Optional
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

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
    /**
     * The process environment, injectable so tests can be hermetic.
     *
     * Production behaviour is unchanged. Tests need this because the file is only the
     * *last* source consulted: a developer with `OPENAI_API_KEY` or `TOGETHER_API_KEY`
     * exported — likely for anyone working on this feature — otherwise saw the real value
     * win over the fixture and several credential-store tests fail. CI passed only because
     * it happens to export none of them.
     */
    private val processEnv: (String) -> String? = System::getenv,
    /** As [processEnv], for `-D` system properties. */
    private val systemProperty: (String) -> String? = System::getProperty,
    /**
     * Whether to consult `launchctl getenv`. Off in tests: it really spawns a process on
     * macOS, which is both slow and another route for the host environment to leak in.
     */
    private val useLaunchctl: Boolean = true,
) {
    private val logger = BossLogger.forComponent("AiEnvResolver")

    /**
     * Memoised per variable name, including misses.
     *
     * [lookup] can spawn `launchctl` and read a file, and it is called for every
     * provider on every credential load — a clean machine means all misses, so without
     * this a single `loadAll()` cost roughly nine process spawns and nine file reads,
     * and that happens again on every save and every model selection. Env vars don't
     * change under a running process; the env file can, hence [invalidate].
     */
    private val cache = ConcurrentHashMap<String, Optional<String>>()

    /**
     * First non-blank value among [names], or null.
     *
     * Providers list more than one variable where both are in common use (Google
     * accepts `GEMINI_API_KEY` and `GOOGLE_API_KEY`), and the order in the registry
     * is the priority order.
     *
     * Suspending because a cache miss does process and file I/O, which must not land on
     * the UI dispatcher — the plugin scope falls back to `Dispatchers.Main`, and the
     * `launchctl` path exists precisely for packaged macOS builds.
     */
    suspend fun resolve(names: List<String>): String? =
        names.firstNotNullOfOrNull { name -> lookup(name)?.takeIf { it.isNotBlank() } }

    /** Which variable actually supplied a value, for display next to the field. */
    suspend fun resolveSourceName(names: List<String>): String? =
        names.firstOrNull { !lookup(it).isNullOrBlank() }

    /** Drop memoised results, e.g. after the user edits `~/.boss/env_vars`. */
    fun invalidate() = cache.clear()

    private suspend fun lookup(name: String): String? {
        cache[name]?.let { return it.orElse(null) }

        // Cheap sources first, and only enter IO when they miss.
        val quick = fromProcessEnv(name) ?: fromSystemProperty(name)
        if (quick != null) {
            cache[name] = Optional.of(quick)
            return quick
        }

        val slow =
            withContext(Dispatchers.IO) {
                (if (useLaunchctl) fromLaunchctl(name) else null) ?: fromEnvFile(name)
            }
        cache[name] = Optional.ofNullable(slow)
        return slow
    }

    private fun fromProcessEnv(name: String): String? = processEnv(name)?.takeIf { it.isNotBlank() }

    private fun fromSystemProperty(name: String): String? = systemProperty(name)?.takeIf { it.isNotBlank() }

    /**
     * `launchctl getenv` picks up machine-wide variables set with `launchctl setenv`,
     * which is how some users make keys visible to GUI apps on macOS.
     */
    private fun fromLaunchctl(name: String): String? {
        if (!System.getProperty("os.name").orEmpty().contains("Mac", ignoreCase = true)) return null
        return runCatching {
            val process = ProcessBuilder("launchctl", "getenv", name).start()
            // Wait BEFORE reading. readText() blocks until stdout closes, so reading first
            // means a wedged launchctl hangs here and the timeout below can never fire —
            // which is the exact case this is supposed to bound. Credential resolution
            // awaits this chain at registration, so a hang costs the whole session.
            val exited = process.waitFor(LAUNCHCTL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
                return@runCatching null
            }
            if (process.exitValue() != 0) return@runCatching null
            process.inputStream.bufferedReader().readText().trim().takeIf { it.isNotBlank() }
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
        private const val LAUNCHCTL_TIMEOUT_SECONDS = 2L

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
