package matt.socket.port

import matt.log.tab
import matt.model.code.vals.portreg.PortRegistry
import matt.shell.ShellResultHandler
import matt.shell.ShellVerbosity
import matt.shell.ShellVerbosity.Companion.SILENT
import matt.shell.context.ShellExecutionContext
import matt.shell.execReturners
import matt.shell.proc.Pid
import matt.shell.proc.ProcessKillSignal.SIGKILL
import matt.socket.lsof.lsof
import matt.socket.socket.isOpen
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess


context(ShellExecutionContext)
/*TODO: if it fails because this function was executed in parallel elsewhere and got the same port, make it retry.*/
fun serverSocketWithFirstUnusedPort(): Pair<ServerSocket, Port> {

    /*more performant and stable to do one lsof command than to do one per possible port*/
    val usedPorts = execReturners.silent.lsof.allPidsUsingAllPorts().keys

    val myPort = PortRegistry.unRegisteredPortPool.asSequence().map {
        Port(it)
    }.first {
        it !in usedPorts
    }
    return myPort.serverSocket() to myPort
}

data class Port(val port: Int) {

    companion object {


        context(ShellExecutionContext)
        private fun shell() =
            execReturners.silent.copy(resultHandler = ShellResultHandler(nonZeroOkIf = { it.output.isBlank() }))

        val myPid by lazy { Pid(ProcessHandle.current().pid()) }
    }

    context(ShellExecutionContext)
    fun isUsed() = processes().isNotEmpty()

    context(ShellExecutionContext)
    fun isUnUsed() = !isUsed()

    context(ShellExecutionContext)
    fun processes(verbosity: ShellVerbosity = SILENT) = shell().copy(verbosity = verbosity).lsof.pidsUsingPort(port)

    context(ShellExecutionContext)
    fun killAllProcesses(verbosity: ShellVerbosity = SILENT) {
        val toKill = processes(verbosity = verbosity)
        toKill.forEach {
            println("there is already a process using $this: $it. Killing it.")
            if (it != myPid) {
                it.kill(SIGKILL, doNotThrowIfNoSuchProcess = true)
            } else error("almost killed self")
        }
        if (toKill.isNotEmpty()) Thread.sleep(100) /*from experience, it seems I need to wait a bit for the port to become available*/
    }

    context(ShellExecutionContext)
    fun serverSocket() = try {
        ServerSocket(port)
    } catch (e: BindException) {
        println("")
        println("port was $port")
        print("checking lsof...")
        processes().forEach {
            tab(it)
        }
        e.printStackTrace()
        exitProcess(1)
    }


    private var _clientSocket: Socket? = null

    @Synchronized
    fun clientSocket() = _clientSocket?.takeIf { it.isOpen } ?: Socket("localhost", port).also {
        _clientSocket = it
    }

    @Synchronized
    fun closeClientSocket() {
        _clientSocket?.close()
    }

}
