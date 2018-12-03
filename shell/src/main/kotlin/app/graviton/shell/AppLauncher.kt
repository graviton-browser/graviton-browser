package app.graviton.shell

import app.graviton.ModuleHacks
import app.graviton.api.v1.Graviton
import app.graviton.api.v1.GravitonRunInShell
import app.graviton.codefetch.CodeFetcher
import app.graviton.codefetch.StartException
import app.graviton.shell.GravitonClassLoader.Companion.build
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.Property
import javafx.event.EventHandler
import javafx.stage.Stage
import javafx.stage.WindowEvent
import tornadofx.*
import java.io.File
import java.io.FileNotFoundException
import java.lang.reflect.InvocationTargetException
import java.net.URI
import java.net.URISyntaxException
import kotlin.concurrent.thread
import kotlin.reflect.jvm.javaMethod

/**
 * An [AppLauncher] performs the tasks needed to start an app, performing callbacks on various methods that can be
 * overridden as it goes.
 */
class AppLauncher(private val options: GravitonCLI,
                  private val events: Events?,
                  private val historyManager: HistoryManager,
                  private val primaryStage: Stage? = null) {
    companion object : Logging()

    /**
     * This class implements the Graviton API.
     */
    inner class Gateway : Graviton {
        /**
         * Returns the integer version of Graviton. This is not the same thing as an API version: it may increase any time
         * without affecting anything.
         */
        override fun getVersion(): Int = envVars?.gravitonVersion ?: -1

        /**
         * Returns the width of the drawable area in either pixels or columns, for GUI and terminal apps respectively.
         * Size your [javafx.scene.Scene] to this width if you want it to fill the browser area, or leave it
         * smaller to allow the background art to show through. May return zero if there is no attached screen.
         */
        override fun getWidth(): Int = primaryStage?.width?.toInt() ?: 0

        /**
         * Returns the height of the drawable area in either pixels or rows, for GUI and terminal apps respectively.
         * Size your [javafx.scene.Scene] to this height if you want it to fill the browser area, or leave it
         * smaller to allow the background art to show through. May return zero if there is no attached screen.
         */
        override fun getHeight(): Int = primaryStage?.height?.toInt() ?: 0
    }

    /** How we plan to find the app's entry point and give it control. */
    enum class LoadStrategy {
        /** Run it via the main method in a separate JVM process. */
        RESTART_AND_RUN,
        /** Just call straight into the main method, in this JVM. Used when we're run from the CLI. */
        INVOKE_MAIN_DIRECTLY,
        /** It knows about us, so run it directly in-process. */
        GRAVITON_APP
    }

    open class Events : CodeFetcher.Events() {
        open fun preparingToDownload() {}
        open fun initializingApp() {}
        open fun aboutToStartApp(outOfProcess: Boolean) {}
        open fun appFinished() {}
        open fun onError(e: Exception) {}
    }

    private val codeFetcher: CodeFetcher = CodeFetcher(options.cachePath.toPath(), events, options.repoSpec())

    /**
     * Takes a 'command' in the form of a partial Graviton command line, extracts the coordinates, flags, and any
     * command line options that should be passed to the app, downloads the app, if successful records the app launch
     * in the history list and then invokes the app in a sub-classloader. A small wrapper around [lookupOrDownload] and [runApp].
     */
    fun start() {
        try {
            if (options.clearCache)
                historyManager.clearCache()
            val userInput = (options.packageName ?: throw StartException("No coordinates specified"))[0]
            check(userInput.isNotBlank())
            val fetch: CodeFetcher.Result = lookupOrDownload(userInput, options.refresh)
            // Update the entry in the history list to move it to the top.
            historyManager.recordHistoryEntry(HistoryEntry(userInput, fetch))
            runApp(userInput, fetch)
        } catch (e: Exception) {
            events?.onError(e)
            throw e
        }
    }

    /**
     * Performs a download from the user's input, reversing coordinates and filling out missing parts if necessary.
     */
    fun lookupOrDownload(userInput: String, forceRefresh: Boolean): CodeFetcher.Result {
        val coordinate = maybeConvertURLToCoordinate(userInput)

        val historyLookup: HistoryEntry? = if (!forceRefresh) {
            // The user has probably specified a coordinate fragment, missing the artifact name or version number.
            // If we never saw this input before, we'll just continue and clean it up later. If we resolved it
            // before to a specific artifact coordinate, we'll look it up again from our history file. That'll
            // let us skip the latest version check.
            historyManager.search(coordinate)
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
            events?.preparingToDownload()
            codeFetcher.download(coordinate)
        }

        info { "App name: ${fetch.name}" }
        info { "App description: ${fetch.artifact.properties["model.description"]}" }
        return fetch
    }

    private fun maybeConvertURLToCoordinate(userInput: String): String {
        if (!(userInput.startsWith("http://") || userInput.startsWith("https://") || userInput.startsWith("github.com/")))
            return userInput

        val full = if (userInput.startsWith("github.com/")) "https://$userInput" else userInput
        try {
            val uri = URI(full)
            val pathComponents = uri.path.drop(1).split('/')
            if (pathComponents.size != 2)
                return userInput
            val (username, reponame) = pathComponents
            val result = "com.github.$username:$reponame"
            info { "User input $userInput parsed as GitHub coordinate: $result" }
            return result
        } catch (e: URISyntaxException) {
            return userInput
        }
    }

    private fun entryStillOnDisk(historyLookup: HistoryEntry) =
            File(historyLookup.classPath.split(currentOperatingSystem.classPathDelimiter)[0]).exists()

    private fun runApp(userInput: String, fetch: CodeFetcher.Result) {
        val loadResult: GravitonClassLoader = try {
            build(fetch.classPath)
        } catch (e: FileNotFoundException) {
            // We thought we had a fetch result but it's not on disk anymore? Probably the user wiped the cache, which deletes
            // downloaded artifacts but leaves the recent apps list alone. Let's re-resolve and try again.
            build(codeFetcher.download(userInput).classPath)
        }

        events?.initializingApp()

        // Apps are started in different ways based on various rules.
        //
        // 1. If we're run from the command line, locate the main method and invoke it, or bail out.
        // 2. If we're in GUI mode, and if the JAR specifies a main class, and if it implements the
        //    GravitonRunInShell interface, then invoke the createScene method and switch to that scene.
        //    Then invoke the start method to pass in the stage.
        // 3. If the main class is a regular JavaFX Application class and there's no main method in it, then
        //    just invoke the start method directly and let it switch out the scene itself. We don't get to do a nice
        //    transition this way or customise the app in other ways, but it's useful for devs who are getting started.
        // 4. Otherwise, if there's a main method, then invoke it in a separate JVM. The app can do whatever it
        //    likes with the new process.

        try {
            val args = options.args.drop(1).toTypedArray()
            val isExecutingFromGUI = primaryStage != null
            val strategy = selectLoadStrategy(loadResult, isExecutingFromGUI)
            info { "Load strategy is $strategy" }
            when (strategy) {
                // From GUI
                LoadStrategy.RESTART_AND_RUN -> restartAndRun(loadResult, args)
                LoadStrategy.GRAVITON_APP -> startGravitonApp(loadResult, fetch)
                // From CLI
                LoadStrategy.INVOKE_MAIN_DIRECTLY -> invokeMainMethod(args, loadResult, andWait = !isExecutingFromGUI)
            }
        } catch (e: StartException) {
            throw e
        } catch (e: Throwable) {
            throw StartException("Application failed to start", e)
        }
    }

    private fun startGravitonApp(loadResult: GravitonClassLoader, fetch: CodeFetcher.Result) {
        val primaryStage = primaryStage!!
        val appClass = loadResult.startClass.asSubclass(Application::class.java)
        // TODO: Create a ThreadGroup and then use SecurityManager to ensure that newly started threads are in that group.
        thread(name = "App init thread: ${fetch.name}") {
            Thread.currentThread().contextClassLoader = appClass.classLoader

            // Create the Application object and cast so Kotlin knows it implements the extra interface.
            val app: Application = appClass.getConstructor().newInstance()
            ModuleHacks.setParams(app, options.args.drop(1).toTypedArray())

            // Application.init is defined by the JavaFX spec as not being run on the main thread, and it's allowed
            // to do slow blocking stuff. This is a convenient place to set up the RPC/server connections.
            // We could show a splash or logo of the app here.
            app.init()
            // Back to the FX thread now the app is initialised.
            fx {
                // The context classloader is a confusing concept that was never really meant to happen. It was added
                // as a quick hack in Java 1.1 for serialization and has haunted us ever since. JavaFX uses it to load
                // stylesheets, amongst other abuses (it should never be used for real), so we do have to ensure we
                // configure it properly here.
                Thread.currentThread().contextClassLoader = appClass.classLoader
                events?.aboutToStartApp(false)
                val curWidth = primaryStage.width
                val curHeight = primaryStage.height
                // We use reflection here to unbind all the Stage properties to avoid having to change this codepath if JavaFX
                // or the shell changes e.g. by adding new properties or binding new ones.
                primaryStage.unbindAllProperties()

                val oldScene = primaryStage.scene
                // The app may not implement the Graviton API if it's opted in via manifest attributes. So we have to
                // handle both codepaths.
                val newScene = (app as? GravitonRunInShell)?.createScene(Gateway())
                val implementsAPI = newScene != null

                fun proceed() {
                    // Pick a default title. The app may of course override it in Application.start()
                    primaryStage.title = fetch.artifact.toString()
                    Platform.setImplicitExit(false)

                    fun restore() {
                        Thread.currentThread().contextClassLoader = AppLauncher::class.java.classLoader
                        primaryStage.titleProperty().unbind()
                        primaryStage.title = "Graviton"
                        primaryStage.scene = oldScene
                        Platform.setImplicitExit(true)
                    }

                    val oldOnCloseRequest = primaryStage.onCloseRequest
                    val closeFilter = object : EventHandler<WindowEvent> {
                        /**
                         * Invoked when a specific event of the type for which this handler is
                         * registered happens.
                         *
                         * @param event the event which occurred
                         */
                        override fun handle(event: WindowEvent) {
                            // Stop the main window closing.
                            event.consume()
                            // Let the app handle the event as if we had not intervened.
                            primaryStage.onCloseRequest?.handle(event)
                            primaryStage.onCloseRequest = oldOnCloseRequest
                            primaryStage.removeEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, this)
                            info { "Inlined application quitting, back to the shell" }
                            restore()
                            events?.appFinished()
                        }
                    }
                    primaryStage.addEventFilter(WindowEvent.WINDOW_CLOSE_REQUEST, closeFilter)

                    try {
                        // This messing around with minWidth/Height is to avoid an ugly window resize on macOS.
                        primaryStage.minWidth = curWidth
                        primaryStage.minHeight = curHeight

                        if (!implementsAPI) {
                            // Opted in via the manifest attribute, without code changes. So it will try to show the
                            // stage, which throws if it's already being shown. This doesn't cause any flicker because
                            // the change only occurs next time we reach the event loop.
                            primaryStage.hide()
                        } else {
                            // The app is expected to notice it's been called inside Graviton and thus, that the stage
                            // is visible. In that case start will do very little.
                        }
                        app.start(primaryStage)
                        if (primaryStage.minWidth == curWidth)
                            primaryStage.minWidth = 0.0
                        if (primaryStage.minHeight == curHeight)
                            primaryStage.minHeight = 0.0
                        info { "JavaFX application has been invoked inline" }
                    } catch (e: Throwable) {
                        logger.warn("Exception caught from Application.start()", e)
                        // TODO: Surface the crash in the GUI.
                        restore()
                    }
                }

                if (implementsAPI) {
                    find<ShellView>().fadeInScene(newScene!!) {
                        proceed()
                    }
                } else {
                    proceed()
                }
            }
        }
    }

    private fun selectLoadStrategy(cl: GravitonClassLoader, executingFromGUI: Boolean): AppLauncher.LoadStrategy {
        return if (executingFromGUI) {
            if (GravitonRunInShell::class.java.isAssignableFrom(cl.startClass) || "inline" in cl.requestedFeatures) {
                AppLauncher.LoadStrategy.GRAVITON_APP
            } else {
                // Start a new JVM to avoid all the GUI stuff we've loaded from interfering. In particular this
                // is necessary for JavaFX apps that haven't opted into being Graviton apps, as you can't start
                // up JavaFX runtime more than once per process.
                AppLauncher.LoadStrategy.RESTART_AND_RUN
            }
        } else {
            AppLauncher.LoadStrategy.INVOKE_MAIN_DIRECTLY
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
        val startPath = if (envVars != null) {
            arrayOf(envVars.gravitonExePath.toString())
        } else {
            // Running not packaged, probably during development. We reflect the start class name here in case one day the main method gets moved.
            arrayOf("java", "-cp", System.getProperty("java.class.path"), ::main.javaMethod!!.declaringClass.name)
        }
        val startArgs = arrayOf(*startPath, *args)
        info { "Restarting to execute command line: " + startArgs.joinToString(" ") }
        val pb = ProcessBuilder(*startArgs)
        pb.environment() += mapOf(
                "GRAVITON_RUN_CP" to cl.originalClassPath,
                "GRAVITON_RUN_CLASSNAME" to cl.startClass.name
        )
        pb.directory(currentOperatingSystem.homeDirectory.toFile())
        events?.aboutToStartApp(true)
        // TODO: Wire up stdin/stdout/stderr to a GUI terminal emulator.
        val proc = pb.inheritIO().start()
        thread {
            proc.waitFor()
            info { "Sub-process finished" }
            events?.appFinished()
        }
    }

    private fun Any.unbindAllProperties() {
        javaClass.methods
                .filter { it.name.endsWith("Property") && Property::class.java.isAssignableFrom(it.returnType) }
                .map { it.invoke(this) as Property<*> }
                .forEach { it.unbind() }
    }

    private fun invokeMainMethod(args: Array<String>, cl: GravitonClassLoader, andWait: Boolean) {
        events?.aboutToStartApp(false)
        val tg = ThreadGroup("Application")
        val t = Thread(tg, Runnable {
            // For me it takes about 0.5 seconds to reach here with a non-optimised build and 0.4 with a jlinked
            // build, but we're hardly using jlink right now. To get faster I bet we need to AOT most of java.base
            //
            // println("Took ${startupStopwatch.elapsedInSec} seconds to reach main()")
            runMain(cl.startClass, args)
        }, "main")
        t.contextClassLoader = cl
        t.start()
        if (andWait) t.join()
        // Note that we don't close the classloader. To do that would require us to know when every thread has finished
        // and we don't care about the leak here, as we'll be exiting soon anyway.
        info { "Main thread has exited" }
    }

}

fun runMain(mainClass: Class<*>, args: Array<String>) {
    try {
        val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
        mainMethod.invoke(null, args)
    } catch (e: NoSuchMethodException) {
        // Maybe it's a JavaFX class?
        if (Application::class.java.isAssignableFrom(mainClass)) {
            Application.launch(mainClass.asSubclass(Application::class.java), *args)
        } else {
            throw e
        }
    } catch (e: InvocationTargetException) {
        throw e.cause!!
    }
}