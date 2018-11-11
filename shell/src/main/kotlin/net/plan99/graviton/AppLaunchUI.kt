package net.plan99.graviton

import javafx.beans.binding.Bindings
import javafx.beans.property.*
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.input.MouseButton
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import net.plan99.graviton.effects.addMacStyleScrolling
import net.plan99.graviton.effects.addTopBottomFades
import net.plan99.graviton.mac.stealFocusOnMac
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

    override val root = scrollpane {
        addClass(Styles.appsPicker)
        addClass("scroll-pane-thin")
        isFitToWidth = true
        addTopBottomFades()
        addMacStyleScrolling()

        // This stack pane is just to vertically center the content.
        stackpane {
            alignment = Pos.TOP_CENTER
            vbox {
                children += logo.root

                pane { minHeight = 25.0 }

                coordinateBar()

                pane { minHeight = 25.0 }

                downloadTracker()

                pane { minHeight = 25.0 }

                recentAppsPicker = vbox {
                    spacing = 15.0
                    padding = Insets(20.0, 0.0, 20.0, 0.0)
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
        }
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
                style {
                    opacity = 0.85
                }
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
            button("◼") {
                setOnAction {
                    cancelIfDownloading()
                }
                style {
                    fontSize = 20.pt
                }
                padding = insets(15.0)
                translateX = 15.0
                visibleProperty().bind(isWorking)
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
        alignment = Pos.TOP_CENTER

        for (entry: HistoryEntry in historyManager.history) {
            createAppTile(entry)
        }
    }

    private fun VBox.createAppTile(entry: HistoryEntry) {
        hbox {
            val observableEntry = SimpleObjectProperty(entry)

            // Give it a white card look, make it only appear when it should be here.
            addClass(Styles.historyEntry)
            visibleWhen(isHistoryVisible)
            managedProperty().bind(isHistoryVisible)

            isWorking.addListener { _, _, newValue ->
                val delay = 0.5.seconds
                opacityProperty().animate(if (newValue) 0.2 else 1.0, delay)
            }

            // Name, description
            vbox {
                label(observableEntry.select { SimpleStringProperty(it.name) }) { addClass(Styles.historyTitle) }
                label(observableEntry.select { SimpleStringProperty(it.description ?: "") }) { addClass(Styles.historyDescription) }
            }

            // Push the next bit to the right.
            pane { HBox.setHgrow(this, Priority.ALWAYS) }

            // Right click menu.
            val menu = contextmenu {
                item("Copy coordinates") {
                    action {
                        val curEntry = observableEntry.get()
                        coordinateBar.text = curEntry.coordinateFragment
                        resetUI()
                    }
                }

                item("Refresh") {
                    setOnAction {
                        isWorking.set(true)
                        task {
                            info { "User requested refresh of $entry" }
                            val curEntry = observableEntry.get()
                            val fetcher = AppLauncher(GravitonCLI.parse(curEntry.coordinateFragment), appLaunchEventHandler, historyManager).codeFetcher
                            fetcher.events = appLaunchEventHandler
                            historyManager.refresh(fetcher, curEntry)
                        } success { newEntry: HistoryEntry ->
                            observableEntry.set(newEntry)
                        } finally {
                            isWorking.set(false)
                        }
                    }
                }

                item("Clear cache") {
                    setOnAction {
                        clearCache()
                        recentAppsPicker.populateRecentAppsPicker()
                    }
                }
            }

            // Handle left and right clicks.
            setOnMouseClicked {
                if (isWorking.get()) return@setOnMouseClicked
                when {
                    it.button == MouseButton.PRIMARY -> {
                        coordinateBar.text = entry.coordinateFragment
                        beginLaunch()
                    }
                    // For some reason without checking if the secondary button is down, this gets duplicated and
                    // yields visual corruption.
                    it.button == MouseButton.SECONDARY && it.isSecondaryButtonDown -> menu.show(this@hbox, it.screenX, it.screenY)
                }
            }
        }
    }

    //region Event handling
    fun resetUI() {
        isWorking.set(false)
        messageText1.set("")
        messageText2.set("")
        coordinateBar.selectAll()
        coordinateBar.requestFocus()
        isHistoryVisible.set(true)
        recentAppsPicker.populateRecentAppsPicker()
    }

    private fun beginLaunch() {
        cancelIfDownloading()

        val text = coordinateBar.text
        if (text.isBlank()) return

        // We animate even if there's no downloading to do because for complex apps, simply resolving dependency graphs and starting the
        // app can take a bit of time.
        isWorking.set(true)

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
            AppLauncher(options, appLaunchEventHandler, historyManager, primaryStage, printStream, printStream).start()
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

    private val appLaunchEventHandler = object : AppLauncher.Events() {
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
                // Hide the shell window.
                FX.primaryStage.hide()
                // This little dance is needed to stop FX shutting us down because all our windows are gone.
                // Shutdown will begin if there are no windows, no pending event loop lambdas and no nested event
                // loops. We want to hide our window, can't start a nested event loop, so, we have to keep the
                // event loop paused until we're ready to come back.
                wedgeFX = CountDownLatch(1)
                runLater {
                    wedgeFX!!.await()
                    // Prepare the UI for next time.
                    resetUI()
                    FX.primaryStage.show()
                    FX.primaryStage.requestFocus()
                    if (currentOperatingSystem == OperatingSystem.MAC)
                        FX.primaryStage.stealFocusOnMac()
                }
            } else {
                // Command line console window open.
                isWorking.set(false)
                messageText1.set("")
                messageText2.set("")
                isHistoryVisible.set(false)
            }
        }

        override fun appFinished() {
            val wedgeFX = wedgeFX
            if (wedgeFX != null) {
                info { "App finished, unblocking FX event loop" }
                wedgeFX.countDown()
                // The UI will now be reset by the code that was stuffed into the blocked event loop above.
            } else {
                // The app finished, so put the UI back.
                resetUI()
            }
        }
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

fun clearCache() {
    historyManager.clearCache()
    Alert(Alert.AlertType.INFORMATION, "Cache has been cleared. Apps will re-download next time they are " +
            "invoked or a background update occurs.", ButtonType.CLOSE).showAndWait()
}