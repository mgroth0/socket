package matt.socket.lsof.output

import matt.lang.common.substringAfterSingular
import matt.lang.common.substringBeforeSingular
import matt.socket.lsof.err.LsofParseException


private object InternetAddressParser {
    fun parse(raw: String): AddressAndPortLike {
        val inBrackets = raw.startsWith("[")
        val colon: Int
        val rawAddress =
            if (inBrackets) {
                val endBracket = raw.indexOf("]")
                colon = endBracket + 1
                raw.substring(1..<endBracket)
            } else {
                colon = raw.indexOf(":")
                raw.substring(0..<colon)
            }
        val remaining = raw.substring(colon)
        val dash = remaining.indexOf("-")
        val portString =
            if (dash == -1) {
                remaining.substring(1)
            } else {
                remaining.substring(1, dash)
            }
        if (portString == "*") return AddressAndStarPort(address = rawAddress)
        val port = portString.toInt()
        return AddressAndPort(rawAddress, port)
    }
}


@JvmInline
value class ClientSocketFile(override val raw: String) : FileNameCommentInternetAddress {
    val clientHost get() = InternetAddressParser.parse(raw).address
    val clientPort
        get() =
            try {
                val loc = InternetAddressParser.parse(raw.substringBeforeSingular("-"))
                (loc as? AddressAndPort)?.port
            } catch (e: NumberFormatException) {
                throw LsofParseException("Exception parsing ClientSocketFile.clientPort with raw arg: $raw", e)
            }
    override val serverHost get() = InternetAddressParser.parse(raw.substringAfterSingular(">")).address
    override val serverPort
        get() =
            try {
                val loc = InternetAddressParser.parse(raw.substringAfterSingular(">"))
                (loc as? AddressAndPort)?.port
            } catch (e: NumberFormatException) {
                throw LsofParseException("Exception parsing ClientSocketFile.serverPort with raw arg: $raw", e)
            }
}

data class ProcessSet(
    val pid: ProcessId,
    val files: List<FileSet>
)


@JvmInline
value class ServerSocketFile(override val raw: String) : FileNameCommentInternetAddress {
    override val serverHost get() = InternetAddressParser.parse(raw).address
    override val serverPort
        get() =
            try {
                val loc = InternetAddressParser.parse(raw)
                (loc as? AddressAndPort)?.port
            } catch (e: NumberFormatException) {
                throw LsofParseException("Exception parsing ServerSocketFile.serverPort with raw arg: $raw", e)
            }
}


sealed interface OutputField

sealed interface FileNameCommentInternetAddress : OutputField {
    val raw: String
    val serverHost: String
    val serverPort: Int?
}

sealed interface AddressAndPortLike {
    val address: String
}

data class AddressAndStarPort(override val address: String) : AddressAndPortLike
data class AddressAndPort(
    override val address: String,
    val port: Int
) : AddressAndPortLike


sealed interface LsofFileDescriptor : OutputField
data class UnknownLsofFileDescriptor(val fileDescriptor: String) : LsofFileDescriptor
enum class KnownLsofFileDescriptor : LsofFileDescriptor {
    cwd,
    txt,
    mem,
    mmap,
    fd,
    DEL,
    rtd,
    err,
    ltx,
    jld,
    tr,
    vtx,
    Mxx,
    `0` /*standard input*/,
    `1` /*standard output*/,
    `2 ` /*standard error
    3 and above are additional opened files, sockets, pipes, etc.*/
}


class ProcessId(val value: Int) : OutputField


data class FileSet(
    val fileDescriptor: LsofFileDescriptor,
    val file: FileNameCommentInternetAddress? = null
)
