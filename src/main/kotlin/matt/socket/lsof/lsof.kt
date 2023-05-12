package matt.socket.lsof

import matt.shell.Shell

fun <R> Shell<R>.lsof(port: Int) = sendCommand(
    "/usr/sbin/lsof",
    "-t",
    "-i",
    "tcp:$port"
)

