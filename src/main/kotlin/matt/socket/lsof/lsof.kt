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

    fun allPidsUsingAllPorts() = programmaticList(
        AllLocalHostTCPAddresses
    ).groupBy {
        Port(it.file!!.serverPort)
    }.mapValues { it.value.map { Pid(it.pid.toLong()) } }


    private fun programmaticList(
        filter: LsofFilter? = null
    ) = sendCommand("-F", listOf('n').joinToString(separator = ""), * optArray(filter) { args }).lines()
        .filterNotBlank().iterator().run {

            val result = mutableListOf<OpenedFile>()

            var building: OpenedFile? = null
            while (hasNext()) {
                val line = next()
                val value = line.substring(1)
                when (val c = line.first()) {
                    'p'  -> {
                        building?.go { result.add(it) }
                        building = OpenedFile(
                            pid = value.toInt()
                        )
                    }

                    'f'  -> {
                        building = building!!.copy(fileDescriptor = value)
                    }

                    'n'  -> {
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
    override val serverPort get() = raw.substringAfter(":").toInt()
}

@JvmInline
value class ClientSocketFile(val raw: String) : FileNameCommentInternetAddress {
    val clientHost get() = raw.substringBefore(":")
    val clientPort get() = raw.substringAfter(":").substringBefore("-").toInt()
    override val serverHost get() = raw.substringAfter(">").substringBefore(":")
    override val serverPort get() = raw.substringAfter(":").substringAfter(":").toInt()
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