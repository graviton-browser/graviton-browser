@file:JvmName("Graviton")
package net.plan99.graviton

import picocli.CommandLine
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.concurrent.thread

fun main(arguments: Array<String>) {
    val myPath = System.getenv("GRAVITON_PATH")
    val myVersion = System.getenv("GRAVITON_VERSION")
    updateLastVersionFile(myPath, myVersion)

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

private fun updateLastVersionFile(myPath: String?, myVersion: String?) {
    // Do it in the background to keep the slow file IO away from starting up.
    thread(start = true) {
        try {
            Files.write(Paths.get(myPath).resolve("last-run-version"), listOf(myVersion))
        } catch (e: Exception) {
            // Ignore - being run outside the normal packaged/installed environment.
        }
    }
}

fun checkForRuntimeUpdate() {
    File("/tmp/last-run-time").writeText(Instant.now().toString())
    // TODO: Finish this off.
}
