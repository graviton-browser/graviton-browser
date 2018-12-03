@file:JvmName("Graviton")
package app.graviton.shell

import javafx.beans.property.SimpleBooleanProperty
import javafx.scene.effect.Effect
import javafx.scene.image.Image
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import picocli.CommandLine
import tornadofx.*
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.concurrent.thread

//
// The main method, argument parsing, first run checks, last run handling (uninstall), console setup.
//

//region Global variables
class BootstrapperEnvVars {
    /**
     * The base directory in which the versioned subdirectories are found: $GRAVITON_PATH/$GRAVITON_VERSION will contain the
     * versioned installation.
     */
    val gravitonPath: Path = Paths.get(System.getenv("GRAVITON_PATH"))

    /**
     * Path to the actual versioned binary the bootstrapped invoked (if we were run via the bootstrapper).
     */
    val gravitonExePath: Path = Paths.get(System.getenv("GRAVITON_EXE"))

    /** The current version, as discovered by the bootstrapper. */
    val gravitonVersion: Int = System.getenv("GRAVITON_VERSION")!!.toInt()
}

val envVars: BootstrapperEnvVars? = if (System.getenv("GRAVITON_PATH") != null) BootstrapperEnvVars() else null

/** The top level logger for the app. */
val mainLog: Logger by lazy { LoggerFactory.getLogger("main") }

/** Global access to parsed command line flags. */
var commandLineArguments = GravitonCLI(arrayOf(""))

/** Controls whether the spinner animation is active or not. */
val isWorking by lazy { SimpleBooleanProperty() }

// Allow for minimal rebranding in future.
const val appBrandName = "Graviton"
/** Should the name be put next to the logo, or, should we just use the logo alone (i.e. it is the name) */
const val appBrandLogoIsName = false
/** If set, an effect to apply to the logo image. */
val appLogoEffect: Effect? = null
/** The logo image to use on the UI;. */
val Component.appBrandLogo get() = Image(resources["art/icons8-rocket-take-off-128.png"])
//endregion

fun main(arguments: Array<String>) {
    try {
        main1(arguments)
    } catch (e: Throwable) {
        try {
            mainLog.error("Failed to start up", e)
            e.printStackTrace()
            if (currentOperatingSystem == OperatingSystem.WIN) {
                windowsAlertBox("Failed to start up", e.asString())
            }
        } catch (e: Throwable) {
            // Just not our day today.....
        }
    }
}

private fun main1(arguments: Array<String>) {
    // The shell may request that we just immediately run a program with a provided classpath, as part of starting
    // up a non-Graviton app outside the shell process.
    if (immediatelyInvokeApplication(arguments))
        return

    var forceAnsi = false
    if (currentOperatingSystem == OperatingSystem.WIN) {
        // Windows has managed to screw up its console handling really, really badly. We need some hacks to
        // make command line apps and GUI apps work from the same (ish) binary. See the configureWindowsConsole
        // function for the gory details.
        forceAnsi = configureWindowsConsole()
    }

    commandLineArguments = GravitonCLI(arguments)
    val cli = CommandLine(commandLineArguments)
    cli.isStopAtPositional = true
    cli.usageHelpWidth = if (arguments.isNotEmpty()) getTermWidth() else 80  // Don't care

    // Force ANSI on because we enable it on Windows 10 now.
    val handler = CommandLine.RunLast()
    if (forceAnsi)
        handler.useAnsi(CommandLine.Help.Ansi.ON)

    // This call will pass control to GravitonCLI.run (as that's the object in commandLineArguments).
    cli.parseWithHandlers(handler, CommandLine.DefaultExceptionHandler<List<Any>>(), *arguments)
}

private fun immediatelyInvokeApplication(arguments: Array<String>): Boolean {
    // This is only called when an app is invoked from the Graviton GUI, so we don't care about ANSI or console stuff here.
    // Use environment variables to allow us to keep the arguments list clean, and to stop anyone from being able to
    // divert us onto this codepath in case of URL handler bugs (URLs cannot set environment variables).
    val runCP: String = System.getenv("GRAVITON_RUN_CP") ?: return false
    val runClassName: String = System.getenv("GRAVITON_RUN_CLASSNAME") ?: return false
    val cl = GravitonClassLoader.build(runCP)
    val clazz = cl.loadClass(runClassName)
    // This thread will kick off and start running the program. It won't be able to see Graviton's classes because it's
    // in a separate classloader that doesn't chain to the one that loaded us. This isn't perfectly compatible (a few
    // big/complex apps expect the classloader to be a sun.misc.AppClassLoader) but it'll do for now. This thread will
    // continue, die, and the new thread will be the only one left. Eventually the GC should clear out the code in
    // this file from memory, and the new app will have a relatively clean stack trace.
    thread(name = "main", contextClassLoader = cl) {
        runMain(clazz, arguments)
    }
    return true
}

private fun getTermWidth(): Int {
    return try {
        when (currentOperatingSystem) {
            OperatingSystem.MAC, OperatingSystem.LINUX -> {
                val proc = ProcessBuilder("stty", "size").redirectInput(ProcessBuilder.Redirect.INHERIT).start()
                proc.waitFor()
                val o2 = String(proc.inputStream.readBytes())
                val output = o2.split(' ')[1].trim()
                output.toInt()
            }
            else -> 80
        }
    } catch (t: Throwable) {
        80
    }
}