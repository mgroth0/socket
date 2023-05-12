package matt.socket.port

import matt.log.tab
import matt.model.code.valjson.ValJson
import matt.shell.ExecReturner
import matt.shell.ShellResultHandler
import matt.shell.ShellVerbosity
import matt.shell.ShellVerbosity.Companion.SILENT
import matt.shell.proc.Pid
import matt.shell.proc.ProcessKillSignal.SIGKILL
import matt.socket.lsof.lsof
import matt.socket.socket.isOpen
import java.net.BindException
import java.net.ServerSocket
import java.net.Socket
import kotlin.system.exitProcess

data class Port(val port: Int) {

    companion object {
        private val SHELL by lazy {
            ExecReturner.SILENT.copy(resultHandler = ShellResultHandler(nonZeroOkIf = { it.output.isBlank() }))
        }
        val myPid by lazy { Pid(ProcessHandle.current().pid()) }
    }

    constructor(name: String) : this(ValJson.Port[name]!!)

    fun isUsed() = processes().isNotEmpty()
    fun isUnUsed() = !isUsed()
    fun processes(verbosity: ShellVerbosity = SILENT) = SHELL.copy(verbosity = verbosity).lsof(port)
        .split("\\s+".toRegex())
        .filter { it.isNotBlank() }
        .map { Pid(it.toLong()) }

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
    fun clientSocket() = _clientSocket
        ?.takeIf { it.isOpen }
        ?: Socket("localhost", port).also {
            _clientSocket = it
        }

    @Synchronized
    fun closeClientSocket() {
        _clientSocket?.close()
    }

}
