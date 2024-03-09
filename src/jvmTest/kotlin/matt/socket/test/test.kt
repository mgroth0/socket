package matt.socket.test


import matt.shell.common.context.DefaultMacExecutionContext
import matt.shell.commonj.context.withReaper
import matt.shell.execReturners
import matt.socket.client.InterAppClient
import matt.socket.client.InterAppClientConfig
import matt.socket.lsof.lsof
import matt.socket.port.Port
import matt.test.Tests
import matt.test.jcommon.mock.co.MockCoroutineScope
import kotlin.test.Test

class SocketTests(): Tests() {
    @Test
    fun instantiateClasses() {
        InterAppClient(Port(0), MockCoroutineScope, InterAppClientConfig.DEFAULT)
    }


    @Test
    fun lsof() =
        assertRunsInOneMinute {
            with(DefaultMacExecutionContext.withReaper(this)) {
                execReturners.silent.lsof.allPidsUsingAllPorts()
            }
        }
}
