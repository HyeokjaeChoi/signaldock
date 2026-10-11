package local.signaldock.demo.feature.shop

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import local.signaldock.TrackResult
import local.signaldock.demo.core.ui.LabeledValue
import local.signaldock.demo.core.ui.NoticeText
import local.signaldock.demo.core.ui.SERVER_RECEIPT_NOTE
import local.signaldock.demo.core.ui.SectionCard

@Composable
fun ShopRoute(viewModel: ShopViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    ShopScreen(state, viewModel::onIntent, modifier)
}

@Composable
fun ShopScreen(state: ShopUiState, onIntent: (ShopIntent) -> Unit, modifier: Modifier = Modifier) {
    BackHandler(enabled = state.step != ShopStep.LIST) { onIntent(ShopIntent.Back) }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.step) {
            ShopStep.LIST -> {
                item { Text("Products", style = MaterialTheme.typography.headlineSmall) }
                items(state.products, key = { it.id }) { p ->
                    SectionCard(p.name, Modifier.clickable { onIntent(ShopIntent.OpenProduct(p.id)) }) {
                        Text(money(p.priceMinor))
                    }
                }
                item { OutlinedButton({ onIntent(ShopIntent.OpenCart) }) { Text("Cart (${state.itemCount})") } }
            }
            ShopStep.DETAIL -> item {
                val p = state.selected
                if (p != null) SectionCard(p.name) {
                    Text(money(p.priceMinor))
                    Button({ onIntent(ShopIntent.AddToCart) }, Modifier.fillMaxWidth()) { Text("Add to cart") }
                    OutlinedButton({ onIntent(ShopIntent.OpenCart) }, Modifier.fillMaxWidth()) { Text("Cart (${state.itemCount})") }
                    OutlinedButton({ onIntent(ShopIntent.Back) }, Modifier.fillMaxWidth()) { Text("Back to products") }
                }
            }
            ShopStep.CART -> item {
                SectionCard("Cart") {
                    if (state.cart.isEmpty()) Text("The cart is empty.")
                    state.cart.forEach { LabeledValue("${it.product.name} x ${it.quantity}", money(it.product.priceMinor * it.quantity)) }
                    LabeledValue("Total", money(state.totalMinor))
                    Button({ onIntent(ShopIntent.StartCheckout) }, Modifier.fillMaxWidth(), enabled = state.cart.isNotEmpty()) { Text("Start checkout") }
                    OutlinedButton({ onIntent(ShopIntent.Back) }, Modifier.fillMaxWidth()) { Text("Back to products") }
                }
            }
            ShopStep.CHECKOUT -> item {
                SectionCard("Checkout") {
                    LabeledValue("Items", state.itemCount.toString())
                    LabeledValue("Total", money(state.totalMinor))
                    NoticeText("Simulated purchase. No real payment.")
                    Button({ onIntent(ShopIntent.PlaceOrder) }, Modifier.fillMaxWidth()) { Text("Place simulated order") }
                    OutlinedButton({ onIntent(ShopIntent.Back) }, Modifier.fillMaxWidth()) { Text("Back to cart") }
                }
            }
            ShopStep.DONE -> item {
                SectionCard("Order placed (simulated)") {
                    state.orderId?.let { LabeledValue("Order", it.take(8)) }
                    Button({ onIntent(ShopIntent.Back) }, Modifier.fillMaxWidth()) { Text("Back to products") }
                }
            }
        }
        item { TrackStatus(state.lastTrack) }
    }
}

@Composable
private fun TrackStatus(outcome: TrackOutcome?) {
    SectionCard("Event collection") {
        Text(
            when (val r = outcome?.result) {
                null -> "No event collected yet."
                is TrackResult.Success -> "Saved on device: ${outcome.eventName} (${r.eventId.take(8)})"
                is TrackResult.Invalid -> "Not saved: ${outcome.eventName} rejected (${r.reason})"
                TrackResult.QueueFull -> "Not saved: queue is full (${outcome.eventName})"
                is TrackResult.StorageError -> "Not saved: storage error for ${outcome.eventName}"
            },
        )
        NoticeText(SERVER_RECEIPT_NOTE)
    }
}

private fun money(minor: Int): String = "KRW " + "%,d".format(minor)
