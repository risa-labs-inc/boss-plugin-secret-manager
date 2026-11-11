package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.FakeSecretDataProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class VaultPaymentGuardTest {
    private fun entry(id: String, website: String, password: String, tags: List<String>) =
        SecretEntryData(id = id, website = website, username = "u", password = password, tags = tags, createdAt = "", updatedAt = "")

    @Test
    fun cardsAndCodesCannotBeRevealedButLoginsCan() = runTest {
        val rows = listOf(
            entry("card", "card/visa-4242", "4242424242424242", listOf("kind:card")),
            entry("untagged", "card/visa-4242", "4242424242424242", emptyList()),
            entry("cvv", "fluck/w/vault/card-4242-cvv", "sealed", listOf("kind:cvv")),
            entry("login", "https://example.test", "pw", listOf("kind:password")),
        )
        val vm = SecretManagerViewModel(
            secretDataProvider = FakeSecretDataProvider(rows),
            supabaseDataProvider = null,
            pluginStoreApiKeyProvider = null,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            authDataProvider = null,
        ).also { it.initialize() }
        advanceUntilIdle()
        assertEquals(4, vm.state.secrets.size)

        rows.forEach { vm.togglePasswordVisibility(it.id) }
        assertEquals(setOf("login"), vm.state.visiblePasswordIds)
    }

}
