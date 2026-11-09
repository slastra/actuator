plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "us.lastra.actuator"
    // Material3 1.5 (the Expressive components) needs compileSdk 37 and AGP 9.1+.
    compileSdk = 37

    defaultConfig {
        applicationId = "us.lastra.actuator"
        // Android 13: the modern BluetoothGatt write/notify APIs, no legacy paths.
        minSdk = 33
        targetSdk = 37
        versionCode = 3
        versionName = "0.3"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    // Ahead of the BOM (which pins 1.4.0): 1.5 is where Material 3 Expressive
    // (button groups, loading indicator, expressive motion) is public.
    implementation("androidx.compose.material3:material3:1.5.0-rc01")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.13.0")
}
