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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
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
import kotlinx.coroutines.delay
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
	internal fun request(context: Context, path: String?, widthPx: Int, heightPx: Int, placeholder: String? = null): ImageRequest? {
		val address = base ?: return null
		val source = path ?: return null
		val url = PlexImageUrl.transcoded(source, address, widthPx, heightPx) ?: return null
		val key = key(source, widthPx, heightPx)
		return ImageRequest.Builder(context)
			.data(url)
			.size(Size(widthPx, heightPx))
			.precision(Precision.INEXACT)
			.httpHeaders(headers)
			.memoryCacheKey(key)
			.diskCacheKey(key)
			.placeholderMemoryCacheKey(placeholder)
			.crossfade(150)
			.build()
	}

	/** The cache entry of [path] asked of the server at [widthPx] by [heightPx]. It leaves out the address, so the same art is not fetched again over a different one. */
	internal fun key(path: String, widthPx: Int, heightPx: Int) = "$scope|$path|${widthPx}x$heightPx"

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
 * Art to ask of the server: [path] drawn [width] by [height], its corners rounded by [radius]. A poster or card hands
 * its own to the page it opens, which draws it from memory until its own art has loaded, so the page's art is never
 * blank and the picture does not change as the page grows from the poster.
 */
@Immutable
data class ArtSpec(val path: String, val width: Dp, val height: Dp, val radius: Dp)

private fun Dp.pixels(density: Float) = ceil(value * density).toInt()

/**
 * A Plex image at [widthDp] by [heightDp], asked of the server at exactly the pixels that size takes once the size
 * has held still ([rememberSettled]). Art that is missing, or not loaded yet, shows the quiet track colour with a
 * film glyph. A [placeholder] is art that is already on screen elsewhere: it is drawn from memory until [path] has
 * loaded, and in place of it while there is no [path] yet.
 */
@Composable
fun PlexArt(
	path: String?,
	widthDp: Dp,
	heightDp: Dp,
	modifier: Modifier = Modifier,
	contentScale: ContentScale = ContentScale.Crop,
	placeholder: ArtSpec? = null,
) {
	val images = LocalPlexImages.current
	val context = LocalContext.current
	val density = LocalDensity.current.density
	val wanted = IntSize(widthDp.pixels(density), heightDp.pixels(density))
	val size = rememberSettled(wanted, path)
	val request = remember(images, path, size, placeholder, density) {
		when {
			path != null -> {
				val shown = placeholder?.let { images?.key(it.path, it.width.pixels(density), it.height.pixels(density)) }
				images?.request(context, path, size.width, size.height, shown)
			}
			placeholder != null -> images?.request(context, placeholder.path, placeholder.width.pixels(density), placeholder.height.pixels(density))
			else -> null
		}
	}
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

/** How long a size must stay put before it counts as the one to ask for. */
private const val SIZE_SETTLE_MILLIS = 200L

/**
 * [value] once it has held still for [SIZE_SETTLE_MILLIS]. A pane that grows or shrinks, as when the sidebar opens,
 * gives every card a new size on every frame, and an image asked for at each is cancelled by the next frame's: in one
 * report from a 120 Hz tablet, 307 requests in two seconds, 244 of them cancelled within a frame, with the art blank
 * meanwhile. The first value, and one that returns to what is shown, apply at once; a different [key], the art's
 * path, starts over.
 */
@Composable
internal fun <T> rememberSettled(value: T, key: Any?): T {
	var settled by remember(key) { mutableStateOf(value) }
	if (settled != value) {
		LaunchedEffect(value) {
			delay(SIZE_SETTLE_MILLIS)
			settled = value
		}
	}
	return settled
}
