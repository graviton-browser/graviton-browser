package net.plan99.graviton

import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.StringProperty
import javafx.concurrent.Task
import javafx.geometry.Pos
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import tornadofx.*
import java.io.OutputStream
import java.io.PrintStream
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * The logo, coordinate bar, progress bar and history list. Things the user interacts with on the main screen.
 */
class AppLaunchUI : View() {
    companion object : Logging()

    private lateinit var messageText1: StringProperty
    private lateinit var messageText2: StringProperty
    private lateinit var outputArea: TextArea
    private lateinit var coordinateBar: TextField
    private val allThreads = ThreadGroup("App Launch")
    private var launcher: Task<*>? = null
    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isHistoryVisible = SimpleBooleanProperty(true)
    private val logo = find<LogoView>()
    private lateinit var recentAppsPicker: VBox

    override val root = vbox {
        children += logo.root

        pane { minHeight = 25.0 }

        coordinateBar()

        pane { minHeight = 25.0 }

        downloadTracker()

        pane { minHeight = 25.0 }

        recentAppsPicker = vbox {
            spacing = 15.0
            populateRecentAppsPicker()
        }

        outputArea = textarea {
            addClass(Styles.shellArea)
            isWrapText = false
            opacity = 0.0
            textProperty().addListener { _, oldValue, newValue ->
                if (oldValue.isBlank() && newValue.isNotBlank()) {
                    opacityProperty().animate(1.0, 0.3.seconds)
                } else if (newValue.isBlank() && oldValue.isNotBlank()) {
                    opacityProperty().animate(0.0, 0.3.seconds)
                }
            }
            prefRowCountProperty().bind(Bindings.`when`(textProperty().isNotEmpty).then(20).otherwise(0))
        }

        // Just wide enough for 80 chars in the output area at currently chosen font size.
        maxWidth = 1024.0
        maxHeight = Double.POSITIVE_INFINITY
        spacing = 5.0
        alignment = Pos.TOP_CENTER
    }

    @Suppress("JoinDeclarationAndAssignment")
    private fun VBox.coordinateBar() {
        coordinateBar = textfield {
            style {
                fontSize = 20.pt
                alignment = Pos.CENTER
                padding = box(20.px)
            }
            // When running the GUI classes standalone via TornadoFX plugin, we of course have no command line params ...
            text = try { commandLineArguments.defaultCoordinate } catch (e: UninitializedPropertyAccessException) { "" }
            requestFocus()
            selectAll()
            disableProperty().bind(isWorking)
            action { beginLaunch() }
        }
    }

    private fun VBox.downloadTracker() {
        stackpane {
            progressbar {
                fitToParentSize()
                progressProperty().bind(downloadProgress)
                style = "-fx-opacity: 0.85"
            }
            vbox {
                addClass(Styles.messageBox)
                padding = insets(15.0)
                alignment = Pos.CENTER
                label {
                    messageText1 = textProperty()
                    textAlignment = TextAlignment.CENTER
                }
                label {
                    messageText2 = textProperty()
                    textAlignment = TextAlignment.CENTER
                    style {
                        fontSize = 15.pt
                    }
                }
            }
            button("✖") {
                setOnAction {
                    cancelIfDownloading()
                }
                style = "-fx-base: white; -fx-font-size: 20pt"
                padding = insets(15.0)
                translateX = 15.0
            }.stackpaneConstraints { alignment = Pos.CENTER_LEFT }

            // If we're not downloading, hide this chunk of UI and take it out of layout.
            val needed = messageText1.isNotEmpty.or(messageText2.isNotEmpty)
            visibleProperty().bind(needed)
            managedProperty().bind(needed)
        }
    }

    private fun cancelIfDownloading() {
        if (launcher != null) {
            info { "Cancelling" }
            launcher!!.cancel()
            launcher = null
        }
    }

    private fun VBox.populateRecentAppsPicker() {
        children.clear()

        // Take 10 entries even though we track 20 for now, just to keep it more manageable until we do scrolling.
        for (entry: HistoryEntry in historyManager.history.take(10)) {
            vbox {
                addClass(Styles.historyEntry)
                label(entry.name) { addClass(Styles.historyTitle) }
                if (entry.description != null)
                    label(entry.description) { addClass(Styles.historyDescription) }

                setOnMouseClicked {
                    coordinateBar.text = entry.coordinateFragment
                    beginLaunch()
                }

                visibleWhen(isHistoryVisible)
                managedProperty().bind(isHistoryVisible)
            }
        }
    }

    //region Event handling
    private fun beginLaunch() {
        cancelIfDownloading()

        val text = coordinateBar.text
        if (text.isBlank()) return

        // We animate even if there's no downloading to do because for complex apps, simply resolving dependency graphs and starting the
        // app can take a bit of time.
        isWorking.set(true)

        fun resetUI() {
            isWorking.set(false)
            messageText1.set("")
            messageText2.set("")
            coordinateBar.selectAll()
            coordinateBar.requestFocus()
            isHistoryVisible.set(true)
            recentAppsPicker.populateRecentAppsPicker()
        }

        val events = object : AppLauncher.Events() {
            // Make sure we update the UI on the right thread, and ignore any events that come in after
            // cancellation by the user.
            private fun wrap(body: () -> Unit) {
                fx {
                    if (launcher == null) return@fx
                    body()
                }
            }

            override fun onStartedDownloading(name: String) = wrap {
                downloadProgress.set(0.0)
                if (name.contains("maven-metadata-")) {
                    messageText1.set("Checking for updates")
                    messageText2.set("")
                    return@wrap
                }
                messageText1.set("Downloading")
                messageText2.set(name)
            }

            private var progress = 0.0

            override fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) = wrap {
                if (name.contains("maven-metadata-")) {
                    messageText1.set("Checking for updates")
                    messageText2.set("")
                    return@wrap
                }
                messageText1.set("Downloading")
                messageText2.set(name)
                val pr = totalDownloadedSoFar.toDouble() / totalBytesToDownload.toDouble()
                // Need to make sure progress only jumps backwards if we genuinely have a big correction.
                if (pr - progress < 0 && Math.abs(pr - progress) < 0.2) return@wrap
                progress = pr
                downloadProgress.set(progress)
            }

            override fun onStoppedDownloading() = wrap {
                downloadProgress.set(1.0)
                messageText1.set("")
                messageText2.set("")
            }

            override fun initializingApp() = wrap {
                messageText1.set("Please wait")
                messageText2.set("App is initializing")
            }

            private var wedgeFX: CountDownLatch? = null

            override fun aboutToStartApp(outOfProcess: Boolean) = wrap {
                if (outOfProcess) {
                    // Prepare the UI for next time.
                    resetUI()
                    // Hide the shell window.
                    FX.primaryStage.hide()
                    // This little dance is needed to stop FX shutting us down because all our windows are gone.
                    // Shutdown will begin if there are no windows, no pending event loop lambdas and no nested event
                    // loops. We want to hide our window, can't start a nested event loop, so, we have to keep the
                    // event loop paused until we're ready to come back.
                    wedgeFX = CountDownLatch(1)
                    runLater {
                        wedgeFX!!.await()
                        FX.primaryStage.show()
                        // TODO: Why doesn't this work on macOS?
                        FX.primaryStage.requestFocus()
                    }
                } else {
                    isWorking.set(false)
                    messageText1.set("")
                    messageText2.set("")
                    isHistoryVisible.set(false)
                }
            }

            override fun appFinished() {
                info { "App finished, unblocking FX event loop" }
                wedgeFX!!.countDown()
            }
        }

        // Capture the output of the program and redirect it to a text area. In future we'll switch this to be a real
        // terminal and get rid of it for graphical apps.
        outputArea.text = ""
        val printStream = PrintStream(object : OutputStream() {
            override fun write(b: Int) = fx {
                outputArea.text += b.toChar()
            }
        }, true)

        // Parse what the user entered as if it were a command line: this feature is a bit of an easter egg,
        // but makes testing a lot easier, e.g. to force a re-download just put --clear-cache at the front.
        val cmdLineParams = app.parameters.raw.joinToString(" ")
        val options = GravitonCLI.parse("$cmdLineParams $text".trim())

        launcher = FXTask {
            AppLauncher(options, events, historyManager, primaryStage, printStream, printStream).start()
        } fail { ex ->
            resetUI()
            onStartError(ex)
        } cancel {
            info { "Cancelled" }
            // TODO: This works but is ugly and who knows what kind of issues it may cause.
            //       Change to using our own HTTP stack and then implement cancellation in that.
            //       This would also let us run the AppLauncher on the JFX thread.
            allThreads.interrupt()
            resetUI()
        }
        Thread(launcher).start()
    }

    private fun onStartError(e: Throwable) {
        isWorking.set(false)
        downloadProgress.set(0.0)
        messageText1.set("Start failed")
        messageText2.set(e.message)
        ShellView.logger.error("Start failed", e)
    }

    @Suppress("unused")
    private fun mockDownload() {
        isWorking.set(true)
        downloadProgress.set(0.0)
        messageText1.set("Mock downloading ..")
        thread {
            Thread.sleep(5000)
            fx {
                downloadProgress.animate(1.0, 5000.millis) {
                    setOnFinished {
                        isWorking.set(false)
                        messageText1.set("")
                        messageText2.set("")
                    }
                }
            }
        }
    }
}