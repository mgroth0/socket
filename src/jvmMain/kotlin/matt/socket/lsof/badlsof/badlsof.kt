package matt.socket.lsof.badlsof

import matt.lang.If
import matt.lang.anno.SeeURL
import matt.lang.optArray
import matt.shell.ControlledShellProgram
import matt.shell.Shell
import matt.shell.proc.pid.Pid
import matt.socket.lsof.filters.LsofFilter
import matt.socket.lsof.filters.TcpPort

@SeeURL("https://linux.die.net/man/8/lsof")
abstract class NonProgrammaticListOfOpenFilesCommand(shell: Shell<String>) : ControlledShellProgram<String>(
    shell = shell, program = "/usr/sbin/lsof"
) {
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
