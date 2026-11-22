package com.nphil.marquee.ui.components

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import coil3.network.NetworkHeaders
import coil3.network.httpHeaders
import coil3.request.ImageRequest
import coil3.request.crossfade
import coil3.size.Precision
import coil3.size.Size
import com.nphil.marquee.AppContainer
import com.nphil.marquee.R
import com.nphil.marquee.core.ClientIdentity
import com.nphil.marquee.core.PlexServer
import com.nphil.marquee.ui.theme.lucent
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import kotlin.math.ceil

/**
 * Where a server's artwork is fetched from and how this tablet identifies itself to it. The token and the
 * client identity travel as headers, never in the URL, so a URL (and the cache entry it names) outlives a
 * rotated token.
 */
@Immutable
class PlexImages(
	/** The server's address in use, or null until the first connection has found it. */
	val base: String?,
	val headers: NetworkHeaders,
	/** The server's identifier: part of every cache key, so two servers never share an entry. */
	val scope: String,
) {
	internal fun request(context: Context, path: String?, widthPx: Int, heightPx: Int): ImageRequest? {
		val address = base ?: return null
		val source = path ?: return null
		val url = PlexImageUrl.transcoded(source, address, widthPx, heightPx) ?: return null
		// The key leaves out the address, so the same art is not fetched again over a different one.
		val key = "$scope|$source|${widthPx}x$heightPx"
		return ImageRequest.Builder(context)
			.data(url)
			.size(Size(widthPx, heightPx))
			.precision(Precision.INEXACT)
			.httpHeaders(headers)
			.memoryCacheKey(key)
			.diskCacheKey(key)
			.crossfade(150)
			.build()
	}

	companion object {
		fun of(server: PlexServer, identity: ClientIdentity, address: String?): PlexImages {
			val headers = NetworkHeaders.Builder().apply {
				set("X-Plex-Client-Identifier", identity.clientIdentifier)
				set("X-Plex-Product", identity.product)
				set("X-Plex-Version", identity.version)
				set("X-Plex-Platform", identity.platform)
				set("X-Plex-Platform-Version", identity.platformVersion)
				set("X-Plex-Device", identity.device)
				set("X-Plex-Device-Name", identity.deviceName)
				server.accessToken?.takeIf { it.isNotEmpty() }?.let { set("X-Plex-Token", it) }
			}.build()
			return PlexImages(address, headers, server.id)
		}
	}
}

/** The signed-in server's artwork source, set once above every screen; null before sign-in. */
val LocalPlexImages = staticCompositionLocalOf<PlexImages?> { null }

/** Gives every screen below it the signed-in server's artwork source. */
@Composable
fun ProvidePlexImages(container: AppContainer, content: @Composable () -> Unit) {
	val state = container.session.state.collectAsStateWithLifecycle()
	val server by remember { derivedStateOf { state.value.server } }
	val address by remember { derivedStateOf { state.value.server?.let { state.value.addresses[it.id] } } }
	val identity by produceState<ClientIdentity?>(null, container) { value = container.clientIdentity() }
	val images = remember(server, address, identity) {
		val current = server
		val me = identity
		if (current != null && me != null) PlexImages.of(current, me, address) else null
	}
	CompositionLocalProvider(LocalPlexImages provides images, content = content)
}

object PlexImageUrl {
	/**
	 * A copy of a Plex image resized by the server to the pixels a view needs, through `/photo/:/transcode`
	 * (Core's `PlexImageURL.transcoded`). [path] is an artwork path (`/library/metadata/12/thumb/1699`) or an
	 * absolute URL such as a cast photo.
	 */
	fun transcoded(path: String, base: String, widthPx: Int, heightPx: Int): String? {
		if (path.isEmpty() || widthPx <= 0 || heightPx <= 0) return null
		val root = base.toHttpUrlOrNull() ?: return null
		return root.newBuilder()
			.encodedPath("/photo/:/transcode")
			.addQueryParameter("width", widthPx.toString())
			.addQueryParameter("height", heightPx.toString())
			.addQueryParameter("minSize", "1")
			.addQueryParameter("upscale", "1")
			.addQueryParameter("url", path)
			.build()
			.toString()
	}
}

/**
 * A Plex image at [widthDp] by [heightDp], asked of the server at exactly the pixels that size takes. Art
 * that is missing, or not loaded yet, shows the quiet track colour with a film glyph.
 */
@Composable
fun PlexArt(
	path: String?,
	widthDp: Dp,
	heightDp: Dp,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
) {
	val images = LocalPlexImages.current
	val context = LocalContext.current
	val density = LocalDensity.current.density
	val widthPx = ceil(widthDp.value * density).toInt()
	val heightPx = ceil(heightDp.value * density).toInt()
	val request = remember(images, path, widthPx, heightPx) { images?.request(context, path, widthPx, heightPx) }
	Box(
		modifier = modifier.size(widthDp, heightDp).background(lucent.trackOff),
		contentAlignment = Alignment.Center,
	) {
		if (request == null) {
			if (minOf(widthDp, heightDp) >= 48.dp) {
				Icon(painterResource(R.drawable.ic_movie), contentDescription = null, tint = lucent.ink3, modifier = Modifier.size(24.dp))
			}
		} else {
			AsyncImage(
				model = request,
				contentDescription = null,
				modifier = Modifier.fillMaxSize(),
				contentScale = contentScale,
			)
		}
	}
}
