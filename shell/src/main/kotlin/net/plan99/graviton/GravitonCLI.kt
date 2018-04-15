package net.plan99.graviton

import javafx.application.Application
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import picocli.CommandLine
import kotlin.system.exitProcess

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
    var args: Array<String> = emptyArray()

    @CommandLine.Option(names = ["--clear-cache"], description = ["Deletes the contents of the app cache directory before starting."])
    var clearCache: Boolean = false

    @CommandLine.Option(names = ["--offline"], description = ["Skip checks against remote repositories for snapshot or LATEST versions."])
    var offline: Boolean = false

    // Invoked by the cron job we install, so don't show it in the help.
    @CommandLine.Option(names = ["--background-update"], hidden = true)
    var backgroundUpdate: Boolean = false

    // Just for development.
    @CommandLine.Option(names = ["--profile-downloads"], description = ["If larger than one downloads the coordinates the given number of times and prints statistics"], hidden = true)
    var profileDownloads: Int = -1

    @CommandLine.Option(names = ["--no-ssl"], description = ["If set, SSL encryption to the Maven repositories will be disabled. This can make downloads much faster, but also less safe."])
    var noSSL: Boolean = false

    @CommandLine.Option(names = ["--verbose"], description = ["Enable logging"])
    var verboseLogging: Boolean = false

    @CommandLine.Option(names = ["--default-coordinate"], description = ["The default launch coordinate put in the address bar of the browser shell, may contain command line arguments"])
    var defaultCoordinate: String = "com.github.ricksbrown:cowsay -f tux \"Hello world!\""

    override fun run() {
        val packageName = packageName
        setupLogging(verboseLogging)
        // TODO: Enable coloured output on Windows 10+, so client apps can use ANSI escapes without fear.
        if (GRAVITON_PATH != null && GRAVITON_VERSION != null) {
            // This will execute asynchronously.
            startupChecks(GRAVITON_PATH, GRAVITON_VERSION)
            val ls = System.lineSeparator()
            mainLog.info("$ls${ls}Starting Graviton $GRAVITON_VERSION$ls$ls")
            mainLog.info("Path is $GRAVITON_PATH")
        }
        if (backgroundUpdate) {
            doBackgroundUpdate()
        } else if (packageName != null || clearCache) {
            handleCommandLineInvocation(packageName)
        } else {
            Application.launch(GravitonBrowser::class.java, *args)
        }
    }

    private fun handleCommandLineInvocation(packageName: Array<String>?) {
        val codeFetcher = CodeFetcher()
        codeFetcher.offline = offline
        codeFetcher.useSSL = !noSSL
        if (clearCache)
            codeFetcher.clearCache()

        if (packageName == null) return
        if (profileDownloads > 1) downloadWithProfiling(packageName, codeFetcher)
        downloadAndRun(packageName, codeFetcher)
    }

    private fun downloadWithProfiling(packageName: Array<String>, codeFetcher: CodeFetcher) {
        val stopwatch = Stopwatch()
        repeat(profileDownloads) {
            codeFetcher.clearCache()
            val artifactName = packageName[0].split(':')[1]
            downloadWithProgressBar(artifactName, codeFetcher, packageName[0])
        }
        val totalSec = stopwatch.elapsedInSec
        println("Total runtime was $totalSec, for an average of ${totalSec / profileDownloads} seconds per run.")
        exitProcess(0)
    }

    private fun downloadAndRun(packageName: Array<String>, codeFetcher: CodeFetcher) {
        try {
            val artifactName = packageName[0].split(':')[1]
            val classpath = downloadWithProgressBar(artifactName, codeFetcher, packageName[0])
            startApp(classpath, packageName[0], args, null, null)
        } catch (original: Throwable) {
            val e = original.rootCause
            if (e is MetadataNotFoundException) {
                println("Sorry, that package is unknown. Check for typos? (${e.metadata})")
            } else if (e is IndexOutOfBoundsException) {
                println("Sorry, could not understand that coordinate. Use groupId:artifactId syntax.")
            } else {
                println(e.message)
            }
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
        return downloader.downloadAndBuildClasspath(packageName)
    } finally {
        if (didDownload) {
            pb.stop()
            val elapsedSec = (System.nanoTime() - timeAtStart) / 100000000 / 10.0
            println("Downloaded successfully in $elapsedSec seconds")
        }
    }
}
