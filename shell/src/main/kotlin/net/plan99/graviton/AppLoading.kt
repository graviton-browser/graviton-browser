package net.plan99.graviton

import io.github.lukehutch.fastclasspathscanner.FastClasspathScanner
import javafx.application.Application
import javafx.application.Platform
import javafx.beans.property.Property
import javafx.stage.Stage
import org.eclipse.aether.RepositoryException
import org.eclipse.aether.transfer.MetadataNotFoundException
import java.io.File
import java.io.PrintStream
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.concurrent.thread

class InvokeException(message: String, cause: Throwable?) : Exception(message, cause) {
    constructor(message: String) : this(message, null)
}

fun startApp(classpath: String, artifactName: String, args: Array<String>, primaryStage: Stage?, outStream: PrintStream?) {
    val loadResult = try {
        buildClassLoaderFor(artifactName, classpath)
    } catch (e: RepositoryException) {
        val rootCause = e.rootCause
        if (rootCause is MetadataNotFoundException) {
            throw InvokeException("Sorry, no package with those coordinates is known.", e)
        } else {
            throw InvokeException("Fetch error: ${rootCause.message}", e)
        }
    }
    val mainClass = loadResult.mainClass
    val jfxApplicationClass = loadResult.jfxApplicationClass
    // We try to run a JavaFX app first, to bypass the main method and give a smoother transition.
    // This also avoids problems with us trying to launch JavaFX twice. However it does mean if the
    // main method has non-trivial logic in it, then it won't be run!
    //
    // TODO: Disassemble the main method if found to see if it just does Application.launch and if so, skip it.
    if (jfxApplicationClass != null) {
        invokeJavaFXApplication(jfxApplicationClass, primaryStage, args, artifactName)
    } else if (mainClass != null) {
        invokeMainMethod(artifactName, args, loadResult, mainClass, outStream, andWait = primaryStage == null)
    } else {
        throw InvokeException("This application is not an executable program.")
    }
}

private fun invokeJavaFXApplication(jfxApplicationClass: Class<out Application>, primaryStage: Stage?, args: Array<String>, artifactName: String) {
    if (primaryStage == null) {
        // Being started from the command line, JavaFX wasn't set up yet.
        Application.launch(jfxApplicationClass, *args)
    } else {
        // Being started from the shell, we have already set up JFX and we aren't allowed to initialise it twice.
        // So we have to do a bit of acrobatics here and create the Application ourselves, then hand it a cleaned
        // version of our stage.
        check(Platform.isFxApplicationThread())
        // Switch context classloader, this is needed for loading code and resources.
        Thread.currentThread().contextClassLoader = jfxApplicationClass.classLoader
        val app = jfxApplicationClass.getConstructor().newInstance()
        app.init()
        // We use reflection here to unbind all the Stage properties to avoid having to change this codepath if JavaFX
        // or the shell changes e.g. by adding new properties or binding new ones.
        primaryStage.unbindAllProperties()
        primaryStage.width = primaryStage.width
        primaryStage.height = primaryStage.height
        primaryStage.scene = null
        primaryStage.title = artifactName
        primaryStage.hide()
        app.start(primaryStage)
    }
}

private fun Any.unbindAllProperties() {
    javaClass.methods
             .filter { it.name.endsWith("Property") && Property::class.java.isAssignableFrom(it.returnType) }
             .map { it.invoke(this) as Property<*> }
             .forEach { it.unbind() }
}

private fun invokeMainMethod(artifactName: String, args: Array<String>, loadResult: AppLoadResult, mainClass: Class<*>, outputStream: PrintStream?, andWait: Boolean) {
    val mainMethod = mainClass.getMethod("main", Array<String>::class.java)
    mainLog.info("Invoking $mainMethod")
    val subArgs = args.drop(1).toTypedArray()
    // Start in a separate thread, so we can continue to intercept IO and render it to the shell.
    // That feature will probably go away soon and we'll get pickier about which thread we run the
    // sub-program on, because the sub-program probably has expectations about this.
    val redirectIO = outputStream != null
    val oldstdout = System.out
    val oldstderr = System.err
    if (redirectIO) {
        System.setOut(outputStream)
        System.setErr(outputStream)
    }
    val t = thread(contextClassLoader = loadResult.classloader, name = "Main thread for $artifactName") {
        // TODO: Stop the program quitting itself.
        try {
            mainMethod.invoke(null, subArgs)
        } finally {
            if (redirectIO) {
                System.setOut(oldstdout)
                System.setErr(oldstderr)
            }
        }
    }
    if (andWait) t.join()
}

private class AppLoadResult(val classloader: URLClassLoader, val appManifest: Manifest) {
    val mainClassName: String? get() = appManifest.mainAttributes.getValue("Main-Class")
    val mainClass: Class<*>? by lazy { mainClassName?.let { Class.forName(it, true, classloader) } }

    val jfxApplicationClass: Class<out Application>? by lazy { calculateJFXClass() }

    private fun calculateJFXClass(): Class<out Application>? {
        val clazz = try {
            mainClass?.asSubclass(Application::class.java)
        } catch (e: ClassCastException) {
            return null
        }
        return if (clazz != null) {
            clazz
        } else {
            val scanner = FastClasspathScanner().overrideClassLoaders(classloader).scan()
            val appClassName: String? = scanner.getNamesOfSubclassesOf(Application::class.java).firstOrNull()
            if (appClassName != null) {
                Class.forName(appClassName, false, classloader).asSubclass(Application::class.java)
            } else {
                null
            }
        }
    }
}

private fun buildClassLoaderFor(packageName: String, classpath: String): AppLoadResult {
    try {
        val classpathDelimiter = currentOperatingSystem.classPathDelimiter
        val files = classpath.split(classpathDelimiter).map { File(it) }
        val urls: Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()
        // Chain to the parent classloader so our internals don't interfere with the application.
        val classloader = URLClassLoader(packageName, urls, Thread.currentThread().contextClassLoader.parent)
        val manifest = JarFile(files[0]).use { it.manifest }
        return AppLoadResult(classloader, manifest)
    } catch (e: Exception) {
        throw RuntimeException("Failed to build classloader given class path: $classpath", e)
    }
}