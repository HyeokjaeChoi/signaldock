package local.signaldock.consumer

import android.app.Activity
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Baseline for the host-cost measurement (issue 36, ADR-0020): the same views as the SDK variant,
 * with no SDK dependency and no SDK call. The buttons do nothing.
 */
class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val output = TextView(this).apply { textSize = 14f; setTextIsSelectable(true); text = "no SDK" }
        val track = Button(this).apply { text = "Track consumer_ping" }
        val diag = Button(this).apply { text = "Show diagnostics" }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 48, 48, 48)
            addView(TextView(context).apply { text = "SignalDock consumer (R8 release)"; textSize = 20f })
            addView(track)
            addView(diag)
            addView(ScrollView(context).apply { addView(output) })
            setOnApplyWindowInsetsListener { v, insets ->
                v.setPadding(48, 48 + insets.systemWindowInsetTop, 48, 48 + insets.systemWindowInsetBottom)
                insets
            }
        }
        setContentView(root)
        if (intent.getIntExtra("burst", 0) > 0) Log.i("SignalDockHostCost", "burst_done n=0 (no SDK)")
    }
}
