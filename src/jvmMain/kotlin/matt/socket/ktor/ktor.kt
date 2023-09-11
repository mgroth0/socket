package matt.socket.ktor

import io.ktor.network.selector.*
import io.ktor.network.sockets.*
import io.ktor.utils.io.*
import io.ktor.utils.io.jvm.javaio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.asSource
import matt.lang.LOCALHOST
import matt.lang.safeconvert.requireIsInt
import matt.socket.port.Port
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.CoroutineContext

typealias ConnectionOp<R> = suspend KtorSocketConnection.() -> R

interface SocketScope : CoroutineScope {
    suspend fun <R> server(
        port: Port,
        op: suspend KtorServerSocket.() -> R
    ): R {
        val serverSocket = KtorServerSocketImpl(port)
        return try {
            serverSocket.run {
                op()
            }
        } finally {
            serverSocket.close()
        }
    }

    suspend fun <R> client(
        port: Port,
        op: ConnectionOp<R>
    ): R {
        val serverSocket = KtorServerSocketTargetImpl(port)
        return serverSocket.connect(op)
    }
}


suspend fun <R> useSockets(
    newContext: CoroutineContext,
    op: suspend SocketScope.() -> R
): R {
    return withContext(newContext) {
        val manager = SelectorManager(this.coroutineContext)
        manager.use {
            val socketScope = SocketScopeImpl(manager)
            socketScope.op()
        }
    }
}

private class SocketScopeImpl(manager: SelectorManager) : SocketScope {
    override val coroutineContext = manager.coroutineContext
}


sealed class KtorSocket {
    companion object {
        @JvmStatic
        protected val SELECTOR_MANAGER by lazy {
            /*WARNING: I used to close this after use. Does it need to be closed? Or does it use daemon threads?*/
            SelectorManager(Dispatchers.IO)
        }
    }

    protected val socketDefinition = aSocket(SELECTOR_MANAGER).tcp()

    protected suspend fun <R> handleConnection(
        rawConnection: Socket,
        op: ConnectionOp<R>
    ): R {
        return try {
            val connection = KtorSocketConnectionImpl(rawConnection)
            val r = connection.op()
            r
        } finally {
            withContext(Dispatchers.IO) {
                rawConnection.close()
            }
        }

    }

}

interface KtorServerSocketTarget {
    suspend fun <R> connect(op: ConnectionOp<R>): R
}

private class KtorServerSocketTargetImpl(private val port: Port) : KtorSocket(), KtorServerSocketTarget {
    override suspend fun <R> connect(op: ConnectionOp<R>): R {
        val rawConnection = socketDefinition.connect(LOCALHOST, port.port)
        return handleConnection(rawConnection, op)
    }
}

interface KtorServerSocket {
    suspend fun <R> accept(op: ConnectionOp<R>): R
}

private class KtorServerSocketImpl(port: Port) : KtorSocket(), KtorServerSocket {
    private val socket = socketDefinition.bind(LOCALHOST, port.port)
    override suspend fun <R> accept(op: ConnectionOp<R>): R {
        val rawConnection = socket.accept()
        return handleConnection(rawConnection, op)
    }

    fun close() {
        socket.close()
    }
}

interface KtorSocketConnection {
    suspend fun readByte(): Byte
    suspend fun readUByte(): UByte
    suspend fun readLine(): String?
    suspend fun writeBytes(bytes: ByteArray)
    suspend fun writeLine(s: String)
    suspend fun writeByte(byte: Byte)
    suspend fun readAllBytes(): ByteArray
    suspend fun check(): ReadChannelStatus
    suspend fun readInt(): Int
    suspend fun readLong(): Long
    suspend fun readDouble(): Double
    suspend fun readFloat(): Float
    suspend fun readChar(): Char
    suspend fun readShort(): Short
    suspend fun readNBytes(n: Int): ByteArray
    suspend fun readBool(): Boolean
    suspend fun writeBool(bool: Boolean)
    suspend fun writeInt(int: Int)
    suspend fun writeLong(long: Long)
    suspend fun writeDouble(double: Double)
    suspend fun writeShort(short: Short)
    suspend fun writeFloat(float: Float)
}

sealed interface ReadChannelStatus
object MoreToRead : ReadChannelStatus
object Closed : ReadChannelStatus

private class KtorSocketConnectionImpl internal constructor(socket: Socket) : KtorSocketConnection {

    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)

    override suspend fun check(): ReadChannelStatus {
        receiveChannel.awaitContent()
        if (receiveChannel.isClosedForRead) {
            return Closed
        }
        return MoreToRead
    }

    override suspend fun readInt(): Int {
        return receiveChannel.readInt()
    }

    override suspend fun writeBool(bool: Boolean) {
        if (bool) writeByte(1.toByte())
        else writeByte(0.toByte())
    }

    override suspend fun writeDouble(double: Double) {
        sendChannel.writeDouble(double)
    }

    override suspend fun writeFloat(float: Float) {
        sendChannel.writeFloat(float)
    }

    override suspend fun writeInt(int: Int) {
        sendChannel.writeInt(int)
    }

    override suspend fun writeLong(long: Long) {
        sendChannel.writeLong(long)
    }

    override suspend fun writeShort(short: Short) {
        sendChannel.writeShort(short)
    }

    override suspend fun readBool(): Boolean {
        val b = readByte()
        return when (b) {
            0.toByte() -> false
            1.toByte() -> true
            else       -> error("what bool is $b???")
        }
    }

    override suspend fun readChar(): Char {
        val buff = ByteBuffer.allocate(2)
        val b1 = readByte()
        val b2 = readByte()
        val ba = byteArrayOf(b1, b2)
        buff.put(ba)
        return buff.asCharBuffer().get()
    }

    override suspend fun readNBytes(n: Int): ByteArray {
        return ByteArray(n) {
            receiveChannel.readByte()
        }
    }

    override suspend fun readDouble(): Double {
        return receiveChannel.readDouble()
    }

    override suspend fun readFloat(): Float {
        return receiveChannel.readFloat()
    }

    override suspend fun readLong(): Long {
        return receiveChannel.readLong()
    }

    override suspend fun readShort(): Short {
        return receiveChannel.readShort()
    }

    override suspend fun readByte(): Byte {
        return receiveChannel.readByte()
    }

    override suspend fun readUByte(): UByte {


        return readByte().toUByte()
    }

    override suspend fun writeByte(byte: Byte) {
        sendChannel.writeByte(byte)
    }


    override suspend fun readLine() = receiveChannel.readUTF8Line(100)
    override suspend fun writeBytes(bytes: ByteArray) {
        bytes.forEach {
            sendChannel.writeByte(it)
        }
    }

    override suspend fun writeLine(s: String) = sendChannel.writeStringUtf8("$s\n")
    override suspend fun readAllBytes(): ByteArray {
        val buffer = Buffer()
        buffer.transferFrom(receiveChannel.toInputStream().asSource())
        val size = buffer.size
        val outputStream = ByteArrayOutputStream(size.requireIsInt())
        val sink = outputStream.asSink()
        buffer.transferTo(sink)
        return outputStream.toByteArray()
    }
}