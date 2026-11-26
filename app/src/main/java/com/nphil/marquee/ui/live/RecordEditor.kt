package com.nphil.marquee.ui.live

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.nphil.marquee.core.GuideProgramme
import com.nphil.marquee.live.DvrModel
import com.nphil.marquee.live.RecordForm
import com.nphil.marquee.ui.theme.Space
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.serialization.json.*
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable internal fun RecordEditor(model: DvrModel, programme: GuideProgramme?, channel: String?, rule: String?, close: () -> Unit) {
    val browser = model.recordings
    val scope = rememberCoroutineScope()
    val sheet = remember(browser, programme?.id, rule) { UUID.randomUUID().toString() }
    var steps by remember(sheet) { mutableStateOf(emptyList<JsonObject>()) }
    val params = remember(sheet, steps) { browser.sheetParams(sheet, programme, channel, rule, steps) }
    var form by remember(sheet) { mutableStateOf<RecordForm?>(null) }
    var error by remember(sheet) { mutableStateOf<String?>(null) }
    var saving by remember(sheet) { mutableStateOf(false) }
    var ready by remember(params) { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    LaunchedEffect(params, retry) {
        error = null
        try { form = browser.form(params); ready = true; if (form == null) error = "Plex offers no way to record this programme." }
        catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.localizedMessage }
    }
    val choose: (JsonObjectBuilder.() -> Unit) -> Unit = { steps = steps + buildJsonObject(it) }
    ModalBottomSheet(onDismissRequest = { if (!saving) close() }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(Space.inset), verticalArrangement = Arrangement.spacedBy(Space.s2)) {
            Text(form?.title ?: "Record", style = MaterialTheme.typography.titleLarge)
            form?.let { draft ->
                draft.scopes.forEach { option -> FilterChip(draft.scope == option.scope, { choose { put("scope", option.scope) } }, { Text(option.title) }, enabled = ready && !saving) }
                draft.rows.forEach { row ->
                    if (row.on != null) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(row.title); Switch(row.on, { choose { put("row", row.id); put("on", it) } }, enabled = ready && !saving)
                        }
                    } else {
                        var expanded by remember(row.id) { mutableStateOf(false) }
                        Box {
                            TextButton({ expanded = true }, enabled = ready && !saving) { Text("${row.title}: ${row.choices.find { it.id == row.selected }?.label.orEmpty()}") }
                            DropdownMenu(expanded, { expanded = false }) { row.choices.forEach { option ->
                                DropdownMenuItem({ Text(option.label) }, { expanded = false; choose { put("row", row.id); put("choice", option.id) } })
                            } }
                        }
                    }
                    row.note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
                Text(draft.summary, Modifier.testTag("record-summary"))
                Button({ saving = true; scope.launch {
                    try { browser.save(params, rule != null); close() } catch (e: CancellationException) { throw e } catch (e: Exception) { error = e.localizedMessage } finally { saving = false }
                } }, enabled = ready && draft.canSave && !saving) { Text(if (rule == null) "Record" else "Save") }
            }
            if (form == null && error == null) CircularProgressIndicator()
            error?.let { Text(it); TextButton({ retry++ }) { Text("Try again") } }
        }
    }
}
