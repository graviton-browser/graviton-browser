package net.plan99.graviton

import org.junit.Test
import java.net.URL
import java.net.URLClassLoader
import kotlin.test.assertEquals

class AppLauncherTest {
    private fun loadFrom(jarName: String, mainClassName: String): Class<*> {
        val url: URL = javaClass.getResource("/testapps/$jarName")
        return URLClassLoader(arrayOf(url)).loadClass(mainClassName)
    }

    @Test
    fun launchStrategies() {
        assertEquals(AppLauncher.LoadStrategy.SEARCH_FOR_JFX_APP, AppLauncher.selectLoadStrategy(null, true))
        assertEquals(AppLauncher.LoadStrategy.INVOKE_MAIN_DIRECTLY,
                AppLauncher.selectLoadStrategy(loadFrom("cli-empty-manifest.jar", "testapp.CLI"), true)
        )
        assertEquals(AppLauncher.LoadStrategy.INVOKE_MAIN_DIRECTLY,
                AppLauncher.selectLoadStrategy(loadFrom("cli-main-class.jar", "testapp.CLI"), true)
        )
        val awtClass = loadFrom("awt.jar", "testapp.AWT")
        assertEquals(AppLauncher.GuiToolkit.AWT, AppLauncher.scanForGUIToolkit(awtClass))
        assertEquals(AppLauncher.LoadStrategy.RESTART_AND_RUN,
                AppLauncher.selectLoadStrategy(awtClass, true)
        )
        assertEquals(AppLauncher.LoadStrategy.INVOKE_MAIN_DIRECTLY,
                AppLauncher.selectLoadStrategy(awtClass, false)
        )
        val swingClass = loadFrom("swing.jar", "testapp.SWING")
        assertEquals(AppLauncher.GuiToolkit.SWING, AppLauncher.scanForGUIToolkit(swingClass))
        assertEquals(AppLauncher.LoadStrategy.RESTART_AND_RUN,
                AppLauncher.selectLoadStrategy(swingClass, true)
        )
        assertEquals(AppLauncher.LoadStrategy.INVOKE_MAIN_DIRECTLY,
                AppLauncher.selectLoadStrategy(swingClass, false)
        )
    }
}