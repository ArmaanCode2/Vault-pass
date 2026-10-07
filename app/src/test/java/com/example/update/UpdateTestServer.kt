package com.example.update

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

/** Minimal local HTTP server for updater tests (127.0.0.1, random port). */
class UpdateTestServer : AutoCloseable {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
    private val routes = ConcurrentHashMap<String, (HttpExchange) -> Unit>()
    val requests = CopyOnWriteArrayList<Pair<String, Map<String, String?>>>()

    val base: String get() = "http://127.0.0.1:${server.address.port}"

    init {
        server.createContext("/") { exchange ->
            val path = exchange.requestURI.path
            requests += path to mapOf(
                "User-Agent" to exchange.requestHeaders.getFirst("User-Agent"),
                "Accept" to exchange.requestHeaders.getFirst("Accept")
            )
            val handler = routes[path]
            if (handler == null) {
                exchange.sendResponseHeaders(404, -1)
            } else {
                handler(exchange)
            }
            exchange.close()
        }
        server.start()
    }

    fun body(path: String, bytes: ByteArray, contentLength: Long = bytes.size.toLong()) {
        routes[path] = { exchange ->
            exchange.sendResponseHeaders(200, if (contentLength < 0) 0 else contentLength)
            exchange.responseBody.write(bytes)
        }
    }

    /**
     * Announces [bytes].size, sends the first [firstChunk] bytes, then stalls (the client blocks in read())
     * until [release] is counted down, then sends the rest. Use a separate server for other requests: the
     * server handles one exchange at a time.
     */
    fun slowBody(path: String, bytes: ByteArray, firstChunk: Int, release: CountDownLatch) {
        routes[path] = { exchange ->
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            try {
                exchange.responseBody.write(bytes, 0, firstChunk)
                exchange.responseBody.flush()
                release.await(30, TimeUnit.SECONDS)
                exchange.responseBody.write(bytes, firstChunk, bytes.size - firstChunk)
            } catch (e: IOException) {
                // The client went away.
            }
        }
    }

    fun redirect(path: String, location: String, code: Int = 302) {
        routes[path] = { exchange ->
            exchange.responseHeaders.add("Location", location)
            exchange.sendResponseHeaders(code, -1)
        }
    }

    override fun close() = server.stop(0)
}
