package us.lastra.actuator

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.channels.Channel

/**
 * Hosts [ActuatorLink] and the Compose UI ([ActuatorScreen]).
 *
 * The link runs only while the activity is visible: the firmware takes one BLE
 * connection, and holding it in the background would lock out the desktop
 * update tool.
 */
class MainActivity : ComponentActivity() {

    private val messages = Channel<String>(Channel.BUFFERED)
    private lateinit var link: ActuatorLink
    private var askedOnce = false

    private val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)

    private val requestPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted.values.all { it }) link.start() else link.needsPermission()
        }

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        link = ActuatorLink(applicationContext) { messages.trySend(it) }

        setContent {
            val context = LocalContext.current
            // Material You: colours from the wallpaper, light or dark with the system.
            val colors = if (isSystemInDarkTheme()) dynamicDarkColorScheme(context)
                         else dynamicLightColorScheme(context)
            MaterialExpressiveTheme(colorScheme = colors, motionScheme = MotionScheme.expressive()) {
                ActuatorScreen(
                    link = link,
                    messages = messages,
                    onGrantPermission = { requestPermissions.launch(permissions) },
                    onEnableBluetooth = { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) },
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        if (permissions.all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }) {
            link.start()
        } else if (!askedOnce) {
            askedOnce = true
            requestPermissions.launch(permissions)
        } else {
            link.needsPermission()
        }
    }

    override fun onStop() {
        super.onStop()
        link.stop()
    }
}
