package matt.socket.endian

import io.ktor.utils.io.core.ByteOrder.*
import matt.lang.NEVER
import matt.prim.endian.MyByteOrder
import matt.prim.endian.MyByteOrder.BIG
import matt.prim.endian.MyByteOrder.LITTLE


val MyByteOrder.ktor
    get() = when (this) {
        BIG    -> BIG_ENDIAN
        LITTLE -> LITTLE_ENDIAN
    }

val io.ktor.utils.io.core.ByteOrder.myByteOrder
    get() = when (this) {
        BIG_ENDIAN    -> BIG
        LITTLE_ENDIAN -> LITTLE
        else          -> NEVER
    }