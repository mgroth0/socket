package matt.socket.reader

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.serializer
import matt.lang.idea.FailableIdea
import matt.log.j.NOPLogger
import matt.log.logger.Logger
import matt.model.data.message.InterAppMessage
import matt.socket.ktor.KtorSocketConnection
import matt.stream.encoding.Encoding
import matt.stream.encoding.reader.message.SuspendingMessageReader
import matt.stream.encoding.result.MessageFailureReason

context(CoroutineScope)
suspend fun <R> KtorSocketConnection.launchNewSocketReader(
    encoding: Encoding,
    logger: Logger = NOPLogger,
    op: suspend NewSocketReaderDsl.() -> R
): R =
    NewSocketReaderImpl(
        this,
        readScope = this@CoroutineScope,
        encoding,
        log = logger
    ).op()


interface NewSocketReaderDsl {
    suspend fun readMessage(): InterAppPossibleMessageResult
}

sealed interface InterAppPossibleMessageResult: FailableIdea
sealed interface InterAppMessageResult: InterAppPossibleMessageResult
data object NoMessage: InterAppPossibleMessageResult
class Success(val message: InterAppMessage): InterAppMessageResult






class MessageReceptionFailure(val reason: MessageFailureReason): InterAppMessageResult

private class NewSocketReaderImpl(
    connection: KtorSocketConnection,
    readScope: CoroutineScope,
    encoding: Encoding,
    log: Logger = NOPLogger
) : SuspendingMessageReader<InterAppMessage>(
        encoding,
        connection,
        serializer<InterAppMessage>(),
        log
    ),
    NewSocketReaderDsl {

    private val messageChannel by lazy {
        with(readScope) {
            launchMessageChannel()
        }
    }

    override suspend fun readMessage(): InterAppPossibleMessageResult {

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




