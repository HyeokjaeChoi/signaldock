package local.signaldock.demo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import local.signaldock.demo.core.ui.SignalDockTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val client = (application as DemoApplication).client
        setContent { SignalDockTheme { DemoApp(client) } }
    }
}
