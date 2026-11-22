package com.nphil.marquee.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private const val PlexToken = "X-Plex-Token"

internal fun hasPlexCredential(headers: Map<String, String>, url: HttpUrl): Boolean =
	headers.keys.any { it.equals(PlexToken, ignoreCase = true) } ||
		(0 until url.querySize).any { url.queryParameterName(it).equals(PlexToken, ignoreCase = true) }

/**
 * MarqueeCore's `PlexTransport` on OkHttp: the bridge hands over a request as JSON plus its body, and
 * the answer, whatever its status, goes back through [sink]. Mirrors Core's `URLSessionTransport`:
 * the request's timeout is an idle timeout, and a request restricted to its origin follows only
 * same-origin redirects, sends no cookies and turns into a bodiless GET where the redirect says so.
 */
class NativeTransport(private val client: OkHttpClient, private val sink: Sink = NativeSink) {
	/** Where answers go; the bridge in the app, a recorder in tests. */
	interface Sink {
		fun response(id: Long, status: Int, headers: ByteArray, body: ByteArray)
		fun failure(id: Long, code: Int)
	}

	private val calls = ConcurrentHashMap<Long, Call>()

	fun send(id: Long, request: ByteArray, body: ByteArray?) {
		val wire = runCatching { json.decodeFromString<WireRequest>(request.decodeToString()) }.getOrNull()
		val url = wire?.url?.toHttpUrlOrNull()
		if (wire == null || url == null) {
			sink.failure(id, OTHER)
			return
		}
		val timeout = wire.timeoutMillis.coerceAtLeast(1)
		val method = wire.method.uppercase()
		val read = method == "GET" || method == "HEAD"
		val repeatable = wire.isIdempotent ?: read
		// OkHttp strips Authorization across origins, but not Plex's custom token header.
		val restricted = wire.restrictToOrigin || hasPlexCredential(wire.headers, url)
		val requestClient = client.newBuilder()
			.connectTimeout(timeout, TimeUnit.MILLISECONDS)
			.readTimeout(timeout, TimeUnit.MILLISECONDS)
			.writeTimeout(timeout, TimeUnit.MILLISECONDS)
			.retryOnConnectionFailure(repeatable)
			.followRedirects(!restricted)
			.followSslRedirects(!restricted)
			.build()
		val headers = Headers.Builder().apply {
			// Device names such as "Nitin’s Pad" go out as they are, as URLSession sends them.
			wire.headers.forEach { (name, value) -> addUnsafeNonAscii(name, value) }
		}.build()
		val requestBody = when {
			read -> null
			body != null -> body.toRequestBody()
			// OkHttp needs a body for these; URLSession sends them empty.
			method == "POST" || method == "PUT" || method == "PATCH" -> ByteArray(0).toRequestBody()
			else -> null
		}
		// Even an empty POST can have been accepted before an error. OkHttp must not replay it.
		val singleSend = if (!repeatable && !read) {
			SingleSendBody(requestBody ?: ByteArray(0).toRequestBody())
		} else requestBody
		val first = Request.Builder().url(url).headers(headers).method(method, singleSend).build()
		execute(id, requestClient, first, if (restricted) url else null, redirects = 0)
	}

	/**
	 * Cancels the request with [id]. A cancel that overtakes its own [send] finds nothing, and that request
	 * runs to its end unheard: the bridge has already answered its caller.
	 */
	fun cancel(id: Long) {
		calls.remove(id)?.cancel()
	}

	/** Runs [request]; with an [origin], same-origin redirects are followed here, up to 20. */
	private fun execute(id: Long, client: OkHttpClient, request: Request, origin: HttpUrl?, redirects: Int) {
		val call = client.newCall(request)
		calls[id] = call
		call.enqueue(object : Callback {
			override fun onFailure(call: Call, e: IOException) {
				if (calls.remove(id, call)) sink.failure(id, failureCode(e))
			}

			override fun onResponse(call: Call, response: Response) {
				response.use {
					val next = origin?.let { redirect(response, it) }
					if (next != null) {
						if (redirects == MAX_REDIRECTS) {
							if (calls.remove(id, call)) sink.failure(id, OTHER)
						} else if (calls[id] === call) {
							execute(id, client, next, origin, redirects + 1)
						}
						return
					}
					val body = try {
						response.body?.bytes() ?: ByteArray(0)
					} catch (e: IOException) {
						if (calls.remove(id, call)) sink.failure(id, failureCode(e))
						return
					}
					if (calls.remove(id, call)) sink.response(id, response.code, headerJson(response.headers), body)
				}
			}
		})
	}

	/** The request a same-origin redirect leads to, or null when the response is the answer. */
	private fun redirect(response: Response, origin: HttpUrl): Request? {
		val code = response.code
		if (code !in REDIRECTS) return null
		val target = response.header("Location")?.let { response.request.url.resolve(it) } ?: return null
		if (target.scheme != origin.scheme || !target.host.equals(origin.host, ignoreCase = true) || target.port != origin.port) {
			return null
		}
		val request = response.request
		val toGet = code == 303 || ((code == 301 || code == 302) && request.method == "POST")
		if (!toGet && request.body?.isOneShot() == true) return null
		return request.newBuilder().url(target).apply {
			if (toGet) {
				method("GET", null)
				removeHeader("Content-Type")
				removeHeader("Content-Length")
			}
		}.build()
	}

	private fun headerJson(headers: Headers): ByteArray = buildJsonObject {
		for (name in headers.names()) put(name, headers.values(name).joinToString(", "))
	}.toString().encodeToByteArray()

	private class SingleSendBody(private val body: RequestBody) : RequestBody() {
		override fun contentType() = body.contentType()
		override fun contentLength() = body.contentLength()
		override fun isOneShot() = true
		override fun writeTo(sink: BufferedSink) = body.writeTo(sink)
	}

	@Serializable
	private class WireRequest(
		val method: String,
		val url: String,
		val headers: Map<String, String> = emptyMap(),
		val timeoutMillis: Long,
		val isIdempotent: Boolean? = null,
		val restrictToOrigin: Boolean = false,
	)

	private object NativeSink : Sink {
		override fun response(id: Long, status: Int, headers: ByteArray, body: ByteArray) =
			NativeCore.transportResponse(id, status, headers, body)

		override fun failure(id: Long, code: Int) = NativeCore.transportFailure(id, code)
	}

	companion object {
		// The codes OkHttpTransport.swift turns back into NetworkFailure.
		const val OTHER = 0
		const val TIMED_OUT = 1
		const val CONNECTION_LOST = 2
		const val CANNOT_CONNECT = 3
		const val HOST_NOT_FOUND = 4
		const val SECURE_CONNECTION_FAILED = 6

		private const val MAX_REDIRECTS = 20
		private val REDIRECTS = setOf(301, 302, 303, 307, 308)
		private val json = Json { ignoreUnknownKeys = true }

		fun failureCode(error: IOException): Int = when (error) {
			is SocketTimeoutException -> TIMED_OUT
			is UnknownHostException -> HOST_NOT_FOUND
			is ConnectException, is NoRouteToHostException -> CANNOT_CONNECT
			is SSLException -> SECURE_CONNECTION_FAILED
			is EOFException, is SocketException -> CONNECTION_LOST
			else -> OTHER
		}
	}
}
