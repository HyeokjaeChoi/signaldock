package local.signaldock.consumer

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import local.signaldock.InitResult
import local.signaldock.SignalDock
import local.signaldock.TrackResult

/**
 * Minimal consumer screen: one button calls track() once per tap, one shows diagnostics().
 * Plain Views on purpose; the app exists to prove the published AAR works under R8.
 */
class MainActivity : Activity() {
    private val scope = CoroutineScope(Dispatchers.Main.immediate)
    private lateinit var output: TextView
    private var taps = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        output = TextView(this).apply { textSize = 14f; setTextIsSelectable(true) }
        val track = Button(this).apply { text = "Track consumer_ping"; setOnClickListener { track() } }
        val diag = Button(this).apply { text = "Show diagnostics"; setOnClickListener { diagnostics() } }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "SignalDock consumer (R8 release)"; textSize = 20f })
            addView(track)
            addView(diag)
            addView(ScrollView(context).apply { addView(output) })
            // Target 36 draws edge to edge: keep the content below the status bar.
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(48, 48 + insets.systemWindowInsetTop, 48, 48 + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        diagnostics()
        // Host-cost measurement (issue 36, ADR-0020): `am start --ei burst N` calls track() N times.
        val burst = intent.getIntExtra("burst", 0)
        if (burst > 0) scope.launch {
            val sdk = sdk() ?: return@launch
            for (i in 1..burst) sdk.track("consumer_burst") { property("i", i) }
            Log.i("SignalDockHostCost", "burst_done n=$burst")
        }
    }

    private suspend fun sdk(): SignalDock? = when (val r = (application as ConsumerApplication).init.await()) {
        is InitResult.Success -> r.sdk
        is InitResult.Failure -> { output.text = "init failed: ${r.error}"; null }
    }

    private fun track() = scope.launch {
        val sdk = sdk() ?: return@launch
        taps++
        val result = sdk.track("consumer_ping") { property("tap", taps) }
        output.text = when (result) {
            is TrackResult.Success -> "track: stored locally, eventId=${result.eventId}\n(server receipt: see diagnostics lastAck)"
            else -> "track: $result"
        }
    }

    private fun diagnostics() = scope.launch {
        val sdk = sdk() ?: return@launch
        output.text = "diagnostics:\n" + sdk.diagnostics().toString().replace(", ", ",\n  ")
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
