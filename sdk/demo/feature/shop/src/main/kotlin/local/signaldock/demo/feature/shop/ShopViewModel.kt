package local.signaldock.demo.feature.shop

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import local.signaldock.TrackResult
import local.signaldock.demo.core.data.DemoEvent
import local.signaldock.demo.core.data.SignalDockClient
import java.util.UUID

enum class ShopStep { LIST, DETAIL, CART, CHECKOUT, DONE }

data class Product(val id: String, val name: String, val priceMinor: Int)

data class CartLine(val product: Product, val quantity: Int)

/** Result of the latest `track` call: a device collection result only, never server receipt. */
data class TrackOutcome(val eventName: String, val result: TrackResult)

/** One immutable state for the whole shop flow. */
data class ShopUiState(
    val step: ShopStep = ShopStep.LIST,
    val products: List<Product> = Catalog.products,
    val selected: Product? = null,
    val cart: List<CartLine> = emptyList(),
    val orderId: String? = null,
    val lastTrack: TrackOutcome? = null,
) {
    val itemCount: Int get() = cart.sumOf { it.quantity }
    val totalMinor: Int get() = cart.sumOf { it.quantity * it.product.priceMinor }
}

/** Events go up from the screen as intents (UDF). */
sealed interface ShopIntent {
    data class OpenProduct(val productId: String) : ShopIntent
    data object AddToCart : ShopIntent
    data object OpenCart : ShopIntent
    data object StartCheckout : ShopIntent
    data object PlaceOrder : ShopIntent
    data object Back : ShopIntent
}

/**
 * Each business action calls `track` once, in the intent handler (never from composition).
 * Intents that are not valid in the current step are ignored, so a double tap cannot track twice.
 * Navigation state is kept in [SavedStateHandle]; restoring it never calls `track`.
 */
class ShopViewModel(
    private val client: SignalDockClient,
    private val saved: SavedStateHandle = SavedStateHandle(),
    private val newOrderId: () -> String = { UUID.randomUUID().toString() },
) : ViewModel() {
    private val _state = MutableStateFlow(restore())
    val state: StateFlow<ShopUiState> = _state.asStateFlow()

    fun onIntent(intent: ShopIntent) {
        val s = _state.value
        when (intent) {
            is ShopIntent.OpenProduct -> {
                val p = Catalog.find(intent.productId)
                if (s.step != ShopStep.LIST || p == null) return
                set(s.copy(step = ShopStep.DETAIL, selected = p))
                track(DemoEvent.ProductViewed(p.id, p.priceMinor))
            }
            ShopIntent.AddToCart -> {
                val p = s.selected
                if (s.step != ShopStep.DETAIL || p == null) return
                set(s.copy(cart = s.cart.add(p)))
                track(DemoEvent.AddToCart(p.id, quantity = 1, priceMinor = p.priceMinor))
            }
            ShopIntent.OpenCart ->
                if (s.step == ShopStep.LIST || s.step == ShopStep.DETAIL) set(s.copy(step = ShopStep.CART))
            ShopIntent.StartCheckout -> {
                if (s.step != ShopStep.CART || s.cart.isEmpty()) return
                set(s.copy(step = ShopStep.CHECKOUT))
                track(DemoEvent.CheckoutStarted(s.itemCount, s.totalMinor))
            }
            ShopIntent.PlaceOrder -> {
                if (s.step != ShopStep.CHECKOUT) return
                val orderId = newOrderId()
                set(s.copy(step = ShopStep.DONE, orderId = orderId, cart = emptyList()))
                track(DemoEvent.PurchaseCompleted(orderId, s.itemCount, s.totalMinor))
            }
            ShopIntent.Back -> when (s.step) {
                ShopStep.LIST -> Unit
                ShopStep.DETAIL, ShopStep.CART, ShopStep.DONE ->
                    set(s.copy(step = ShopStep.LIST, selected = null, orderId = null))
                ShopStep.CHECKOUT -> set(s.copy(step = ShopStep.CART))
            }
        }
    }

    private fun track(event: DemoEvent) {
        viewModelScope.launch {
            val result = client.track(event)
            _state.update { it.copy(lastTrack = TrackOutcome(event.name, result)) }
        }
    }

    private fun set(next: ShopUiState) {
        _state.value = next
        saved[KEY_STEP] = next.step.name
        saved[KEY_SELECTED] = next.selected?.id
        saved[KEY_CART] = ArrayList(next.cart.flatMap { l -> List(l.quantity) { l.product.id } })
        saved[KEY_ORDER] = next.orderId
    }

    private fun restore(): ShopUiState {
        val step = saved.get<String>(KEY_STEP)?.let { runCatching { ShopStep.valueOf(it) }.getOrNull() } ?: ShopStep.LIST
        val ids = saved.get<ArrayList<String>>(KEY_CART).orEmpty()
        val cart = ids.mapNotNull(Catalog::find).fold(emptyList<CartLine>()) { acc, p -> acc.add(p) }
        return ShopUiState(
            step = step,
            selected = saved.get<String>(KEY_SELECTED)?.let(Catalog::find),
            cart = cart,
            orderId = saved.get<String>(KEY_ORDER),
        )
    }

    private fun List<CartLine>.add(p: Product): List<CartLine> =
        if (any { it.product.id == p.id }) map { if (it.product.id == p.id) it.copy(quantity = it.quantity + 1) else it }
        else this + CartLine(p, 1)

    private companion object {
        const val KEY_STEP = "step"
        const val KEY_SELECTED = "selected"
        const val KEY_CART = "cart"
        const val KEY_ORDER = "order"
    }
}

object Catalog {
    val products = listOf(
        Product("P-001", "Trail backpack", 59_000),
        Product("P-002", "Insulated bottle", 18_000),
        Product("P-003", "Headlamp", 32_000),
        Product("P-004", "Wool socks", 12_000),
    )

    fun find(id: String): Product? = products.firstOrNull { it.id == id }
}
