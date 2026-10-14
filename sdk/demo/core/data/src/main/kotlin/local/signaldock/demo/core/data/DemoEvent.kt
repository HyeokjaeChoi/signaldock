package local.signaldock.demo.core.data

import local.signaldock.demo.core.data.tracking.EventNames

/**
 * The shop actions the demo collects. One user action maps to exactly one event.
 * Names and properties come from `tracking-plan/plan.json`: [SdkSignalDockClient] sends each event
 * through the generated functions in `tracking-plan/generated/kotlin` (ADR-0021).
 */
sealed interface DemoEvent {
    val name: String

    data class ProductViewed(val productId: String, val priceMinor: Int) : DemoEvent {
        override val name = EventNames.PRODUCT_VIEWED
    }

    data class AddToCart(val productId: String, val quantity: Int, val priceMinor: Int) : DemoEvent {
        override val name = EventNames.ADD_TO_CART
    }

    data class CheckoutStarted(val itemCount: Int, val totalMinor: Int) : DemoEvent {
        override val name = EventNames.CHECKOUT_STARTED
    }

    data class PurchaseCompleted(val orderId: String, val itemCount: Int, val totalMinor: Int) : DemoEvent {
        override val name = EventNames.PURCHASE_COMPLETED
    }
}
