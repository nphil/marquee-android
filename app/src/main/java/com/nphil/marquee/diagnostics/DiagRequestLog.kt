package com.nphil.marquee.diagnostics

import okhttp3.HttpUrl
import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Logs each request the app's engine makes to Plex, as `net request`: the method, the address without its
 * query, the status and the milliseconds it took. Successes are Debug, so they show in Detailed; a client error
 * is a Notice, a server error or a failure a Warning or Notice. The ntfy messages carry no Plex headers and are
 * left out, so sending the log never adds to it.
 */
internal class DiagRequestLog(private val nanos: () -> Long = System::nanoTime) : Interceptor {
	override fun intercept(chain: Interceptor.Chain): Response {
		val request = chain.request()
		if (request.header(PLEX_CLIENT) == null || request.url.encodedPath == IDENTITY) return chain.proceed(request)
		val started = nanos()
		try {
			val response = chain.proceed(request)
			Diag.event(
				level(response.code), DiagCategory.Net, "request",
				"method" to request.method,
				"url" to address(request.url),
				"status" to response.code,
				"ms" to millis(started),
				"bytes" to response.header("Content-Length")?.toLongOrNull(),
			)
			return response
		} catch (e: IOException) {
			Diag.event(
				DiagLevel.Notice, DiagCategory.Net, "request",
				"method" to request.method,
				"url" to address(request.url),
				"ms" to millis(started),
				"error" to e,
			)
			throw e
		}
	}

	private fun millis(started: Long) = (nanos() - started) / NANOS_PER_MILLI

	companion object {
		private const val PLEX_CLIENT = "X-Plex-Client-Identifier"
		private const val IDENTITY = "/identity"
		private const val NANOS_PER_MILLI = 1_000_000L

		fun level(status: Int): DiagLevel = when {
			status >= 500 -> DiagLevel.Warning
			status >= 400 -> DiagLevel.Notice
			else -> DiagLevel.Debug
		}

		/** The address without its query or fragment, where the Plex token and search terms are. */
		fun address(url: HttpUrl): String = url.newBuilder().query(null).fragment(null).build().toString()
	}
}
