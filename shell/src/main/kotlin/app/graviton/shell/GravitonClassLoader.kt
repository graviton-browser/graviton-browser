package app.graviton.shell

import app.graviton.codefetch.StartException
import io.github.classgraph.ArrayTypeSignature
import io.github.classgraph.ClassGraph
import io.github.classgraph.ClassRefTypeSignature
import io.github.classgraph.MethodInfo
import javafx.application.Application
import tornadofx.*
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.sql.Driver
import java.sql.DriverManager
import java.util.*
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
                throw StartException("Failed to build classloader given class path: $classPath", e)
            }
        }
    }

    // Don't initialise the main class, until we're in the right classloading context.
    val startClass: Class<*> by lazy {
        specifiedMainClass() ?: foundMainClass() ?: throw StartException("Could not locate any way to start the app.")
    }

    val requestedFeatures: List<String> = appManifest.mainAttributes.getValue("Graviton-Features")?.split(" ") ?: emptyList()

    private fun specifiedMainClass() =
            appManifest.mainAttributes.getValue("Main-Class")?.let { Class.forName(it, false, this) }

    private fun foundMainClass(): Class<*>? {
        return stopwatch("Searching for a main class") {
            var appClassName: String? = null
            // Search for a JavaFX or TornadoFX main class in the first JAR.
            ClassGraph().overrideClasspath(urls[0]).enableClassInfo().scan().use {
                appClassName = it.getSubclasses(App::class.java.name).firstOrNull()?.name
                if (appClassName == null)
                    appClassName = it.getSubclasses(Application::class.java.name).firstOrNull()?.name
            }

            // Not found? Try re-scanning to find any class with a main method and pick the first.
            if (appClassName == null) {
                ClassGraph().overrideClasspath(urls[0]).enableMethodInfo().scan().use { scanResult ->
                    appClassName = scanResult
                            .allStandardClasses
                            .flatMap {
                                checkNotNull(it.getMethodInfo("main")) { it }
                            }
                            .firstOrNull { methodInfo ->
                                // getMethodInfo only returns public methods by default
                                methodInfo.isStatic && takesArrayOfStrings(methodInfo)
                                // so this is a public static void main(String[] args) method
                            }
                            ?.classInfo
                            ?.name
                }
            }
            if (appClassName != null)
                Class.forName(appClassName, false, this)
            else
                null
        }
    }

    private fun takesArrayOfStrings(methodInfo: MethodInfo): Boolean {
        return (methodInfo.parameterInfo.singleOrNull()?.typeDescriptor as? ArrayTypeSignature)?.let {
            it.numDimensions == 1 && (it.elementTypeSignature as? ClassRefTypeSignature)?.baseClassName == "java.lang.String"
        } ?: false
    }

    /**
     * This is called when the class isn't found in the parent classloader, i.e. it's not a Java standard class.
     * Calling the super-method will search the app classpath but we'll check for access to the Graviton API first.
     * This ensures that even if the app has accidentally included or fat-jar'd the API somehow, we'll still link
     * it against the shared copy.
     */
    override fun findClass(name: String): Class<*> {
        return if (name.startsWith("app.graviton.api."))
            launcherClassLoader.loadClass(name)
        else
            super.findClass(name)
    }

    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        // TODO: Isolate static state in the core JVM from the app by re-loading classes outside of java.lang
        //       and other special packages.
        return super.loadClass(name, resolve)
    }

    init {
        ClassLoader.registerAsParallelCapable()
    }

    // This is a hack to work around the current lack of java.* static fields isolation between apps.
    // PicoCLI initialises the java.sql subsystem as part of a dubious feature that lets you specify
    // JDBC connection strings on the command line. The DriverManager scans for DB drivers and finds
    // none, and won't check again later. So we have to do this ourselves.
    private val jdbcDrivers: List<Driver> = run {
        val loader = ServiceLoader.load(Driver::class.java, this).iterator()
        val results = mutableListOf<Driver>()
        while (loader.hasNext()) {
            // DriverManager seems to want to skip drivers that don't load during startup, albeit, the code looks buggy.
            try { results += loader.next() } catch (e: Throwable) {}
        }
        results
    }

    override fun close() {
        jdbcDrivers.forEach { DriverManager.deregisterDriver(it) }
        super.close()
    }
}