package ai.rever.boss.plugin.dynamic.secretmanager.security

import ai.rever.boss.plugin.api.SecretDataProvider
import ai.rever.boss.plugin.api.PaginatedSecretsWithSharingAccessData
import ai.rever.boss.plugin.dynamic.secretmanager.SecretManagerViewModel
import ai.rever.boss.plugin.dynamic.secretmanager.ai.FakeSecretDataProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.runCurrent
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import java.nio.file.Files

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class VaultBackupLifecycleTest {
    @Test
    fun `dispose cancels export before file replacement and clears the passphrase`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        val fake = FakeSecretDataProvider(emptyList())
        val provider = object : SecretDataProvider by fake {
            override suspend fun getUserSecretsWithSharingAccess(limit: Int, offset: Int): Result<PaginatedSecretsWithSharingAccessData> {
                entered.complete(Unit)
                gate.await()
                return Result.success(PaginatedSecretsWithSharingAccessData(emptyList(), false))
            }
        }
        val parent = Job()
        val scope = CoroutineScope(coroutineContext + parent)
        val vm = SecretManagerViewModel(provider, null, null, scope)
        val file = Files.createTempFile("backup-lifecycle", ".bossvlt").toFile()
        val original = byteArrayOf(1, 2, 3)
        file.writeBytes(original)
        val passphrase = "strong test passphrase".toCharArray()
        try {
            vm.exportVaultToFile(file, passphrase)
            runCurrent()
            entered.await()
            assertTrue(vm.state.isBackupBusy)
            vm.dispose()
            gate.complete(Unit)
            parent.children.toList().forEach { it.join() }
            assertFalse(vm.state.isBackupBusy)
            assertTrue(passphrase.all { it == '\u0000' })
            kotlin.test.assertContentEquals(original, file.readBytes())
        } finally { parent.cancel(); file.delete() }
    }

    @Test
    fun `passphrase is cleared even when scope is already cancelled or provider absent`() = runTest {
        val parent = Job().also { it.cancel() }
        val vm = SecretManagerViewModel(FakeSecretDataProvider(emptyList()), null, null,
            CoroutineScope(coroutineContext + parent))
        val passphrase = "test passphrase".toCharArray()
        vm.exportVaultToFile(java.io.File("unused"), passphrase)
        runCurrent()
        assertTrue(passphrase.all { it == '\u0000' })
        assertFalse(vm.state.isBackupBusy)
        val noProvider = SecretManagerViewModel(null, null, null, this)
        val second = "test passphrase".toCharArray()
        noProvider.importVaultFromFile(java.io.File("unused"), second)
        assertTrue(second.all { it == '\u0000' })
    }

    @Test
    fun `file helpers reject oversized files and replace encrypted data without temp leftovers`() {
        val directory = Files.createTempDirectory("backup-files").toFile()
        val file = directory.resolve("vault.bossvlt")
        try {
            java.io.RandomAccessFile(file, "rw").use { it.setLength(VaultCrypto.MAX_BLOB_BYTES.toLong() + 1) }
            kotlin.test.assertFailsWith<VaultBackupException> { VaultBackupFiles.read(file) }
            val encrypted = byteArrayOf(5, 6, 7)
            VaultBackupFiles.write(file, encrypted)
            kotlin.test.assertContentEquals(encrypted, VaultBackupFiles.read(file))
            assertEquals(listOf("vault.bossvlt"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }
    @Test
    fun `backup failure keeps the secrets surface and retry opens a fresh dialog`() = runTest {
        var fail = true
        val fake = FakeSecretDataProvider(emptyList())
        val provider = object : SecretDataProvider by fake {
            override suspend fun getUserSecretsWithSharingAccess(limit: Int, offset: Int): Result<PaginatedSecretsWithSharingAccessData> =
                if (fail) Result.failure(IllegalStateException("offline")) else fake.getUserSecretsWithSharingAccess(limit, offset)
        }
        val parent = Job()
        val vm = SecretManagerViewModel(provider, null, null, CoroutineScope(coroutineContext + parent))
        val file = Files.createTempFile("backup-retry", ".bossvlt").toFile()
        try {
            vm.exportVaultToFile(file, "strong test passphrase".toCharArray())
            parent.children.toList().forEach { it.join() }
            assertTrue(vm.state.backupError != null)
            kotlin.test.assertNull(vm.state.errorMessage)
            assertFalse(vm.state.isBackupBusy)
            vm.showBackupDialog()
            assertTrue(vm.state.showBackupDialog)
            kotlin.test.assertNull(vm.state.backupError)
            fail = false
            vm.exportVaultToFile(file, "strong test passphrase".toCharArray())
            parent.children.toList().forEach { it.join() }
            assertTrue(vm.state.backupStatus != null)
            kotlin.test.assertNull(vm.state.backupError)
            vm.dispose()
            assertFalse(vm.state.showBackupDialog)
        } finally { parent.cancel(); file.delete() }
    }

    @Test
    fun `provider cancellation ends busy state and allows another backup`() = runTest {
        val fake = FakeSecretDataProvider(emptyList())
        val provider = object : SecretDataProvider by fake {
            override suspend fun getUserSecretsWithSharingAccess(limit: Int, offset: Int): Result<PaginatedSecretsWithSharingAccessData> =
                Result.failure(kotlinx.coroutines.CancellationException("cancelled"))
        }
        val parent = Job()
        val vm = SecretManagerViewModel(provider, null, null, CoroutineScope(coroutineContext + parent))
        val file = Files.createTempFile("backup-cancellation", ".bossvlt").toFile()
        val passphrase = "strong test passphrase".toCharArray()
        try {
            vm.exportVaultToFile(file, passphrase)
            parent.children.toList().forEach { it.join() }
            assertFalse(vm.state.isBackupBusy)
            assertTrue(passphrase.all { it == '\u0000' })
            vm.showBackupDialog()
            assertTrue(vm.state.showBackupDialog)
        } finally { parent.cancel(); file.delete() }
    }

    @Test
    fun `atomic replacement rejection falls back and cancellation preserves old file`() {
        val directory = Files.createTempDirectory("backup-replacement").toFile()
        val file = directory.resolve("vault.bossvlt")
        try {
            file.writeBytes(byteArrayOf(1))
            VaultBackupFiles.write(file, byteArrayOf(2), atomicMove = { _, target ->
                throw java.nio.file.FileAlreadyExistsException(target.toString())
            })
            kotlin.test.assertContentEquals(byteArrayOf(2), file.readBytes())
            kotlin.test.assertFailsWith<kotlinx.coroutines.CancellationException> {
                VaultBackupFiles.write(file, byteArrayOf(3)) { throw kotlinx.coroutines.CancellationException("cancelled") }
            }
            kotlin.test.assertContentEquals(byteArrayOf(2), file.readBytes())
            assertEquals(listOf("vault.bossvlt"), directory.list()!!.toList())
        } finally { directory.deleteRecursively() }
    }

}
