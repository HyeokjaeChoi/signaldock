package local.signaldock.demo.core.data

/** Typed property value. The SDK takes flat string / number / boolean values (spec section 3). */
sealed interface PropertyValue {
    data class Text(val value: String) : PropertyValue
    data class Whole(val value: Int) : PropertyValue
    data class Decimal(val value: Double) : PropertyValue
    data class Flag(val value: Boolean) : PropertyValue
}

/** The shop actions the demo collects. One user action maps to exactly one event. */
sealed interface DemoEvent {
    val name: String
    val properties: Map<String, PropertyValue>

    data class ProductViewed(val productId: String, val priceMinor: Int) : DemoEvent {
        override val name = "product_viewed"
        override val properties = mapOf(
            "product_id" to PropertyValue.Text(productId),
            "price_minor" to PropertyValue.Whole(priceMinor),
        )
    }

    data class AddToCart(val productId: String, val quantity: Int, val priceMinor: Int) : DemoEvent {
        override val name = "add_to_cart"
        override val properties = mapOf(
            "product_id" to PropertyValue.Text(productId),
            "quantity" to PropertyValue.Whole(quantity),
            "price_minor" to PropertyValue.Whole(priceMinor),
        )
    }

    data class CheckoutStarted(val itemCount: Int, val totalMinor: Int) : DemoEvent {
        override val name = "checkout_started"
        override val properties = mapOf(
            "item_count" to PropertyValue.Whole(itemCount),
            "total_minor" to PropertyValue.Whole(totalMinor),
        )
    }

    data class PurchaseCompleted(val orderId: String, val itemCount: Int, val totalMinor: Int) : DemoEvent {
        override val name = "purchase_completed"
        override val properties = mapOf(
            "order_id" to PropertyValue.Text(orderId),
            "item_count" to PropertyValue.Whole(itemCount),
            "total_minor" to PropertyValue.Whole(totalMinor),
            // Simulated purchase: no real payment (spec section 1).
            "is_simulated" to PropertyValue.Flag(true),
        )
    }
}
