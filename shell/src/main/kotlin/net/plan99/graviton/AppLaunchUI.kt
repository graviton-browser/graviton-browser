package net.plan99.graviton

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.StringProperty
import javafx.geometry.Pos
import javafx.scene.control.TextArea
import javafx.scene.control.TextField
import javafx.scene.layout.VBox
import javafx.scene.text.TextAlignment
import kotlinx.coroutines.experimental.javafx.JavaFx
import tornadofx.*
import java.io.OutputStream
import java.io.PrintStream
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
    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isHistoryVisible = SimpleBooleanProperty(true)

    override val root = vbox {
            pane { minHeight = 0.0 }

            // Logo
            hbox {
                alignment = Pos.CENTER
                imageview(appBrandLogo)
                label("graviton") {
                    addClass(Styles.logoText)
                }
            }

            pane { minHeight = 25.0 }

            coordinateBar()

            pane { minHeight = 25.0 }

            downloadTracker()

            pane { minHeight = 25.0 }

            recentAppsPicker()

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
            spacing = 5.0
            alignment = Pos.TOP_CENTER
        }

    private fun VBox.coordinateBar() {
        coordinateBar = textfield {
            style {
                fontSize = 20.pt
                alignment = Pos.CENTER
            }
            // When running the GUI classes standalone via TornadoFX plugin, we of course have no command line params ...
            text = try { commandLineArguments.defaultCoordinate } catch (e: UninitializedPropertyAccessException) { "" }
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
        }.also {
            // If we're not downloading, hide this chunk of UI and take it out of layout.
            val needed = messageText1.isNotEmpty.or(messageText2.isNotEmpty)
            it.visibleProperty().bind(needed)
            it.managedProperty().bind(needed)
        }
    }

    private fun VBox.recentAppsPicker() {
        vbox {
            spacing = 15.0

            // Take 10 entries even though we track 20 for now, just to keep it more manageable until we do scrolling.
            for (entry: HistoryEntry in historyManager.history.take(10)) {
                vbox {
                    addClass(Styles.historyEntry)
                    label(entry.name) { addClass(Styles.historyTitle) }
                    if (entry.description != null)
                        label(entry.description)

                    setOnMouseClicked {
                        coordinateBar.text = entry.coordinateFragment
                        beginLaunch()
                    }

                    visibleWhen(isHistoryVisible)
                    managedProperty().bind(isHistoryVisible)
                }
            }
        }
    }

    //region Event handling
    private fun beginLaunch() {
        val text = coordinateBar.text
        if (text.isBlank()) return

        // We animate even if there's no downloading to do because for complex apps, simply resolving dependency graphs and starting the
        // app can take a bit of time.
        isWorking.set(true)

        // Parse what the user entered as if it were a command line: this feature is a bit of an easter egg,
        // but makes testing a lot easier, e.g. to force a re-download just put --clear-cache at the front.
        val cmdLineParams = app.parameters.raw.joinToString(" ")
        val options = GravitonCLI.parse("$cmdLineParams $text")

        // These callbacks will run on the FX event thread.
        val events = object : AppLauncher.Events {
            override suspend fun onStartedDownloading(name: String) {
                downloadProgress.set(0.0)
                if (name.contains("maven-metadata-")) {
                    messageText1.set("Checking for updates")
                    messageText2.set("")
                    return
                }
                messageText1.set("Downloading")
                messageText2.set(name)
            }

            var progress = 0.0

            override suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {
                if (name.contains("maven-metadata-")) {
                    messageText1.set("Checking for updates")
                    messageText2.set("")
                    return
                }
                messageText1.set("Downloading")
                messageText2.set(name)
                val pr = totalDownloadedSoFar.toDouble() / totalBytesToDownload.toDouble()
                // Need to make sure progress only jumps backwards if we genuinely have a big correction.
                if (pr - progress < 0 && Math.abs(pr - progress) < 0.2) return
                progress = pr
                downloadProgress.set(progress)
            }

            override suspend fun onStoppedDownloading() {
                downloadProgress.set(1.0)
                messageText1.set("")
                messageText2.set("")
            }

            override suspend fun aboutToStartApp() {
                isWorking.set(false)
                messageText1.set("")
                messageText2.set("")
                isHistoryVisible.set(false)
            }
        }

        // Capture the output of the program and redirect it to a text area. In future we'll switch this to be a real
        // terminal and get rid of it for graphical apps.
        outputArea.text = ""
        val printStream = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                Platform.runLater {
                    outputArea.text += b.toChar()
                }
            }
        }, true)

        // Now start a coroutine that will run everything on the FX thread other than background tasks.
        kotlinx.coroutines.experimental.launch(JavaFx) {
            try {
                AppLauncher(options, historyManager, primaryStage, JavaFx, events, printStream, printStream).start()
            } catch (e: Throwable) {
                onStartError(e)
                coordinateBar.selectAll()
                coordinateBar.requestFocus()
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
            Platform.runLater {
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