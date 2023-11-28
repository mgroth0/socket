package matt.socket.lsof.err



class LsofParseException(
    message: String,
    cause: Exception
) : Exception(message, cause)


