import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Signing credentials are read from environment variables or an untracked
// signing.properties file. Nothing secret is stored in this repository.
val signingProps = Properties().apply {
    val f = File(rootProject.projectDir, "signing.properties")
    if (f.exists()) FileInputStream(f).use { load(it) }
}
fun signingValue(env: String, key: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: signingProps.getProperty(key)?.takeIf { it.isNotBlank() }

val releaseKeystore = signingValue("FB_KEYSTORE", "keystore")
val releaseAlias = signingValue("FB_KEY_ALIAS", "alias")
val releaseStorePass = signingValue("FB_STORE_PASS", "storePass")
val releaseKeyPass = signingValue("FB_KEY_PASS", "keyPass")
val signingReady = listOf(releaseKeystore, releaseAlias, releaseStorePass, releaseKeyPass).all { it != null }

android {
    namespace = "com.netherish.flashbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.netherish.flashbridge"
        minSdk = 26
        targetSdk = 36
        versionCode = 9
        versionName = "1.8"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (signingReady) {
            create("release") {
                storeFile = File(releaseKeystore!!)
                storePassword = releaseStorePass!!
                keyAlias = releaseAlias!!
                keyPassword = releaseKeyPass!!
            }
        }
    }

    // `full` is the primary/F-Droid build with every advanced capability.
    // `play` is a restricted variant: advanced operations are compiled out.
    flavorDimensions += "distribution"
    productFlavors {
        create("full") {
            dimension = "distribution"
            buildConfigField("boolean", "ADVANCED_FEATURES", "true")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "ADVANCED_FEATURES", "false")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingReady) signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/*")
        }
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    val composeBom = platform("androidx.compose:compose-bom:2026.02.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.5.0-alpha15")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    testImplementation("junit:junit:4.13.2")
}
