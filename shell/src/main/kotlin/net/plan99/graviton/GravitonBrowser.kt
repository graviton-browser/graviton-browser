package net.plan99.graviton

import javafx.application.Platform
import javafx.beans.binding.Bindings
import javafx.beans.property.SimpleBooleanProperty
import javafx.beans.property.SimpleDoubleProperty
import javafx.beans.property.StringProperty
import javafx.geometry.Pos
import javafx.scene.control.TextArea
import javafx.scene.image.Image
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.text.FontWeight
import javafx.scene.text.TextAlignment
import javafx.stage.Stage
import javafx.stage.StageStyle
import kotlinx.coroutines.experimental.javafx.JavaFx
import kotlinx.coroutines.experimental.launch
import org.eclipse.aether.transfer.ArtifactNotFoundException
import tornadofx.*
import java.io.OutputStream
import java.io.PrintStream
import kotlin.concurrent.thread

class GravitonBrowser : App(ShellView::class, Styles::class) {
    init {
        importStylesheet("/net/plan99/graviton/graviton.css")
    }

    override fun start(stage: Stage) {
        stage.isMaximized = true
        if (currentOperatingSystem == OperatingSystem.MAC) {
            // This looks nice on OS X but not so great on other platforms.
            stage.initStyle(StageStyle.UNIFIED)
        }
        super.start(stage)
    }
}

class ShellView : View("Graviton Browser") {
    companion object : Logging()

    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isDownloading = SimpleBooleanProperty()
    private lateinit var progressAnimation: ThreeDSpinner
    private lateinit var messageText1: StringProperty
    private lateinit var messageText2: StringProperty
    private lateinit var outputArea: TextArea

    private val historyManager by lazy { HistoryManager.create() }

    // Build up the UI layouts and widgets using the TornadoFX DSL.
    override val root = stackpane {
        style {
            fontFamily = "Raleway"
            fontWeight = FontWeight.EXTRA_LIGHT
        }

        data class Art(val fileName: String, val topPadding: Int, val animationColor: Color)

        val allArt = listOf(
                Art("paris.png", 0, Color.BLUE),
                Art("forest.jpg", 200, Color.color(0.0, 0.5019608, 0.0, 0.5))
        )
        val art = allArt[0]

        vbox {
            stackpane {
                style {
                    backgroundColor = multi(LinearGradient.valueOf("white,rgb(218,239,244)"))
                }
                vbox {
                    minHeight = art.topPadding.toDouble()
                }
            }
            // Background image.
            imageview {
                image = Image(resources["art/${art.fileName}"])
                fitWidthProperty().bind(this@stackpane.widthProperty())
                isPreserveRatio = true

                // setOnMouseClicked { isDownloading.set(!isDownloading.value) }
            }.stackpaneConstraints {
                alignment = Pos.BOTTOM_CENTER
            }
        }.stackpaneConstraints { alignment = Pos.TOP_CENTER }

        progressAnimation = ThreeDSpinner(art.animationColor)
        progressAnimation.root.maxWidth = 600.0
        progressAnimation.root.maxHeight = 600.0
        progressAnimation.root.translateY = -150.0
        children += progressAnimation.root
        progressAnimation.visible.bind(isDownloading)

        vbox {
            pane { minHeight = 275.0 }

            textfield {
                style {
                    fontSize = 20.pt
                    alignment = Pos.CENTER
                }
                text = commandLineArguments.defaultCoordinate
                selectAll()
                disableProperty().bind(isDownloading)
                action { onNavigate(text) }
            }

            pane { minHeight = 25.0 }

            vbox {
                style {
                    backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.9))
                    backgroundRadius = multi(box(5.px))
                    borderWidth = multi(box(3.px))
                    borderColor = multi(box(Color.LIGHTGREY))
                    borderRadius = multi(box(5.px))
                }
                padding = insets(15.0)
                alignment = Pos.CENTER
                label {
                    messageText1 = textProperty()
                    textAlignment = TextAlignment.CENTER
                }
                label {
                    messageText2 = textProperty()
                    textAlignment = TextAlignment.CENTER
                }
                visibleProperty().bind(messageText1.isNotEmpty.or(messageText2.isNotEmpty))
            }

            pane { minHeight = 25.0 }

            outputArea = textarea {
                styleClass.add("output-area")
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

            maxWidth = 800.0
            spacing = 5.0
            alignment = Pos.TOP_CENTER
        }

        label("Background art by Vexels") {
            style {
                padding = box(10.px)
            }
        }.stackpaneConstraints { alignment = Pos.BOTTOM_RIGHT }
    }

    private fun onNavigate(text: String) {
        if (text.isBlank()) return

        // Parse what the user entered as if it were a command line: this feature is a bit of an easter egg,
        // but makes testing a lot easier, e.g. to force a re-download just put --clear-cache at the front.
        val options = GravitonCLI.parse(text)

        // These callbacks will run on the FX event thread.
        val events = object : CodeFetcher.Events {
            override suspend fun onStartedDownloading(name: String) {
                downloadProgress.set(0.0)
                isDownloading.set(true)
                messageText1.set("Please wait ...")
                messageText2.set("Resolving")
            }

            override suspend fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {
                val pr = totalDownloadedSoFar.toDouble() / totalBytesToDownload.toDouble()
                downloadProgress.set(pr)
                messageText1.set("DOWNLOADING")
                messageText2.set(name)
            }

            override suspend fun onStoppedDownloading() {
                downloadProgress.set(1.0)
                isDownloading.set(false)
                messageText1.set("")
                messageText2.set("")
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
        launch(JavaFx) {
            try {
                AppLauncher(options, historyManager, primaryStage, JavaFx, events, printStream, printStream).start()
            } catch (e: Throwable) {
                onStartError(e)
            }
        }
    }

    private fun onStartError(e: Throwable) {
        // TODO: Handle errors much better than just splatting the exception name onto the screen!
        isDownloading.set(false)
        downloadProgress.set(0.0)
        messageText1.set("Start failed")
        val msg = if (e is AppLauncher.StartException) {
            if (e.rootCause is ArtifactNotFoundException) {
                "Could not locate the requested application"
            } else
                e.message
        } else
            e.toString()
        messageText2.set(msg)
        logger.error("Start failed", e)
    }

    private fun mockDownload() {
        isDownloading.set(true)
        downloadProgress.set(0.0)
        messageText1.set("Mock downloading ..")
        thread {
            Thread.sleep(5000)
            Platform.runLater {
                downloadProgress.animate(1.0, 5000.millis) {
                    setOnFinished {
                        isDownloading.set(false)
                        messageText1.set("")
                        messageText2.set("")
                    }
                }
            }
        }
    }
}

class Styles : Stylesheet() {
    companion object {
        val shellArea by cssclass()
        val content by cssclass()
    }

    init {
        shellArea {
            fontFamily = "monospace"
            content {
                backgroundColor = multi(Color.gray(1.0, 0.5))
            }
        }
    }
}
