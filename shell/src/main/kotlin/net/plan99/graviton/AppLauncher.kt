package net.plan99.graviton

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.Property
import javafx.stage.Stage
import kotlinx.coroutines.experimental.yield
import org.apache.http.client.HttpResponseException
import org.eclipse.aether.RepositoryException
import org.eclipse.aether.transfer.MetadataNotFoundException
import java.io.File
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.concurrent.thread

/**
 * An [AppLauncher] performs the tasks needed to start an app, performing callbacks on various methods that can be
 * overridden as it goes. It uses coroutines and must be started from inside a suspending function, so even though
 * it does many slow background tasks, you do not have to think about blocking operations or background sync.
 */
open class AppLauncher(private val options: GravitonCLI,
                       private val historyManager: HistoryManager,
                       private val primaryStage: Stage? = null,
                       private val events: AppLauncher.Events,
                       private val stdOutStream: PrintStream = System.out,
                       private val stdErrStream: PrintStream = System.err) {
    companion object : Logging()

    class StartException(message: String, cause: Throwable?) : Exception(message, cause) {
        constructor(message: String) : this(message, null)
    }

    open class Events : CodeFetcher.Events() {
        open fun initializingApp() {}
        open fun aboutToStartApp() {}
    }

    private val once = Once()

    /**
     * Takes a 'command' in the form of a partial Graviton command line, extracts the coordinates, flags, and any
     * command line options that should be passed to the app, downloads the app, if successful records the app launch
     * in the history list and then invokes the app in a sub-classloader.
     */
    suspend fun start() = once {
        val codeFetcher = CodeFetcher(options.cachePath.toPath())
        codeFetcher.events = events
        if (options.clearCache)
            historyManager.clearCache()
        codeFetcher.useSSL = !(commandLineArguments.noSSL || options.noSSL)

        val userInput = (options.packageName ?: throw StartException("No coordinates specified"))[0]
        check(userInput.isNotBlank())
        val historyLookup: HistoryEntry? = if (!options.refresh) {
            // The user has probably specified a coordinate fragment, missing the artifact name or version number.
            // If we never saw this input before, we'll just continue and clean it up later. If we resolved it
            // before to a specific artifact coordinate, we'll look it up again from our history file. That'll
            // let us skip the latest version check. Put another way, this step gives us the following policy:
            //
            // - Check for new versions ONLY every AppManager.refreshInterval seconds
            // - But allow the user to always specify a newer version by hand
            historyManager.search(userInput)
        } else null

        yield()

        // The history entry contains the classpath to avoid a local POM walk, which is slow (about 0.2 seconds for
        // a very simple app like cowsay, as we're still mostly in the interpreter at this point).
        val fetch: CodeFetcher.Result = if (historyLookup != null) {
            info { "Used previously resolved coordinates $historyLookup" }
            CodeFetcher.Result(historyLookup.classPath, historyLookup.artifact)
        } else {
            download(userInput, codeFetcher)
        }

        yield()

        info { "App name: ${fetch.name}" }
        info { "App description: ${fetch.artifact.properties["model.description"]}" }

        val loadResult: AppLoadResult = try {
            buildClassLoaderFor(fetch)
        } catch (e: java.io.FileNotFoundException) {
            // We thought we had a fetch result but it's not on disk anymore? Probably the user wiped the cache, which deletes
            // downloaded artifacts but leaves the recent apps list alone. Let's re-resolve and try again.
            buildClassLoaderFor(download(userInput, codeFetcher))
        }

        // Update the last used timestamp.
        historyManager.recordHistoryEntry(HistoryEntry(userInput, fetch))

        // We try to run a JavaFX app first, to bypass the main method and give a smoother transition.
        // This also avoids problems with us trying to launch JavaFX twice. However it does mean if the
        // main method has non-trivial logic in it, then it won't be run!
        //
        // TODO: Disassemble the main method if found to see if it just does Application.launch and if so, skip it.
        events.initializingApp()
        val mainClass = loadResult.mainClass

        // TODO: This is super-slow, re-evaluate if it's really worth it and try upgrading to ClassGraph.
        val jfxApplicationClass: Class<out Application>? = stopwatch("Searching for a JavaFX main class") { loadResult.calculateJFXClass() }
        if (jfxApplicationClass != null)
            info { "JavaFX Application class found: $jfxApplicationClass" }

        try {
            when {
                jfxApplicationClass != null -> invokeJavaFXApplication(jfxApplicationClass, primaryStage, options.args.drop(1), fetch.artifact.toString())
                mainClass != null -> invokeMainMethod(fetch.artifact.toString(), options.args, loadResult, mainClass,
                        stdOutStream, stdErrStream, andWait = primaryStage == null)
                else -> throw StartException("This application is not an executable program.")
            }
        } catch (e: StartException) {
            throw e
        } catch (e: Throwable) {
            throw StartException("Application failed to start", e)
        }
    }

    private suspend fun download(userInput: String, codeFetcher: CodeFetcher): CodeFetcher.Result {
        return try {
            val coordinates: String = calculateCoordinates(userInput)
            codeFetcher.downloadAndBuildClasspath(coordinates)
        } catch (e: RepositoryException) {
            val rootCause = e.rootCause
            if (rootCause is MetadataNotFoundException) {
                throw AppLauncher.StartException("Sorry, no package with those coordinates is known.", e)
            } else if (rootCause is HttpResponseException && rootCause.statusCode == 401 && CodeFetcher.isPossiblyJitPacked(userInput)) {
                // JitPack can return 401 Unauthorized when no repository is found e.g. typo, because it
                // might be a private repository that requires authentication.
                throw AppLauncher.StartException("Sorry, no repository was found with those coordinates.", e)
            } else {
                // Put all the errors together into some sort of coherent story.
                val m = StringBuilder()
                var cursor: Throwable = e.cause!!
                var lastMessage = ""
                while (true) {
                    if (cursor.message != lastMessage) {
                        lastMessage = cursor.message ?: ""
                        m.appendln(lastMessage)
                    }
                    cursor = cursor.cause ?: break
                }
                throw AppLauncher.StartException(m.toString(), e)
            }
        }
    }

    private fun calculateCoordinates(userInput: String): String {
        var packageName: String = userInput

        // TODO: Detect if the group ID was entered the "wrong" way around (i.e. normal web style).
        // We can do this using the TLD suffix list. If the user did that, let's flip it first.

        // If there's no : anywhere in it, it's just a reverse domain name, then assume the artifact ID is the
        // same as the last component of the group ID.
        val components = packageName.split(':').toMutableList()
        if (components.size == 1) {
            components += components[0].split('.').last()
        }
        packageName = components.joinToString(":")

        return packageName
    }

    private suspend fun invokeJavaFXApplication(jfxApplicationClass: Class<out Application>, primaryStage: Stage?, args: List<String>, artifactName: String) {
        if (primaryStage == null) {
            // Being started from the command line, JavaFX wasn't set up yet.
            events.aboutToStartApp()
            Application.launch(jfxApplicationClass, *args.toTypedArray())
        } else {
            // Being started from the shell, we have already set up JFX and we aren't allowed to initialise it twice.
            // So we have to do a bit of acrobatics here and create the Application ourselves, then hand it a cleaned
            // version of our stage.
            check(Platform.isFxApplicationThread())
            // contextClassLoader is a Java API wart we have to support. It should never be used but some things
            // do use it.
            Thread.currentThread().contextClassLoader = jfxApplicationClass.classLoader
            val app: Application = jfxApplicationClass.getConstructor().newInstance()
            ModuleHacks.setParams(app, args.toTypedArray())
            // Application.init is defined by the JavaFX spec as not being run on the main thread, and it's allowed
            // to do slow blocking stuff. This is a convenient place to set up the RPC/server connections.
            background {
                // We have to set the thread ccl again because this runs on a background thread pool.
                Thread.currentThread().contextClassLoader = jfxApplicationClass.classLoader
                app.init()
            }
            events.aboutToStartApp()
            // We use reflection here to unbind all the Stage properties to avoid having to change this codepath if JavaFX
            // or the shell changes e.g. by adding new properties or binding new ones.
            primaryStage.unbindAllProperties()
            val oldScene = primaryStage.scene
            primaryStage.scene = null
            primaryStage.title = artifactName
            primaryStage.hide()
            try {
                app.start(primaryStage)
                info { "JavaFX application has been invoked" }
            } catch (e: Exception) {
                primaryStage.title = "Graviton"
                primaryStage.scene = oldScene
                primaryStage.show()
            }
        }
    }

    private fun Any.unbindAllProperties() {
        javaClass.methods
                .filter { it.name.endsWith("Property") && Property::class.java.isAssignableFrom(it.returnType) }
                .map { it.invoke(this) as Property<*> }
                .forEach { it.unbind() }
    }

    private suspend fun invokeMainMethod(artifactName: String, args: Array<String>, loadResult: AppLoadResult,
                                         mainClass: Class<*>, outStream: PrintStream, errStream: PrintStream,
                                         andWait: Boolean) {
        val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
        val subArgs = args.drop(1).toTypedArray()
        // Start in a separate thread, so we can continue to intercept IO and render it to the shell.
        // That feature will probably go away soon and we'll get pickier about which thread we run the
        // sub-program on, because the sub-program probably has expectations about this.
        val oldstdout = System.out
        val oldstderr = System.err
        System.setOut(outStream)
        System.setErr(errStream)
        events.aboutToStartApp()
        val t = thread(contextClassLoader = loadResult.classloader, name = "Main thread for $artifactName") {
            // TODO: Stop the program quitting itself.
            try {
                // For me it takes about 0.5 seconds to reach here with a non-optimised build and 0.4 with a jlinked
                // build, but we're hardly using jlink right now. To get faster I bet we need to AOT most of java.base
                //
                // println("Took ${startupStopwatch.elapsedInSec} seconds to reach main()")
                mainMethod.invoke(null, subArgs)
            } catch (e: InvocationTargetException) {
                throw e.cause!!
            } finally {
                System.setOut(oldstdout)
                System.setErr(oldstderr)
                loadResult.classloader.close()
            }
        }
        if (andWait) t.join()
        info { "App has finished" }
    }

    private class AppLoadResult(val classloader: URLClassLoader, val appManifest: Manifest) {
        val mainClassName: String? get() = appManifest.mainAttributes.getValue("Main-Class")
        val mainClass: Class<*>? by lazy { mainClassName?.let { Class.forName(it, true, classloader) } }

        suspend fun calculateJFXClass(): Class<out Application>? {
            try {
                return mainClass?.asSubclass(Application::class.java)
            } catch (e: ClassCastException) {
            }
            val scanner = FastClasspathScanner().overrideClassLoaders(classloader)
            val scanResult = background { scanner.scan() }
            val appClassName: String? = scanResult.getNamesOfSubclassesOf(Application::class.java).firstOrNull()
            return if (appClassName != null) {
                Class.forName(appClassName, false, classloader).asSubclass(Application::class.java)
            } else {
                null
            }
        }
    }

    private fun buildClassLoaderFor(fetch: CodeFetcher.Result): AppLoadResult {
        try {
            val classpathDelimiter = currentOperatingSystem.classPathDelimiter
            val files = fetch.classPath.split(classpathDelimiter).map { File(it) }
            val urls: Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()
            // Chain to the parent classloader so our internals don't interfere with the application.
            // TODO: J9: Use classloader names.
            val classloader = GravitonClassLoader(urls)
            val manifest = JarFile(files[0]).use { it.manifest }
            return AppLoadResult(classloader, manifest)
        } catch (e: java.io.FileNotFoundException) {
            throw e
        } catch (e: Exception) {
            throw AppLauncher.StartException("Failed to build classloader given class path: ${fetch.classPath}", e)
        }
    }

    private class GravitonClassLoader(urls: Array<URL>) : URLClassLoader(urls, GravitonClassLoader::class.java.classLoader.parent)
}