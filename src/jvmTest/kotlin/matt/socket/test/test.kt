package matt.socket.test


import matt.shell.context.DefaultMacExecutionContext
import matt.shell.execReturners
import matt.socket.client.InterAppClient
import matt.socket.lsof.lsof
import matt.socket.port.Port
import matt.test.JupiterTestAssertions.assertRunsInOneMinute
import kotlin.test.Test

class SocketTests() {
    @Test
    fun instantiateClasses() = assertRunsInOneMinute {
        InterAppClient(Port(0))
    }


    @Test
    fun lsof() = assertRunsInOneMinute {
        with(DefaultMacExecutionContext) {
            execReturners.silent.lsof.allPidsUsingAllPorts()
        }
    }
}