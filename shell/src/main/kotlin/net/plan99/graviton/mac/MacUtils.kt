package net.plan99.graviton.mac

import com.sun.jna.Pointer
import de.codecentric.centerdevice.MenuToolkit
import de.codecentric.centerdevice.dialogs.about.AboutStageBuilder
import javafx.stage.Stage
import javafx.stage.StageStyle
import net.plan99.graviton.*
import tornadofx.*
import java.awt.image.BufferedImage
import java.util.*
import javax.imageio.ImageIO

fun View.setupMacMenuBar() {
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
                    clearCache()
                }
            }
            separator()
            this += tk.createQuitMenuItem(appBrandName)
        }
        tk.setForceQuitOnCmdQ(false)  // So Cmd-Q can go "back"
        tk.setApplicationMenu(appMenu)
    }
}

fun App.configureMacWindow(stage: Stage) {
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
}

fun Stage.stealFocusOnMac() {
    if (currentOperatingSystem == OperatingSystem.MAC) {
        // We can steal focus using this macOS specific API. Unfortunately JavaFX won't do it for us.
        val nsRunningApplication: Pointer = ObjectiveC.objc_lookUpClass("NSRunningApplication")
        check(ObjectiveC.class_getName(nsRunningApplication) == "NSRunningApplication")
        val currentAppSel: Pointer = ObjectiveC.sel_getUid("currentApplication")
        val currentApp = Pointer(ObjectiveC.objc_msgSend(nsRunningApplication, currentAppSel))
        val activateSel: Pointer = ObjectiveC.sel_getUid("activateWithOptions:")
        // 3 = NSApplicationActivateIgnoringOtherApps | NSApplicationActivateAllWindows
        ObjectiveC.objc_msgSend(currentApp, activateSel, 3)
    }
}
