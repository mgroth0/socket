package matt.socket.ktor

import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.Socket
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.utils.io.core.ByteOrder
import io.ktor.utils.io.jvm.javaio.toInputStream
import io.ktor.utils.io.readDouble
import io.ktor.utils.io.readFloat
import io.ktor.utils.io.readInt
import io.ktor.utils.io.readLong
import io.ktor.utils.io.readShort
import io.ktor.utils.io.writeDouble
import io.ktor.utils.io.writeFloat
import io.ktor.utils.io.writeInt
import io.ktor.utils.io.writeLong
import io.ktor.utils.io.writeShort
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.asSource
import matt.lang.LOCALHOST
import matt.lang.safeconvert.verifyToInt
import matt.socket.endian.myByteOrder
import matt.socket.port.Port
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

typealias ConnectionOp<R> = suspend KtorSocketConnection.() -> R

abstract class SocketScope : CoroutineScope {
    abstract val manager: SelectorManager /*should be abstract protected!!!!!*/
    final suspend fun <R> server(
        port: Port,
        op: suspend KtorServerSocket.() -> R
    ): R {
        val serverSocket = with(manager) { KtorServerSocketImpl(port) }
        return try {
            serverSocket.run {
                op()
            }
        } finally {
            serverSocket.close()
        }
    }


    final suspend fun <R> client(
        port: Port,
        op: ConnectionOp<R>
    ): R {
        val serverSocket = with(manager) { KtorServerSocketTargetImpl(port) }
        return serverSocket.connect(op)
    }
}


suspend fun <R> useSockets(op: suspend SocketScope.() -> R): R {
    val manager = SelectorManager(coroutineContext)
    val rr = manager.use {
        val socketScope = SocketScopeImpl(manager)
        val r = socketScope.op()
        r
    }
    return rr
}

private class SocketScopeImpl(override val manager: SelectorManager) : SocketScope() {
    override val coroutineContext = manager.coroutineContext
}

context(SelectorManager)
sealed class KtorSocket {
//    companion object {
//        @JvmStatic
//        protected val SELECTOR_MANAGER by lazy {
//            /*WARNING: I used to close this after use. Does it need to be closed? Or does it use daemon threads?*/
//            SelectorManager(Dispatchers.IO)
//        }
//    }

    protected val socketDefinition = aSocket(this@SelectorManager).tcp()

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

context(SelectorManager)
private class KtorServerSocketTargetImpl(private val port: Port) : KtorSocket(), KtorServerSocketTarget {
    override suspend fun <R> connect(op: ConnectionOp<R>): R {
        val rawConnection = socketDefinition.connect(LOCALHOST, port.port)
        return handleConnection(rawConnection, op)
    }
}

interface KtorServerSocket {
    suspend fun <R> accept(
        timeout: Duration = Duration.INFINITE,
        op: ConnectionOp<R>
    ): R
}

context(SelectorManager)
private class KtorServerSocketImpl(port: Port) : KtorSocket(), KtorServerSocket {
    private val socket = try {
        socketDefinition.bind(LOCALHOST, port.port)
    } catch (e: java.net.BindException) {
        println("Got BindException when trying to bind server socket to port ${port.port}")
        throw e
    }

    override suspend fun <R> accept(
        timeout: Duration,
        op: ConnectionOp<R>
    ): R {
        val rawConnection = withTimeout(timeout) {
            socket.accept()
        }
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
data object MoreToRead : ReadChannelStatus
data object Closed : ReadChannelStatus

val LOCAL_SOCKET_BYTE_ORDER = ByteOrder.nativeOrder()
val MY_LOCAL_SOCKET_BYTE_ORDER = LOCAL_SOCKET_BYTE_ORDER.myByteOrder

private class KtorSocketConnectionImpl internal constructor(socket: Socket) : KtorSocketConnection {


    companion object {
        /*Since I implement sockets for inter-app communications, I should use the OS ByteOrder. I think the default behavior for ktor sockets is network (big endian) whereas Mac aarch64 is little endian, and thats what java sockets used I think (java sockets used native)*/

    }

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
        return receiveChannel.readInt(LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun writeBool(bool: Boolean) {
        if (bool) writeByte(1.toByte())
        else writeByte(0.toByte())
    }

    override suspend fun writeDouble(double: Double) {
        sendChannel.writeDouble(double, LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun writeFloat(float: Float) {
        sendChannel.writeFloat(float, LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun writeInt(int: Int) {
        sendChannel.writeInt(int, LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun writeLong(long: Long) {
        println("writing long from java: $long")
        sendChannel.writeLong(long, LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun writeShort(short: Short) {
        sendChannel.writeShort(short, LOCAL_SOCKET_BYTE_ORDER)
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
        return receiveChannel.readDouble(LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun readFloat(): Float {
        return receiveChannel.readFloat(LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun readLong(): Long {
        return receiveChannel.readLong(LOCAL_SOCKET_BYTE_ORDER)
    }

    override suspend fun readShort(): Short {
        return receiveChannel.readShort(LOCAL_SOCKET_BYTE_ORDER)
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
        val outputStream = ByteArrayOutputStream(size.verifyToInt())
        val sink = outputStream.asSink()
        buffer.transferTo(sink)
        return outputStream.toByteArray()
    }
}