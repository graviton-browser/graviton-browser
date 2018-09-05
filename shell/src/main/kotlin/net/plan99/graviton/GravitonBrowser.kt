package net.plan99.graviton

import de.codecentric.centerdevice.MenuToolkit
import de.codecentric.centerdevice.dialogs.about.AboutStageBuilder
import javafx.geometry.Pos
import javafx.scene.control.Alert
import javafx.scene.control.ButtonType
import javafx.scene.effect.DropShadow
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.paint.Color
import javafx.scene.paint.LinearGradient
import javafx.scene.paint.Paint
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.stage.StageStyle
import tornadofx.*
import java.awt.image.BufferedImage
import java.util.*
import javax.imageio.ImageIO

// TODO: Organise all attribution links for artwork in the about box, once there is one.
// icons8.com
// OFL 1.1 license for the font
// vexels for the vector art

/** General app class: holds initialization code, etc. */
class GravitonBrowser : App(ShellView::class, Styles::class) {
    init {
        importStylesheet("/net/plan99/graviton/graviton.css")
    }

    override fun start(stage: Stage) {
        stage.icons.addAll(
                Image(resources["art/icons8-rocket-take-off-128.png"]),
                Image(resources["/net/plan99/graviton/art/icons8-rocket-take-off-512.png"]),
                Image(resources["art/icons8-rocket-take-off-64.png"])
        )
        stage.isMaximized = true
        if (currentOperatingSystem == OperatingSystem.MAC) {
            // This looks nice on OS X but not so great on other platforms. Note that it doesn't work on Java 8, but looks right on
            // Java 10. Once we upgrade we'll get it back.
            stage.initStyle(StageStyle.UNIFIED)
            val dockImage: BufferedImage = ImageIO.read(resources.stream("art/icons8-rocket-take-off-512.png"))
            try {
                // This is a PITA - stage.icons doesn't work on macOS, instead there's two other APIs, one for Java 8 and one for post-J8.
                // Try the Java 9 API first. We could also write this code out the obvious way but for now I want to be able to switch
                // back and forth between compile JDKs easily. TODO: Simplify away the reflection when I'm committed to building with Java 11.
                Class.forName("java.awt.Taskbar")
                        ?.getMethod("getTaskbar")
                        ?.invoke(null)?.let { taskbar ->
                            taskbar.javaClass
                                    .getMethod("setIconImage", java.awt.Image::class.java)
                                    .invoke(taskbar, dockImage)
                        }
            } catch (e: ClassNotFoundException) {
                try {
                    Class.forName("com.apple.eawt.Application")
                            ?.getMethod("getApplication")
                            ?.invoke(null)?.let { app ->
                                app.javaClass
                                        .getMethod("setDockIconImage", java.awt.Image::class.java)
                                        .invoke(app, dockImage)
                            }
                } catch (e: Exception) {
                    mainLog.warn("Failed to set dock icon", e)
                }
            }
            stage.title = "Graviton"
        }

        super.start(stage)
    }
}

/**
 * The main window the user interacts with.
 */
class ShellView : View() {
    companion object : Logging()

    private lateinit var spinnerAnimation: ThreeDSpinner

    data class Art(val fileName: String, val topPadding: Int, val animationColor: Color, val topGradient: Paint)

    private val allArt = listOf(
            Art("paris.png", 200, Color.BLUE, Color.WHITE),
            Art("forest.jpg", 200,
                    Color.color(0.0, 0.5019608, 0.0, 0.5),
                    LinearGradient.valueOf("transparent,rgb(218,239,244)")
            )
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
        stackpane {
            children += find<AppLaunchUI>().root
        }
        artCredits()
    }

    private fun StackPane.artCredits() {
        label("Background art by Vexels") {
            style {
                padding = box(10.px)
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

    private fun setupMacMenuBar() {
        val tk = MenuToolkit.toolkit()
        val aboutStage = AboutStageBuilder
                .start("About $appBrandName")
                .withAppName(appBrandName)
                .withCloseOnFocusLoss()
                .withVersionString("Version $gravitonShellVersionNum")
                .withCopyright("Copyright \u00A9 " + Calendar.getInstance().get(Calendar.YEAR))
                .withImage(appBrandLogo)
                .build()

        menubar {
            val appMenu = menu(appBrandName) {
                // Note that the app menu name can't be changed at runtime and will be ignored; to make the menu bar say Graviton
                // requires bundling it. During development it will just say 'java' and that's OK.
                this += tk.createAboutMenuItem(appBrandName, aboutStage)
                separator()
                item("Clear cache ...") {
                    setOnAction {
                        historyManager.clearCache()
                        Alert(Alert.AlertType.INFORMATION, "Cache has been cleared. Apps will re-download next time they are " +
                                "invoked or a background update occurs.", ButtonType.CLOSE).showAndWait()
                    }
                }
                separator()
                this += tk.createQuitMenuItem(appBrandName)
            }
            tk.setApplicationMenu(appMenu)
        }
    }
}

class Styles : Stylesheet() {
    companion object {
        val shellArea by cssclass()
        val content by cssclass()
        val logoText by cssclass()
        val messageBox by cssclass()
        val historyEntry by cssclass()
        val historyTitle by cssclass()
    }

    private val wireFont: Font = loadFont("/net/plan99/graviton/art/Wire One regular.ttf", 25.0)!!

    init {
        val cornerRadius = multi(box(10.px))

        shellArea {
            fontFamily = "monospace"
            fontSize = 15.pt
            borderColor = multi(box(Color.gray(0.8, 1.0)))
            borderWidth = multi(box(3.px))
            borderRadius = cornerRadius
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.95))
            backgroundRadius = cornerRadius
            scrollPane {
                content {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
                viewport {
                    backgroundColor = multi(Color.TRANSPARENT)
                }
            }
        }

        logoText {
            font = wireFont
            fontSize = 120.pt
            effect = DropShadow(15.0, Color.WHITE)
        }

        messageBox {
            backgroundColor = multi(Color.color(1.0, 1.0, 1.0, 0.9))
            backgroundRadius = multi(box(5.px))
            borderWidth = multi(box(3.px))
            borderColor = multi(box(Color.LIGHTGREY))
            borderRadius = multi(box(5.px))
            fontSize = 25.pt
        }

        historyEntry {
            borderWidth = multi(box(2.px))
            borderColor = multi(box(Color.web("#dddddd")))
            borderRadius = cornerRadius
            backgroundColor = multi(LinearGradient.valueOf("white,#eeeeeedd"))
            backgroundRadius = cornerRadius
            padding = box(20.px)
        }

        historyEntry and hover {
            borderColor = multi(box(Color.web("#555555")))
            cursor = javafx.scene.Cursor.HAND
        }

        historyTitle {
            fontSize = 25.pt
            padding = box(0.px, 0.px, 15.pt, 0.px)
        }
    }
}