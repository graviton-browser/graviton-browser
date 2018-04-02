@file:JvmName("Graviton")
package net.plan99.graviton

import javafx.application.Application
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.stage.Stage
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.RepositoryException
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
    var args = arguments
    if (args.isNotEmpty()) {
        if (args[0] == "--background-update") {
            checkForRuntimeUpdate()
            exitProcess(0)
        }
        val codeFetcher = CodeFetcher()
        fun dropArg() { args = args.drop(1).toTypedArray() }
        while (args.isNotEmpty()) {
            if (args[0] == "--clear-cache") {
                codeFetcher.clearCache()
                dropArg()
            } else if (args[0] == "--offline") {
                codeFetcher.offline = true
                dropArg()
            } else if (args[0].split(':').size >= 2) {
                invokeCommandLineApp(args, codeFetcher)
            } else break
        }
        println("Usage: graviton [--clear-cache] [--offline] group-id:artifact-id[:version] app-arg-1 app-arg-2...")
        exitProcess(1)
    }
    Application.launch(GravitonBrowser::class.java, *args)
}

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
    // TODO: Finish this off.
}

private fun downloadWithProgressBar(artifactName: String, downloader: CodeFetcher, packageName: String): String {
    var totalBytesToDownload = 0L
    var totalDownloaded = 0L
    val pb = ProgressBar("Download $artifactName", -1, 100, System.out, ProgressBarStyle.ASCII)
    var needToStart = true
    downloader.allTransferEvents.subscribe {
        if (it.type == TransferEvent.EventType.INITIATED) {
            if (needToStart) {
                pb.start()
                needToStart = false
            }
        } else if (it.type == TransferEvent.EventType.STARTED) {
            totalBytesToDownload += it.data.resource.contentLength
            pb.maxHint(totalBytesToDownload / 1024)
        }
        if (it.type == TransferEvent.EventType.FAILED || it.type == TransferEvent.EventType.CORRUPTED) {
            pb.stop()
        } else {
            totalDownloaded += it.data.dataLength
            pb.stepTo(totalDownloaded / 1024)
            pb.extraMessage = "${it.data.requestType.name}: ${it.data.resource.file.name}"
        }
    }
    return try {
        downloader.downloadAndBuildClasspath(packageName)
    } finally {
        if (!needToStart) pb.stop()
    }
}

private class AppLoadResult(val classloader: URLClassLoader, val appManifest: Manifest) {
    val mainClassName: String get() = appManifest.mainAttributes.getValue("Main-Class")
    val mainClass: Class<*> get() = Class.forName(mainClassName, true, classloader)
}

private fun buildClassLoaderFor(packageName: String, downloader: CodeFetcher): AppLoadResult {
    val artifactName = packageName.split(':')[1]
    val classpath = downloadWithProgressBar(artifactName, downloader, packageName)
    val files = classpath.split(':').map { File(it) }
    val urls: Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()
    // Chain to the parent classloader so our internals don't interfere with the application.
    val classloader = URLClassLoader(packageName, urls, Thread.currentThread().contextClassLoader.parent)
    val manifest = JarFile(files[0]).use { it.manifest }
    return AppLoadResult(classloader, manifest)
}

private fun invokeCommandLineApp(args: Array<String>, codeFetcher: CodeFetcher) {
    val packageName = args[0]
    val loadResult = try {
        buildClassLoaderFor(packageName, codeFetcher)
    } catch (e: RepositoryException) {
        val rootCause = e.rootCause
        if (rootCause is MetadataNotFoundException) {
            println("Sorry, no package with those coordinates is known.")
        } else {
            println("Fetch error: ${rootCause.message}")
        }
        exitProcess(1)
    }
    val mainMethod = loadResult.mainClass.getMethod("main", Array<String>::class.java)
    val subArgs = args.drop(1).toTypedArray()
    mainMethod.invoke(null, subArgs)
}

