package app.graviton.shell

import app.graviton.codefetch.CodeFetcher
import app.graviton.codefetch.RepoSpec
import com.github.markusbernhardt.proxy.ProxySearch
import javafx.application.Application
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.transfer.MetadataNotFoundException
import picocli.CommandLine
import java.io.IOException
import java.lang.invoke.MethodHandles
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI
import kotlin.math.max
import kotlin.system.exitProcess


val gravitonShellVersionNum: String get() = MethodHandles.lookup().lookupClass().`package`.implementationVersion.let { if (it.isNullOrBlank()) "DEV" else it }

@CommandLine.Command(
        name = "graviton",
        description = [
            "Graviton is an application browser and shell for the JVM. It will run and keep up to date programs from Maven repositories.",
            "If no arguments are specified, the GUI is invoked."
        ],
        mixinStandardHelpOptions = true,
        versionProvider = GravitonCLI.VersionProvider::class
)
class GravitonCLI(private val arguments: Array<String>) : Runnable {
    companion object : Logging() {
        fun parse(text: String): GravitonCLI {
            val options = GravitonCLI(text.split(' ').toTypedArray())
            val cli = CommandLine(options)
            cli.isStopAtPositional = true
            cli.parse(*text.split(' ').toTypedArray())
            return options
        }
    }

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

    @CommandLine.Option(names = ["--uninstall"], hidden = true)
    var uninstall: Boolean = false

    @CommandLine.Option(names = ["--update-url"], hidden = true)
    var updateURL: String = "https://update.graviton.app/"

    // Just for development.
    @CommandLine.Option(names = ["--profile-downloads"], description = ["If larger than one downloads the coordinates the given number of times and prints statistics"], hidden = true)
    var profileDownloads: Int = -1

    @CommandLine.Option(names = ["--verbose"], description = ["Enable logging"])
    var verboseLogging: Boolean = false

    @CommandLine.Option(names = ["--default-coordinate"], description = ["The default launch coordinate put in the address bar of the browser shell, may contain command line arguments"])
    var defaultCoordinate: String = "plan99.net:tictactoe"

    @CommandLine.Option(names = ["--refresh", "-r"], description = ["Re-check with the servers to see if a newer version is available. A new version check occurs every 24 hours by default."])
    var refresh: Boolean = false

    @CommandLine.Option(names = ["--cache-path"], description = ["If specified, overrides the default cache directory."])
    var cachePath: String = currentOperatingSystem.appCacheDirectory.toString()

    @CommandLine.Option(names = ["--proxy"], description = [
        "If set, the protocol://host:port pair identifying a proxy server, where protocol is either http or https.",
        "If unset or 'auto', OS settings are used. If 'none', no proxy is used regardless of auto detection."
    ])
    var proxy: String = "auto"

    @CommandLine.Option(names = ["--disable-ssl"], description = ["Disables the use of encrypted connections. This is done automatically when a proxy is in use."])
    var disableSSL: Boolean = false

    @CommandLine.Option(names = ["--repositories"], description = [
        "A comma separated list of Maven repository aliases or URLs, which will be resolved in order."
    ], showDefaultValue = CommandLine.Help.Visibility.ALWAYS)
    var repositories: String = RepoSpec.aliases.keys.joinToString(",")

    override fun run() {
        // This is where Graviton startup really begins.
        setupLogging(verboseLogging)
        setupProxies()

        val packageName = packageName

        if (uninstall) {
            lastRun()
            return
        }

        if (gravitonPath != null && gravitonVersion != null) {
            // This will execute asynchronously.
            startupChecks(gravitonPath, gravitonVersion)
            val ls = System.lineSeparator()
            mainLog.info("$ls${ls}Starting Graviton $gravitonVersion$ls$ls")
            mainLog.info("Path is $gravitonPath")
        }

        if (backgroundUpdate) {
            mainLog.info("BACKGROUND UPDATE")
            BackgroundUpdates().doBackgroundUpdate(cachePath.toPath(), gravitonVersion, gravitonPath?.toPath(), URI.create(updateURL))
            return
        }

        if (packageName != null) {
            handleCommandLineInvocation(packageName[0])
        } else {
            Application.launch(GravitonBrowser::class.java, *arguments)
        }
    }

    private fun setupProxies() {
        when (proxy) {
            "auto" -> {
                // If someone is using the default proxy selector somehow, we still want it to use native proxy
                // settings on Windows and Gnome 2.x
                System.setProperty("java.net.useSystemProxies", "true")
                stopwatch("Proxy search") {
                    // But we replace the Java proxy selector with a better one, Proxy Vole, which knows how to handle
                    // PAC files and other advanced features.
                    val proxySearch = ProxySearch.getDefaultProxySearch()
                    val proxySelector = proxySearch.proxySelector
                    // If we seem to want to use a proxy then we need to disable SSL, otherwise HttpClient will try to
                    // open a CONNECT tunnel through and the proxy would be unable to examine/intercept the request.
                    if (!proxySelector.select(URI.create("https://repo1.maven.org")).isEmpty())
                        disableSSL = true
                    ProxySelector.setDefault(proxySelector)
                }
            }
            "none" -> ProxySelector.setDefault(object : ProxySelector() {
                private val result = listOf(Proxy.NO_PROXY)
                override fun select(uri: URI) = result
                override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) = Unit
            })
            else -> {
                if (proxy.startsWith("http://") || proxy.startsWith("https://")) {
                    try {
                        val uri = URI.create(proxy)
                        System.setProperty("http.proxyHost", uri.host)
                        System.setProperty("https.proxyHost", uri.host)
                        System.setProperty("http.proxyPort", (if (uri.port == -1) 80 else uri.port).toString())
                        System.setProperty("https.proxyPort", (if (uri.port == -1) 80 else uri.port).toString())
                        // If we seem to want to use a proxy then we need to disable SSL, otherwise HttpClient will try to
                        // open a CONNECT tunnel through and the proxy would be unable to examine/intercept the request.
                        disableSSL = true
                    } catch (e: Exception) {
                        logger.warn("Couldn't parse proxy specification '$proxy': ${e.message}")
                    }
                } else {
                    logger.warn("Unknown proxy protocol '$proxy' - must be of the form 'http://host:port'")
                }
            }
        }
    }

    private fun handleCommandLineInvocation(coordinates: String) {
        if (profileDownloads > 1) downloadWithProfiling(coordinates)
        try {
            val manager = HistoryManager.create()
            val launcher = AppLauncher(this@GravitonCLI, createProgressBar(), manager, null)
            launcher.start()
        } catch (original: Throwable) {
            val e = original.rootCause
            if (e is MetadataNotFoundException) {
                println("Sorry, that package is unknown. Check for typos? (${e.metadata})")
            } else if (e is IndexOutOfBoundsException) {
                println("Sorry, could not understand that coordinate. Use groupId:artifactId syntax.")
            } else {
                val msg = e.message
                if (msg != null)
                    println(msg)
                else
                    e.printStackTrace()
            }
        }
    }

    private fun createProgressBar(): AppLauncher.Events {
        return object : AppLauncher.Events() {
            private val stopwatch = Stopwatch()
            private var pb: ProgressBar? = null
            private val startupMessage = "Please wait ... "
            private var havePrintedStartupMessage = false
            private fun wipe() {
                if (havePrintedStartupMessage)
                    print("\r" + " ".repeat(startupMessage.length) + "\r")
            }

            override fun preparingToDownload() {
                print(startupMessage)
                havePrintedStartupMessage = true
            }

            override fun onError(e: Exception) {
                wipe()
                pb?.close()
            }

            override fun onStartedDownloading(name: String) {
                wipe()
                pb = ProgressBar("Update", 1, 100, System.out, ProgressBarStyle.COLORFUL_UNICODE_BLOCK, "kb", 1)
                pb!!.extraMessage = name
            }

            override fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {
                val pb = pb!!
                if (name.endsWith(".pom"))
                    pb.extraMessage = name
                else
                    pb.extraMessage = ""
                // The ProgressBar library gets unhappy if we use ranges like 0/0 - it works but doesn't expand
                // to fill the terminal so we get visual artifacts.
                pb.maxHint(max(1, totalBytesToDownload / 1024))
                pb.stepTo(totalDownloadedSoFar / 1024)
            }

            override fun onStoppedDownloading() {
            }

            override fun aboutToStartApp(outOfProcess: Boolean) {
                if (pb != null) {
                    pb!!.close()
                    println("Downloaded successfully in ${stopwatch.elapsedInSec} seconds")
                }
            }
        }
    }

    private fun downloadWithProfiling(coordinates: String) {
        val stopwatch = Stopwatch()
        repeat(profileDownloads) {
            HistoryManager.create().clearCache()
            val codeFetcher = CodeFetcher(cachePath.toPath(), createProgressBar(), repoSpec())
            codeFetcher.offline = offline
            codeFetcher.downloadAndBuildClasspath(coordinates)
        }
        val totalSec = stopwatch.elapsedInSec
        println("Total runtime was $totalSec, for an average of ${totalSec / profileDownloads} seconds per run.")
        exitProcess(0)
    }

    fun repoSpec() = RepoSpec(repositories, disableSSL)

    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion() = arrayOf(gravitonShellVersionNum)
    }
}
