package net.plan99.graviton

import org.apache.maven.repository.internal.MavenRepositorySystemUtils
import org.eclipse.aether.RepositorySystem
import org.eclipse.aether.RepositorySystemSession
import org.eclipse.aether.artifact.DefaultArtifact
import org.eclipse.aether.collection.CollectRequest
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory
import org.eclipse.aether.graph.Dependency
import org.eclipse.aether.repository.LocalRepository
import org.eclipse.aether.repository.RemoteRepository
import org.eclipse.aether.resolution.DependencyRequest
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory
import org.eclipse.aether.spi.connector.transport.TransporterFactory
import org.eclipse.aether.transfer.AbstractTransferListener
import org.eclipse.aether.transfer.TransferEvent
import org.eclipse.aether.transfer.TransferResource
import org.eclipse.aether.transport.file.FileTransporterFactory
import org.eclipse.aether.transport.http.HttpTransporterFactory
import org.eclipse.aether.util.graph.visitor.PreorderNodeListGenerator
import org.reactfx.EventSource
import org.reactfx.EventStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.*
import java.util.concurrent.Executor
import java.util.concurrent.Executors

/**
 * A wrapper around the Aether library that configures it to download artifacts from Maven Central, reports progress
 * and returns calculated classpaths.
 */
class CodeFetcher {
    /** The location on disk where the local Maven repository is. */
    val cachePath: Path = currentOperatingSystem.appCacheDirectory
    init {
        // Ensure the local Maven repo exists.
        Files.createDirectories(cachePath)
    }

    private val repoSystem: RepositorySystem by lazy {
        val locator = MavenRepositorySystemUtils.newServiceLocator()
        locator.addService(RepositoryConnectorFactory::class.java, BasicRepositoryConnectorFactory::class.java)
        locator.addService(TransporterFactory::class.java, FileTransporterFactory::class.java)
        locator.addService(TransporterFactory::class.java, HttpTransporterFactory::class.java)
        locator.getService(RepositorySystem::class.java)
    }

    private val session: RepositorySystemSession by lazy {
        val session = MavenRepositorySystemUtils.newSession()
        session.transferListener = object : AbstractTransferListener() {
            fun push(event: TransferEvent, type: TransferEvent.EventType) {
                _eventExecutor.submit {
                    _allTransferEvents.push(Event(event, type))
                }
            }
            override fun transferInitiated(event: TransferEvent) = push(event, TransferEvent.EventType.INITIATED)
            override fun transferStarted(event: TransferEvent) = push(event, TransferEvent.EventType.STARTED)
            override fun transferSucceeded(event: TransferEvent) = push(event, TransferEvent.EventType.SUCCEEDED)
            override fun transferProgressed(event: TransferEvent) = push(event, TransferEvent.EventType.PROGRESSED)
            override fun transferFailed(event: TransferEvent) = push(event, TransferEvent.EventType.FAILED)
            override fun transferCorrupted(event: TransferEvent) = push(event, TransferEvent.EventType.CORRUPTED)
        }
        val localRepo = LocalRepository(cachePath.toFile())
        session.localRepositoryManager = repoSystem.newLocalRepositoryManager(session, localRepo)
        session
    }

    private val _eventExecutor = Executors.newSingleThreadExecutor {
        Thread(it, "Aether Downloader Event Dispatch").also { it.isDaemon = true }
    }
    /** The executor on which stream events are dispatched. Use it with [EventStream.threadBridge] */
    val eventExecutor: Executor = _eventExecutor

    class Event(val data: TransferEvent, val type: TransferEvent.EventType)
    private val _allTransferEvents = EventSource<Event>()
    /** A stream of transfer events related to different downloads all mixed together. */
    val allTransferEvents: EventStream<Event> = _allTransferEvents
    /** A stream of streams. Each new stream represents a new transfer. */
    val transferStreams: EventStream<EventStream<Event>> = EventSource()

    init {
        val transferEventStreams = HashMap<TransferResource, EventStream<Event>>()
        allTransferEvents.subscribe {
            val source = transferEventStreams.getOrPut(it.data.resource) { EventSource() } as EventSource<Event>
            if (it.type == TransferEvent.EventType.INITIATED)
                (transferStreams as EventSource<EventStream<Event>>).push(source)
            source.push(it)
            if (it.type == TransferEvent.EventType.FAILED || it.type == TransferEvent.EventType.CORRUPTED)
                transferEventStreams.remove(it.data.resource)
        }
    }

    fun clearCache() {
        // TODO: This should synchronise with the repository manager to ensure nothing is downloading at the time.
        if (!cachePath.toFile().deleteRecursively()) {
            throw Exception("Failed to clear disk cache")
        }
    }

    /**
     * Returns a classpath for the given resolved and dependency-downloaded package.
     *
     * @param packageName Package name in standard groupId:artifactId:version form.
     */
    fun downloadAndBuildClasspath(packageName: String): String {
        val dependency = Dependency(DefaultArtifact(packageName), "runtime")
        val mavenCentral = RemoteRepository.Builder("central", "default", "https://repo1.maven.org/maven2/").build()
        val collectRequest = CollectRequest().also {
            it.root = dependency
            it.addRepository(mavenCentral)
        }
        val collectDependencies = repoSystem.collectDependencies(session, collectRequest)
        val node = collectDependencies.root
        repoSystem.resolveDependencies(session, DependencyRequest(node, null))
        val classPathGenerator = PreorderNodeListGenerator()
        node.accept(classPathGenerator)
        return classPathGenerator.classPath
    }
}