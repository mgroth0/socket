package matt.socket.socket

import java.io.OutputStream
import java.io.PrintStream
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import kotlin.time.Duration

val Socket.isOpen get() = !isClosed


fun ServerSocket.acceptOrTimeout(): Socket? =
    try {
        accept()
    } catch (e: SocketTimeoutException) {
        null
    }

fun ServerSocket.acceptWithTimeout(timeout: Duration): Socket? {
    soTimeout =
        if (timeout == Duration.INFINITE) {
            0
        } else {
            timeout.inWholeMilliseconds.toInt()
        }

    return acceptOrTimeout()
}

fun OutputStream.printStream(autoFlush: Boolean = true) = PrintStream(this, autoFlush)
