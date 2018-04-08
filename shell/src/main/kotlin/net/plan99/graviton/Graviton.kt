@file:JvmName("Graviton")
package net.plan99.graviton

import mu.KotlinLogging
import net.plan99.graviton.scheduler.OSScheduledTaskDefinition
import net.plan99.graviton.scheduler.OSTaskScheduler
import picocli.CommandLine
import java.io.File
import java.io.PrintWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Duration
import java.time.Instant
import kotlin.concurrent.thread

private val log = KotlinLogging.logger {}

fun main(arguments: Array<String>) {
    if (arguments.isNotEmpty() && arguments[0] == "--uninstall") {
        lastRun()
        return
    }

    val myPath: String? = System.getenv("GRAVITON_PATH")
    val myVersion: String? = System.getenv("GRAVITON_VERSION")
    if (myPath != null && myVersion != null) {
        // This will execute asynchronously.
        startupChecks(myPath, myVersion)
    }

    val cli = CommandLine(GravitonCLI())
    cli.isStopAtPositional = true
    cli.usageHelpWidth = if (arguments.isNotEmpty()) getTermWidth() else 80  // Don't care
    // TODO: Set up bash/zsh auto completion.
    cli.parseWithHandlers(CommandLine.RunLast(), CommandLine.DefaultExceptionHandler<List<Any>>(), *arguments)
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

private fun startupChecks(myPath: String, myVersion: String) {
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
            log.error(e) { "Failed to do background startup checks" }
        }
    }
}

private val taskName = "net.plan99.graviton.update"

private fun firstRun(myPath: Path, taskSchedulerErrorFile: Path) {
    log.info { "First run, attempting to register scheduled task" }
    val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
    if (scheduler == null) {
        log.info { "No support for task scheduling on this OS: $currentOperatingSystem" }
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
        log.info { "Registered background task successfully with name '$taskName'" }
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
    log.info { "Uninstallation requested, removing scheduled task" }
    val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
    if (scheduler == null) {
        log.info { "No support for task scheduling on this OS: $currentOperatingSystem" }
        return
    }
    scheduler.deregister(taskName)
}

fun doBackgroundUpdate() {
    File("/tmp/last-run-time").writeText(Instant.now().toString())
    // TODO: Finish this off.
}
