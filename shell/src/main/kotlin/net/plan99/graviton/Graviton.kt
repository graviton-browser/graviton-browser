@file:JvmName("Graviton")
package net.plan99.graviton

import javafx.application.Application
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.RepositoryException
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import picocli.CommandLine
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.time.Instant
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.system.exitProcess

fun main(arguments: Array<String>) {
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

@CommandLine.Command(
        name = "graviton",
        description = [
            "Graviton is an application browser and shell for the JVM. It will run and keep up to date programs from Maven repositories.",
            "If no arguments are specified, the GUI is invoked."
        ],
        mixinStandardHelpOptions = true,
        versionProvider = GravitonCLI.VersionProvider::class
)
class GravitonCLI : Runnable {
    @CommandLine.Parameters(
            arity = "0..1",
            description = [
                "Maven coordinates of the package to run in the form of groupId:artifactId[:version]",
                "You can omit the version number to fetch the latest version."
            ]
    )
    var packageName: Array<String>? = null

    @CommandLine.Parameters(arity = "0..1", description = ["Arguments to pass to the invoked program"])
    var args: Array<String>? = null

    @CommandLine.Option(names = ["--clear-cache"], description = ["Deletes the contents of the app cache directory before starting."])
    var clearCache: Boolean = false

    @CommandLine.Option(names = ["--offline"], description = ["Skip checks against remote repositories for snapshot or LATEST versions."])
    var offline: Boolean = false

    // Invoked by the cron job we install, so don't show it in the help.
    @CommandLine.Option(names = ["--background-update"], hidden = true)
    var backgroundUpdate: Boolean = false

    override fun run() {
        if (backgroundUpdate) {
            checkForRuntimeUpdate()
        } else if (packageName != null || clearCache) {
            val codeFetcher = CodeFetcher()
            codeFetcher.offline = offline
            if (clearCache) codeFetcher.clearCache()
            if (packageName != null) invokeCommandLineApp(args ?: emptyArray(), codeFetcher)
        } else {
            Application.launch(GravitonBrowser::class.java, *(args ?: emptyArray()))
        }
    }

    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion(): Array<String> {
            return arrayOf(javaClass.`package`.implementationVersion.let { if (it.isNullOrBlank()) "DEV" else it })
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
    val timeAtStart = System.nanoTime()
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
        if (!needToStart) {
            pb.stop()
            val elapsedSec = (System.nanoTime() - timeAtStart) / 100000000 / 10.0
            println("Downloaded successfully in $elapsedSec seconds")
        }
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
    try {
        mainMethod.invoke(null, subArgs)
    } catch (e: Throwable) {
        (e.cause ?: e).printStackTrace()
        exitProcess(1)
    }
}

