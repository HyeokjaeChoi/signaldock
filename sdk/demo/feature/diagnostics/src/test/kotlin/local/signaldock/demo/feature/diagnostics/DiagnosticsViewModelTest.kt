package local.signaldock.demo.feature.diagnostics

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import local.signaldock.AckDiagnostic
import local.signaldock.DeliveryErrorDiagnostic
import local.signaldock.DeliveryErrorKind
import local.signaldock.Diagnostics
import local.signaldock.RejectionDiagnostic
import local.signaldock.TrackResult
import local.signaldock.demo.core.data.DemoEvent
import local.signaldock.demo.core.data.SignalDockClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

private fun snapshot(queued: Int) = Diagnostics(
    sdkVersion = "0.1.0",
    queuedEvents = queued,
    queuedBytes = queued * 100L,
    oldestPendingEpochMillis = 1_000,
    recentRejections = listOf(RejectionDiagnostic("e-1", "INVALID", 2_000)),
    lastAck = AckDiagnostic(3_000, accepted = 2, duplicate = 1, rejected = 1),
    lastDeliveryError = DeliveryErrorDiagnostic(DeliveryErrorKind.NETWORK, null, 4_000),
    retryFailureCount = 2,
    nextAttemptEpochMillis = 5_000,
)

private class FakeClient : SignalDockClient {
    var calls = 0
    var next: suspend () -> Diagnostics = { snapshot(calls) }
    override suspend fun track(event: DemoEvent): TrackResult = error("not used by diagnostics")
    override suspend fun diagnostics(): Diagnostics { calls++; return next() }
}

@OptIn(ExperimentalCoroutinesApi::class)
class DiagnosticsViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    @Test fun creating_the_view_model_does_not_read_diagnostics() {
        val client = FakeClient()
        DiagnosticsViewModel(client)
        assertEquals(0, client.calls)
    }

    @Test fun screen_entry_reads_once_and_exposes_the_snapshot() {
        val client = FakeClient()
        val vm = DiagnosticsViewModel(client)
        vm.onIntent(DiagnosticsIntent.ScreenEntered)
        assertEquals(1, client.calls)
        val s = vm.state.value
        assertEquals(false, s.loading)
        assertEquals(1, s.diagnostics?.queuedEvents)
        assertEquals(2, s.diagnostics?.lastAck?.accepted)
        assertEquals(DeliveryErrorKind.NETWORK, s.diagnostics?.lastDeliveryError?.kind)
        assertEquals(5_000L, s.diagnostics?.nextAttemptEpochMillis)
        assertEquals("INVALID", s.diagnostics?.recentRejections?.single()?.code)
    }

    @Test fun manual_refresh_reads_again_and_replaces_the_snapshot() {
        val client = FakeClient()
        val vm = DiagnosticsViewModel(client)
        vm.onIntent(DiagnosticsIntent.ScreenEntered)
        vm.onIntent(DiagnosticsIntent.RefreshClicked)
        assertEquals(2, client.calls)
        assertEquals(2, vm.state.value.diagnostics?.queuedEvents)
    }

    @Test fun a_refresh_during_a_read_is_ignored() {
        val client = FakeClient()
        val gate = CompletableDeferred<Unit>()
        client.next = { gate.await(); snapshot(7) }
        val vm = DiagnosticsViewModel(client)
        vm.onIntent(DiagnosticsIntent.ScreenEntered)
        assertEquals(true, vm.state.value.loading)
        vm.onIntent(DiagnosticsIntent.RefreshClicked)
        assertEquals(1, client.calls)
        gate.complete(Unit)
        assertEquals(7, vm.state.value.diagnostics?.queuedEvents)
        assertEquals(false, vm.state.value.loading)
    }

    @Test fun a_failed_read_keeps_the_last_snapshot_and_shows_the_error() {
        val client = FakeClient()
        val vm = DiagnosticsViewModel(client)
        vm.onIntent(DiagnosticsIntent.ScreenEntered)
        client.next = { throw IllegalStateException("storage down") }
        vm.onIntent(DiagnosticsIntent.RefreshClicked)
        assertNotNull(vm.state.value.diagnostics)
        assertEquals("storage down", vm.state.value.error)
        client.next = { snapshot(9) }
        vm.onIntent(DiagnosticsIntent.RefreshClicked)
        assertNull(vm.state.value.error)
        assertEquals(9, vm.state.value.diagnostics?.queuedEvents)
    }
}
