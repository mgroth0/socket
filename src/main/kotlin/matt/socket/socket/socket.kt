package matt.socket.socket

import java.net.Socket

val Socket.isOpen get() = !isClosed