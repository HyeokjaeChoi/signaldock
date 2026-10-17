package local.signaldock.demo.core.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import local.signaldock.SignalDock
import local.signaldock.SignalDockConfig
import local.signaldock.TrackResult
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Adapter boundary (ADR-0021): the real [SdkSignalDockClient] and the generated wrappers run on a
 * real SDK instance (Robolectric, real Room). The test reads the stored events back from the SDK's
 * queue database, so a wrong mapping (name, key, value, type, a missing `is_simulated`) fails here.
 * One test method: the SDK keeps one process-wide instance, and every Robolectric test gets a new
 * files directory.
 */
@RunWith(RobolectricTestRunner::class)
class SdkSignalDockClientTest {
    @Test
    fun `each DemoEvent is stored with its plan name and exactly its plan properties`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        // Nothing listens on this port: delivery fails and every event stays in the queue.
        val init = SignalDock.initialize(
            context,
            SignalDockConfig(endpoint = "http://localhost:9", collectionKey = "test-key", allowInsecureEndpoint = true),
        )
        val client = SdkSignalDockClient(CompletableDeferred(init))
        val events = listOf(
            DemoEvent.ProductViewed("P-1", 1200),
            DemoEvent.AddToCart("P-2", 3, 500),
            DemoEvent.CheckoutStarted(4, 2900),
            DemoEvent.PurchaseCompleted("O-9", 4, 2900),
        )
        for (e in events) assertTrue("$e", client.track(e) is TrackResult.Success)

        val dbFile = File(context.noBackupFilesDir, "signaldock/queue.db").path
        val stored = SQLiteDatabase.openDatabase(dbFile, null, SQLiteDatabase.OPEN_READONLY).use { d ->
            d.rawQuery("SELECT payloadJson FROM events ORDER BY seq", null).use { c ->
                generateSequence { if (c.moveToNext()) JSONObject(c.getString(0)) else null }.toList()
            }
        }
        assertEquals(events.map { it.name }, stored.map { it.getString("name") })
        val expected = listOf(
            mapOf("product_id" to "P-1", "price_minor" to 1200L),
            mapOf("product_id" to "P-2", "quantity" to 3L, "price_minor" to 500L),
            mapOf("item_count" to 4L, "total_minor" to 2900L),
            mapOf("order_id" to "O-9", "item_count" to 4L, "total_minor" to 2900L, "is_simulated" to true),
        )
        for ((i, want) in expected.withIndex()) {
            val props = stored[i].getJSONObject("properties")
            assertEquals("${events[i].name}: keys", want.keys, props.keys().asSequence().toSet())
            for ((k, v) in want) assertEquals("${events[i].name}.$k", v, props.get(k).let { if (it is Int) it.toLong() else it })
        }
    }
}
