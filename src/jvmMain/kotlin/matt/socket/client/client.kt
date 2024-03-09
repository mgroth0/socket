package matt.socket.client

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import matt.lang.anno.Duplicated
import matt.lang.j.sync
import matt.lang.model.file.AnyResolvableFilePath
import matt.lang.sync.common.SimpleReferenceMonitor
import matt.log.j.NOPLogger
import matt.log.logger.CalculatedPrefixLogger
import matt.model.data.message.ACTIVATE
import matt.model.data.message.EXIT
import matt.model.data.message.Go
import matt.model.data.message.InterAppMessage
import matt.model.data.message.Open
import matt.model.data.message.PING
import matt.model.data.message.PONG
import matt.prim.bytestr.encodeToByteString
import matt.shell.commonj.context.ReapingShellExecutionContext
import matt.socket.port.Port
import matt.socket.reader.InterAppMessageResult
import matt.socket.reader.MessageReceptionFailure
import matt.socket.reader.NoMessage
import matt.socket.reader.Success
import matt.socket.reader.launchNewSocketReader
import matt.socket.thing.InterAppThing
import matt.stream.encoding.result.ConnectionError
import matt.stream.encoding.result.MalformedInput2
import matt.stream.suspendchannels.writeBytes
import java.net.ConnectException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource.Monotonic


data class InterAppClientConfig(
    val timeout: Duration,
    val logging: Boolean
) {
    companion object {
        val DEFAULT =
            InterAppClientConfig(
                timeout = 2000.milliseconds,
                logging = false
            )
    }
}



open class InterAppClient(
    val port: Port,
    private val scope: CoroutineScope,
    private val config: InterAppClientConfig
) : InterAppThing(), AutoCloseable {


    private val sendChannel = Channel<InterAppMessage>(Channel.UNLIMITED)
    private val readChannel = Channel<InterAppMessageResult>(Channel.UNLIMITED)

    private var initialized = false
    private var closed = false
    private val monitor = SimpleReferenceMonitor()

    private val startMark = Monotonic.markNow()
    val logger = if (config.logging) CalculatedPrefixLogger { "[ client @ ${startMark.elapsedNow()} ]:" } else NOPLogger

    private fun debugLog(message: String) = logger.log(message)

    private val job: Job by lazy {
        monitor.sync {
            check(!closed)
            initialized = true
            scope.launch(Dispatchers.IO) {
                port.newClientSocket {
                    @Duplicated(2834523)
                    launchNewSocketReader(ENCODING, logger = logger) {
                        coroutineScope {
                            launch {
                                sendChannel.consumeEach {
                                    writeBytes(
                                        ENCODING.encodeMessage(
                                            Json.encodeToString(it).encodeToByteString()
                                        )
                                    )
                                }
                                writeBytes(ENCODING.encodeEnd())
                            }
                            launch {
                                do {
                                    val m = readMessage()
                                    when (m) {
                                        is MessageReceptionFailure -> {
                                            readChannel.send(m)
                                            break
                                        }
                                        is Success                 -> readChannel.send(m)
                                        NoMessage  -> break
                                    }
                                } while (true)
                                readChannel.close()
                            }
                        }
                    }
                }
            }
        }
    }

    /*this is critical. Because if I don't do this, and I am just interacting with channels, errors might not be thrown if the job has become inactive.*/
    private fun ensureActive() {
        check(job.isActive) {
            "job is not active? cancelled=${job.isCancelled}, completed=${job.isCompleted}"
        }
    }

    context(ReapingShellExecutionContext)
    fun serverSeemsToBeOpen() = port.processes().isNotEmpty()

    suspend fun receive(message: InterAppMessage) = sendAndReceive(message)

    suspend fun activate() = justSend(ACTIVATE)
    suspend fun areYouRunning() = receive(PING)
    suspend fun isReceptive(): Boolean =
        when (val response = receive(PING)) {
            is MessageReceptionFailure ->
                when (val reason = response.reason) {
                    is ConnectionError -> false
                    is MalformedInput2 -> error("malformed response ($reason)... unclear whether this counts as receptive or not")
                }
            is Success                 ->
                when (response.message) {
                    is PONG -> true
                    else -> error("weird response to ping: $response")
                }
        }

    suspend fun exit() = justSend(EXIT)
    suspend fun go(value: String) = justSend(Go(value))
    suspend fun open(value: String) = justSend(Open(value))
    suspend fun open(file: AnyResolvableFilePath) = open(file.path)


    final override fun close() {
        logger.log("closing 1")
        monitor.sync {
            logger.log("closing 2")
            closed = true
            readChannel.close()
            logger.log("closing 3")
            sendChannel.close()
            logger.log("closing 4")
            if (initialized) {
                logger.log("closing 5")
                job.cancel()
                logger.log("closing 6")
            }
            logger.log("closing 7")
        }
        logger.log("closing 8")
    }

    suspend fun justSend(
        message: InterAppMessage,
        andClose: Boolean = true
    ) {
        mutex.withLock {
            ensureActive()
            sendChannel.send(message)
            if (andClose) close()
        }
    }


    suspend fun sendAndReceive(
        message: InterAppMessage,
        andClose: Boolean = true,
        timeout: Duration = config.timeout
    ): InterAppMessageResult =
        mutex.withLock {
            ensureActive()
            sendChannel.send(message)
            val response: InterAppMessageResult =
                try {
                    withTimeout(timeout) {
                        readChannel.receive()
                    }
                } catch (e: ConnectException) {
                    MessageReceptionFailure(ConnectionError(e))
                }
            if (andClose) close()
            return response
        }


    suspend fun plusAssign(s: InterAppMessage) = justSend(s, andClose = false)




    private val mutex = Mutex()
}

