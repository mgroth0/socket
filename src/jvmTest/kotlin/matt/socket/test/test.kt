package matt.socket.test


import matt.shell.context.DefaultMacExecutionContext
import matt.shell.context.withReaper
import matt.shell.execReturners
import matt.socket.client.InterAppClient
import matt.socket.lsof.lsof
import matt.socket.port.Port
import matt.test.Tests
import kotlin.test.Test

class SocketTests(): Tests() {
    @Test
    fun instantiateClasses() = assertRunsInOneMinute {
        InterAppClient(Port(0))
    }


    @Test
    fun lsof() = assertRunsInOneMinute {
        with(DefaultMacExecutionContext.withReaper(this)) {
            execReturners.silent.lsof.allPidsUsingAllPorts()
        }
    }
}
