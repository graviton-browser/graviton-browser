@file:JvmName("Graviton")
package net.plan99.graviton

import javafx.application.Application
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.stage.Stage
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import kotlin.system.exitProcess

class GravitonBrowser : Application() {
    override fun start(stage: Stage) {
        val myPath = System.getenv("GRAVITON_PATH")
        val myVersion = System.getenv("GRAVITON_VERSION")
        Alert(Alert.AlertType.INFORMATION, "Path: $myPath, Args: ${parameters.raw}, Version: $myVersion", ButtonType.CLOSE).showAndWait()
        updateLastVersionFile(myPath, myVersion)
    }

    private fun updateLastVersionFile(myPath: String?, myVersion: String?) {
        try {
            Files.write(Paths.get(myPath).resolve("last-run-version"), listOf(myVersion))
        } catch (e: Exception) {
            Alert(Alert.AlertType.ERROR, "Failed to write to app directory: $e", ButtonType.CLOSE).showAndWait()
            exitProcess(0)
        }
    }
}

private fun checkForRuntimeUpdate() {
    File("/tmp/last-run-time").writeText(Instant.now().toString())
}

fun main(args: Array<String>) {
    if (args.isNotEmpty() && args[0] == "--background-update") {
        checkForRuntimeUpdate()
    } else {
        Application.launch(GravitonBrowser::class.java, *args)
    }
}
