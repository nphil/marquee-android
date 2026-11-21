package com.nphil.marquee.diagnostics

import android.app.Application
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.nphil.marquee.BuildConfig
import com.nphil.marquee.core.CoreException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.TimeZone
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The app's diagnostic log (DESIGN §8.12). A call site says what happened, with [info], [warning] and the
 * rest, and the bridge keeps the journal, redacts it and sends it to ntfy. Calls only enqueue and never block,
 * so they are safe anywhere, including the main thread and a dying process.
 */
object Diag {
	private const val TAG = "MarqueeDiag"
	private const val MAX_LOGGED_FAILURES = 5

	private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
	private val pipeline = DiagPipeline(NativeDiagBridge, onFailure = ::failed)
	private val installed = AtomicBoolean()
	private val profileName = MutableStateFlow<String?>(null)
	private val mutableDefaultName = MutableStateFlow("Android tablet")
	private val mutableTitle = MutableStateFlow("Marquee ${BuildConfig.VERSION_NAME}")
	private var pushed: Pair<DiagConfig, String?>? = null
	private var failures = 0

	lateinit var application: Application
		private set

	lateinit var settings: DiagSettings
		private set

	/** What the messages call this tablet when no name is typed. */
	val defaultName: StateFlow<String> = mutableDefaultName.asStateFlow()

	/** The title on every message: the app version and the tablet's name. */
	val title: StateFlow<String> = mutableTitle.asStateFlow()

	val detailed: Boolean get() = pipeline.detailed

	/** Opens the log, starts sending it and watches the system. Once per process, from `Application.onCreate`. */
	fun install(app: Application) {
		if (!installed.compareAndSet(false, true)) return
		application = app
		settings = DiagSettings(app)
		val system = DiagSystem(app, scope)
		pipeline.begin(startInfo(app))
		pipeline.run(scope, onStarted = system::launched)
		installCrashHandler()
		system.install()
		scope.launch {
			settings.load()
			combine(settings.config.filterNotNull(), profileName) { config, name -> config to name }
				.collect { (config, name) -> push(config, name) }
		}
	}

	fun event(level: DiagLevel, category: DiagCategory, name: String, vararg fields: Pair<String, Any?>) =
		pipeline.event(level, category, name, fields)

	fun debug(category: DiagCategory, name: String, vararg fields: Pair<String, Any?>) =
		pipeline.event(DiagLevel.Debug, category, name, fields)

	fun info(category: DiagCategory, name: String, vararg fields: Pair<String, Any?>) =
		pipeline.event(DiagLevel.Info, category, name, fields)

	fun notice(category: DiagCategory, name: String, vararg fields: Pair<String, Any?>) =
		pipeline.event(DiagLevel.Notice, category, name, fields)

	fun warning(category: DiagCategory, name: String, vararg fields: Pair<String, Any?>) =
		pipeline.event(DiagLevel.Warning, category, name, fields)

	fun error(category: DiagCategory, name: String, vararg fields: Pair<String, Any?>) =
		pipeline.event(DiagLevel.Error, category, name, fields)

	/** Playback's own lines are kept apart; the player brackets a session with these two. */
	fun beginPlayback(): String = pipeline.beginPlayback()

	fun endPlayback() = pipeline.endPlayback()

	/** foreground, background or ended: when to send and when to rest. */
	fun phase(name: String) = pipeline.phase(name)

	fun flush() = pipeline.flush()

	/** The signed-in profile, which names the tablet on messages until a name is typed. */
	fun profile(name: String?) {
		profileName.value = name
	}

	/** Saves a change to the settings and hands it to the bridge before returning. */
	suspend fun update(change: (DiagConfig) -> DiagConfig) {
		push(settings.update(change), profileName.value)
	}

	suspend fun status(): DiagStatus? = attempt(null) { DiagWire.status(DiagWire.decode(pipeline.request("diag.state"))) }

	/** Sends a test message to the saved server. */
	suspend fun test(): DiagTest = try {
		DiagWire.test(DiagWire.decode(pipeline.request("diag.test")))
	} catch (e: CancellationException) {
		throw e
	} catch (_: CoreException) {
		DiagTest.NotSetUp
	} catch (_: Exception) {
		DiagTest.Failed(null)
	}

	suspend fun parse(draft: DiagDraft): DiagParse = attempt(DiagParse(null, null)) {
		val dto = DiagWire.decode<ParseDto>(pipeline.request("diag.parse", DiagWire.parse(draft)))
		DiagParse(DiagProblem.of(dto.problem), dto.host)
	}

	/** The journal, redacted, at most its last [maxBytes]. */
	suspend fun journal(maxBytes: Int? = null): String =
		DiagWire.decode<JournalDto>(pipeline.request("diag.journal", DiagWire.journal(maxBytes))).text

	/** Sends a report: [details] above the whole journal, an optional screenshot, and a summary with the [note]. */
	suspend fun report(note: String, source: String, details: String, screen: String, screenshot: ByteArray?): DiagReportResult {
		val reply = try {
			DiagWire.decode<ReportDto>(pipeline.request("diag.report", DiagWire.report(note, source, details, screen, screenshot)))
		} catch (e: CancellationException) {
			throw e
		} catch (_: Exception) {
			return DiagReportResult.Failed(DiagText.REPORT_FAILED)
		}
		val id = reply.id
		return if (reply.sent && id != null) DiagReportResult.Sent(id, reply.attached ?: true)
		else DiagReportResult.Failed(reply.text ?: DiagText.REPORT_FAILED)
	}

	private inline fun <T> attempt(fallback: T, block: () -> T): T = try {
		block()
	} catch (e: CancellationException) {
		throw e
	} catch (_: Exception) {
		fallback
	}

	@Synchronized
	private fun push(config: DiagConfig, name: String?) {
		if (pushed == config to name) return
		pushed = config to name
		val tablet = application.resources.configuration.smallestScreenWidthDp >= DiagFacts.TABLET_DP
		mutableDefaultName.value = DiagNames.default(name, tablet)
		val device = DiagNames.device(config.name, name, tablet)
		val title = DiagNames.title(BuildConfig.VERSION_NAME, device)
		mutableTitle.value = title
		pipeline.configure(DiagWire.configure(config, title, device), config.detailed)
	}

	private fun startInfo(app: Application): JsonObject {
		val now = System.currentTimeMillis()
		val born = now - (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime())
		return DiagWire.start(
			folder = File(app.noBackupFilesDir, "Diagnostics").path,
			version = BuildConfig.VERSION_NAME,
			build = BuildConfig.VERSION_CODE.toString(),
			commit = BuildConfig.COMMIT,
			bornMillis = born,
			offsetSeconds = TimeZone.getDefault().getOffset(now) / 1000,
		)
	}

	/** An uncaught exception reaches the journal, and the bridge's sender, before the process goes. */
	private fun installCrashHandler() {
		val previous = Thread.getDefaultUncaughtExceptionHandler()
		Thread.setDefaultUncaughtExceptionHandler { thread, error ->
			try {
				pipeline.crash(DiagCrash.events(thread.name, error, System.currentTimeMillis()))
			} catch (_: Throwable) {
				// The crash is already on its way to the system's handler; nothing here may hide it.
			}
			previous?.uncaughtException(thread, error)
		}
	}

	private fun failed(method: String, error: Throwable) {
		if (failures++ < MAX_LOGGED_FAILURES) Log.w(TAG, "$method failed", error)
	}
}
