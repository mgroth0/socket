package matt.socket.lsof

import matt.lang.enumValueOfOrNull
import matt.lang.go
import matt.lang.optArray
import matt.prim.str.filterNotBlank
import matt.shell.Shell
import matt.shell.proc.pid.Pid
import matt.socket.lsof.badlsof.NonProgrammaticListOfOpenFilesCommand
import matt.socket.lsof.err.LsofParseException
import matt.socket.lsof.filters.AllLocalHostTCPAddresses
import matt.socket.lsof.filters.LsofFilter
import matt.socket.lsof.filters.PidFilter
import matt.socket.lsof.filters.TcpPort
import matt.socket.lsof.output.ClientSocketFile
import matt.socket.lsof.output.FileNameCommentInternetAddress
import matt.socket.lsof.output.FileSet
import matt.socket.lsof.output.KnownLsofFileDescriptor
import matt.socket.lsof.output.LsofFileDescriptor
import matt.socket.lsof.output.ProcessId
import matt.socket.lsof.output.ProcessSet
import matt.socket.lsof.output.ServerSocketFile
import matt.socket.lsof.output.UnknownLsofFileDescriptor
import matt.socket.port.Port


val Shell<String>.lsof get() = ListOfOpenFilesCommand(this)

/**
 * [Manual](https://linux.die.net/man/8/lsof)
 */
class ListOfOpenFilesCommand(shell: Shell<String>) : NonProgrammaticListOfOpenFilesCommand(shell = shell) {


    fun pidsUsingPort(port: Int) = programmaticList(
        TcpPort(port)
    ).map { Pid(it.pid.value.toLong()) }

    fun allPidsUsingAllPorts() = run {
        try {
            programmaticList(
                AllLocalHostTCPAddresses
            ).flatMap { processSet -> processSet.files.map {processSet.pid to it  } }.groupBy {
                Port(
                    it.second.file!!.serverPort
                )
            }.mapValues { it.value.map { Pid(it.first.value.toLong()) } }
        } catch (e: LsofParseException) {
            println("Doing a full lsof for debugging:\n\n${this()}\n\n")
            throw e
        }
    }

    operator fun invoke() = sendCommand()


    fun openedFilesOf(pid: Pid) = programmaticList(
        PidFilter(pid)
    ).single().files.map { it.file!! }

    private fun programmaticList(
        filter: LsofFilter? = null,
    ): List<ProcessSet> {
        val rawOutput = sendCommand(
            "-n", /*
            * Inhibits network numbers from being converted to names.
            * Supposedly improves performance.
        */
            "-P" /*
            * Inhibits conversion of port numbers to port names for network files.
            * May make lsof run a little faster.
            * It is also useful when port name lookup is not working properly.
        */,
            "-F", /*OUTPUT FOR OTHER PROGRAMS*/
            listOf(
                'n'
            ).joinToString(separator = ""),
            * optArray(filter) { args }
        )

        val unitsOfInformation = rawOutput.lines().filterNotBlank()


        val result = mutableListOf<ProcessSet>()

        val fields = unitsOfInformation.map { info ->
            val fieldIdentifier = info.first()
            val fieldValue = info.substring(1)

            when (fieldIdentifier) {
                'p'  -> ProcessId(fieldValue.toInt())
                'f'  -> enumValueOfOrNull<KnownLsofFileDescriptor>(fieldValue) ?: UnknownLsofFileDescriptor(fieldValue)
                'n'  -> when {
                    "->" in fieldValue -> ClientSocketFile(fieldValue)
                    else               -> ServerSocketFile(fieldValue)
                }

                else -> TODO("lsof field '$fieldIdentifier' is not implemented")
            }
        }

        var currentPid: ProcessId? = null
        var currentFileDescriptor: LsofFileDescriptor? = null
        var currentFileNameCommentInetAddress: FileNameCommentInternetAddress? = null
        val currentFileSets = mutableListOf<FileSet>()

        fun buildFileSetIfPossible() {
            currentFileDescriptor?.go {
                currentFileSets.add(
                    FileSet(
                        fileDescriptor = it,
                        file = currentFileNameCommentInetAddress
                    )
                )
                currentFileDescriptor = null
                currentFileNameCommentInetAddress = null
            }
            check(currentFileNameCommentInetAddress == null)
        }

        fun buildProcessSetIfPossible() {
            currentPid?.go {
                result.add(ProcessSet(it, currentFileSets.toList()))
                currentFileSets.clear()
                currentPid = null
            }
        }

        fields.forEach { field ->
            when (field) {
                is ProcessId                      -> {
                    buildFileSetIfPossible()
                    buildProcessSetIfPossible()
                    currentPid = field
                }

                is LsofFileDescriptor             -> {
                    buildFileSetIfPossible()
                    check(currentFileDescriptor == null)
                    currentFileDescriptor = field
                }

                is FileNameCommentInternetAddress -> {
                    check(currentFileNameCommentInetAddress == null)
                    currentFileNameCommentInetAddress = field
                }
            }
        }
        buildFileSetIfPossible()
        buildProcessSetIfPossible()

        check(currentPid == null)
        check(currentFileDescriptor == null)
        check(currentFileNameCommentInetAddress == null)
        check(currentFileSets.isEmpty())

        return result.toList()
    }
}





