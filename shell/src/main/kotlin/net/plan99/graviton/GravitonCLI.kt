package net.plan99.graviton

import javafx.application.Application
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import picocli.CommandLine

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
        val packageName = packageName
        if (backgroundUpdate) {
            checkForRuntimeUpdate()
        } else if (packageName != null || clearCache) {
            val codeFetcher = CodeFetcher()
            codeFetcher.offline = offline
            if (clearCache) codeFetcher.clearCache()
            if (packageName != null) {
                try {
                    val artifactName = packageName[0].split(':')[1]
                    val classpath = downloadWithProgressBar(artifactName, codeFetcher, packageName[0])
                    invokeMainClass(classpath, packageName[0],args ?: emptyArray()).join()
                } catch (original: Throwable) {
                    val e = original.rootCause
                    if (e is MetadataNotFoundException) {
                        println("Sorry, that package is unknown. Check for typos? (${e.metadata})")
                    } else {
                        println(e.message)
                    }
                }
            }
        } else {
            Application.launch(GravitonBrowser::class.java, *(args
                    ?: emptyArray()))
        }
    }

    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion(): Array<String> {
            return arrayOf(javaClass.`package`.implementationVersion.let { if (it.isNullOrBlank()) "DEV" else it })
        }
    }
}

private fun downloadWithProgressBar(artifactName: String, downloader: CodeFetcher, packageName: String): String {
    var totalBytesToDownload = 0L
    var totalDownloaded = 0L
    val pb = ProgressBar("Download $artifactName", -1, 100, System.out, ProgressBarStyle.ASCII)
    var didDownload = false
    val timeAtStart = System.nanoTime()
    downloader.allTransferEvents.subscribe {
        if (it.type == TransferEvent.EventType.INITIATED) {
            if (!didDownload) {
                pb.start()
                didDownload = true
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
    try {
        val cp = downloader.downloadAndBuildClasspath(packageName)
        if (didDownload) {
            val elapsedSec = (System.nanoTime() - timeAtStart) / 100000000 / 10.0
            println("Downloaded successfully in $elapsedSec seconds")
        }
        return cp
    } finally {
        if (didDownload) {
            pb.stop()
        }
    }
}
