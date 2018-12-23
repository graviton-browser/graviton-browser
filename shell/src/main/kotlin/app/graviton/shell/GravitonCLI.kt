package app.graviton.shell

import app.graviton.codefetch.CodeFetcher
import app.graviton.codefetch.RepoSpec
import app.graviton.scheduler.OSScheduledTaskDefinition
import app.graviton.scheduler.OSTaskScheduler
import com.github.markusbernhardt.proxy.ProxySearch
import javafx.application.Application
import me.tongfei.progressbar.ProgressBar
import me.tongfei.progressbar.ProgressBarStyle
import org.eclipse.aether.transfer.MetadataNotFoundException
import picocli.CommandLine
import java.io.IOException
import java.io.PrintWriter
import java.lang.invoke.MethodHandles
import java.net.*
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread
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
    var repositories: String = (RepoSpec.aliases.keys + "dev-local").joinToString(",")

    override fun run() {
        // This is where Graviton startup really begins.
        setupLogging(verboseLogging)

        val packageName = packageName

        if (uninstall) {
            lastRun()
            return
        }

        // This will execute asynchronously, only if run from an installed package (via the bootstrapper).
        startupChecks()

        // TODO: Run these steps async.
        setupProxies()

        if (backgroundUpdate) {
            checkNotNull(envVars)
            mainLog.info("BACKGROUND UPDATE")
            BackgroundUpdates().doBackgroundUpdate(cachePath.toPath(), envVars.gravitonVersion, envVars.gravitonPath, URI.create(updateURL))
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
                    if (proxySelector == null) {
                        info { "Proxy search failed" }
                        return
                    }
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
            val launcher = AppLauncher(this, createProgressBar(), manager, null)
            launcher.start()
        } catch (original: Throwable) {
            val e = original.rootCause
            if (e is MetadataNotFoundException) {
                println("Sorry, that package is unknown. Check for typos? (${e.metadata})")
            } else if (e is IndexOutOfBoundsException) {
                println("Sorry, could not understand that coordinate. Use groupId:artifactId syntax.")
            } else if (e is UnknownHostException) {
                println("Sorry, could not look up ${e.message}. Are you offline? If so try the --offline flag.")
            } else {
                logger.warn("Exception during start", e)
                val msg = e.message
                if (msg != null) {
                    println("Error during startup: $msg")
                } else {
                    e.printStackTrace()
                }
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

            @Synchronized
            override fun onError(e: Exception) {
                wipe()
                pb?.close()
            }

            @Synchronized
            override fun onStartedDownloading(name: String) {
                wipe()
                val style = if (currentOperatingSystem == OperatingSystem.WIN) ProgressBarStyle.ASCII else ProgressBarStyle.COLORFUL_UNICODE_BLOCK
                pb = ProgressBar("Update", 1, 100, System.out, style, "kb", 1)
                pb!!.extraMessage = name
            }

            @Synchronized
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

            @Synchronized
            override fun aboutToStartApp(outOfProcess: Boolean) {
                wipe()
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
            val codeFetcher = CodeFetcher(cachePath.toPath(), createProgressBar(), repoSpec(), offline)
            codeFetcher.downloadAndBuildClasspath(coordinates)
        }
        val totalSec = stopwatch.elapsedInSec
        println("Total runtime was $totalSec, for an average of ${totalSec / profileDownloads} seconds per run.")
        exitProcess(0)
    }

    fun repoSpec() = RepoSpec(repositories, disableSSL)

    private fun startupChecks() {
        // Do it in the background to keep the slow file IO away from blocking startup.
        val envVars = envVars ?: return
        val ls = System.lineSeparator()
        mainLog.info("$ls${ls}Starting Graviton ${envVars.gravitonVersion}$ls$ls")
        mainLog.info("Versioned install path is ${envVars.gravitonPath}")
        mainLog.info("Binary path is ${envVars.gravitonExePath}")
        thread {
            try {
                Files.createDirectories(currentOperatingSystem.appCacheDirectory)
                val versionPath = currentOperatingSystem.appCacheDirectory / "last-run-version"
                val taskSchedulerErrorFile = currentOperatingSystem.appCacheDirectory / "task-scheduler-error-log.txt"
                if (!versionPath.exists || taskSchedulerErrorFile.exists)
                    firstRun(envVars.gravitonPath, taskSchedulerErrorFile)
                Files.write(versionPath, listOf("${envVars.gravitonVersion}"))
            } catch (e: Exception) {
                // Log but don't block startup.
                mainLog.error("Failed to do background startup checks", e)
            }
        }
    }

    private val taskName = "app.graviton.update"

    private fun firstRun(myPath: Path, taskSchedulerErrorFile: Path) {
        mainLog.info("First run, attempting to register scheduled task")
        val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
        if (scheduler == null) {
            mainLog.info("No support for task scheduling on this OS: $currentOperatingSystem")
            return
        }
        val executePath = when (currentOperatingSystem) {
            OperatingSystem.MAC -> myPath / "MacOS" / "Graviton"
            OperatingSystem.WIN -> myPath / "GravitonBrowser.exe"
            OperatingSystem.LINUX -> myPath / "GravitonBrowser"
            OperatingSystem.UNKNOWN -> return
        }
        // Poll the server four times a day. This is a pretty aggressive interval but is useful in the project's early
        // life where I want to be able to update things quickly and users may be impatient.
        val scheduledTask = OSScheduledTaskDefinition(
                executePath = executePath,
                arguments = listOf("--background-update"),
                frequency = when (currentOperatingSystem) {
                    // I couldn't make the Windows task scheduler do non-integral numbers of days, see WindowsTaskScheduler.kt
                    OperatingSystem.WIN -> Duration.ofHours(24)
                    else -> Duration.ofHours(6)
                },
                description = "Graviton background upgrade task. If you disable this, Graviton Browser may become insecure.",
                networkSensitive = true
        )
        try {
            Files.deleteIfExists(taskSchedulerErrorFile)
            scheduler.register(taskName, scheduledTask)
            mainLog.info("Registered background task successfully with name '$taskName'")
        } catch (e: Exception) {
            // If we failed to register the task we will store the error to a dedicated file, which will act
            // as a marker to retry next time.
            taskSchedulerErrorFile.toFile().writer().use {
                e.printStackTrace(PrintWriter(it))
            }
            mainLog.error("Failed to register background task", e)
        }
    }

    private fun lastRun() {
        mainLog.info("Uninstallation requested, removing scheduled task")
        try {
            val scheduler: OSTaskScheduler? = OSTaskScheduler.get()
            if (scheduler == null) {
                mainLog.info("No support for task scheduling on this OS: $currentOperatingSystem")
                return
            }
            scheduler.deregister(taskName)
        } catch (e: Throwable) {
            // Don't want to spam the user with errors.
            mainLog.error("Exception during uninstall", e)
        }
    }

    class VersionProvider : CommandLine.IVersionProvider {
        override fun getVersion() = arrayOf(gravitonShellVersionNum)
    }
}
