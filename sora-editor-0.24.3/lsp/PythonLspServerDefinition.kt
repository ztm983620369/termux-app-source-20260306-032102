package io.github.rosemoe.sora.lsp.server

import io.github.rosemoe.sora.lsp.connection.StreamConnectionProvider
import io.github.rosemoe.sora.lsp.connection.InputStream
import io.github.rosemoe.sora.lsp.connection.OutputStream
import io.github.rosemoe.sora.lsp.server.LanguageServerDefinition
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket

class PythonLspStreamConnectionProvider : StreamConnectionProvider {
    private val host: String
    private val port: Int
    
    private var inputStream: InputStream? = null
    private var outputStream: OutputStream? = null
    private var socket: Socket? = null
    
    constructor(host: String, port: Int) {
        this.host = host
        this.port = port
    }
    
    override fun getInputStream(): InputStream {
        return inputStream!!
    }
    
    override fun getOutputStream(): OutputStream {
        return outputStream!!
    }
    
    @Throws(IOException::class)
    override fun connect() {
        socket = Socket()
        socket!!.connect(InetSocketAddress(host, port), 10000)
        inputStream = socket!!.getInputStream()
        outputStream = socket!!.getOutputStream()
    }
    
    @Throws(IOException::class)
    override fun close() {
        outputStream?.close()
        inputStream?.close()
        socket?.close()
    }
}

class PythonLspServerDefinition : LanguageServerDefinition {
    companion object {
        const val SERVER_HOST = "127.0.0.1"
        const val SERVER_PORT = 4389
        const val LANGUAGE_ID = "python"
        const val LANGUAGE_NAME = "Python"
    }
    
    override val languageId: String
        get() = LANGUAGE_ID
    
    override val languageName: String
        get() = LANGUAGE_NAME
    
    override fun languageIdFor(extension: String): String {
        return when (extension.lowercase()) {
            "py" -> LANGUAGE_ID
            "pyw" -> LANGUAGE_ID
            else -> LANGUAGE_ID
        }
    }
    
    override fun start(): Pair<InputStream, OutputStream> {
        val provider = PythonLspStreamConnectionProvider(SERVER_HOST, SERVER_PORT)
        provider.connect()
        return Pair(provider.getInputStream(), provider.getOutputStream())
    }
    
    override fun stop() {
    }
}
