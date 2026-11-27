package com.nphil.marquee.television

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.nphil.marquee.AppContainer
import com.nphil.marquee.browse.HomeRowsModel
import com.nphil.marquee.ui.theme.lucent

@Composable internal fun TvHomeRows(container: AppContainer) {
    val place by container.browse.source.place.collectAsStateWithLifecycle()
    val active = place ?: return
    val model = viewModel<HomeRowsModel>(key = "home.rows:${active.key}") { HomeRowsModel(container.browse, active) }
    LaunchedEffect(model) { model.load() }
    val ui by model.ui.collectAsStateWithLifecycle()
    when {
        ui.remote.failure != null && ui.rows.isEmpty() -> Column { Text("Couldn’t load Home rows", color = lucent.ink); Action("Retry", model::load) }
        ui.remote.value == null -> Text("Loading Home rows…", color = lucent.ink2)
        ui.rows.isEmpty() -> Text("This server has no Home rows.", color = lucent.ink2)
        else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(ui.rows, key = { _, row -> row.id }) { index, row ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(row.title, color = lucent.ink, style = MaterialTheme.typography.titleMedium)
                        Text(if (row.shown) "Shown" else "Hidden", color = lucent.ink2)
                    }
                    Action(if (row.shown) "Hide" else "Show") { model.setShown(row, !row.shown) }
                    if (index > 0) Action("Move up") { model.move(index, index - 1) }
                    if (index < ui.rows.lastIndex) Action("Move down") { model.move(index, index + 1) }
                }
            }
            if (!ui.isDefault) item { Action("Restore default order and visibility", model::reset) }
        }
    }
}
