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
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.io.Buffer
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.bytestring.ByteString
import matt.async.co.withThrowingTimeout
import matt.lang.common.LOCALHOST
import matt.lang.safeconvert.verifyToInt
import matt.prim.bytestr.byteString
import matt.prim.bytestr.toByteString
import matt.socket.endian.myByteOrder
import matt.socket.port.Port
import matt.stream.suspendchannels.AwaitedReadChannelStatus
import matt.stream.suspendchannels.BytesAvailable
import matt.stream.suspendchannels.ClosedAndNoMoreToRead
import matt.stream.suspendchannels.OpenButNoBytesAvailable
import matt.stream.suspendchannels.ReadChannelStatus
import matt.stream.suspendchannels.SuspendingReadChannel
import matt.stream.suspendchannels.SuspendingWriteChannel
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.time.Duration

typealias ConnectionReturningOp<R> = suspend KtorSocketConnection.() -> R
typealias ConnectionOp = suspend KtorSocketConnection.() -> Unit


abstract class SocketScope : CoroutineScope {
    abstract val manager: SelectorManager /*should be abstract protected!!!!!*/
    suspend fun <R> server(
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


    suspend fun <R> client(
        port: Port,
        op: ConnectionReturningOp<R>
    ): R {
        val serverSocket = with(manager) { KtorServerSocketTargetImpl(port) }
        return serverSocket.connect(op)
    }
}


suspend fun <R> useSockets(op: suspend SocketScope.() -> R): R {
    val manager = SelectorManager(coroutineContext)
    val rr =
        manager.use {
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

    protected val socketDefinition = aSocket(this@SelectorManager).tcp()

    protected suspend fun <R> handleReturningConnection(
        rawConnection: Socket,
        op: ConnectionReturningOp<R>
    ): R =
        try {
            val connection = KtorSocketConnectionImpl(rawConnection)
            connection.op()
        } finally {
            @Suppress("BlockingMethodInNonBlockingContext")
            /*
            Whoever wrote this warning seems to want me to put this close call inside a `withContext(IO){}` block.
            However, this would be a terrible idea here. The whole point of this is that it runs even if the coroutine is cancelled.
            If the coroutine is cancelled and this close call is located inside a withContext block, I would have much less of a strong guarantee that the socket would be closed. After all, if the coroutine is cancelled why would it it execute something inside of a withContext block, which basically launches a new coroutine.


            ... the moment I removed the withContext block, this finally worked.
             */
            rawConnection.close()
        }
    protected suspend fun handleConnection(
        rawConnection: Socket,
        op: ConnectionOp
    ) =
        try {
            val connection = KtorSocketConnectionImpl(rawConnection)
            connection.op()
        } finally {
            @Suppress("BlockingMethodInNonBlockingContext")
            /*
         Whoever wrote this warning seems to want me to put this close call inside a `withContext(IO){}` block.
         However, this would be a terrible idea here. The whole point of this is that it runs even if the coroutine is cancelled.
         If the coroutine is cancelled and this close call is located inside a withContext block, I would have much less of a strong guarantee that the socket would be closed. After all, if the coroutine is cancelled why would it it execute something inside of a withContext block, which basically launches a new coroutine.


         ... the moment I removed the withContext block, this finally worked.
             */
            rawConnection.close()
        }
}

interface KtorServerSocketTarget {
    suspend fun <R> connect(op: ConnectionReturningOp<R>): R
}

context(SelectorManager)
private class KtorServerSocketTargetImpl(private val port: Port) : KtorSocket(), KtorServerSocketTarget {
    override suspend fun <R> connect(op: ConnectionReturningOp<R>): R {
        val rawConnection = socketDefinition.connect(LOCALHOST, port.port)
        return handleReturningConnection(rawConnection, op)
    }
}

interface KtorServerSocket {
    suspend fun <R> accept(
        timeout: Duration = Duration.INFINITE,
        op: ConnectionReturningOp<R>
    ): R
    suspend fun <R: Any> acceptOrNull(
        timeout: Duration = Duration.INFINITE,
        op: ConnectionReturningOp<R>
    ): R?
    suspend fun asyncClient(op: ConnectionOp): Job
}

context(SelectorManager)
private class KtorServerSocketImpl(port: Port) : KtorSocket(), KtorServerSocket {
    private val socket =
        try {
            socketDefinition.bind(LOCALHOST, port.port)
        } catch (e: java.net.BindException) {
            println("Got BindException when trying to bind server socket to port ${port.port}")
            throw e
        }

    override suspend fun <R> accept(
        timeout: Duration,
        op: ConnectionReturningOp<R>
    ): R {
        val rawConnection =
            withThrowingTimeout(timeout) {
                socket.accept()
            }

        return handleReturningConnection(rawConnection, op)
    }
    override suspend fun <R: Any> acceptOrNull(
        timeout: Duration,
        op: ConnectionReturningOp<R>
    ): R? {
        val rawConnection =
            withTimeoutOrNull(timeout) {
                socket.accept()
            }
        return rawConnection?.let {
            handleReturningConnection(it, op)
        }
    }


    override suspend fun asyncClient(op: ConnectionOp): Job {
        val rawConnection = socket.accept()
        return launch {
            handleConnection(rawConnection, op)
        }
    }


    fun close() {
        socket.close()
    }
}

interface KtorSocketConnection: SuspendingReadChannel, SuspendingWriteChannel





val LOCAL_SOCKET_BYTE_ORDER = ByteOrder.nativeOrder()
val MY_LOCAL_SOCKET_BYTE_ORDER = LOCAL_SOCKET_BYTE_ORDER.myByteOrder

private class KtorSocketConnectionImpl internal constructor(socket: Socket) : KtorSocketConnection {


    companion object {
        /*Since I implement sockets for inter-app communications, I should use the OS ByteOrder. I think the default behavior for ktor sockets is network (big endian) whereas Mac aarch64 is little endian, and thats what java sockets used I think (java sockets used native)*/
    }

    private val receiveChannel = socket.openReadChannel()
    private val sendChannel = socket.openWriteChannel(autoFlush = true)

    override suspend fun checkNow(): ReadChannelStatus {
        if (receiveChannel.isClosedForRead) {
            return ClosedAndNoMoreToRead
        } else if (receiveChannel.availableForRead > 0) {
            return BytesAvailable
        } else {
            return OpenButNoBytesAvailable
        }
    }

    override suspend fun awaitBytesOrCloseAndCheck(): AwaitedReadChannelStatus {
        receiveChannel.awaitContent()
        if (receiveChannel.isClosedForRead) {
            return ClosedAndNoMoreToRead
        }
        return BytesAvailable
    }

    override suspend fun readInt(): Int = receiveChannel.readInt(LOCAL_SOCKET_BYTE_ORDER)

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

    override suspend fun readNBytes(n: Int): ByteString =
        byteString(n) {
            receiveChannel.readByte()
        }

    override suspend fun readDouble(): Double = receiveChannel.readDouble(LOCAL_SOCKET_BYTE_ORDER)

    override suspend fun readFloat(): Float = receiveChannel.readFloat(LOCAL_SOCKET_BYTE_ORDER)

    override suspend fun readLong(): Long = receiveChannel.readLong(LOCAL_SOCKET_BYTE_ORDER)

    override suspend fun readShort(): Short = receiveChannel.readShort(LOCAL_SOCKET_BYTE_ORDER)

    override suspend fun readByte(): Byte = receiveChannel.readByte()

    override suspend fun readUByte(): UByte = readByte().toUByte()

    override suspend fun writeByte(byte: Byte) {
        sendChannel.writeByte(byte)
    }


    override suspend fun readLine() = receiveChannel.readUTF8Line(100)
    override suspend fun writeBytes(bytes: ByteArray) {
        sendChannel.writeFully(bytes, 0, bytes.size)
    }

    override suspend fun writeLine(s: String) = sendChannel.writeStringUtf8("$s\n")
    override suspend fun readAllBytes(): ByteString {
        val buffer = Buffer()
        buffer.transferFrom(receiveChannel.toInputStream().asSource())
        val size = buffer.size
        val outputStream = ByteArrayOutputStream(size.verifyToInt())
        val sink = outputStream.asSink()
        buffer.transferTo(sink)
        return outputStream.toByteArray().toByteString()
    }
}
