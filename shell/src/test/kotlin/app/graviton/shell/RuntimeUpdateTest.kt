package app.graviton.shell

import org.junit.Test
import java.nio.file.Files
import java.nio.file.Files.newOutputStream
import java.nio.file.Files.walk
import java.nio.file.Path
import java.security.SignatureException
import java.util.jar.JarEntry
import java.util.jar.JarInputStream
import java.util.jar.JarOutputStream
import kotlin.streams.toList
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RuntimeUpdateTest : TestWithFakeJRE() {
    @Test
    fun createAndApply() {
        val update: RuntimeUpdate = testUpdate
        val jarEntries = update.jar.readAsJar().entriesIterator.asSequence().toList()
        // Check we have the same names but order doesn't matter.
        val jarFileNames = jarEntries.map {
            if (it.isDirectory) it.realName.dropLast(1) else it.realName
        }.toSortedSet()
        val expected = (
                walk(fakeJreDir).toList().drop(1).map { fakeJreDir.relativize(it).toString() } +
                        listOf("META-INF/SIGNER.SF", "META-INF/SIGNER.EC")
                ).toSortedSet()
        assertEquals(expected, jarFileNames)
        // Now check every file is correctly signed by the proper signing key.
        jarEntries
                .filterNot { it.isDirectory || it.realName.startsWith("META-INF/") }
                .map { it.certificates.single() }
                .distinct()
                .single()
                .verify(priv1.certificate.publicKey)
        // Apply to a real install directory.
        val installDir = root / "install-dir"
        update.install(installDir)
        // Check it was unpacked.
        assertTrue((installDir / "Contents").exists)
        val installedFiles = walk(installDir).map { installDir.relativize(it).toString() }.toList().drop(1)
        assertEquals(jarFileNames.filterNot { it.startsWith("META-INF") }.toSet(), installedFiles.toSet())
    }

    private fun editJar(badPath: Path, jarPath: Path, action: (JarEntry, JarOutputStream, JarInputStream) -> Unit) {
        JarOutputStream(newOutputStream(badPath)).use { newJarStream ->
            jarPath.readAsJar().use { oldJarStream ->
                for (entry in oldJarStream.entriesIterator) {
                    action(entry, newJarStream, oldJarStream)
                }
            }
        }
    }

    @Test
    fun notSigned() {
        // Create an update but drop the signature files, then try to apply it.
        val badPath = root / "bad.update.jar"
        editJar(badPath, testUpdate.jar) { entry, newJarStream, oldJarStream ->
            if (".SF" !in entry.realName) {
                newJarStream.putNextEntry(entry)
                oldJarStream.copyTo(newJarStream)
                newJarStream.closeEntry()
            }
        }
        assertFailsWith<SignatureException> {
            RuntimeUpdate(badPath, pub1).install(root / "does-not-work")
        }
    }

    @Test
    fun fileCorrupted() {
        val badPath = root / "bad.update.jar"
        editJar(badPath, testUpdate.jar) { entry, newJarStream, oldJarStream ->
            newJarStream.putNextEntry(entry)
            if (".class" !in entry.realName) {
                oldJarStream.copyTo(newJarStream)
            } else {
                newJarStream.write(1234)  // Some garbage.
            }
            newJarStream.closeEntry()
        }
        assertFailsWith<SignatureException> {
            RuntimeUpdate(badPath, pub1).install(root / "does-not-work")
        }
        assertFalse(Files.exists(root / "does-not-work"))
    }
}