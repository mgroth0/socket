package matt.socket.test


import matt.socket.client.InterAppClient
import matt.socket.port.Port
import matt.test.JupiterTestAssertions.assertRunsInOneMinute
import kotlin.test.Test

class SocketTests() {
    @Test
    fun instantiateClasses() = assertRunsInOneMinute {
        InterAppClient(Port(0))
    }
}