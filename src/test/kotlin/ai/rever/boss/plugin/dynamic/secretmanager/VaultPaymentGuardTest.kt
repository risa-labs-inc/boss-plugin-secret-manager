package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.CreateSecretRequestData
import ai.rever.boss.plugin.api.SecretEntryData
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.text.AnnotatedString
import ai.rever.boss.plugin.dynamic.secretmanager.ai.FakeSecretDataProvider
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        val clipboard = FakeClipboard()
        rows.filter { it.id != "login" }.forEach { vm.copyPasswordToClipboard(it, clipboard) }
        assertNull(clipboard.getText())
        vm.copyPasswordToClipboard(rows.last(), clipboard)
        assertEquals("pw", clipboard.getText()?.text)
    }

    @Test
    fun creatingACardSwitchesTheFilterToCards() = runTest {
        val vm = SecretManagerViewModel(
            secretDataProvider = FakeSecretDataProvider(emptyList()),
            supabaseDataProvider = null,
            pluginStoreApiKeyProvider = null,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            authDataProvider = null,
        )
        val card = VaultRows.newCard("4242424242424242", "08/29", "J")!!
        vm.createSecret(CreateSecretRequestData(card.website, card.username, card.password, card.notes, tags = card.tags))
        advanceUntilIdle()
        assertEquals(VaultKind.CARD, vm.vaultKind)
        assertTrue(vm.vaultKindChosen)
    }

    private class FakeClipboard : ClipboardManager {
        private var stored: AnnotatedString? = null
        override fun setText(annotatedString: AnnotatedString) { stored = annotatedString }
        override fun getText(): AnnotatedString? = stored
    }
}
