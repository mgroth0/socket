package matt.socket.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.KSerializer
import matt.lang.idea.FailableIdea
import matt.log.j.NOPLogger
import matt.log.logger.Logger
import matt.socket.ktor.KtorSocketConnection
import matt.stream.encoding.Encoding
import matt.stream.encoding.reader.message.SuspendingMessageReader
import matt.stream.encoding.result.MessageFailureReason


/*terrible naming! sounds like launch, but it executes in place and the coroutine does not advance... so it needs to be inside another launch...*/
context(CoroutineScope)
suspend fun <T: Any, R> KtorSocketConnection.launchNewSocketReader(
    encoding: Encoding,
    logger: Logger = NOPLogger,
    serializer: KSerializer<T>,
    op: suspend NewSocketReaderDsl<T>.() -> R
): R =
    NewSocketReaderImpl(
        this,
        readScope = this@CoroutineScope,
        encoding,
        log = logger,
        serializer = serializer
    ).op()


interface NewSocketReaderDsl<T: Any> {
    suspend fun readMessage(): InterAppPossibleMessageResult<T>
}

sealed interface InterAppPossibleMessageResult<out T: Any>: FailableIdea
sealed interface InterAppMessageResult<out T: Any>: InterAppPossibleMessageResult<T>
data object NoMessage: InterAppPossibleMessageResult<Nothing>
class Success<T: Any>(val message: T): InterAppMessageResult<T>
class MessageReceptionFailure(val reason: MessageFailureReason): InterAppMessageResult<Nothing>

private class NewSocketReaderImpl<T: Any>(
    connection: KtorSocketConnection,
    readScope: CoroutineScope,
    encoding: Encoding,
    log: Logger = NOPLogger,
    serializer: KSerializer<T>
) : SuspendingMessageReader<T>(
        encoding,
        connection,
        serializer,
        log
    ),
    NewSocketReaderDsl<T> {

    private val messageChannel by lazy {
        with(readScope) {
            launchMessageChannel()
        }
    }

    override suspend fun readMessage(): InterAppPossibleMessageResult<T> {

        val sectionResult =
            try {
                messageChannel.receive()
            } catch (e: ClosedReceiveChannelException) {
                /*this happens normally, since I close messageChannel manually when the socket is closed*/
                return NoMessage
            }
        return Success(sectionResult)
    }
}




