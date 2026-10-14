package local.signaldock.demo.feature.shop

import androidx.lifecycle.SavedStateHandle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import local.signaldock.Diagnostics
import local.signaldock.TrackResult
import local.signaldock.demo.core.data.DemoEvent
import local.signaldock.demo.core.data.SignalDockClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

private class FakeClient(var result: TrackResult = TrackResult.Success("event-1")) : SignalDockClient {
    val tracked = mutableListOf<DemoEvent>()
    override suspend fun track(event: DemoEvent): TrackResult { tracked += event; return result }
    override suspend fun diagnostics(): Diagnostics = error("not used by the shop")
}

private class GatedClient : SignalDockClient {
    val gates = mutableListOf<CompletableDeferred<TrackResult>>()
    override suspend fun track(event: DemoEvent): TrackResult =
        CompletableDeferred<TrackResult>().also { gates += it }.await()
    override suspend fun diagnostics(): Diagnostics = error("not used by the shop")
}

@OptIn(ExperimentalCoroutinesApi::class)
class ShopViewModelTest {
    @Before fun setUp() = Dispatchers.setMain(UnconfinedTestDispatcher())
    @After fun tearDown() = Dispatchers.resetMain()

    private fun vm(client: FakeClient, saved: SavedStateHandle = SavedStateHandle()) =
        ShopViewModel(client, saved, newOrderId = { "order-1" })

    @Test fun opening_a_product_tracks_product_viewed_once() {
        val client = FakeClient()
        val vm = vm(client)
        vm.onIntent(ShopIntent.OpenProduct("P-001"))
        assertEquals(listOf<DemoEvent>(DemoEvent.ProductViewed("P-001", 59_000)), client.tracked)
        assertEquals(ShopStep.DETAIL, vm.state.value.step)
    }

    @Test fun full_flow_tracks_one_event_per_action_with_typed_properties() {
        val client = FakeClient()
        val vm = vm(client)
        vm.onIntent(ShopIntent.OpenProduct("P-002"))
        vm.onIntent(ShopIntent.AddToCart)
        vm.onIntent(ShopIntent.OpenCart)
        vm.onIntent(ShopIntent.StartCheckout)
        vm.onIntent(ShopIntent.PlaceOrder)
        assertEquals(
            listOf("product_viewed", "add_to_cart", "checkout_started", "purchase_completed"),
            client.tracked.map { it.name },
        )
        assertEquals(DemoEvent.PurchaseCompleted("order-1", 1, 18_000), client.tracked.last())
        assertEquals(ShopStep.DONE, vm.state.value.step)
        assertTrue(vm.state.value.cart.isEmpty())
    }

    @Test fun repeated_taps_that_are_invalid_in_the_current_step_do_not_track() {
        val client = FakeClient()
        val vm = vm(client)
        vm.onIntent(ShopIntent.OpenProduct("P-001"))
        vm.onIntent(ShopIntent.OpenProduct("P-001")) // double tap on the list item
        vm.onIntent(ShopIntent.StartCheckout) // wrong step
        vm.onIntent(ShopIntent.PlaceOrder) // wrong step
        assertEquals(1, client.tracked.size)
        vm.onIntent(ShopIntent.AddToCart)
        vm.onIntent(ShopIntent.OpenCart)
        vm.onIntent(ShopIntent.StartCheckout)
        vm.onIntent(ShopIntent.StartCheckout) // double tap
        vm.onIntent(ShopIntent.PlaceOrder)
        vm.onIntent(ShopIntent.PlaceOrder) // double tap
        assertEquals(listOf("product_viewed", "add_to_cart", "checkout_started", "purchase_completed"), client.tracked.map { it.name })
    }

    @Test fun navigation_without_a_business_action_does_not_track() {
        val client = FakeClient()
        val vm = vm(client)
        vm.onIntent(ShopIntent.OpenCart)
        vm.onIntent(ShopIntent.StartCheckout) // empty cart
        vm.onIntent(ShopIntent.Back)
        assertTrue(client.tracked.isEmpty())
    }

    @Test fun restoring_saved_state_does_not_track() = runTest {
        val client = FakeClient()
        val first = vm(client)
        first.onIntent(ShopIntent.OpenProduct("P-003"))
        first.onIntent(ShopIntent.AddToCart)
        first.onIntent(ShopIntent.OpenCart)
        first.onIntent(ShopIntent.StartCheckout)
        val before = client.tracked.size
        // Same SavedStateHandle contents in a new ViewModel: process recreation or new owner.
        val restoredHandle = SavedStateHandle(
            mapOf("step" to "CHECKOUT", "selected" to "P-003", "cart" to arrayListOf("P-003"), "order" to null),
        )
        val restored = vm(client, restoredHandle)
        assertEquals(ShopStep.CHECKOUT, restored.state.value.step)
        assertEquals(1, restored.state.value.itemCount)
        assertEquals(before, client.tracked.size)
    }

    @Test fun collecting_the_state_again_does_not_track() = runTest {
        val client = FakeClient()
        val vm = vm(client)
        vm.onIntent(ShopIntent.OpenProduct("P-004"))
        repeat(5) { vm.state.first() } // a re-collection, as after recomposition or rotation
        assertEquals(1, client.tracked.size)
    }

    @Test fun device_collection_result_is_kept_in_state_and_failure_does_not_retrack() {
        val client = FakeClient(TrackResult.QueueFull)
        val vm = vm(client)
        vm.onIntent(ShopIntent.OpenProduct("P-001"))
        assertEquals(TrackOutcome("product_viewed", TrackResult.QueueFull), vm.state.value.lastTrack)
        assertEquals(1, client.tracked.size)
        client.result = TrackResult.Success("event-2")
        vm.onIntent(ShopIntent.AddToCart)
        assertEquals(TrackOutcome("add_to_cart", TrackResult.Success("event-2")), vm.state.value.lastTrack)
        assertEquals(2, client.tracked.size)
    }

    @Test fun an_older_result_that_finishes_late_does_not_overwrite_lastTrack() {
        val client = GatedClient()
        val vm = ShopViewModel(client, SavedStateHandle(), newOrderId = { "order-1" })
        vm.onIntent(ShopIntent.OpenProduct("P-001"))
        vm.onIntent(ShopIntent.AddToCart)
        client.gates[1].complete(TrackResult.Success("event-2")) // second action finishes first
        client.gates[0].complete(TrackResult.QueueFull)
        assertEquals(TrackOutcome("add_to_cart", TrackResult.Success("event-2")), vm.state.value.lastTrack)
    }
}
