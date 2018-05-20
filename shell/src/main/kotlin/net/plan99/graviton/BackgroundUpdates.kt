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

// TODO: Include secure hashes in the redirect file and check post-download that it's correct.
// TODO: Test on macOS with a full background update.
// TODO: Make application of updates atomic by renaming destination directory to final form after unpack.
// TODO: Check disk space before starting any background updates.
// TODO: Document how to prepare a runtime update for each OS.
// TODO: Test on Windows with a full background update.
// TODO: Backup private keys.
// TODO: Document how to generate a keystore and sign a JAR
//       openssl req -new -key ec_key.pem -nodes -x509 -days 3650 -out update_cert.pem
//       openssl pkcs12 -inkey ec_key.pem -in update_cert.pem  -export -out keystore.p12 -alias mike

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

    /**
     * Returns the version number that we were upgraded to, or null if no upgrade was performed.
     */
    fun checkForGravitonUpdate(currentVersion: Int, currentInstallDir: Path, baseURL: URI,
                               signingPublicKey: PublicKey = mikePubKey): Int? {
        val client = OkHttpClient.Builder()
                .followRedirects(true)
                .followSslRedirects(true)
                .build()
        try {
            val controlFileData = fetchControlData(baseURL, currentVersion, client)
            val (verNum, redirectURL) = controlFileData
            logger.info("Latest version is $verNum")
            // Do we need to update at all? Usually not.
            if (verNum <= currentVersion) {
                logger.info("We are up to date.")
                return null
            }

            fetch(client, redirectURL) {
                downloadAndInstallUpgrade(it, currentInstallDir, verNum, signingPublicKey)
            }
            return verNum
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
        val redirect = controlProps.getProperty(LATEST_VERSION_URL) ?: error("No $LATEST_VERSION_URL property in control file:\n$controlProps")
        val latestVersionURL = (baseURL.newBuilder(redirect) ?: error("Failed to parse $redirect as link URL")).build()
        logger.info("Update JAR URL is $latestVersionURL")
        // We expect the redirect to take us to a file of the form X.jar where X is an integer
        val remoteFileName = latestVersionURL.pathSegments().last()
        val verNum = remoteFileName.split(".")[0].toInt()
        return ControlFileResults(verNum, latestVersionURL)
    }

    private fun downloadAndInstallUpgrade(response: Response, currentInstallDir: Path, verNum: Int, signingPublicKey: PublicKey) {
        val updateJarPath = Files.createTempFile("graviton-update", ".update.jar")
        try {
            Files.newOutputStream(updateJarPath).use { out ->
                response.body()!!.byteStream().copyTo(out)
            }
            logger.info("Saved update JAR to $updateJarPath")
            val targetInstallDir = currentInstallDir / verNum.toString()
            logger.info("Unpacking to $targetInstallDir")
            RuntimeUpdate(updateJarPath, signingPublicKey).install(targetInstallDir)
        } finally {
            Files.delete(updateJarPath)
        }
    }

    suspend fun CoroutineScope.refreshRecentApps(cachePath: Path) {
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
