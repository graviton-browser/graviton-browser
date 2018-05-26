package net.plan99.graviton

import java.nio.file.Files
import java.nio.file.Files.getPosixFilePermissions
import java.nio.file.Files.setPosixFilePermissions
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.security.PublicKey
import java.security.SignatureException
import java.security.cert.Certificate
import java.util.jar.JarEntry

// TODO: J9: Remove.
val JarEntry.realName: String get() = name

/**
 * A [RuntimeUpdate] is a signed JAR that contains a JRE image produced by javapackager. This class is responsible
 * for creating updates and checking/unpacking them to the given install location.
 *
 * Note: The update JAR contains the contents of the versioned sub-directory. It does not contain a full Graviton
 * install directory with bootstrapper. This is because we wouldn't be able to overwrite those files on Windows anyway
 * as they'd be locked. Therefore to unpack it to the right place you must know the version number it represents already.
 */
class RuntimeUpdate(val jar: Path, private val signingKey: PublicKey) {
    fun install(targetInstallDir: Path) {
        // Create the target installation directory.
        targetInstallDir.createDirectories()

        // Unpack the update.
        jar.readAsJar().use { stream ->
            for (entry in stream.entriesIterator) {
                check(entry.realName.first() != '/') { entry.realName }
                val target = targetInstallDir / entry.realName
                if (entry.isDirectory) {
                    target.createDirectories()
                    continue
                }
                if (entry.realName.startsWith("META-INF") && (entry.realName.endsWith(".SF") || entry.realName.endsWith(".EC"))) {
                    // Don't unpack the signature files.
                    continue
                }

                Files.newOutputStream(target).buffered().use { out ->
                    val size = stream.copyTo(out)
                    check(size == entry.size) { "$size != ${entry.size}"}
                }
                val certificates: Array<out Certificate> = entry.certificates ?: throw SignatureException("File ${entry.realName} is not signed")
                val publicKey = certificates.single().publicKey
                if (!(publicKey.encoded contentEquals signingKey.encoded)) {
                    throw SignatureException("File ${entry.realName} is not signed by the right key: $publicKey vs $signingKey")
                }
            }
        }

        // If we need to, flip the execute bit on the unpacked binary.
        when (currentOperatingSystem) {
            OperatingSystem.MAC -> setExecuteBit(targetInstallDir / "Contents" / "MacOS" / "Graviton Browser")
            OperatingSystem.WIN -> {}
            OperatingSystem.LINUX -> TODO()
            OperatingSystem.UNKNOWN -> TODO()
        }
    }

    private fun setExecuteBit(path: Path) {
        val perms: MutableSet<PosixFilePermission> = getPosixFilePermissions(path).toMutableSet()
        perms += PosixFilePermission.OWNER_EXECUTE
        perms += PosixFilePermission.GROUP_EXECUTE
        perms += PosixFilePermission.OTHERS_EXECUTE
        setPosixFilePermissions(path, perms)
    }
}