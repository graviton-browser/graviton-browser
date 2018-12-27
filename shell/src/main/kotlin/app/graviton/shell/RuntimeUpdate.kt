package app.graviton.shell

import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Files.getPosixFilePermissions
import java.nio.file.Files.setPosixFilePermissions
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
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
        // Unpack to a temp directory and rename on success.
        val tmpTargetDir = targetInstallDir.parent / "tmp.${targetInstallDir.fileName}"
        recursivelyDeleteIfExists(tmpTargetDir)

        // Create the target installation directory.
        tmpTargetDir.createDirectories()

        // Unpack the update.
        try {
            jar.readAsJar().use { stream ->
                for (entry in stream.entriesIterator) {
                    if (entry.realName.startsWith("META-INF")) {
                        // Don't unpack metadata or manifest files, as they're only used for signing.
                        continue
                    }

                    check(entry.realName.first() != '/') { entry.realName }
                    val target = tmpTargetDir / entry.realName
                    if (entry.isDirectory) {
                        target.createDirectories()
                        continue
                    }

                    Files.newOutputStream(target).buffered().use { out ->
                        val size = stream.copyTo(out)
                        check(size == entry.size) { "$size != ${entry.size}" }
                    }
                    val certificates: Array<out Certificate> = entry.certificates
                            ?: throw SignatureException("File ${entry.realName} is not signed")
                    val publicKey = certificates.single().publicKey
                    if (!(publicKey.encoded contentEquals signingKey.encoded)) {
                        throw SignatureException("File ${entry.realName} is not signed by the right key: $publicKey vs $signingKey")
                    }
                }
            }

            // If we need to, flip the execute bit on the unpacked binary.
            when (currentOperatingSystem) {
                OperatingSystem.MAC -> setExecuteBit(tmpTargetDir / "Contents" / "MacOS" / "Graviton Browser")
                OperatingSystem.WIN -> {
                }
                OperatingSystem.LINUX -> setExecuteBit(tmpTargetDir / "graviton")
                OperatingSystem.UNKNOWN -> TODO()
            }

            Files.move(tmpTargetDir, targetInstallDir)
        } finally {
            recursivelyDeleteIfExists(tmpTargetDir)
        }
    }

    private fun setExecuteBit(path: Path) {
        val perms: MutableSet<PosixFilePermission> = getPosixFilePermissions(path).toMutableSet()
        perms += PosixFilePermission.OWNER_EXECUTE
        perms += PosixFilePermission.GROUP_EXECUTE
        perms += PosixFilePermission.OTHERS_EXECUTE
        setPosixFilePermissions(path, perms)
    }

    private fun recursivelyDeleteIfExists(dir: Path) {
        if (!Files.exists(dir))
            return
        require(Files.isDirectory(dir)) { "$dir is not a directory" }
        Files.walkFileTree(dir, object : SimpleFileVisitor<Path>() {
            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                Files.delete(file)
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                if (exc != null)
                    Files.delete(dir)
                return FileVisitResult.CONTINUE
            }
        })
    }
}