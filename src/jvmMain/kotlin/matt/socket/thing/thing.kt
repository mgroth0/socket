package matt.socket.thing

import matt.stream.encoding.Encoding

abstract class InterAppThing {
    companion object {
        @JvmStatic protected val ENCODING = Encoding.DEFAULT
    }
}