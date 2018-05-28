package net.plan99.graviton

import kotlinx.coroutines.experimental.CoroutineScope
import kotlinx.coroutines.experimental.runBlocking
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.KeyFactory
import java.security.PublicKey
import java.security.spec.X509EncodedKeySpec
import java.time.Duration
import java.util.*

// TODO: Test on Windows with a full background update.

object BackgroundUpdates : Logging() {
    private const val signingPubKeyB64 = "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAElI2KSzGh1b9KYsYXAU/YYtOqsm1aFcwkuj1VhjNmaVo/0SKUZvdApP5N9+wa2KvQS0UoXdD4XvHKxiEtJ0/QiA=="
    val mikePubKey: PublicKey by lazy {
        val spec = X509EncodedKeySpec(Base64.getDecoder().decode(signingPubKeyB64))
        KeyFactory.getInstance("EC").generatePublic(spec)
    }

    private var currentVersion: Int = 0

    fun doBackgroundUpdate(cachePath: Path, currentVersion: Int?, currentInstallDir: Path?, baseUpdateURL: URI) {
        runBlocking {
            try {
                stopwatch("Background update") {
                    refreshRecentApps(cachePath)
                }
                // We won't check for online updates unless run from the main install image, as otherwise we may not have
                // a version or installation path.
                if (currentVersion != null && currentInstallDir != null) {
                    this@BackgroundUpdates.currentVersion = currentVersion
                    stopwatch("Graviton update") {
                        checkForGravitonUpdate(currentVersion, currentInstallDir, baseUpdateURL)
                    }
                }
            } catch (e: Exception) {
                // For some sorts of errors don't bother dumping a giant stack trace to the log; we expect them.
                when (e) {
                    is IOException, is HTTPRequestException -> logger.error(e.message)
                    else -> throw e
                }
            }
        }
    }

    data class ControlFileResults(val verNum: Int, val url: HttpUrl)

    sealed class Result {
        data class UpdatedTo(val version: Int) : Result()
        object AlreadyFresh : Result()
        object InsufficientDiskSpace : Result()
    }

    /**
     * Returns the version number that we were upgraded to, or null if no upgrade was performed.
     */
    fun checkForGravitonUpdate(currentVersion: Int, currentInstallDir: Path, baseURL: URI,
                               signingPublicKey: PublicKey = mikePubKey): Result {
        val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        try {
            val controlFileData = fetchControlData(baseURL, currentVersion, client)
            val (verNum, redirectURL) = controlFileData
            logger.info("Latest version is $verNum")

            if (verNum <= currentVersion) {
                logger.info("We are up to date.")
                return Result.AlreadyFresh
            }

            return fetch(client, redirectURL) {
                downloadAndInstallUpgrade(it, currentInstallDir, verNum, signingPublicKey)
            }
        } finally {
            client.dispatcher().executorService().shutdown()
            client.connectionPool().evictAll()
        }
    }

    internal fun fetchControlData(baseURL: URI, currentVersion: Int, client: OkHttpClient): ControlFileResults {
        val url = HttpUrl.get(baseURL)!!
                .newBuilder()
                .addPathSegment(currentOperatingSystem.name.toLowerCase())
                .addPathSegment("control")
                .addEncodedQueryParameter("c", currentVersion.toString())
                .build()
        return fetch(client, url) { extractControlDataFrom(it, url) }
    }

    // Runs a request and passes the response to the block. The block scopes the request
    // and it will be closed, freeing the resources used, after the block completes.
    private fun <T> fetch(client: OkHttpClient, url: HttpUrl, block: (Response) -> T): T {
        try {
            logger.info("GET $url")
            val request = Request.Builder()
                    .addHeader("User-Agent", "Graviton/$currentVersion")
                    .url(url)
                    .build()
            return client.newCall(request).execute().use { response ->
                if (!response.isSuccessful)
                    throw HTTPRequestException(response.code(), response.message(), url)
                block(response)
            }
        } catch (e: HTTPRequestException) {
            throw e
        } catch (e: Exception) {
            throw HTTPRequestException(0, e.message ?: "(unknown)", url, e)
        }
    }

    private const val LATEST_VERSION_URL = "Latest-Version-URL"

    private fun extractControlDataFrom(response: Response, baseURL: HttpUrl): ControlFileResults {
        logger.info("Got control data from server, parsing")
        val controlProps = Properties().apply { load(response.body()!!.charStream()) }
        val redirect = controlProps.getProperty(LATEST_VERSION_URL)
                ?: error("No $LATEST_VERSION_URL property in control file:\n$controlProps")
        val latestVersionURL = (baseURL.newBuilder(redirect) ?: error("Failed to parse $redirect as link URL")).build()
        logger.info("Update JAR URL is $latestVersionURL")
        // We expect the redirect to take us to a file of the form X.jar where X is an integer
        val remoteFileName = latestVersionURL.pathSegments().last()
        val verNum = remoteFileName.split(".")[0].toInt()
        return ControlFileResults(verNum, latestVersionURL)
    }

    private fun downloadAndInstallUpgrade(response: Response, currentInstallDir: Path, verNum: Int, signingPublicKey: PublicKey): Result {
        val updateJarPath = Files.createTempFile("graviton-update", ".update.jar")
        try {
            if (!isEnoughDiskSpace(updateJarPath, currentInstallDir))
                return Result.InsufficientDiskSpace
            Files.newOutputStream(updateJarPath).use { out ->
                response.body()!!.byteStream().copyTo(out)
            }
            logger.info("Saved update JAR to $updateJarPath")
            val targetInstallDir = currentInstallDir / verNum.toString()
            logger.info("Unpacking to $targetInstallDir")
            RuntimeUpdate(updateJarPath, signingPublicKey).install(targetInstallDir)
            return Result.UpdatedTo(verNum)
        } finally {
            Files.delete(updateJarPath)
        }
    }

    var requiredFreeSpaceMB = 500

    private fun isEnoughDiskSpace(updateJarPath: Path, targetInstallDir: Path): Boolean {
        // Check the temp directory for sufficient space.
        val usableSpaceOnTempDriveMB = Files.getFileStore(updateJarPath).usableSpace / 1024 / 1024
        if (usableSpaceOnTempDriveMB < requiredFreeSpaceMB) {
            logger.warn("Not enough disk space in temporary directory ${updateJarPath.parent}. Have " +
                    "$usableSpaceOnTempDriveMB MB but won't proceed without $requiredFreeSpaceMB MB")
            return false
        }
        // Now check the install location, they may be different.
        val usableSpaceOnTargetDriveMB = Files.getFileStore(targetInstallDir).usableSpace / 1024 / 1024
        if (usableSpaceOnTargetDriveMB < requiredFreeSpaceMB) {
            logger.warn("Not enough disk space to apply update. Have $usableSpaceOnTargetDriveMB " +
                    "but won't proceed without $requiredFreeSpaceMB")
            return false
        }
        return true
    }

    private suspend fun CoroutineScope.refreshRecentApps(cachePath: Path) {
        try {
            val codeFetcher = CodeFetcher(coroutineContext, cachePath)
            val historyManager = HistoryManager(currentOperatingSystem.appCacheDirectory, refreshInterval = Duration.ofHours(12))
            historyManager.refreshRecentlyUsedApps(codeFetcher)
        } catch (e: Exception) {
            logger.error("App refresh failed", e)
            // Fall through.
        }
    }
}
