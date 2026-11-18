package us.lastra.actuator.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.material3.ColorScheme
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import kotlinx.coroutines.delay
import us.lastra.actuator.DeviceStatus

/**
 * The dial without an actuator, for emulators and screenshots (debug builds
 * only). A stand-in device follows the controls: it moves where it's told, and
 * Sweep runs it end to end once.
 *
 * Extras set the starting state: `--ei angle 625` (tenths), `--ei heat 80`,
 * `--ei cooldown 12` (starts locked and counts down), `--es notice "text"`
 * (opens with a refusal notice up).
 */
class DemoActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val startAngle = intent.getIntExtra("angle", 625)
        val heat = intent.getIntExtra("heat", 20)
        val startCooldown = intent.getIntExtra("cooldown", 0)
        val startNotice = intent.getStringExtra("notice")

        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(LocalContext.current) ?: ColorScheme()) {
                var angle by remember { mutableIntStateOf(startAngle) }
                var driving by remember { mutableStateOf(true) }
                var sweep by remember { mutableIntStateOf(0) } // counts runs; odd while sweeping
                var cooldown by remember { mutableIntStateOf(startCooldown) }
                var notice by remember { mutableStateOf(startNotice) }

                LaunchedEffect(sweep) {
                    if (sweep % 2 == 1) {
                        for (target in listOf(0, 900, 450)) {
                            angle = target
                            delay(900)
                        }
                        sweep++
                    }
                }
                LaunchedEffect(Unit) {
                    while (cooldown > 0) {
                        delay(1000)
                        cooldown--
                    }
                }

                Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                    Dial(
                        status = DeviceStatus(
                            angleTenths = angle,
                            us = 1500,
                            driving = driving,
                            limits = true,
                            locked = cooldown > 0,
                            sweeping = sweep % 2 == 1,
                            heatPct = heat,
                            cooldownS = cooldown,
                        ),
                        onTurn = { angle = it; driving = true },
                        onPreset = { angle = it; driving = true },
                        onSweep = { driving = true; sweep++ },
                        onOff = { driving = false; if (sweep % 2 == 1) sweep++ },
                    )
                    notice?.let { Notice(it) { notice = null } }
                }
            }
        }
    }
}
