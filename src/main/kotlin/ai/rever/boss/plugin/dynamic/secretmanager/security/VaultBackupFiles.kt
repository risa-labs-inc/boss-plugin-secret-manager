package ai.rever.boss.plugin.dynamic.secretmanager.security

import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.FileAlreadyExistsException
import java.nio.file.StandardCopyOption
import java.nio.file.Path

/** Bounded reads and replacement through a temporary encrypted file. */
internal object VaultBackupFiles {
    fun read(file: File): ByteArray {
        if (file.length() > VaultCrypto.MAX_BLOB_BYTES) throw VaultBackupException("Backup exceeds the 64 MiB limit")
        val bytes = file.inputStream().use { it.readNBytes(VaultCrypto.MAX_BLOB_BYTES + 1) }
        if (bytes.size > VaultCrypto.MAX_BLOB_BYTES) throw VaultBackupException("Backup exceeds the 64 MiB limit")
        return bytes
    }

    fun write(
        file: File,
        bytes: ByteArray,
        atomicMove: (Path, Path) -> Unit = { source, target ->
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        },
        checkActive: () -> Unit = {},
    ) {
        val destination = file.toPath().toAbsolutePath()
        val temporary = Files.createTempFile(destination.parent, ".boss-backup-", ".tmp")
        try {
            Files.write(temporary, bytes)
            checkActive()
            try {
                atomicMove(temporary, destination)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: FileAlreadyExistsException) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}
