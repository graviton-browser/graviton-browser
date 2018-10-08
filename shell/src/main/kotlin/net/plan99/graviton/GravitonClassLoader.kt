package net.plan99.graviton

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import javafx.application.Application
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.Manifest

/** Nothing special: just a regular URLClassLoader that chains to the boot class loader. */
class GravitonClassLoader(urls: Array<URL>, private val appManifest: Manifest, val originalClassPath: String) : URLClassLoader(urls, GravitonClassLoader::class.java.classLoader.parent) {
    companion object : Logging() {
        fun buildClassLoaderFor(classPath: String): GravitonClassLoader {
            try {
                val classpathDelimiter = currentOperatingSystem.classPathDelimiter
                val files: List<File> = classPath.split(classpathDelimiter).map { File(it) }
                val urls: Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()
                // Chain to the parent classloader so our internals don't interfere with the application.
                // TODO: J9: Use classloader names.
                val manifest = JarFile(files[0]).use { it.manifest }
                return GravitonClassLoader(urls, manifest, classPath)
            } catch (e: java.io.FileNotFoundException) {
                throw e
            } catch (e: Exception) {
                throw AppLauncher.StartException("Failed to build classloader given class path: $classPath", e)
            }
        }
    }

    val mainClassName: String? = appManifest.mainAttributes.getValue("Main-Class")
    // Don't initialise the main class, until we're in the right classloading context.
    val mainClass: Class<*>? by lazy { mainClassName?.let { Class.forName(it, false, this) } }

    val foundJFXClass: Class<out Application>? by lazy {
        stopwatch("Searching for a JavaFX Application class") {
            try {
                if (mainClass != null)
                    return@lazy mainClass!!.asSubclass(Application::class.java)
            } catch (e: ClassCastException) {
            }
            val scanner = FastClasspathScanner().overrideClassLoaders(this)
            val scanResult = scanner.scan()
            val appClassName: String? = scanResult.getNamesOfSubclassesOf(Application::class.java).firstOrNull()
            if (appClassName != null) {
                Class.forName(appClassName, false, this).asSubclass(Application::class.java)
            } else {
                null
            }
        }
    }

    init {
        ClassLoader.registerAsParallelCapable()
    }
}