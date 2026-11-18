package us.lastra.actuator.wear

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import kotlinx.coroutines.channels.Channel
import us.lastra.actuator.ActuatorLink

/**
 * The watch app: the same [ActuatorLink] as the phone, straight from the watch
 * to the actuator, behind a small round UI ([WatchScreen]).
 *
 * As on the phone, the link runs only while the activity is visible. The
 * firmware takes one BLE connection, so the watch lets go of it when the app
 * leaves the screen and the phone or the desktop update tool can have it.
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        link = ActuatorLink(applicationContext) { messages.trySend(it) }

        setContent {
            // The watch face's colors where the system offers them (Wear OS 6).
            MaterialTheme(colorScheme = dynamicColorScheme(LocalContext.current) ?: ColorScheme()) {
                WatchScreen(
                    link = link,
                    messages = messages,
                    onGrantPermission = { requestPermissions.launch(permissions) },
                    onEnableBluetooth = {
                        runCatching { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                    },
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
