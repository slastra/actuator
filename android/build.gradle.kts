// AGP 9 compiles Kotlin itself (no kotlin-android plugin). It ships with KGP
// 2.2.10; the newer KGP on the classpath here is what it uses, and the Compose
// compiler plugin has to match it exactly.
buildscript {
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.5.1" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
