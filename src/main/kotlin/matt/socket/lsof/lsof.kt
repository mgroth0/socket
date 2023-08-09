package matt.socket.lsof

import matt.lang.If
import matt.lang.anno.SeeURL
import matt.lang.go
import matt.lang.optArray
import matt.prim.str.filterNotBlank
import matt.shell.ControlledShellProgram
import matt.shell.Shell
import matt.shell.proc.Pid
import matt.socket.port.Port

val Shell<String>.lsof get() = ListOfOpenFilesCommand(this)

@SeeURL("https://linux.die.net/man/8/lsof")
class ListOfOpenFilesCommand(shell: Shell<String>) : ControlledShellProgram<String>(
    shell = shell, program = "/usr/sbin/lsof"
) {


    fun pidsUsingPort(port: Int) = programmaticList(
        TcpPort(port)
    ).map { Pid(it.pid.toLong()) }

    fun allPidsUsingAllPorts() = run {
        try {
            programmaticList(
                AllLocalHostTCPAddresses
            ).groupBy {
                Port(it.file!!.serverPort)
            }.mapValues { it.value.map { Pid(it.pid.toLong()) } }
        } catch (e: LsofParseException) {
            println("Doing a full lsof for debugging:\n\n${this()}\n\n")
            throw e
        }
    }

    operator fun invoke() = sendCommand()

    private fun programmaticList(
        filter: LsofFilter? = null,

        ) = sendCommand(
        "-n", /*inhibits network numbers from being converted to names. Also supposedly improves performance.*/
        "-P" /*This option inhibits the conversion of port numbers to port names for network files. Inhibiting the conversion may make lsof run a little faster. It is also useful when port name lookup is not working properly.*/,
        "-F",
        listOf(
            'n'
        ).joinToString(separator = ""), * optArray(filter) { args }).lines()
        .filterNotBlank().iterator().run {

            val result = mutableListOf<OpenedFile>()

            var building: OpenedFile? = null
            while (hasNext()) {
                val line = next()
                val value = line.substring(1)
                when (val c = line.first()) {
                    'p' -> {
                        building?.go { result.add(it) }
                        building = OpenedFile(
                            pid = value.toInt()
                        )
                    }

                    'f' -> {
                        building = building!!.copy(fileDescriptor = value)
                    }

                    'n' -> {
                        building = building!!.copy(
                            file = when {
                                "->" in value -> ClientSocketFile(value)
                                else          -> ServerSocketFile(value)
                            }

                        )
                    }

                    else -> TODO("lsof field '$c' is not implemented")
                }
            }

            building?.go { result.add(it) }

            result.toList()
        }


    @Suppress("DeprecatedCallableAddReplaceWith")
    @Deprecated("this output is not intended by lsof for programmatic use, use -F instead")
    private fun list(
        @Suppress("SameParameterValue") terse: Boolean,
        filter: LsofFilter? = null
    ) = sendCommand(*If(terse).then("-t"), * optArray(filter) { args })

    @Suppress("DEPRECATION")
    @Deprecated("this output is not intended by lsof for programmatic use, use -F instead")
    private fun oldWayToGetPidsUsingPort(port: Int) =
        list(terse = true, TcpPort(port)).split("\\s+".toRegex()).filter { it.isNotBlank() }.map { Pid(it.toLong()) }


}

sealed interface FileNameCommentInternetAddress {
    val serverHost: String
    val serverPort: Int
}

@JvmInline
value class ServerSocketFile(val raw: String) : FileNameCommentInternetAddress {
    override val serverHost get() = raw.substringBefore(":")
    override val serverPort
        get() = try {
            raw.substringAfter(":").toInt()
        } catch (e: NumberFormatException) {
            throw LsofParseException("Exception parsing ServerSocketFile.serverPort with raw arg: $raw", e)
        }
}

class LsofParseException(
    message: String,
    cause: Exception
) : Exception(message, cause)

@JvmInline
value class ClientSocketFile(val raw: String) : FileNameCommentInternetAddress {
    val clientHost get() = raw.substringBefore(":")
    val clientPort
        get() = try {
            raw.substringAfter(":").substringBefore("-").toInt()
        } catch (e: NumberFormatException) {
            throw LsofParseException("Exception parsing ClientSocketFile.clientPort with raw arg: $raw", e)
        }
    override val serverHost get() = raw.substringAfter(">").substringBefore(":")
    override val serverPort
        get() = try {
            raw.substringAfter(":").substringAfter(":").toInt()
        } catch (e: NumberFormatException) {
            throw LsofParseException("Exception parsing ClientSocketFile.serverPort with raw arg: $raw", e)
        }
}

data class OpenedFile(
    val pid: Int,
    val fileDescriptor: String? = null,
    val file: FileNameCommentInternetAddress? = null
)

private sealed interface LsofFilter {
    val args: Array<String>
}

data object AllInternetAddresses : LsofFilter {
    override val args = arrayOf("-i")
}

data object AllTCPAddresses : LsofFilter {
    override val args = arrayOf("-iTCP")
}

data object AllLocalHostTCPAddresses : LsofFilter {
    override val args = arrayOf("-iTCP@localhost")
}

sealed class InternetAddress(private val specifier: String) : LsofFilter {
    override val args = arrayOf("-i", specifier)
}

class TcpPort(val port: Int) : InternetAddress(specifier = "tcp:$port")