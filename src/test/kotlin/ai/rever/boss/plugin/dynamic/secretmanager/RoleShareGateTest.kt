package ai.rever.boss.plugin.dynamic.secretmanager

import ai.rever.boss.plugin.api.AuthDataProvider
import ai.rever.boss.plugin.api.UserData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
    ): Pair<SecretManagerViewModel, FakeAuth> {
        val auth = FakeAuth(permissions, isAdmin)
        val vm = SecretManagerViewModel(
            secretDataProvider = null,
            supabaseDataProvider = null,
            pluginStoreApiKeyProvider = null,
            scope = TestScope(StandardTestDispatcher(test.testScheduler)),
            authDataProvider = auth,
        ).also { it.initialize() }
        return vm to auth
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
