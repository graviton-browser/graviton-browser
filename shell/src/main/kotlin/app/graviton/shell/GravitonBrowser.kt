package app.graviton.shell

import app.graviton.mac.configureMacWindow
import app.graviton.mac.setupMacMenuBar
import app.graviton.ui.ThreeDSpinner
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.effect.GaussianBlur
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.stage.Stage
import tornadofx.*

// TODO: Organise all attribution links for artwork in the about box, once there is one.
// icons8.com
// OFL 1.1 license for the font
// vexels for the vector art

/** General app class: holds initialization code, etc. */
class GravitonBrowser : App(ShellView::class, Styles::class) {
    init {
        importStylesheet("/app/graviton/shell/graviton.css")
    }

    override fun start(stage: Stage) {
        mainLog.info("GravitonBrowser::start reached")
        stage.icons.addAll(
                Image(resources["art/icons8-rocket-take-off-128.png"]),
                Image(resources["art/icons8-rocket-take-off-512.png"]),
                Image(resources["art/icons8-rocket-take-off-64.png"])
        )
        stage.isMaximized = true
        if (currentOperatingSystem == OperatingSystem.MAC) {
            configureMacWindow(stage)
        }
        stage.title = "Graviton"
        super.start(stage)
    }
}

/**
 * The main window the user interacts with.
 */
class ShellView : View() {
    companion object : Logging()

    private lateinit var spinnerAnimation: ThreeDSpinner
    private val appLaunchUI: AppLaunchUI by inject()
    private val loginUI: LoginUI by inject()
    private lateinit var mainStackPane: StackPane
    private lateinit var screenshotView: ImageView

    // The idea of this is to let the shell have multiple 'screens' that nicely transition between them, and
    // maybe Graviton apps can also be a screen. However the support isn't finished yet.
    private val screens = iterator {
        // yield(loginUI)
        yield(appLaunchUI)
    }

    init {
        primaryStage.setOnCloseRequest {
            appLaunchUI.stop()
        }
    }

    data class Art(val fileName: String, val topPadding: Int, val animationColor: Color, val topGradient: Paint)

    @Suppress("UNREACHABLE_CODE")
    fun fadeInScene(newScene: Scene, finished: () -> Unit) {
        // TODO: Finish the animation.
        primaryStage.scene = newScene
        finished()
        return

//        var screenshot: WritableImage? = newScene.snapshot(null)
//        screenshotView.image = screenshot
//        screenshotView.opacity = 0.0
//        screenshotView.fitWidth = screenshotView.image.width
//        screenshotView.fitHeight = screenshotView.image.height
//        screenshotView.opacityProperty().animate(1.0, 0.5.seconds) {
//            setOnFinished {
//                // Get rid of all the pointers so the image can be collected.
//                screenshotView.image = null
//                screenshot = null
//                primaryStage.scene = newScene
//                finished()
//            }
//        }
    }

    private val allArt = listOf(
            Art("paris.png", 200, Color.BLUE, Color.WHITE),
            Art("forest.jpg", 200,
                    Color.color(0.0, 0.5019608, 0.0, 0.5),
                    LinearGradient.valueOf("transparent,rgb(218,239,244)")
            ),
            Art("evening-forest.jpg", 0, Color.WHITE, Color.WHITE)
    )
    private val art = allArt[1]

    override val root = stackpane {
        style { backgroundColor = multi(Color.WHITE) }

        if (currentOperatingSystem == OperatingSystem.MAC) {
            // On macOS we can't avoid having a menu bar, and anyway it's a good place to stash an about box
            // and other such things. TODO: Consider a menu strategy on other platforms.
            setupMacMenuBar()
        }

        artVBox()
        createSpinnerAnimation()
        mainStackPane = stackpane {
            children += screens.next().root
            alignment = Pos.TOP_CENTER
        }
        artCredits()

        screenshotView = imageview()
    }

    private fun StackPane.artCredits() {
        label("Background art by Vexels") {
            style {
                padding = box(10.px)
                textFill = Color.LIGHTGRAY
            }
        }.stackpaneConstraints { alignment = Pos.BOTTOM_RIGHT }
    }

    private fun StackPane.createSpinnerAnimation() {
        spinnerAnimation = ThreeDSpinner(art.animationColor)
        spinnerAnimation.root.maxWidth = 600.0
        spinnerAnimation.root.maxHeight = 600.0
        spinnerAnimation.root.translateY = 0.0
        children += spinnerAnimation.root
        spinnerAnimation.visible.bind(isWorking)
    }

    private fun StackPane.artVBox() {
        vbox {
            stackpane {
                style {
                    backgroundColor = multi(art.topGradient)
                }
                vbox {
                    minHeight = art.topPadding.toDouble()
                }
            }
            // Background image.
            imageview {
                image = Image(resources["art/${art.fileName}"])
                fitWidthProperty().bind(this@artVBox.widthProperty())
                isPreserveRatio = true

                // This line is useful when fiddling with the animation:
                // setOnMouseClicked { isDownloading.set(!isDownloading.value) }
            }.stackpaneConstraints {
                alignment = Pos.BOTTOM_CENTER
            }
        }.stackpaneConstraints { alignment = Pos.TOP_CENTER }
    }

    fun switchToNextScreen() {
        check(screens.hasNext())
        val animTime = 3.seconds
        val current = mainStackPane.children.first()
        val effect = GaussianBlur()
        current.effect = effect
        current.opacityProperty().animate(0.0, animTime)
        effect.radiusProperty().animate(100.0, animTime) {
            setOnFinished {
                // Helps with hot reload when iterating on the UI.
                current.opacity = 1.0
                mainStackPane.children.remove(current)
            }
        }
        val next = screens.next()
        mainStackPane.children += next.root
        next.root.opacity = 0.0
        next.root.opacityProperty().animate(1.0, animTime.divide(2.0)) {
            this.delay = 0.7.seconds
        }
    }
}