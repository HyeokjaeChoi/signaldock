package local.signaldock.measureapp

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import local.signaldock.measure.MeasureRunner

/**
 * Runs one measurement phase in this (R8-minified) app process:
 *   adb shell am instrument -w -e phase <name> -e endpoint <url> -e key <key> -e runId <id> \
 *       local.signaldock.measureapp/.MeasureInstrumentation
 * Result code -1 (RESULT_OK) means the phase finished; anything else carries an `error` entry.
 */
class MeasureInstrumentation : Instrumentation() {
    private var args = emptyMap<String, String>()

    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        args = arguments?.keySet()?.associateWith { arguments.getString(it).orEmpty() }.orEmpty()
        start()
    }

    override fun onStart() {
        super.onStart()
        val out = Bundle()
        val code = try {
            if (MeasureRunner(targetContext, args) { sendStatus(0, it) }.run()) Activity.RESULT_OK
            else { out.putString("error", "unknown phase"); Activity.RESULT_CANCELED }
        } catch (t: Throwable) {
            out.putString("error", t.toString())
            Activity.RESULT_CANCELED
        }
        finish(code, out)
    }
}
