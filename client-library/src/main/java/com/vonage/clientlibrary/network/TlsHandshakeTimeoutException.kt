package com.vonage.clientlibrary.network

import java.io.IOException

/**
 * Thrown internally when DNS resolution + TCP connect + TLS handshake do not complete within the
 * configured handshake timeout. [ClientSocket.open] converts it into an
 * `sdk_tls_handshake_timeout` error response; it never reaches SDK callers.
 */
internal class TlsHandshakeTimeoutException(message: String, cause: Throwable? = null) :
    IOException(message, cause)
