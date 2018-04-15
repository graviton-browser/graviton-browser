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
import org.eclipse.aether.transfer.ArtifactNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import picocli.CommandLine
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

    private val codeFetcher = CodeFetcher()

    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isDownloading = SimpleBooleanProperty()
    private lateinit var progressCircle: ProgressCircle
    private lateinit var messageText1: StringProperty
    private lateinit var messageText2: StringProperty
    private lateinit var outputArea: TextArea

    override val root = stackpane {
        style {
            fontFamily = "Raleway"
            fontWeight = FontWeight.EXTRA_LIGHT
        }

        vbox {
            stackpane {
                style {
                    backgroundColor = multi(LinearGradient.valueOf("white,rgb(218,239,244)"))
                }
                vbox {
                    minHeight = 200.0
                }
            }
            // Background image.
            imageview {
                image = Image(resources["art/forest.jpg"])
                fitWidthProperty().bind(this@stackpane.widthProperty())
                isPreserveRatio = true
            }.stackpaneConstraints {
                alignment = Pos.BOTTOM_CENTER
            }
        }.stackpaneConstraints { alignment = Pos.TOP_CENTER }

        progressCircle = ProgressCircle(this, downloadProgress, isDownloading, 350.0)

        vbox {
            pane { minHeight = 25.0 }

            label("Enter a domain name or coordinate") {
                style {
                    fontSize = 25.pt
                }
            }

            pane { minHeight = 25.0 }

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

            label {
                messageText1 = textProperty()
                textAlignment = TextAlignment.CENTER
            }
            label {
                messageText2 = textProperty()
                textAlignment = TextAlignment.CENTER
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
            alignment = Pos.CENTER
            //translateY = -70.0
        }.stackpaneConstraints { alignment = Pos.CENTER }

        label("Background art by Vexels") {
            style {
                padding = box(10.px)
                textFill = Color.GRAY
            }
        }.stackpaneConstraints { alignment = Pos.BOTTOM_RIGHT }
    }

    private fun onNavigate(text: String) {
        if (text.isBlank()) return

        val options = GravitonCLI()
        val cli = CommandLine(options)
        cli.isStopAtPositional = true
        cli.parse(*text.split(' ').toTypedArray())
        if (options.clearCache) codeFetcher.clearCache()
        val packageName = (options.packageName ?: return)[0]

        download(packageName) { classpath ->
            try {
                redirectIOAndStart(classpath, packageName, options)
            } catch (e: Exception) {
                onStartError(e)
            }
        }
    }

    private fun redirectIOAndStart(classpath: String, packageName: String, options: GravitonCLI) {
        outputArea.text = ""
        val printStream = PrintStream(object : OutputStream() {
            override fun write(b: Int) {
                Platform.runLater {
                    outputArea.text += b.toChar()
                }
            }
        }, true)
        startApp(classpath, packageName, options.args, currentStage, printStream)
    }

    private fun onStartError(e: Throwable) {
        // TODO: Handle errors much better than just splatting the exception name onto the screen!
        isDownloading.set(false)
        downloadProgress.set(0.0)
        messageText1.set("Start failed")
        messageText2.set(e.toString())
        logger.error("Start failed", e)
    }

    private fun download(text: String, andThen: (String) -> Unit) {
        if (false) {
            mockDownload()
            return
        }
        var totalBytesToDownload = 0L
        var totalBytesDownloaded = 0L
        var downloadStarted = false
        val startTime = System.nanoTime()
        val subscription = codeFetcher.allTransferEvents.threadBridgeToFx(codeFetcher.eventExecutor).subscribe {
            // Only care about JAR fetches.
            val elapsedSecs = (System.nanoTime() - startTime) / 100000000 / 10.0
            messageText1.set("[$elapsedSecs secs]")
            messageText2.set(it.data.resource.resourceName.split('/').last())

            if (it.type == TransferEvent.EventType.INITIATED) {
                if (!downloadStarted) {
                    downloadProgress.set(0.0)
                    isDownloading.set(true)
                    messageText1.set("")
                    messageText2.set("Please wait ...")
                    downloadStarted = true
                }
            }

            if (it.data.requestType == TransferEvent.RequestType.GET && it.data.resource.resourceName.endsWith(".jar")) {
                when {
                    it.type == TransferEvent.EventType.STARTED -> {
                        totalBytesToDownload += it.data.resource.contentLength
                    }
                    it.type == TransferEvent.EventType.FAILED -> {
                        downloadProgress.set(-1.0)
                        if (it.data.exception !is ArtifactNotFoundException) {
                            warn { it.data.exception.message ?: it.data.exception.toString() }
                        }
                    }
                }
                totalBytesDownloaded += it.data.dataLength
                val pr = totalBytesDownloaded.toDouble() / totalBytesToDownload.toDouble()
                downloadProgress.set(pr)
            }
        }

        runAsync {
            try {
                codeFetcher.downloadAndBuildClasspath(text)
            } finally {
                subscription.unsubscribe()
            }
        } fail { throwable ->
            onStartError(throwable)
        } ui { classpath ->
            if (downloadStarted) {
                downloadProgress.set(1.0)
                isDownloading.set(false)
                messageText1.set("")
                messageText2.set("")
            }
            andThen(classpath)
        }
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