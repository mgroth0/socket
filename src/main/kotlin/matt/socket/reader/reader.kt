package matt.socket.reader

import kotlinx.coroutines.delay
import kotlinx.serialization.serializer
import matt.lang.until
import matt.lang.untilIs
import matt.log.NOPLogger
import matt.log.logger.Logger
import matt.model.data.message.InterAppMessage
import matt.stream.encoding.Encoding
import matt.stream.encoding.reader.message.MessageReader
import matt.stream.encoding.result.EOF
import matt.stream.encoding.result.ReadSectionParsed
import matt.stream.encoding.result.TIMEOUT
import matt.stream.encoding.result.UNREADABLE
import java.lang.System.currentTimeMillis
import java.net.Socket
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

fun Socket.socketReader(
    log: Logger = NOPLogger,
    encoding: Encoding,
    sleepTime: Duration = 100.milliseconds,
    readTime: Duration = 1.milliseconds,
) = SocketReader(
    this,
    log = log,
    encoding = encoding,
    sleepTime = sleepTime,
    readTime = readTime
)

class SocketReader(
    val socket: Socket,
    encoding: Encoding,
    readTime: Duration,
    private var sleepTime: Duration,
    log: Logger = NOPLogger
) : MessageReader<InterAppMessage>(encoding, socket.getInputStream(), serializer<InterAppMessage>(), log) {

    private var readTime: Duration?
        get() = socket.soTimeout.takeIf { it != 0 }?.milliseconds
        set(value) {
            socket.soTimeout = value?.inWholeMilliseconds?.toInt() ?: 0
        }

    init {
        this.readTime = readTime
    }

    suspend fun readSectionsBeforeTimeout(
        timeout: Duration,
    ): List<InterAppMessage> = decorate(timeout) {
        val stopAt = currentTimeMillis() + timeout.inWholeMilliseconds
        val sections = mutableListOf<InterAppMessage>()
        until(stopAt) {
            sections += readSectionOrSuspend() ?: return@decorate sections
        }
        sections
    }

    suspend fun readSectionBeforeTimeout(
        timeout: Duration,
    ): InterAppMessage? = decorate(timeout) {
        val stopAt = currentTimeMillis() + timeout.inWholeMilliseconds
        until(stopAt) {
            readSectionOrSuspend()?.let {
                return@decorate it
            }
        }
        null
    }

    suspend fun readSectionOrSuspend() = readMessageOr {
        delay(sleepTime.inWholeMilliseconds)
    }

    private inline fun readMessageOr(op: () -> Unit): InterAppMessage? = decorate {
        untilIs {
            when (val sectionResult = message()) {
                EOF, UNREADABLE         -> null
                TIMEOUT                 -> op()
                is ReadSectionParsed<*> -> sectionResult.sect
            }
        }
    }


}



