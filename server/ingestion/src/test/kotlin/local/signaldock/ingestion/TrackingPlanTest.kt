package local.signaldock.ingestion

import kotlin.test.Test
import kotlin.test.assertEquals

class TrackingPlanTest {
    private val plan = TrackingPlan(mapOf(
        "product_viewed" to listOf(
            PlanProperty("product_id", PlanValueType.STRING, required = true),
            PlanProperty("price_minor", PlanValueType.INTEGER, required = false),
        ),
        "rated_item" to listOf(
            PlanProperty("score", PlanValueType.NUMBER, required = true),
            PlanProperty("is_simulated", PlanValueType.BOOLEAN, required = true),
        ),
        "app_opened" to emptyList(),
    ))

    private fun text(v: String) = PropertyValue.Text(v)
    private fun num(v: String) = PropertyValue.Number(v)

    @Test
    fun `planned when name in plan and properties match`() {
        assertEquals(PlanStatus.PLANNED, plan.check("product_viewed", mapOf("product_id" to text("p1"))))
        assertEquals(PlanStatus.PLANNED, plan.check("product_viewed", mapOf("product_id" to text("p1"), "price_minor" to num("1990"))))
        assertEquals(PlanStatus.PLANNED, plan.check("rated_item", mapOf("score" to num("4.5"), "is_simulated" to PropertyValue.Flag(false))))
        assertEquals(PlanStatus.PLANNED, plan.check("app_opened", emptyMap()))
    }

    @Test
    fun `unplanned_event when name not in plan, whatever the properties are`() {
        assertEquals(PlanStatus.UNPLANNED_EVENT, plan.check("screen_opened", emptyMap()))
        assertEquals(PlanStatus.UNPLANNED_EVENT, plan.check("Product_Viewed", mapOf("product_id" to text("p1")))) // exact match, like Q64
    }

    @Test
    fun `invalid_properties when missing required key`() {
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("product_viewed", emptyMap()))
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("product_viewed", mapOf("price_minor" to num("1"))))
    }

    @Test
    fun `invalid_properties when unknown key`() {
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("product_viewed", mapOf("product_id" to text("p1"), "color" to text("red"))))
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("app_opened", mapOf("x" to text("y"))))
    }

    @Test
    fun `invalid_properties when wrong value type, also for an optional key`() {
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("product_viewed", mapOf("product_id" to num("5"))))
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("product_viewed", mapOf("product_id" to text("p1"), "price_minor" to text("5"))))
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("rated_item", mapOf("score" to num("1"), "is_simulated" to text("false"))))
    }

    @Test
    fun `integer accepts whole numbers in any spelling and rejects a fraction`() {
        fun status(literal: String) = plan.check("product_viewed", mapOf("product_id" to text("p"), "price_minor" to num(literal)))
        assertEquals(PlanStatus.PLANNED, status("5"))
        assertEquals(PlanStatus.PLANNED, status("-5"))
        assertEquals(PlanStatus.PLANNED, status("5.0"))
        assertEquals(PlanStatus.PLANNED, status("1e3"))
        assertEquals(PlanStatus.INVALID_PROPERTIES, status("5.5"))
        assertEquals(PlanStatus.INVALID_PROPERTIES, status("1e-3"))
    }
}
