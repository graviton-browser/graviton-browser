package net.plan99.graviton

import com.google.common.jimfs.Jimfs
import net.plan99.graviton.TestWithFakeJRE.FileOrDirectory.DIRECTORY
import net.plan99.graviton.TestWithFakeJRE.FileOrDirectory.FILE
import java.nio.file.FileSystem
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyStore

open class TestWithFakeJRE {
    // Shared file system that is left alone between tests.
    private val fs: FileSystem = Jimfs.newFileSystem()
    protected val root: Path = fs.rootDirectories.first()

    // fakeJreDir is the directory produced by javapackager, before version mangling.
    protected val fakeJreDir = root / "fake-jre"

    // We have to load a pre-generated private key and cert because the Java crypto API is incomplete. There's no
    // API for building a certificate. We could probably do it with Bouncy Castle but I don't have that on the plane.
    private val testKeyStore = javaClass.getResourceAsStream("/test.pks").use {
        val ks = KeyStore.getInstance("PKCS12")
        ks.load(it, "testtest".toCharArray())
        ks
    }
    protected val priv1 = testKeyStore.getEntry("mykey", KeyStore.PasswordProtection("testtest".toCharArray())) as KeyStore.PrivateKeyEntry
    protected val pub1 = priv1.certificate.publicKey

    enum class FileOrDirectory { FILE, DIRECTORY }

    fun createFakeJREImage(location: Path) {
        // The fake image isn't that realistic, but it has roughly the right structure.
        RuntimeUpdateTest::class.java.getResourceAsStream("/example-runtime-files.txt").bufferedReader().useLines { lines ->
            // Use a heuristic to figure out if each line is supposed to be a file or directory.
            val types = LinkedHashMap<Path, FileOrDirectory>()
            for (line in lines) {
                val path = location / line
                // Assume a file unless we see a following entry that is underneath it.
                types[path] = FILE
                if (path.parent?.let { types[it] } == FILE) {
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

    init {
        createFakeJREImage(fakeJreDir)
    }
}