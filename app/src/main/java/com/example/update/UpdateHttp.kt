package com.example.update

import java.io.IOException
import java.net.HttpURLConnection
import java.net.MalformedURLException
import java.net.URL

/**
 * GET with redirects followed by hand, so every hop (GitHub release downloads redirect to another host)
 * is checked against [policy] before anything is sent to it.
 */
internal class UpdateHttp(
    private val policy: UpdateUrlPolicy,
    private val userAgent: String,
    private val timeoutMs: Int = UpdateConfig.TIMEOUT_MS,
    private val maxRedirects: Int = 5
) {
    class Failure(val failure: UpdateFailure, message: String, cause: Throwable? = null) : IOException(message, cause)

    /** An open connection that answered 200. The caller reads it and calls disconnect(). */
    fun get(url: String, accept: String): HttpURLConnection {
        var current = url
        repeat(maxRedirects + 1) {
            val target = policy.check(current)
                ?: throw Failure(UpdateFailure.INSECURE_URL, "Refusing a URL that is not HTTPS: $current")
            val connection = target.openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            connection.connectTimeout = timeoutMs
            connection.readTimeout = timeoutMs
            connection.useCaches = false
            connection.requestMethod = "GET"
            connection.setRequestProperty("User-Agent", userAgent)
            connection.setRequestProperty("Accept", accept)
            val code = try {
                connection.responseCode
            } catch (e: IOException) {
                connection.disconnect()
                throw Failure(UpdateFailure.NETWORK, "Could not reach ${target.host}: ${e.message}", e)
            }
            when (code) {
                HttpURLConnection.HTTP_OK -> return connection
                301, 302, 303, 307, 308 -> {
                    val location = connection.getHeaderField("Location")
                    connection.disconnect()
                    if (location.isNullOrBlank()) {
                        throw Failure(UpdateFailure.BAD_RESPONSE, "Redirect without a Location from ${target.host}")
                    }
                    current = try {
                        URL(target, location).toString()
                    } catch (e: MalformedURLException) {
                        throw Failure(UpdateFailure.BAD_RESPONSE, "Malformed redirect target: $location", e)
                    }
                }
                else -> {
                    connection.disconnect()
                    throw Failure(UpdateFailure.HTTP_STATUS, "HTTP $code from ${target.host}")
                }
            }
        }
        throw Failure(UpdateFailure.TOO_MANY_REDIRECTS, "More than $maxRedirects redirects")
    }
}
