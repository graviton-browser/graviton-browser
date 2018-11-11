package net.plan99.graviton

import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.Property
import javafx.stage.Stage
import net.plan99.graviton.GravitonClassLoader.Companion.build
import org.apache.http.client.HttpResponseException
import org.eclipse.aether.RepositoryException
import org.eclipse.aether.transfer.MetadataNotFoundException
import java.io.File
import java.io.FileNotFoundException
import java.io.PrintStream
import java.lang.reflect.InvocationTargetException
import kotlin.concurrent.thread
import kotlin.reflect.jvm.javaMethod

/**
 * An [AppLauncher] performs the tasks needed to start an app, performing callbacks on various methods that can be
 * overridden as it goes.
 */
class AppLauncher(private val options: GravitonCLI,
                  private val events: Events?,
                  private val historyManager: HistoryManager,
                  private val primaryStage: Stage? = null,
                  private val stdOutStream: PrintStream = System.out,
                  private val stdErrStream: PrintStream = System.err) {
    /** How we plan to find the app's entry point and give it control. */
    enum class LoadStrategy {
        /** Look for a subclass of [Application] */
        SEARCH_FOR_JFX_APP,
        /** Run it via the main method in a separate JVM process. */
        RESTART_AND_RUN,
        /** Just call straight into the main method, in this JVM. Used for simple CLI apps - may go away. */
        INVOKE_MAIN_DIRECTLY
    }

    companion object : Logging() {
        fun selectLoadStrategy(mainClass: Class<*>?, executingFromGUI: Boolean): LoadStrategy {
            return if (mainClass == null) {
                LoadStrategy.SEARCH_FOR_JFX_APP
            } else if (executingFromGUI) {
                // Start a new JVM to avoid all the GUI stuff we've loaded from interfering. In particular this
                // is necessary for JavaFX apps that haven't opted into being Graviton apps, as you can't start
                // up JavaFX runtime more than once per process.
                //
                // TODO: Allow developers to override the load strategy.
                LoadStrategy.RESTART_AND_RUN
            } else {
                // From the command line, so pass control directly to main.
                LoadStrategy.INVOKE_MAIN_DIRECTLY
            }
        }
    }

    class StartException(message: String, cause: Throwable?) : Exception(message, cause) {
        constructor(message: String) : this(message, null)
    }

    open class Events : CodeFetcher.Events() {
        open fun initializingApp() {}
        open fun aboutToStartApp(outOfProcess: Boolean) {}
        open fun appFinished() {}
    }

    private val codeFetcher: CodeFetcher = CodeFetcher(options.cachePath.toPath()).also {
        it.events = events
        it.useSSL = !(commandLineArguments.noSSL || options.noSSL)
    }

    /**
     * Takes a 'command' in the form of a partial Graviton command line, extracts the coordinates, flags, and any
     * command line options that should be passed to the app, downloads the app, if successful records the app launch
     * in the history list and then invokes the app in a sub-classloader. A small wrapper around [download] and [runApp].
     */
    fun start() {
        if (options.clearCache)
            historyManager.clearCache()
        val userInput = (options.packageName ?: throw StartException("No coordinates specified"))[0]
        check(userInput.isNotBlank())
        val fetch: CodeFetcher.Result = download(userInput, options.refresh)
        // Update the entry in the history list to move it to the top.
        historyManager.recordHistoryEntry(HistoryEntry(userInput, fetch))
        runApp(userInput, fetch)
    }

    /**
     * Performs a download from the user's input, reversing coordinates and filling out missing parts if necessary.
     */
    fun download(userInput: String, forceRefresh: Boolean): CodeFetcher.Result {
        val historyLookup: HistoryEntry? = if (!forceRefresh) {
            // The user has probably specified a coordinate fragment, missing the artifact name or version number.
            // If we never saw this input before, we'll just continue and clean it up later. If we resolved it
            // before to a specific artifact coordinate, we'll look it up again from our history file. That'll
            // let us skip the latest version check.
            historyManager.search(userInput)
        } else null

        val fetch: CodeFetcher.Result = if (historyLookup != null && entryStillOnDisk(historyLookup)) {
            // The history entry contains the classpath to avoid a local POM walk, which is slow (about 0.2 seconds for
            // a very simple app like cowsay, as we're still mostly in the interpreter at this point).
            info { "Used previously resolved coordinates $historyLookup" }
            CodeFetcher.Result(historyLookup.classPath, historyLookup.artifact)
        } else {
            // Either:
            //   1. Not found in the history list, or
            //   2. Found in history list but not on disk, which can happen if the user wiped the cache.
            //
            // ... so attempt to (re)download from our repositories.
            download(userInput, codeFetcher)
        }

        info { "App name: ${fetch.name}" }
        info { "App description: ${fetch.artifact.properties["model.description"]}" }
        return fetch
    }

    private fun entryStillOnDisk(historyLookup: HistoryEntry) =
            File(historyLookup.classPath.split(currentOperatingSystem.classPathDelimiter)[0]).exists()

    private fun runApp(userInput: String, fetch: CodeFetcher.Result) {
        val loadResult: GravitonClassLoader = try {
            build(fetch.classPath)
        } catch (e: FileNotFoundException) {
            // We thought we had a fetch result but it's not on disk anymore? Probably the user wiped the cache, which deletes
            // downloaded artifacts but leaves the recent apps list alone. Let's re-resolve and try again.
            build(download(userInput, codeFetcher).classPath)
        }

        events?.initializingApp()

        // Apps are started in different ways based on various heuristics
        //
        // 1. If the JAR doesn't specify a main class, search for a JavaFX Application class and use that.
        // 2. If the specified main class doesn't seem to use JavaFX, Swing or AWT then assume it's a console app
        //    and run it in a separate thread, piped to the UI.
        // 3. If it does, and the app hasn't opted into Graviton hosting, then run out of process.
        // 4. Otherwise, give it control over our Scene.
        //
        // TODO: Define a "Graviton App" format and allow this to be customised.

        try {
            val mainClass = loadResult.mainClass
            val args = options.args.drop(1).toTypedArray()
            val isExecutingFromGUI = primaryStage != null
            val strategy = selectLoadStrategy(mainClass, isExecutingFromGUI)
            info { "Load strategy is $strategy" }
            when (strategy) {
                LoadStrategy.SEARCH_FOR_JFX_APP -> searchForAndInvokeJFXAppClass(loadResult, fetch)
                LoadStrategy.RESTART_AND_RUN -> restartAndRun(loadResult, args)
                LoadStrategy.INVOKE_MAIN_DIRECTLY -> invokeMainMethod(args, loadResult, andWait = !isExecutingFromGUI)
            }
        } catch (e: StartException) {
            throw e
        } catch (e: Throwable) {
            throw StartException("Application failed to start", e)
        }
    }

    private fun restartAndRun(cl: GravitonClassLoader, args: Array<String>) {
        // Invoke our own binary again, but this time with some special command line flags set, such that we'll boot
        // straight into the requested program. They get a whole JVM process to themselves.
        //
        // The arguments are passed straight through. The JVM does not get a chance to see them, so the user cannot control
        // JVM options this way. This is both intended, but also a limitation of the program javapackager creates, which
        // really wants us to edit a config file to alter JVM options.
        //
        // Force a full GC to release memory back to the OS (G1 doesn't do that but we don't use it)
        System.gc()
        val startPath = if (gravitonPath != null) {
            arrayOf(gravitonPath)
        } else {
            // Running not packaged, probably during development. We reflect the name "net.plan99.graviton.Graviton"
            // here in case one day the main method gets moved.
            arrayOf("java", "-cp", System.getProperty("java.class.path"), ::main.javaMethod!!.declaringClass.name)
        }
        val startArgs = arrayOf(*startPath, *args)
        info { "Restarting to execute command line: " + startArgs.joinToString(" ") }
        val pb = ProcessBuilder(*startArgs)
        pb.environment() += mapOf(
                "GRAVITON_RUN_CP" to cl.originalClassPath,
                "GRAVITON_RUN_CLASSNAME" to cl.mainClassName!!
        )
        pb.directory(currentOperatingSystem.homeDirectory.toFile())
        events?.aboutToStartApp(true)
        val proc = pb.start()
        thread {
            proc.waitFor()
            info { "Sub-process finished" }
            events?.appFinished()
        }
    }

    private fun searchForAndInvokeJFXAppClass(cl: GravitonClassLoader, fetch: CodeFetcher.Result) {
        info { "No main class, searching for a JavaFX Application subclass" }
        val clazz = cl.foundJFXClass
        if (clazz != null) {
            info { "JavaFX Application class found: $clazz" }
            invokeJavaFXApplication(clazz, primaryStage, options.args.drop(1), fetch.artifact.toString())
        } else {
            warn { "Couldn't locate any JavaFX application class" }
        }
    }

    private fun download(userInput: String, codeFetcher: CodeFetcher, reverseInput: Boolean = false): CodeFetcher.Result {
        return try {
            val coordinates: String = calculateCoordinates(userInput, reverseInput)
            info { "Attempt fetch for $coordinates" }
            codeFetcher.downloadAndBuildClasspath(coordinates)
        } catch (e: RepositoryException) {
            if (reverseInput) {
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
            } else {
                info { "User input '$userInput' not found, reversing the coordinates and trying again" }
                download(userInput, codeFetcher, true)
            }
        }
    }

    private fun calculateCoordinates(userInput: String, reverseInput: Boolean): String {
        var packageName: String = if (reverseInput) reversedCoordinates(userInput) else userInput

        // If there's no : anywhere in it, it's just a reverse domain name, then assume the artifact ID is the
        // same as the last component of the group ID.
        val components = packageName.split(':').toMutableList()
        if (components.size == 1) {
            components += components[0].split('.').last()
        }
        packageName = components.joinToString(":")

        return packageName
    }

    private fun invokeJavaFXApplication(jfxApplicationClass: Class<out Application>, primaryStage: Stage?, args: List<String>, artifactName: String) {
        if (primaryStage == null) {
            // Being started from the command line, JavaFX wasn't set up yet.
            events?.aboutToStartApp(false)
            Application.launch(jfxApplicationClass, *args.toTypedArray())
        } else {
            // Being started from the shell, we have already set up JFX and we aren't allowed to initialise it twice.
            // So we have to do a bit of acrobatics here and create the Application ourselves, then hand it a cleaned
            // version of our stage.
            fx {
                // contextClassLoader is a Java API wart we have to support. It should never be used but some things
                // do use it.
                Thread.currentThread().contextClassLoader = jfxApplicationClass.classLoader
                val app: Application = jfxApplicationClass.getConstructor().newInstance()
                ModuleHacks.setParams(app, args.toTypedArray())
                thread(name = "App initialisation thread") {
                    // Application.init is defined by the JavaFX spec as not being run on the main thread, and it's allowed
                    // to do slow blocking stuff. This is a convenient place to set up the RPC/server connections.
                    app.init()
                    fx {
                        events?.aboutToStartApp(false)
                        val curWidth = primaryStage.width
                        val curHeight = primaryStage.height
                        // We use reflection here to unbind all the Stage properties to avoid having to change this codepath if JavaFX
                        // or the shell changes e.g. by adding new properties or binding new ones.
                        primaryStage.unbindAllProperties()
                        val oldScene = primaryStage.scene
                        primaryStage.scene = null
                        primaryStage.title = artifactName
                        primaryStage.hide()
                        Platform.setImplicitExit(false)

                        fun restore() {
                            primaryStage.titleProperty().unbind()
                            primaryStage.title = "Graviton"
                            primaryStage.scene = oldScene
                            Platform.setImplicitExit(true)
                        }

                        fun quit() {
                            info { "Inlined application quitting, back to the shell" }
                            primaryStage.onCloseRequest = null
                            restore()
                            events?.appFinished()
                        }

                        primaryStage.setOnCloseRequest {
                            it.consume()   // Stop the main window closing.
                            quit()
                        }

                        try {
                            // This messing around with minWidth/Height is to avoid an ugly window resize on macOS.
                            primaryStage.minWidth = curWidth
                            primaryStage.minHeight = curHeight
                            app.start(primaryStage)
                            if (primaryStage.minWidth == curWidth)
                                primaryStage.minWidth = 0.0
                            if (primaryStage.minHeight == curHeight)
                                primaryStage.minHeight = 0.0
                            info { "JavaFX application has been invoked inline" }
                        } catch (e: Exception) {
                            restore()
                            primaryStage.show()
                        }
                    }
                }
            }
        }
    }

    private fun Any.unbindAllProperties() {
        javaClass.methods
                .filter { it.name.endsWith("Property") && Property::class.java.isAssignableFrom(it.returnType) }
                .map { it.invoke(this) as Property<*> }
                .forEach { it.unbind() }
    }

    private fun invokeMainMethod(args: Array<String>, cl: GravitonClassLoader, andWait: Boolean) {
        // Start in a separate thread, so we can continue to intercept IO and render it to the shell.
        // We'll switch to running it properly out of process in a pty at some point.
        val oldstdout = System.out
        val oldstderr = System.err
        System.setOut(stdOutStream)
        System.setErr(stdErrStream)
        events?.aboutToStartApp(false)
        val mainClass = cl.mainClass!!
        val t = thread(contextClassLoader = cl, name = "main") {
            try {
                // For me it takes about 0.5 seconds to reach here with a non-optimised build and 0.4 with a jlinked
                // build, but we're hardly using jlink right now. To get faster I bet we need to AOT most of java.base
                //
                // println("Took ${startupStopwatch.elapsedInSec} seconds to reach main()")
                runMain(mainClass, args)
            } finally {
                System.setOut(oldstdout)
                System.setErr(oldstderr)
                cl.close()
            }
        }
        if (andWait) t.join()
        info { "App has finished" }
    }

}

fun runMain(mainClass: Class<*>, args: Array<String>) {
    try {
        val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
        mainMethod.invoke(null, args)
    } catch (e: InvocationTargetException) {
        throw e.cause!!
    }
}