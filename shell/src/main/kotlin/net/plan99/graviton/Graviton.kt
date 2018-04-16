@file:JvmName("Graviton")
package net.plan99.graviton

import net.plan99.graviton.scheduler.OSScheduledTaskDefinition
import net.plan99.graviton.scheduler.OSTaskScheduler
import org.slf4j.LoggerFactory
import picocli.CommandLine
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import kotlin.concurrent.thread

val startupStopwatch = Stopwatch()

val GRAVITON_PATH: String? = System.getenv("GRAVITON_PATH")
val GRAVITON_VERSION: String? = System.getenv("GRAVITON_VERSION")

val mainLog get() = LoggerFactory.getLogger("main")

/** Global access to parsed command line flags. */
val commandLineArguments = GravitonCLI()

fun main(arguments: Array<String>) {
    try {
        if (arguments.isNotEmpty() && arguments[0] == "--uninstall") {
            lastRun()
            return
        }

        val cli = CommandLine(commandLineArguments)
        cli.isStopAtPositional = true
        cli.usageHelpWidth = if (arguments.isNotEmpty()) getTermWidth() else 80  // Don't care
        // TODO: Set up bash/zsh auto completion.
        cli.parseWithHandlers(CommandLine.RunLast(), CommandLine.DefaultExceptionHandler<List<Any>>(), *arguments)
    } catch (e: Throwable) {
        mainLog.error("Failed to start up", e)
        e.printStackTrace()
        if (currentOperatingSystem == OperatingSystem.WIN) {
            windowsAlertBox("Failed to start up", e.asString())
        }
    }
}

private fun getTermWidth(): Int {
    return try {
        when (currentOperatingSystem) {
            OperatingSystem.MAC, OperatingSystem.LINUX -> {
                val proc = ProcessBuilder("stty", "size").redirectInput(ProcessBuilder.Redirect.INHERIT).start()
                proc.waitFor()
                val o2 = String(proc.inputStream.readAllBytes())
                val output = o2.split(' ')[1].trim()
                output.toInt()
            }
            else -> 80
        }
    } catch (t: Throwable) {
        80
    }
}

fun startupChecks(myPath: String, myVersion: String) {
    // Do it in the background to keep the slow file IO away from blocking startup.
    thread(start = true) {
        try {
            val appPath: Path = Paths.get(myPath)
            val versionPath = appPath / "last-run-version"
            val taskSchedulerErrorFile = appPath / "task-scheduler-error-log.txt"
            if (!versionPath.exists || taskSchedulerErrorFile.exists)
                firstRun(appPath, taskSchedulerErrorFile)
            Files.write(versionPath, listOf(myVersion))
        } catch (e: Exception) {
            // Log but don't block startup.
            mainLog.error("Failed to do background startup checks", e)
        }
    }
}

private const val taskName = "net.plan99.graviton.update"

private fun firstRun(myPath: Path, taskSchedulerErrorFile: Path) {
    mainLog.info("First run, attempting to register scheduled task")
    val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
    if (scheduler == null) {
        mainLog.info("No support for task scheduling on this OS: $currentOperatingSystem")
        return
    }
    val executePath = when (currentOperatingSystem) {
        OperatingSystem.MAC -> myPath / "MacOS" / "Graviton Browser"
        OperatingSystem.WIN -> myPath / "GravitonBrowser.exe"
        OperatingSystem.LINUX -> myPath / "GravitonBrowser"
        OperatingSystem.UNKNOWN -> return
    }
    val scheduledTask = OSScheduledTaskDefinition(
            executePath = executePath,
            arguments = listOf("--background-update"),
            frequency = Duration.ofDays(1),
            description = "Graviton background upgrade task. If you disable this, Graviton Browser may become insecure.",
            networkSensitive = true
    )
    try {
        // TODO: For some reason the Windows setup always throws an error from schtasks, but always seems to work anyway.
        scheduler.register(taskName, scheduledTask)
        mainLog.info("Registered background task successfully with name '$taskName'")
    } catch (e: Exception) {
        // If we failed to register the task we will store the error to a dedicated file, which will act
        // as a marker to retry next time.
        Files.deleteIfExists(taskSchedulerErrorFile)
        taskSchedulerErrorFile.toFile().writer().use {
            e.printStackTrace(PrintWriter(it))
        }
    }
}

private fun lastRun() {
    mainLog.info("Uninstallation requested, removing scheduled task")
    val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
    if (scheduler == null) {
        mainLog.info("No support for task scheduling on this OS: $currentOperatingSystem")
        return
    }
    scheduler.deregister(taskName)
}