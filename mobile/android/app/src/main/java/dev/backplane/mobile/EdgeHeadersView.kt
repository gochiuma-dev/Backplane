package dev.backplane.mobile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Fork (cf-access, docs/FORK-CF-ACCESS.md): the rows of edge proxy headers, on the pairing screen and in Settings → Hubs.
// A value is write-only: a saved one shows as "saved", and a row left blank keeps it.

/** Name and value rows; `saved` are the names that already have a value. */
@Composable
internal fun EdgeRows(rows: List<EdgeHeaders.H>, saved: Set<String>, onChange: (List<EdgeHeaders.H>) -> Unit) {
    rows.forEachIndexed { i, r ->
        DField(r.name, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(name = v) }) }, Msg.fork_edge_name, Modifier.testTag("edge.name.$i"))
        Spacer(Modifier.height(4.dp))
        Box(Modifier.fillMaxWidth().background(dview).border(BorderStroke(1.dp, dcs.outlineVariant)).padding(horizontal = 10.dp, vertical = 10.dp)) {
            if (r.value.isEmpty()) Text(if (r.name.trim().lowercase() in saved) Msg.fork_edge_kept else Msg.fork_edge_value, color = dfaint, fontFamily = BpMono, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(r.value, { v -> onChange(rows.toMutableList().also { it[i] = r.copy(value = v) }) }, textStyle = TextStyle(color = dcs.onSurface, fontFamily = BpMono, fontSize = 13.sp),
                cursorBrush = SolidColor(dcs.primary), singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().testTag("edge.value.$i"))
        }
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DBtn(Msg.fork_edge_add, false, modifier = Modifier.testTag("edge.add")) { onChange(rows + EdgeHeaders.H("", "")) }
        DBtn(Msg.fork_edge_cf, false, modifier = Modifier.testTag("edge.cf")) {
            val have = rows.map { it.name.lowercase() }.toSet()
            onChange(rows.filter { it.name.isNotBlank() } + listOf(EdgeHeaders.CF_ID, EdgeHeaders.CF_SECRET).filter { it.lowercase() !in have }.map { EdgeHeaders.H(it, "") })
        }
    }
}

/** The pairing screen's section: what is typed goes to EdgeHeaders.pending, given to the hub when Pair is pressed. */
@Composable
internal fun EdgePairSection(epoch: Int) {
    var open by remember(epoch) { mutableStateOf(false) }
    var rows by remember(epoch) { mutableStateOf(listOf<EdgeHeaders.H>()) }
    Spacer(Modifier.height(16.dp))
    DBtn(Msg.fork_edge_head, false, modifier = Modifier.testTag("pair.edge")) { open = !open; if (rows.isEmpty()) rows = listOf(EdgeHeaders.H("", "")) }
    if (!open) return
    Spacer(Modifier.height(8.dp))
    DMono(Msg.fork_edge_note, dcs.onSurfaceVariant, 11, lines = 4)
    Spacer(Modifier.height(8.dp))
    EdgeRows(rows, emptySet()) { rows = it; EdgeHeaders.pending = it }
}

/** Settings → Hubs: a hub's headers in a dialog. Saving reconnects when it is the hub in use. */
@Composable
internal fun EdgeHubDialog(link: String, close: () -> Unit) {
    val hub = EdgeHeaders.hubOf(link)
    val saved = remember(hub, EdgeHeaders.version) { EdgeHeaders.names(hub) }
    var rows by remember(hub) { mutableStateOf(saved.map { EdgeHeaders.H(it, "") }.ifEmpty { listOf(EdgeHeaders.H("", "")) }) }
    var err by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = close, title = { Text(Msg.fork_edge_title(Pairing.key(link) ?: link)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                DMono(if (hub == null) Msg.fork_edge_https else Msg.fork_edge_note, dcs.onSurfaceVariant, 11, lines = 5)
                Spacer(Modifier.height(8.dp))
                if (hub != null) EdgeRows(rows, saved.map { it.lowercase() }.toSet()) { rows = it; err = "" }
                if (err.isNotEmpty()) Text(err, color = dcs.error, fontSize = 13.sp)
            }
        },
        confirmButton = { if (hub != null) TextButton({
            if (!EdgeHeaders.put(hub, rows)) { err = Msg.fork_edge_bad; return@TextButton }
            close(); if (Pairing.key(DataClient.link ?: "") == Pairing.key(link)) DataClient.connect()
        }, Modifier.testTag("edge.save")) { Text(Msg.common_save) } },
        dismissButton = { TextButton(close) { Text(Msg.common_cancel) } })
}
