package net.plan99.graviton

import org.eclipse.aether.RepositoryException
import org.eclipse.aether.transfer.MetadataNotFoundException
import java.io.File
import java.net.URL
import java.net.URLClassLoader
import java.util.jar.JarFile
import java.util.jar.Manifest
import kotlin.concurrent.thread

class InvokeException(message: String, cause: Throwable?) : Exception(message, cause) {
    constructor(message: String) : this(message, null)
}

fun invokeMainClass(classpath: String, packageName: String, args: Array<String>, onFinish: () -> Unit = {}): Thread {
    val loadResult = try {
        buildClassLoaderFor(packageName, classpath)
    } catch (e: RepositoryException) {
        val rootCause = e.rootCause
        if (rootCause is MetadataNotFoundException) {
            throw InvokeException("Sorry, no package with those coordinates is known.", e)
        } else {
            throw InvokeException("Fetch error: ${rootCause.message}", e)
        }
    }
    val mainMethod = loadResult.mainClass.getMethod("main", Array<String>::class.java)
    val subArgs = args.drop(1).toTypedArray()
    return thread(contextClassLoader = loadResult.classloader, name = "Main thread for $packageName") {
        // TODO: Stop the program quitting itself.
        try {
            mainMethod.invoke(null, subArgs)
        } finally {
            onFinish()
        }
    }
}

private class AppLoadResult(val classloader: URLClassLoader, val appManifest: Manifest) {
    val mainClassName: String get() = appManifest.mainAttributes.getValue("Main-Class") ?: throw InvokeException("This application is not an executable program.")
    val mainClass: Class<*> get() = Class.forName(mainClassName, true, classloader)
}

private fun buildClassLoaderFor(packageName: String, classpath: String): AppLoadResult {
    val files = classpath.split(':').map { File(it) }
    val urls: Array<URL> = files.map { it.toURI().toURL() }.toTypedArray()
    // Chain to the parent classloader so our internals don't interfere with the application.
    val classloader = URLClassLoader(packageName, urls, Thread.currentThread().contextClassLoader.parent)
    val manifest = JarFile(files[0]).use { it.manifest }
    return AppLoadResult(classloader, manifest)
}