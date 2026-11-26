package com.nphil.marquee.live

import com.nphil.marquee.AppContainer
import com.nphil.marquee.core.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*
import java.util.TimeZone

@Serializable internal data class RecordingDay(val heading: String, val items: List<UpcomingGrab>)
@Serializable internal data class UpcomingGrab(val id: String, val ruleID: String? = null, val title: String, val episodeTitle: String? = null, val start: Double, val end: Double, val channel: String? = null, val status: String, val freshness: String? = null, val conflictID: String? = null, val isRecording: Boolean, val padding: String? = null)
@Serializable internal data class RuleInfo(val id: String, val title: String, val summary: String)
@Serializable internal data class GrabHistory(val id: String, val title: String, val outcome: String, val reason: String, val airedAt: Double)
@Serializable internal data class OtherAiring(val id: String, val start: Double, val channel: String? = null)
@Serializable internal data class LosingGrab(val id: String, val alternatives: List<OtherAiring>)
@Serializable internal data class GrabConflict(val id: String, val sentence: String, val losers: List<LosingGrab>)
@Serializable internal data class AlertEvent(val id: String, val title: String, val body: String)
@Serializable internal data class RecordingPage(val days: List<RecordingDay>, val rules: List<RuleInfo>, val history: List<GrabHistory>, val conflicts: List<GrabConflict>, val storage: String? = null, val journal: String? = null, val events: List<AlertEvent>, val splitMinWidth: Double)
@Serializable internal data class JournalUpdate(val journal: String? = null, val events: List<AlertEvent> = emptyList())
@Serializable internal data class RecordForm(val title: String, val scope: String, val scopes: List<Scope>, val rows: List<Field>, val canSave: Boolean, val summary: String) {
    @Serializable data class Scope(val scope: String, val title: String, val detail: String)
    @Serializable data class Choice(val id: String, val label: String)
    @Serializable data class Field(val id: String, val title: String, val on: Boolean? = null, val selected: String? = null, val choices: List<Choice>, val note: String? = null)
}

internal class RecordingBrowser(private val app: AppContainer, private val guide: LiveGuideModel) {
    val alerts get() = app.recordingAlerts
    val key get() = guide.key
    private val core get() = app.core
    private fun params(body: JsonObjectBuilder.() -> Unit) = core.serverParams(guide.place.server, guide.remembered, extra = body)
    suspend fun page(): RecordingPage = alerts.locked {
        val zone = TimeZone.getDefault()
        val result = core.call<RecordingPage>("live.dvr.recordings", params {
            put("directory", guide.directory); put("now", wallTime()); put("timeZone", zone.id); put("utcOffset", zone.rawOffset / 1000)
            alerts.journal(key)?.let { put("journal", it) }
        })
        alerts.accept(key, result.journal, result.events)
        result
    }
    suspend fun observe(baseline: Boolean) = alerts.locked {
        val result = core.call<JournalUpdate>(if (baseline) "live.dvr.look" else "live.dvr.observe", params {
            put("now", wallTime()); alerts.journal(key)?.let { put("journal", it) }
        }); alerts.accept(key, result.journal, result.events)
    }
    suspend fun move(id: String, from: Int, to: Int) { core.call<JsonObject>("live.dvr.move", params { put("id", id); put("from", from); put("to", to) }) }
    suspend fun switch(grab: String, airing: String) = alerts.locked {
        val result = core.call<JournalUpdate>("live.dvr.switch", params {
            put("grab", grab); put("airing", airing); put("directory", guide.directory); put("now", wallTime()); alerts.journal(key)?.let { put("journal", it) }
        }); alerts.accept(key, result.journal, result.events)
    }
    fun sheetParams(sheet: String, programme: GuideProgramme?, channel: String?, rule: String?, steps: List<JsonObject>) = params {
        put("sheet", sheet); put("steps", JsonArray(steps)); rule?.let { put("rule", it) }
        programme?.let { put("airing", CoreJson.encodeToJsonElement(GuideProgramme.serializer(), it)) }
        guide.guide?.channels?.firstOrNull { it.id == channel }?.let { put("channel", CoreJson.encodeToJsonElement(GuideChannel.serializer(), it)) }
    }
    suspend fun form(params: JsonObject): RecordForm? = core.call("live.dvr.sheet", params)
    suspend fun save(params: JsonObject, editing: Boolean) {
        val write: suspend () -> Unit = { core.call<JsonObject>("live.dvr.save", params); Unit }
        if (editing) write() else check(app.live.recordAfterTunerRelease(write)) { "The tuner is still busy. Try again." }
        guide.dvr.refreshSchedule()
    }
}
