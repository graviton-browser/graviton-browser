package app.graviton.shell

import app.graviton.shell.TestWithFakeJRE.FileOrDirectory.DIRECTORY
import app.graviton.shell.TestWithFakeJRE.FileOrDirectory.FILE
import com.google.common.jimfs.Configuration
import com.google.common.jimfs.Jimfs
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore
import java.security.PublicKey

open class TestWithFakeJRE {
    // Shared file system that is left alone between tests.
    private val fs: FileSystem = Jimfs.newFileSystem(
            Configuration.unix().toBuilder().setAttributeViews("basic", "posix").build()
    )
    protected val root: Path = fs.rootDirectories.first()

    // fakeMacJreDir is the directory produced by javapackager, before version mangling.
    val fakeMacJreDir = root / "fake-jre-mac"
    val fakeLinuxJreDir = root / "fake-jre-linux"

    // We have to load a pre-generated private key and cert because the Java crypto API is incomplete. There's no
    // API for building a certificate. We could probably do it with Bouncy Castle but I don't have that on the plane.
    private val testKeyStore = javaClass.getResourceAsStream("/test.pks").use {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(it, "testtest".toCharArray())
        ks
    }
    val priv1 = testKeyStore.getEntry("mykey", KeyStore.PasswordProtection("testtest".toCharArray())) as KeyStore.PrivateKeyEntry
    val pub1: PublicKey = priv1.certificate.publicKey
    val testUpdateMac = RuntimeUpdate(javaClass.getResource("/test-update-mac.jar").toPath(), pub1)
    val testUpdateLinux = RuntimeUpdate(javaClass.getResource("/test-update-linux.jar").toPath(), pub1)

    enum class FileOrDirectory { FILE, DIRECTORY }

    init {
        // The fake images aren't that realistic, but it has roughly the right structure.
        createFakeJREImage("/example-runtime-files-mac.txt", fakeMacJreDir)
        createFakeJREImage("/example-runtime-files-linux.txt", fakeLinuxJreDir)
    }

    private fun createFakeJREImage(layoutResourcePath: String, destinationPath: Path) {
        RuntimeUpdateTest::class.java.getResourceAsStream(layoutResourcePath).bufferedReader().useLines { lines ->
            // Use a heuristic to figure out if each line is supposed to be a file or directory.
            val types = LinkedHashMap<Path, FileOrDirectory>()
            for (line in lines) {
                val path = destinationPath / line
                // Assume a file unless we see a following entry that is underneath it.
                types[path] = FILE
                if (path.toString().endsWith("/") || path.parent?.let { types[it] } == FILE) {
                    types[path.parent] = DIRECTORY
                }
            }
            for ((path, type) in types) {
                when (type) {
                    DIRECTORY -> Files.createDirectories(path)
                    FILE -> Files.write(path, ByteArray(1))
                }
            }
        }
    }

    /* - requires java 9+ for the JarSigner API, run manually and the result checked into git.
         TODO: Once we're upgraded to Java 11 uncomment this and generate it each time.

    fun createFrom(from: Path, signingKey: KeyStore.PrivateKeyEntry, outputPath: Path): RuntimeUpdate {
        val tempZip = Files.createTempFile("graviton-runtimeupdate-test", ".zip")
        try {
            // Step 1. Create the unsigned zip. Unfortunately the JarSigner API is quite old and wants to
            // write to a different file to the input, even though it could be modified in place.
            ZipOutputStream(Files.newOutputStream(tempZip).buffered()).use { zos ->
                Files.walk(from).use { walk ->
                    for (path in walk) {
                        val isDir = Files.isDirectory(path)
                        if (path == from) continue
                        val s = from.relativize(path).toString()
                        val pathStr = s + if (isDir && !s.endsWith("/")) "/" else ""
                        zos.putNextEntry(ZipEntry(pathStr))
                        if (!isDir)
                            Files.newInputStream(path).use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }
            // Step 2. Copy the temp zip and sign it along the way.
            Files.newOutputStream(outputPath, StandardOpenOption.CREATE_NEW).buffered().use { stream ->
                JarSigner.Builder(signingKey).build().sign(ZipFile(tempZip.toFile()), stream)
            }
            return RuntimeUpdate(outputPath, signingKey.certificate.publicKey)
        } finally {
            Files.deleteIfExists(tempZip)
        }
    } */
}
