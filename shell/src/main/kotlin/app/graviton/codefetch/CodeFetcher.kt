package app.graviton.codefetch

import app.graviton.shell.Logging
import app.graviton.shell.reversedCoordinates
import app.graviton.shell.rootCause
import app.graviton.shell.withNameAndDescription
import org.apache.http.client.HttpResponseException
import org.apache.maven.model.Model
import org.apache.maven.repository.internal.ArtifactDescriptorReaderDelegate
import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.conscrypt.Conscrypt
import org.eclipse.aether.DefaultRepositorySystemSession
import org.eclipse.aether.RepositoryException
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.Artifact
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.graph.DependencyNode
import org.eclipse.aether.impl.DefaultServiceLocator
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RepositoryPolicy
import org.eclipse.aether.resolution.ArtifactDescriptorResult
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.resolution.VersionRangeRequest
import org.eclipse.aether.resolution.VersionRangeResult
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.MetadataNotFoundException
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.graph.transformer.*
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import org.eclipse.aether.util.repository.JreProxySelector
import java.nio.file.Files
import java.nio.file.Path
import java.security.Security
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong


class StartException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * A wrapper around the Aether library that configures it to download artifacts from various different Maven
 * repositories, reports progress and returns calculated classpaths.
 */
class CodeFetcher(private val cachePath: Path,
                  private val events: Events?,
                  private val repoSpec: RepoSpec,
                  val offline: Boolean = false,
                  private val clock: Clock = Clock.systemUTC()) {
    companion object : Logging() {
        fun isPossiblyJitPacked(packageName: String) =
                packageName.startsWith("com.github.") ||
                        packageName.startsWith("org.bitbucket.") ||
                        packageName.startsWith("com.gitlab.")
    }

    init {
        // Ensure the local Maven repo exists.
        Files.createDirectories(cachePath)

        // Override built-in security using Open/BoringSSL via the Conscrypt provider. The index here is 1-based.
        // This essentally eliminates the overhead of enabling SSL using JSSE and doubles download performance.
        Security.insertProviderAt(Conscrypt.newProvider(), 1)
    }

    private val repoSystem: RepositorySystem by lazy {
        val locator: DefaultServiceLocator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        locator.getService(RepositorySystem::class.java)
    }

    private val session: RepositorySystemSession by lazy {
        val session: DefaultRepositorySystemSession = MavenRepositorySystemUtils.newSession()
        session.isOffline = offline
        session.transferListener = TransferListener()

        // Configure Aether to use the Java proxy selector API by default, which it doesn't do out of the box.
        // We set up the Proxy Vole library which can read browser settings and use PAC files in the startup code.
        session.proxySelector = JreProxySelector()

        // The separate checksum files are of questionable use in an SSL world and slow down the transfer quite
        // a bit, so we toss them here.
        session.checksumPolicy = RepositoryPolicy.CHECKSUM_POLICY_IGNORE

        // Graviton does its own lookup caching on top of CodeFetcher so we want to always poll the remote servers
        // when asked if there's a new version.
        session.updatePolicy = RepositoryPolicy.UPDATE_POLICY_ALWAYS

        // Set up the local repository for caching downloaded artifacts.
        val localRepo = LocalRepository(cachePath.toFile())
        session.localRepositoryManager = repoSystem.newLocalRepositoryManager(session, localRepo)

        // Make Maven Resolver be multi-threaded, and make it copy the name and description fields of the POM
        // into the artifact props so we can grab them later.
        session.setConfigProperties(mapOf(
                "aether.metadataResolver.threads" to 20,
                "aether.connector.basic.threads" to 20,
                ArtifactDescriptorReaderDelegate::class.java.name to object : ArtifactDescriptorReaderDelegate() {
                    override fun populateResult(session: RepositorySystemSession, result: ArtifactDescriptorResult, model: Model) {
                        super.populateResult(session, result, model)
                        val name = model.name ?: result.artifact.artifactId
                        // Yes, really ...
                        val description = if (model.description == "null") null else model.description
                        result.artifact = result.artifact.withNameAndDescription(name, description)
                    }
                }
        ))

        // Re-configure the conflict resolver. By default it uses a weird way to resolve version conflicts in a dependency
        // graph, the so-called "nearest wins" strategy which basically says versions closer to the root node in the graph
        // (of lower depth) take precedence over versions deeper in the tree. This is neither intuitive nor what Gradle does.
        // So we replace it here with one that just always picks the newest version - of course such dependency graphs are
        // inherently unstable and the best we can do is hope. One day Jigsaw might actually clean this whole mess up.
        val conflictResolver = ConflictResolver(HighestVersionSelector(), JavaScopeSelector(), SimpleOptionalitySelector(), JavaScopeDeriver())
        ChainedDependencyGraphTransformer(conflictResolver, JavaDependencyContextRefiner())
        session.dependencyGraphTransformer = conflictResolver

        session
    }

    private class HighestVersionSelector : ConflictResolver.VersionSelector() {
        override fun selectVersion(context: ConflictResolver.ConflictContext) {
            val items = context.items
            var winner = items.first()
            if (items.map { it.node.version }.toSet().size == 1) {
                context.winner = winner
                return
            }
            logger.warn("Resolving conflict for ${context.items.first().node.artifact} between ${items.map { it.node.version }}")
            for (item in items) {
                if (item.node.version > winner.node.version)
                    winner = item
            }
            // We ignore version constraints here - maybe we should use them, but it's not clear we can ever do better than highest wins.
            // If that doesn't work the app is broken.
            context.winner = winner
        }
    }

    private inner class TransferListener : AbstractTransferListener() {
        val didDownload = AtomicBoolean(false)
        private val totalBytesToDownload = AtomicLong(0L)
        private val totalDownloaded = AtomicLong(0L)

        private fun isBoring(event: TransferEvent): Boolean {
            val name = event.resource.resourceName
            return name.endsWith(".xml") || name.endsWith(".md5") || name.endsWith(".sha1")
        }

        override fun transferInitiated(event: TransferEvent) {
            if (isBoring(event)) return
            if (!didDownload.getAndSet(true))
                events?.onStartedDownloading(event.resource.file.name)
        }

        @Volatile
        var lastFinish: String = ""
        @Volatile
        var lastStart: String = ""

        override fun transferStarted(event: TransferEvent) {
            info { "$event" }
            if (isBoring(event)) return
            totalBytesToDownload.addAndGet(event.resource.contentLength)
            lastStart = event.resource.file.name
        }

        override fun transferSucceeded(event: TransferEvent) {
            info { "$event" }
            if (isBoring(event)) return
            lastFinish = event.resource.file.name
        }

        override fun transferProgressed(event: TransferEvent) {
            debug { "$event" }
            if (isBoring(event)) return
            totalDownloaded.addAndGet(event.dataLength.toLong())
            val fileNameToReport = if (lastStart == event.resource.file.name) lastStart else lastFinish
            try {
                events?.onFetch(fileNameToReport, totalBytesToDownload.get(), totalDownloaded.get())
            } catch (e: Exception) {
                logger.error("Exception in event handler", e)
            }
        }

        override fun transferFailed(event: TransferEvent) {
            debug { "$event" }
        }

        override fun transferCorrupted(event: TransferEvent) = transferFailed(event)
    }

    open class Events {
        /** Called exactly once, if we decide we need to do any network transfers of non-trivial files (like JARs). */
        open fun onStartedDownloading(name: String) {}

        open fun onFetch(name: String, totalBytesToDownload: Long, totalDownloadedSoFar: Long) {}

        /** If [onStartedDownloading] was called, this is called when we are finished or have failed. */
        open fun onStoppedDownloading() {}
    }

    /** The result of resolving the given coordinates. */
    data class Result(val classPath: String, val artifact: Artifact, val refreshTime: Instant) {
        /** The contents of the POM name field or the artifact ID if no name was specified. */
        val name: String get() = artifact.properties["model.name"] ?: artifact.artifactId

        /** The contents of the POM description field, or null if not specified. */
        val description: String? get() = artifact.properties["model.description"]
    }

    fun download(userInput: String): CodeFetcher.Result {
        fun download1(reverseInput: Boolean): CodeFetcher.Result {
            val coordinates: String = calculateCoordinates(userInput, reverseInput)
            info { "Attempt fetch for $coordinates" }
            return downloadAndBuildClasspath(coordinates)
        }
        return try {
            download1(false)
        } catch (e: RepositoryException) {
            info { "User input '$userInput' not found, reversing the coordinates and trying again" }
            try {
                download1(true)
            } catch (_: RepositoryException) {
                // We deliberately ignore the second exception here, instead we report the first as we
                // don't want to expose reversed names to the end user in error messages (the Maven
                // coordinate in its unmolested form is still canonical and seeing unexpectedly reversed
                // coordinates might be confusing).
                val rootCause = e.rootCause
                if (rootCause is MetadataNotFoundException) {
                    throw StartException("Sorry, no package with those coordinates is known.", e)
                } else if (rootCause is HttpResponseException && rootCause.statusCode == 401 && CodeFetcher.isPossiblyJitPacked(userInput)) {
                    // JitPack can return 401 Unauthorized when no repository is found e.g. typo, because it
                    // might be a private repository that requires authentication.
                    throw StartException("Sorry, no repository was found with those coordinates.", e)
                } else {
                    // Put all the errors together into some sort of coherent story.
                    val m = StringBuilder()
                    var cursor: Throwable = e.cause!!
                    var lastMessage = ""
                    while (true) {
                        if (cursor.message != lastMessage) {
                            lastMessage = cursor.message ?: ""
                            m.appendln(lastMessage)
                        }
                        cursor = cursor.cause ?: break
                    }
                    throw StartException(m.toString(), e)
                }
            }
        }
    }

    private fun calculateCoordinates(userInput: String, reverseInput: Boolean): String {
        var packageName: String = if (reverseInput) reversedCoordinates(userInput) else userInput

        // If there's no : anywhere in it, it's just a reverse domain name, then assume the artifact ID is the
        // same as the last component of the group ID.
        val components = packageName.split(':').toMutableList()
        if (components.size == 1) {
            components += components[0].split('.').last()
        }
        packageName = components.joinToString(":")

        return packageName
    }

    /**
     * Returns a classpath for the given resolved and dependency-downloaded package. If only two parts of the coordinate
     * are specified, the latest version is assumed.
     *
     * @param packageName Package name as a coordinate fragment.
     */
    fun downloadAndBuildClasspath(packageName: String): Result {
        val name = try {
            checkLatestVersion(packageName)
        } catch (e: MetadataNotFoundException) {
            // Maybe try again if the user entered something like com.github.username:reponame in a repo with
            // no releases and without specifying :master explicitly.
            if (!isPossiblyJitPacked(packageName) || packageName.split(':').size > 2) throw e
            info { "Trying again with $packageName:master because we failed to download and the name may be a jitpack.io package" }
            checkLatestVersion("$packageName:master")
        }
        info { "Request to download and build classpath for $name" }
        val artifact = DefaultArtifact(name)
        val node: DependencyNode = resolveArtifact(artifact)
        if ((session.transferListener as TransferListener).didDownload.get())
            events?.onStoppedDownloading()
        val classPathGenerator = PreorderNodeListGenerator()
        node.accept(classPathGenerator)
        val classPath = classPathGenerator.classPath
        info { "Classpath for ${classPath.split(':').joinToString(System.lineSeparator())}" }
        // node.artifact has been enhanced with name/description properties as part of collectDependencies
        // so we must use it, rather than 'artifact' even though they may appear to be the same.
        return Result(classPath, node.artifact, clock.instant())
    }

    private fun resolveArtifact(artifact: DefaultArtifact): DependencyNode {
        val dependency = Dependency(artifact, "runtime")
        val collectRequest = CollectRequest()
        collectRequest.root = dependency
        collectRequest.repositories = remoteRepos
        lateinit var node: DependencyNode
        stopwatch("Dependency resolution") {
            node = repoSystem.collectDependencies(session, collectRequest).root
            repoSystem.resolveDependencies(session, DependencyRequest(node, null))
        }
        return node
    }

    private fun checkLatestVersion(packageName: String): String {
        // Parse the coordinate fragment the user entered.
        val components = packageName.split(':').toMutableList()

        // If there's no version, perform a version range request to find the latest version.
        if (components.size == 2) {
            // [0,) is magic syntax meaning: any version from 0 to infinity, i.e. all versions.
            val artifact = DefaultArtifact((components + "[0,)").joinToString(":"))
            val request = VersionRangeRequest(artifact, remoteRepos, null)
            val result: VersionRangeResult = stopwatch("Latest version lookup for " + components.joinToString(":")) {
                repoSystem.resolveVersionRange(session, request)
            }
            info { "Latest version is $result" }
            if (result.highestVersion == null)
                throw result.exceptions.first()
            components += result.highestVersion.toString()
        }

        return components.joinToString(":")
    }

    private val remoteRepos by lazy { repoSpec.resolve(repoSystem, session) }
}