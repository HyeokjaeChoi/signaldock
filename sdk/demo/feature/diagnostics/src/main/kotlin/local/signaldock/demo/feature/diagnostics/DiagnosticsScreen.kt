package local.signaldock.demo.feature.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.signaldock.Diagnostics
import local.signaldock.demo.core.ui.LabeledValue
import local.signaldock.demo.core.ui.NoticeText
import local.signaldock.demo.core.ui.SERVER_RECEIPT_NOTE
import local.signaldock.demo.core.ui.SectionCard
import local.signaldock.demo.core.ui.formatTime

@Composable
fun DiagnosticsRoute(viewModel: DiagnosticsViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onIntent(DiagnosticsIntent.ScreenEntered) }
    DiagnosticsScreen(state, viewModel::onIntent, modifier)
}

@Composable
fun DiagnosticsScreen(state: DiagnosticsUiState, onIntent: (DiagnosticsIntent) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Button({ onIntent(DiagnosticsIntent.RefreshClicked) }, Modifier.fillMaxWidth(), enabled = !state.loading) {
                Text(if (state.loading) "Reading..." else "Refresh")
            }
        }
        state.error?.let { item { Text("Could not read diagnostics: $it", color = MaterialTheme.colorScheme.error) } }
        state.diagnostics?.let { diagnosticsItems(it) }
        item { NoticeText("This is a snapshot of the device queue. $SERVER_RECEIPT_NOTE") }
    }
}

private fun LazyListScope.diagnosticsItems(d: Diagnostics) {
    item {
        SectionCard("Waiting on device") {
            LabeledValue("Pending events", d.queuedEvents.toString())
            LabeledValue("Pending bytes", d.queuedBytes.toString())
            LabeledValue("Oldest pending", d.oldestPendingEpochMillis?.let(::formatTime) ?: "none")
        }
    }
    item {
        SectionCard("Last ACK from server") {
            val ack = d.lastAck
            if (ack == null) Text("No ACK yet.") else {
                LabeledValue("Time", formatTime(ack.atEpochMillis))
                LabeledValue("Accepted / duplicate / rejected", "${ack.accepted} / ${ack.duplicate} / ${ack.rejected}")
            }
            NoticeText("A recent ACK does not mean the whole queue was sent.")
        }
    }
    item {
        SectionCard("Last delivery error") {
            val e = d.lastDeliveryError
            if (e == null) Text("None.") else {
                LabeledValue("Kind", e.kind.name)
                LabeledValue("HTTP status", e.httpStatus?.toString() ?: "none")
                LabeledValue("Time", formatTime(e.atEpochMillis))
            }
        }
    }
    item {
        SectionCard("Next retry") {
            LabeledValue("Next attempt", d.nextAttemptEpochMillis?.let(::formatTime) ?: "none scheduled")
            LabeledValue("Failures in a row", d.retryFailureCount.toString())
        }
    }
    item {
        SectionCard("Recent rejections") {
            if (d.recentRejections.isEmpty()) Text("None.")
            d.recentRejections.forEach { LabeledValue("${it.eventId.take(8)}  ${it.code}", formatTime(it.rejectedAtEpochMillis)) }
        }
    }
    item { NoticeText("SDK version ${d.sdkVersion}") }
}
