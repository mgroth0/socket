package matt.socket.lsof.filters

import matt.shell.proc.pid.Pid


internal sealed interface LsofFilter {
    val args: Array<String>
}

data object AllInternetAddresses : LsofFilter {
    override val args = arrayOf("-i")
}

data object AllTCPAddresses : LsofFilter {
    override val args = arrayOf("-iTCP")
}

@Deprecated("\"the @localhost part is causing lines like \\\"n*:65004\\\" not to show. Apparently the asterisk is used to signal zero ports, which are aparently excluded when localhost is specified, even though my regular apps like Launch are getting ports like this\". Replace with AllTCPAddresses")
data object AllLocalHostTCPAddresses : LsofFilter {
    override val args = arrayOf("-iTCP@localhost")
}

sealed class InternetAddress(private val specifier: String) : LsofFilter {
    override val args = arrayOf("-i", specifier)
}

class TcpPort(val port: Int) : InternetAddress(specifier = "tcp:$port")

class PidFilter(private val pid: Pid) : LsofFilter {
    override val args = arrayOf("-p", pid.id.toString())
}


