package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.QueryFilter
import ai.rever.boss.plugin.api.QueryRange
import ai.rever.boss.plugin.api.PaginatedSecretsData
import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.SecretEntryData
import ai.rever.boss.plugin.api.SupabaseDataProvider
import ai.rever.boss.plugin.api.UserData
import ai.rever.boss.plugin.dynamic.secretmanager.ai.FakeSecretDataProvider
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers `canShareWithRoles`, which decides whether the share dialog offers role targets.
 *
 * A role share reaches every holder of that role, and `user` is a descendant of every
 * role, so a role target is the one control in this panel that can publish a credential
 * deployment-wide. Migration 20260809000000 refuses it server-side without
 * `secret.share.role`; this flag keeps the control that cannot work off the screen.
 *
 * The permission is COLLECTED, not read once, because the panel is built as soon as the
 * plugin registers and that can precede the claim landing. These tests pin the late
 * arrival and the revocation, which a one-shot read would both get wrong.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RoleShareGateTest {

    @Test
    fun `a user without the permission cannot share with roles`() = runTest {
        val (vm, _) = viewModel(this, permissions = setOf("secret.read"))
        advanceUntilIdle()

        assertFalse(vm.state.canShareWithRoles)
    }

    @Test
    fun `a user holding the permission can`() = runTest {
        val (vm, _) = viewModel(this, permissions = setOf("secret.read", "secret.share.role"))
        advanceUntilIdle()

        assertTrue(vm.state.canShareWithRoles)
    }

    @Test
    fun `an admin can without holding it explicitly`() = runTest {
        val (vm, _) = viewModel(this, permissions = emptySet(), isAdmin = true)
        advanceUntilIdle()

        assertTrue(vm.state.canShareWithRoles, "hasPermission answers true for an admin")
    }

    /**
     * The panel is constructed before the session's claim is necessarily available. A
     * one-shot read in initialize() leaves an admin looking at a hidden Roles tab until
     * they close and reopen the panel.
     */
    @Test
    fun `a permission arriving after initialize is picked up`() = runTest {
        val (vm, auth) = viewModel(this, permissions = emptySet())
        advanceUntilIdle()
        assertFalse(vm.state.canShareWithRoles, "precondition: starts closed")

        auth.grant("secret.share.role")
        advanceUntilIdle()

        assertTrue(vm.state.canShareWithRoles)
    }

    /**
     * The `isAdmin` half of the combine. `an admin can without holding it explicitly`
     * does NOT cover this: it sets isAdmin before initialize(), so `userPermissions`'
     * first emission already carries the answer and collecting that flow alone passes.
     * This is the scenario the combine exists for, and it is the mutation that survives
     * without it.
     */
    @Test
    fun `an admin claim arriving after initialize is picked up`() = runTest {
        val (vm, auth) = viewModel(this, permissions = emptySet())
        advanceUntilIdle()
        assertFalse(vm.state.canShareWithRoles, "precondition: starts closed")

        auth.promote()
        advanceUntilIdle()

        assertTrue(vm.state.canShareWithRoles)
    }

    /** Closing the panel must stop the collector; nothing else in the VM outlives it. */
    @Test
    fun `dispose stops tracking the permission`() = runTest {
        val (vm, auth) = viewModel(this, permissions = emptySet())
        advanceUntilIdle()

        vm.dispose()
        auth.grant("secret.share.role")
        advanceUntilIdle()

        assertFalse(vm.state.canShareWithRoles, "a disposed ViewModel must not still be collecting")
    }

    @Test
    fun `a revoked permission closes the gate again`() = runTest {
        val (vm, auth) = viewModel(this, permissions = setOf("secret.share.role"))
        advanceUntilIdle()
        assertTrue(vm.state.canShareWithRoles, "precondition: starts open")

        auth.revoke("secret.share.role")
        advanceUntilIdle()

        assertFalse(vm.state.canShareWithRoles)
    }

    /**
     * The Tab appears the moment the flag flips, so a claim landing while the dialog is
     * already open must also fetch the roles behind it. Otherwise the user clicks into a
     * pane with no data, no spinner and no empty state, recoverable only by reopening.
     */
    @Test
    fun `a permission arriving with the dialog open fetches the roles`() = runTest {
        val supabase = RecordingSupabase()
        val (vm, auth) = viewModel(this, permissions = emptySet(), supabase = supabase)
        advanceUntilIdle()

        vm.showShareDialog(SECRET)
        advanceUntilIdle()
        assertEquals(0, supabase.roleSelects, "precondition: no role fetch without the permission")

        auth.grant("secret.share.role")
        advanceUntilIdle()

        assertEquals(1, supabase.roleSelects, "the newly visible Roles tab must have its data")
    }

    /** Without the permission the fetch must not happen at all - that is the other half. */
    @Test
    fun `opening the dialog without the permission does not fetch roles`() = runTest {
        val supabase = RecordingSupabase()
        val (vm, _) = viewModel(this, permissions = emptySet(), supabase = supabase)
        advanceUntilIdle()

        vm.showShareDialog(SECRET)
        advanceUntilIdle()

        assertEquals(0, supabase.roleSelects)
    }

    /**
     * The security-relevant half of dispose: SecretEntryData carries the decrypted
     * password, so a closed panel must not still be holding the list.
     */
    @Test
    fun `dispose clears the decrypted secrets`() = runTest {
        val (vm, _) = viewModel(this, permissions = emptySet(), secrets = FakeSecretDataProvider(listOf(SECRET)))
        advanceUntilIdle()
        assertEquals(1, vm.state.secrets.size, "precondition: the list is loaded")

        vm.dispose()

        assertEquals(emptyList(), vm.state.secrets)
        assertNull(vm.state.selectedSecret)
    }

    /**
     * Cancelling the collector is not enough: createSecret/updateSecret call loadSecrets()
     * on success, which would refill the plaintext list on a ViewModel nobody can see.
     */
    @Test
    fun `a load that lands after dispose does not refill the list`() = runTest {
        val (vm, _) = viewModel(this, permissions = emptySet(), secrets = FakeSecretDataProvider(listOf(SECRET)))
        advanceUntilIdle()

        vm.dispose()
        vm.loadSecrets()
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.secrets, "a disposed ViewModel must not reload")
    }

    /**
     * The case the entry guard alone does NOT cover, and the one that actually happens.
     *
     * `loadSecrets()` launches without assigning `loadJob`, so `dispose()`'s cancel cannot
     * reach it. Panel opens, `initialize()` starts the load, the user closes before it
     * returns - and the response would write the decrypted list onto a disposed ViewModel.
     * `a load that lands after dispose does not refill the list` misses this: it disposes
     * first and calls `loadSecrets()` after, exercising only the entry check.
     */
    @Test
    fun `a load already in flight at dispose does not refill the list`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val secrets = GatedSecrets(SECRET, gate)
        val (vm, _) = viewModel(this, permissions = emptySet(), secrets = secrets)
        advanceUntilIdle()
        assertEquals(emptyList(), vm.state.secrets, "precondition: the load has not returned")

        vm.dispose()
        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(emptyList(), vm.state.secrets, "an in-flight load must not land after dispose")
    }

    /** The same fake must genuinely deliver when nothing disposes - else the test above is vacuous. */
    @Test
    fun `the gated load does populate when not disposed`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val (vm, _) = viewModel(this, permissions = emptySet(), secrets = GatedSecrets(SECRET, gate))
        advanceUntilIdle()

        gate.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, vm.state.secrets.size)
    }

    /** A typed-but-unsaved provider key must not survive the panel either. */
    @Test
    fun `dispose clears a typed AI provider key`() = runTest {
        val (vm, _) = viewModel(this, permissions = emptySet())
        advanceUntilIdle()
        vm.setAiProviderKeyDraft("sk-typed-but-never-saved")
        assertEquals("sk-typed-but-never-saved", vm.state.aiProviderKeyDraft)

        vm.dispose()

        assertEquals("", vm.state.aiProviderKeyDraft)
    }

    /** A host too old to supply the provider must fail closed, not open. */
    @Test
    fun `a null auth provider fails closed`() = runTest {
        val vm = SecretManagerViewModel(
            secretDataProvider = null,
            supabaseDataProvider = null,
            pluginStoreApiKeyProvider = null,
            scope = TestScope(StandardTestDispatcher(testScheduler)),
            authDataProvider = null,
        ).also { it.initialize() }
        advanceUntilIdle()

        assertFalse(vm.state.canShareWithRoles)
    }

    @Test
    fun `the permission string matches the one the migration grants`() {
        // A drift here is silent: the tab would simply never appear for anyone but an
        // admin, who passes on isAdmin rather than on the name.
        assertEquals("secret.share.role", PERMISSION_SHARE_WITH_ROLE)
    }

    // ---------------------------------------------------------------------

    private fun viewModel(
        test: TestScope,
        permissions: Set<String>,
        isAdmin: Boolean = false,
        secrets: SecretDataProvider? = null,
        supabase: SupabaseDataProvider? = null,
    ): Pair<SecretManagerViewModel, FakeAuth> {
        val auth = FakeAuth(permissions, isAdmin)
        val vm = SecretManagerViewModel(
            secretDataProvider = secrets,
            supabaseDataProvider = supabase,
            pluginStoreApiKeyProvider = null,
            scope = TestScope(StandardTestDispatcher(test.testScheduler)),
            authDataProvider = auth,
        ).also { it.initialize() }
        return vm to auth
    }

    /** Holds the first read until [gate] completes, so a dispose can land mid-flight. */
    private class GatedSecrets(
        private val secret: SecretEntryData,
        private val gate: CompletableDeferred<Unit>,
    ) : SecretDataProvider by FakeSecretDataProvider(emptyList()) {
        override suspend fun getUserSecrets(limit: Int, offset: Int): Result<PaginatedSecretsData> {
            gate.await()
            return Result.success(PaginatedSecretsData(listOf(secret), hasMore = false))
        }
    }

    /** Counts only the `roles` select; the share dialog also loads users. */
    private class RecordingSupabase : SupabaseDataProvider {
        var roleSelects = 0
            private set

        override suspend fun select(
            table: String,
            columns: String,
            filters: List<QueryFilter>,
            range: QueryRange?,
        ): Result<String> {
            if (table == "roles") roleSelects++
            return Result.success("[]")
        }

        override suspend fun rpc(function: String, parameters: String): Result<String> =
            Result.success("[]")
    }

    private companion object {
        val SECRET = SecretEntryData(
            id = "s1",
            website = "github.com",
            username = "someone",
            password = "hunter2",
            createdAt = "2026-08-09T00:00:00Z",
            updatedAt = "2026-08-09T00:00:00Z",
        )
    }

    private class FakeAuth(permissions: Set<String>, admin: Boolean) : AuthDataProvider {
        private val _permissions = MutableStateFlow(permissions)
        private val _isAdmin = MutableStateFlow(admin)

        override val currentUser: StateFlow<UserData?> = MutableStateFlow(null)
        override val isAdmin: StateFlow<Boolean> = _isAdmin
        override val userPermissions: StateFlow<Set<String>> = _permissions

        // Mirrors the host: UserInfo.hasPermission is `isAdmin || permissions.contains(..)`.
        override fun hasPermission(permission: String): Boolean =
            _isAdmin.value || permission in _permissions.value

        override fun hasAnyPermission(vararg permissions: String): Boolean =
            permissions.any { hasPermission(it) }

        fun grant(permission: String) {
            _permissions.value = _permissions.value + permission
        }

        fun revoke(permission: String) {
            _permissions.value = _permissions.value - permission
        }

        /** An admin claim landing with no accompanying change to the permission set. */
        fun promote() {
            _isAdmin.value = true
        }
    }
}
