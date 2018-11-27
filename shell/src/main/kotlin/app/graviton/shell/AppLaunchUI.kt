package app.graviton.shell

import app.graviton.effects.addMacStyleScrolling
import app.graviton.effects.addTopBottomFades
import app.graviton.mac.stealFocusOnMac
import javafx.animation.Timeline
import javafx.beans.InvalidationListener
import javafx.beans.property.*
import javafx.concurrent.Task
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Cursor
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.control.TextField
import javafx.scene.effect.BlurType
import javafx.scene.effect.DropShadow
import javafx.scene.input.MouseButton
import javafx.scene.layout.*
import javafx.scene.paint.Color
import javafx.scene.shape.Rectangle
import javafx.scene.text.TextAlignment
import tornadofx.*
import java.io.File
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * The logo, coordinate bar, progress bar and history list. Things the user interacts with on the main screen.
 */
class AppLaunchUI : View() {
    companion object : Logging()

    private lateinit var messageText1: StringProperty
    private lateinit var messageText2: StringProperty
    private lateinit var coordinateBar: TextField
    private val allThreads = ThreadGroup("App Launch")
    private var launcher: Task<*>? = null
    private val downloadProgress = SimpleDoubleProperty(0.0)
    private val isHistoryVisible = SimpleBooleanProperty(true)
    private val logo = find<LogoView>()
    private lateinit var recentAppsPicker: VBox
    private lateinit var tracker: StackPane

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

                tracker = downloadTracker()

                pane { minHeight = 25.0 }

                recentAppsPicker = vbox {
                    spacing = 15.0
                    padding = Insets(20.0, 0.0, 20.0, 0.0)
                    populateRecentAppsPicker()
                }

                maxWidth = 1024.0
                maxHeight = Double.POSITIVE_INFINITY
                spacing = 5.0
                alignment = Pos.TOP_CENTER
            }
        }
    }

    private fun fadeAnim(tiles: List<HBox>, tracker: StackPane, nowWorking: Boolean) {
        fun tiles(): Timeline? {
            var timeline: Timeline? = null
            for ((index, tile) in tiles.withIndex()) {
                tile.opacityProperty().animate(if (nowWorking) 0.0 else 1.0, 0.1.seconds) {
                    delay = (index * 0.02).seconds
                    timeline = this
                }
            }
            return timeline
        }
        if (nowWorking) {
            val tl = tiles()
            fun go() {
                tracker.isManaged = true
                tracker.opacityProperty().animate(1.0, 0.2.seconds)
            }
            if (tl == null) go() else tl.setOnFinished { go() }
        } else {
            tracker.opacityProperty().animate(0.0, 0.2.seconds) {
                setOnFinished {
                    tracker.isManaged = false
                    tiles()
                }
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

    private fun VBox.downloadTracker(): StackPane {
        return stackpane {
            fitToParentWidth()
            maxHeight = 100.0
            opacity = 0.0

            rectangle {
                styleClass += "stripey"
                style {
                    opacity = 0.3
                }
                widthProperty().bind(this@stackpane.widthProperty())
                heightProperty().bind(this@stackpane.heightProperty())
            }

            rectangle {
                styleClass += "stripey"
                widthProperty().bind(this@stackpane.widthProperty().multiply(downloadProgress))
                heightProperty().bind(this@stackpane.heightProperty())
            }.stackpaneConstraints { alignment = Pos.CENTER_LEFT }

            // Labels
            vbox {
                padding = insets(15.0)
                alignment = Pos.CENTER
                label {
                    messageText1 = textProperty()
                    textAlignment = TextAlignment.CENTER
                    effect = DropShadow()
                    style {
                        fontSize = 20.pt
                        textFill = Color.WHITE
                    }
                }
                label {
                    messageText2 = textProperty()
                    textAlignment = TextAlignment.CENTER
                    style {
                        fontSize = 15.pt
                        textFill = Color.WHITE
                    }
                    effect = DropShadow()
                }
            }

            // Stop button.
            stackpane {
                circle {
                    fill = Color.WHITE
                    radius = 25.0
                    effect = DropShadow(BlurType.THREE_PASS_BOX, Color.BLACK, 4.5, 0.0, 1.0, 1.0)
                }

                rectangle {
                    fill = Color.BLACK
                    width = 20.0
                    height = 20.0
                    arcWidth = 5.0
                    arcHeight = 5.0
                }

                setOnMouseClicked {
                    cancelIfDownloading()
                }

                minWidth = Region.USE_PREF_SIZE
                minHeight = Region.USE_PREF_SIZE
                maxWidth = Region.USE_PREF_SIZE
                maxHeight = Region.USE_PREF_SIZE
                translateX = 15.0
                cursor = Cursor.HAND
                alignment = Pos.CENTER
            }.stackpaneConstraints { alignment = Pos.CENTER_LEFT }

            isManaged = false
            isCache = true

            clip = Rectangle().apply {
                widthProperty().bind(this@stackpane.widthProperty())
                heightProperty().bind(this@stackpane.heightProperty())
                arcWidth = 10.0
                arcHeight = 10.0
            }
        }
    }

    private fun cancelIfDownloading() {
        if (launcher != null) {
            info { "Cancelling" }
            launcher!!.cancel()
            launcher = null
        }
    }

    private var lastListener: InvalidationListener? = null

    private fun VBox.populateRecentAppsPicker() {
        children.clear()
        alignment = Pos.TOP_CENTER
        val tiles = historyManager.history.map { createAppTile(it) }
        // Set up the animation as downloads start and stop.
        lastListener?.let { isWorking.removeListener(it) }
        lastListener = InvalidationListener { fadeAnim(tiles, tracker, isWorking.get()) }
        isWorking.addListener(lastListener)
    }

    private fun VBox.createAppTile(entry: HistoryEntry): HBox {
        return hbox {
            val observableEntry = SimpleObjectProperty(entry)

            // Give it a white card look, make it only appear when it should be here.
            addClass(Styles.historyEntry)
            visibleWhen(isHistoryVisible)
            managedProperty().bind(isHistoryVisible)

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
                        startWorking("Refreshing ...")
                        task {
                            info { "User requested refresh of $entry" }
                            val curEntry = observableEntry.get()
                            val fetcher = AppLauncher(GravitonCLI.parse(""), appLaunchEventHandler, historyManager)
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

                item("Remove") {
                    setOnAction {
                        historyManager.removeEntry(entry)
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

            isCache = true
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
        tracker.opacity = 0.0
        recentAppsPicker.populateRecentAppsPicker()
    }

    private fun beginLaunch() {
        cancelIfDownloading()

        val text = coordinateBar.text
        if (text.isBlank()) return

        // Parse what the user entered as if it were a command line: this feature is a bit of an easter egg,
        // but makes testing a lot easier, e.g. to force a re-download just put --clear-cache at the front.
        val cmdLineParams = app.parameters.raw.joinToString(" ")
        val options = GravitonCLI.parse("$cmdLineParams $text".trim())

        launcher = FXTask {
            AppLauncher(options, appLaunchEventHandler, historyManager, primaryStage).start()
        } success {
            resetUI()
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
        Thread(launcher).also { it.isDaemon = true }.start()
    }

    private fun startWorking(message: String) {
        // We animate even if there's no downloading to do because for complex apps, simply resolving dependency graphs and starting the
        // app can take a bit of time.
        isWorking.set(true)
        messageText1.set(message)
        root.vvalueProperty().animate(0.0, 0.5.seconds)
    }

    private val appLaunchEventHandler = object : AppLauncher.Events() {
        private val eventLog = ArrayList<String>()
        private var lastEventTime = System.currentTimeMillis()

        private fun ev(str: String) {
            val now = System.currentTimeMillis()
            val elapsed = now - lastEventTime
            eventLog += "$elapsed $str"
            lastEventTime = now
        }

        // Make sure we update the UI on the right thread, and ignore any events that come in after
        // cancellation by the user.
        private fun wrap(body: () -> Unit) {
            fx {
                if (launcher == null) return@fx
                body()
            }
        }

        override fun preparingToDownload() = wrap {
            startWorking("Locating ...")
        }

        override fun onStartedDownloading(name: String) = wrap {
            ev("START $name")
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
            ev("FETCH $name $totalBytesToDownload $totalDownloadedSoFar")
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
            progress = min(1.0, max(0.0, pr))
            downloadProgress.set(progress)
        }

        override fun onStoppedDownloading() = wrap {
            ev("STOP")
            //File("/tmp/eventlog").writeText(eventLog.joinToString(System.lineSeparator()))
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
                this.wedgeFX = null
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
        messageText1.set("Locating ...")
        val fetchDelayMsec = 0
        thread(isDaemon = true) {
            File("/tmp/eventlog").useLines { seq ->
                for (line in seq) {
                    val parts = line.split(' ')
                    val delayMsec = parts[0].toLong()
                    val event = parts[1]
                    Thread.sleep(delayMsec + if(event == "FETCH") fetchDelayMsec else 0)
                    when (event) {
                        "START" -> appLaunchEventHandler.onStartedDownloading(parts[2])
                        "FETCH" -> appLaunchEventHandler.onFetch(parts[2], parts[3].toLong(), parts[4].toLong())
                        "STOP" -> appLaunchEventHandler.onStoppedDownloading()
                    }
                }
            }
            fx {
                isWorking.set(false)
            }
        }
    }

    fun stop() {
        cancelIfDownloading()
    }
}

fun clearCache() {
    historyManager.clearCache()
    Alert(Alert.AlertType.INFORMATION, "Cache has been cleared. Apps will re-download next time they are " +
            "invoked or a background update occurs.", ButtonType.CLOSE).showAndWait()
}