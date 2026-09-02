package ai.rever.boss.plugin.dynamic.secretmanager.ai

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins [isProviderListed] — the rule deciding which providers appear in the compact
 * "your providers" list versus only being reachable through "Add provider".
 *
 * The one non-obvious case is a keyless provider: [ProviderConnection.isConfigured] is
 * unconditionally true for one (there is no credential to wait on), so listing has to key
 * off something else or Ollama would appear for every user from first launch.
 */
class ProviderListingTest {
    private fun connection(providerId: String, configured: Boolean): ProviderConnection =
        ProviderConnection(
            providerId = providerId,
            apiKey = if (configured) "sk-test" else "",
            source = if (configured) CredentialSource.STORED else CredentialSource.NONE,
        )

    @Test
    fun `an ordinary provider is listed exactly when it is configured`() {
        val descriptor = ProviderRegistry.find(ProviderRegistry.OPENROUTER)!!

        assertTrue(isProviderListed(descriptor, connection(descriptor.id, configured = true), CatalogState.NotConfigured))
        assertFalse(isProviderListed(descriptor, connection(descriptor.id, configured = false), CatalogState.NotConfigured))
    }

    @Test
    fun `a keyless provider is not listed until its catalog has actually loaded`() {
        val ollama = ProviderRegistry.find(ProviderRegistry.OLLAMA)!!
        val alwaysConfigured = connection(ollama.id, configured = false) // no key stored, still isConfigured == true

        assertTrue(alwaysConfigured.isConfigured, "requiresApiKey = false should be unconditionally configured")
        assertFalse(isProviderListed(ollama, alwaysConfigured, CatalogState.NotConfigured))
        assertFalse(isProviderListed(ollama, alwaysConfigured, CatalogState.Loading))
        assertFalse(
            isProviderListed(
                ollama,
                alwaysConfigured,
                CatalogState.Failed(message = "offline"),
            ),
        )
        assertTrue(
            isProviderListed(
                ollama,
                alwaysConfigured,
                CatalogState.Loaded(models = listOf(AiModel(id = "llama3.2:latest", displayName = "llama3.2:latest")), fetchedAtEpochMs = 0L),
            ),
        )
    }
}
