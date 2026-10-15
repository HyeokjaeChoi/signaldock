package local.signaldock.server

import local.signaldock.ingestion.PlanStatus
import local.signaldock.ingestion.PropertyValue
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TrackingPlanFileTest {
    private val repoPlan = "../../tracking-plan/plan.json" // the file compose mounts

    @Test
    fun `the repository plan loads and marks its own events`() {
        val plan = loadTrackingPlan(repoPlan)
        assertEquals(PlanStatus.PLANNED, plan.check("add_to_cart", mapOf(
            "product_id" to PropertyValue.Text("p"), "quantity" to PropertyValue.Number("1"), "price_minor" to PropertyValue.Number("100"),
        )))
        assertEquals(PlanStatus.UNPLANNED_EVENT, plan.check("screen_opened", emptyMap()))
        assertEquals(PlanStatus.INVALID_PROPERTIES, plan.check("add_to_cart", emptyMap()))
    }

    @Test
    fun `no plan is configured when the variable is unset or blank`() {
        assertNull(trackingPlanFromEnv { null })
        assertNull(trackingPlanFromEnv { " " })
        assertTrue(trackingPlanFromEnv { if (it == "SIGNALDOCK_TRACKING_PLAN") repoPlan else null } != null)
    }

    @Test
    fun `a missing file fails with the variable name and the path`() {
        val error = assertFailsWith<IllegalArgumentException> { trackingPlanFromEnv { "/no/such/plan.json" } }
        assertTrue("SIGNALDOCK_TRACKING_PLAN" in error.message!! && "/no/such/plan.json" in error.message!!, error.message)
    }

    @Test
    fun `text that is not JSON fails`() {
        val file = File.createTempFile("plan", ".json").apply { writeText("{not json"); deleteOnExit() }
        val error = assertFailsWith<IllegalArgumentException> { loadTrackingPlan(file.path) }
        assertTrue("not valid JSON" in error.message!!, error.message)
    }

    private fun invalid(json: String): String = assertFailsWith<IllegalArgumentException> { parseTrackingPlan(json) }.message!!

    private fun plan(events: String, version: String = "1") = """{"version":$version,"events":$events}"""

    @Test
    fun `invalid structure is reported with every problem`() {
        assertTrue("version must be 1" in invalid(plan("""[{"name":"a_b","properties":[]}]""", version = "2")))
        assertTrue("version must be 1" in invalid(plan("""[{"name":"a_b","properties":[]}]""", version = "\"1\"")))
        assertTrue("version must be 1" in invalid("[]"))
        assertTrue("events must be a non-empty array" in invalid(plan("[]")))
        assertTrue("events must be a non-empty array" in invalid("""{"version":1}"""))
        assertTrue("name must be a non-empty string" in invalid(plan("""[{"name":"","properties":[]}]""")))
        assertTrue("name must be a non-empty string" in invalid(plan("""[{"name":["a_b"],"properties":[]}]""")))
        assertTrue("duplicate event name" in invalid(plan("""[{"name":"a_b","properties":[]},{"name":"a_b","properties":[]}]""")))
        assertTrue("properties must be an array" in invalid(plan("""[{"name":"a_b"}]""")))
    }

    @Test
    fun `invalid property fields are reported`() {
        fun prop(p: String) = invalid(plan("""[{"name":"a_b","properties":[$p]}]"""))
        assertTrue("type must be one of" in prop("""{"key":"k","type":"float","required":true}"""))
        assertTrue("type must be one of" in prop("""{"key":"k","type":["string"],"required":true}"""))
        assertTrue("required must be true or false" in prop("""{"key":"k","type":"string","required":"yes"}"""))
        assertTrue("required must be true or false" in prop("""{"key":"k","type":"string"}"""))
        assertTrue("key must be a non-empty string" in prop("""{"type":"string","required":true}"""))
        assertTrue("duplicate key" in prop("""{"key":"k","type":"string","required":true},{"key":"k","type":"integer","required":false}"""))
        val both = prop("""{"key":"k","type":"x","required":1}""")
        assertTrue("type must be" in both && "required must be" in both, both)
    }
}
