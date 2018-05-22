package net.plan99.graviton

import org.pmw.tinylog.Configuration
import org.pmw.tinylog.Configurator
import org.pmw.tinylog.LogEntry
import org.pmw.tinylog.policies.SizePolicy
import org.pmw.tinylog.writers.LogEntryValue
import org.pmw.tinylog.writers.RollingFileWriter
import org.pmw.tinylog.writers.Writer
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.bridge.SLF4JBridgeHandler
import org.slf4j.event.Level
import picocli.CommandLine
import picocli.CommandLine.Help.Ansi.Style.*
import java.io.PrintStream

/**
 * Intended to be the superclass of a companion object, adds some useful logging utilities.
 */
open class Logging {
    @Suppress("LeakingThis")
    val logger: Logger = LoggerFactory.getLogger(this::class.java.enclosingClass ?: this::class.java)

    inline fun debug(message: () -> String) = if (logger.isDebugEnabled) logger.debug(message()) else Unit
    inline fun info(message: () -> String) = if (logger.isInfoEnabled) logger.info(message()) else Unit
    inline fun warn(message: () -> String) = if (logger.isWarnEnabled) logger.warn(message()) else Unit
    inline fun error(message: () -> String) {
        val msg = message()
        logger.error(msg)
        throw IllegalStateException(msg)
    }

    /**
     * A convenience wrapper to make logging the length of time tasks took easy.
     */
    inline fun <T> stopwatch(msg: String, level: Level = Level.INFO, block: () -> T): T {
        val sw = Stopwatch()
        try {
            return block()
        } finally {
            val elapsed = sw.elapsedInSec
            val l = if (elapsed > 0.0) "$msg took ${sw.elapsedInSec} seconds" else "$msg completed immediately."
            when (level) {
                Level.ERROR -> logger.error(l)
                Level.WARN -> logger.warn(l)
                Level.INFO -> logger.info(l)
                Level.DEBUG -> logger.debug(l)
                Level.TRACE -> logger.trace(l)
            }
        }
    }
}

private object NicerConsoleWriter : Writer {
    override fun getRequiredLogEntryValues() = setOf(LogEntryValue.LEVEL, LogEntryValue.RENDERED_LOG_ENTRY)

    private val err = System.err
    private val out = System.out

    private fun getPrintStream(level: org.pmw.tinylog.Level): PrintStream {
        return if (level == org.pmw.tinylog.Level.ERROR || level == org.pmw.tinylog.Level.WARNING) err else out
    }

    private val errorStyle = arrayOf(fg_white, bold, bg_red)
    private val normalStyle = arrayOf(faint)

    override fun write(logEntry: LogEntry) {
        val stream = getPrintStream(logEntry.level)
        val text = logEntry.renderedLogEntry
        if (CommandLine.Help.Ansi.AUTO.enabled()) {
            val style = if (stream == System.err) errorStyle else normalStyle
            val i = text.indexOf(System.lineSeparator())
            stream.print(CommandLine.Help.Ansi.Style.on(*style))
            stream.print(text.subSequence(0, i))
            stream.print(CommandLine.Help.Ansi.Style.reset.on())
            stream.print(text.substring(i))
        } else {
            stream.print(text)
        }
    }

    override fun init(configuration: Configuration) = Unit

    override fun flush() {
        out.flush()
        err.flush()
    }

    override fun close() = Unit
}

fun setupLogging(logToConsole: Boolean) {
    // Tell TinyLog to roll every 5mb of logs.
    with(Configurator.defaultConfig()) {
        writer(RollingFileWriter((currentOperatingSystem.loggingDirectory / "log.txt").toString(), 5, false, SizePolicy(1024*1024*5)))
        if (logToConsole)
            addWriter(NicerConsoleWriter)
        formatPattern("{date:yyyy-MM-dd HH:mm:ss} {level} [{thread}] {class_name}.{method}()\\n{message}")
        activate()
    }
    // Point java.util.logging (used by TornadoFX at least) at SLF4J, and from there, to TinyLog.
    // We lose the method name this way, will need to make TinyLog a bit more flexible around stack walking.
    SLF4JBridgeHandler.removeHandlersForRootLogger()
    SLF4JBridgeHandler.install()
    java.util.logging.Logger.getLogger("").level = java.util.logging.Level.FINEST
}
