package matt.socket.client

import kotlinx.coroutines.runBlocking
import matt.lang.model.file.AnyResolvableFilePath
import matt.model.data.message.ACTIVATE
import matt.model.data.message.EXIT
import matt.model.data.message.Go
import matt.model.data.message.InterAppMessage
import matt.model.data.message.Open
import matt.model.data.message.PING
import matt.model.data.message.PONG
import matt.shell.context.ReapingShellExecutionContext
import matt.socket.port.Port
import matt.socket.reader.socketReader
import matt.socket.thing.InterAppThing
import matt.stream.encoding.writer.EncodingOutputStream
import matt.stream.encoding.writer.withEncoding
import java.net.ConnectException
import kotlin.time.Duration.Companion.milliseconds


open class InterAppClient(val port: Port) : InterAppThing() {

    context(ReapingShellExecutionContext)
    fun serverSeemsToBeOpen() = port.processes().isNotEmpty()

    fun receive(message: InterAppMessage) = send(message, andReceive = true)

    fun activate() = send(ACTIVATE, andReceive = false)
    fun areYouRunning() = receive(PING)
    fun isReceptive(): Boolean {
        val response = receive(PING)
        if (response == null) return false
        if (response !is PONG) {
            error("weird response to ping: $response")
        }
        return true
    }

    fun exit() = send(EXIT, andReceive = false)
    fun go(value: String) = send(Go(value), andReceive = false)
    fun open(value: String) = send(Open(value), andReceive = false)
    fun open(file: AnyResolvableFilePath): InterAppMessage? = open(file.path)

    private var out: EncodingOutputStream? = null

    private val socket get() = port.clientSocket()

    @Synchronized
    private fun oldOrNewOutputStream() =
        out ?: (socket.getOutputStream().withEncoding(ENCODING).also {
            out = it
        })

    @Synchronized
    fun close() {
        port.closeClientSocket()
        out?.close()
        out = null
    }

    @Synchronized
    fun send(
        message: InterAppMessage,
        andReceive: Boolean = false,
        andClose: Boolean = true
    ): InterAppMessage? = runBlocking {
        var response: InterAppMessage? = null
        try {
            oldOrNewOutputStream().sendJson(message)
            if (andReceive) response =
                socket.socketReader(encoding = ENCODING).readSectionBeforeTimeout(2000.milliseconds)
        } catch (e: ConnectException) {
            println(e)
            e.printStackTrace()
            return@runBlocking null
        }
        if (andClose) close()
        return@runBlocking response
    }


    operator fun plusAssign(s: InterAppMessage) = send(s, andReceive = false, andClose = false).let { }

}

