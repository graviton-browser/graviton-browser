package app.graviton.shell

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import javafx.application.Application
import tornadofx.App
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.Manifest

/**
 * An extended [URLClassLoader] that provides some functionality specific to us.
 *
 * Chains to the *parent* classloader, so our internals don't interfere with the application, except for the
 * Graviton API, which is exposed to the app. This means the shell and launcher can share classes with the app
 * as long as they are either:
 *
 * 1. Standard Java classes in the JDK
 * 2. In the app.graviton.api package
 */
class GravitonClassLoader private constructor(
        private val urls: Array<URL>,
        private val appManifest: Manifest,
        val originalClassPath: String) : URLClassLoader(urls, launcherClassLoader.parent) {
    companion object : Logging() {
        val launcherClassLoader: ClassLoader = GravitonClassLoader::class.java.classLoader

        fun build(classPath: String): GravitonClassLoader {
            try {
                val classpathDelimiter = currentOperatingSystem.classPathDelimiter
                val files: List<File> = classPath.split(classpathDelimiter).map { File(it) }
                val urls: Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()
                // TODO: J9: Use classloader names.
                val manifest = JarFile(files[0]).use { it.manifest }
                return GravitonClassLoader(urls, manifest, classPath)
            } catch (e: Exception) {
                throw AppLauncher.StartException("Failed to build classloader given class path: $classPath", e)
            }
        }
    }

    // Don't initialise the main class, until we're in the right classloading context.
    val startClass: Class<*> by lazy {
        specifiedMainClass() ?: foundMainClass() ?: throw AppLauncher.StartException("Could not locate any way to start the app.")
    }

    private fun specifiedMainClass() =
            appManifest.mainAttributes.getValue("Main-Class")?.let { Class.forName(it, false, this) }

    private fun foundMainClass(): Class<*>? {
        return stopwatch("Searching for a main class") {
            // TODO: Scan this classloader instead of the first JAR.
            val scanner = FastClasspathScanner().overrideClasspath(urls[0])
            val scanResult = scanner.scan()
            // Search for a JavaFX main class.
            // TODO: Scan for any class with a main method as well as just JFX.
            var appClassName: String? = scanResult.getNamesOfSubclassesOf(App::class.java).firstOrNull()
            if (appClassName == null) {
                appClassName = scanResult.getNamesOfSubclassesOf(Application::class.java).firstOrNull()
            }
            if (appClassName != null)
                Class.forName(appClassName, false, this)
            else
                null
        }
    }

    /**
     * This is called when the class isn't found in the parent classloader, i.e. it's not a Java standard class.
     * Calling the super-method will search the app classpath but we'll check for access to the Graviton API first.
     * This ensures that even if the app has accidentally included or fat-jard the API somehow, we'll still link
     * it against the shared copy.
     */
    override fun findClass(name: String): Class<*> {
        return if (name.startsWith("app.graviton.api."))
            launcherClassLoader.loadClass(name)
        else
            super.findClass(name)
    }

    init {
        ClassLoader.registerAsParallelCapable()
    }
}