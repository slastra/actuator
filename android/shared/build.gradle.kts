// What the phone and watch apps have in common: the BLE link and the launcher icon.
plugins {
    id("com.android.library")
}

android {
    namespace = "us.lastra.actuator.shared"
    compileSdk = 37

    defaultConfig {
        // Android 13 / Wear OS 4: the modern BluetoothGatt write/notify APIs, no legacy paths.
        minSdk = 33
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Snapshot state only (mutableStateOf). No composables here, so no compiler plugin.
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.runtime:runtime")
}
