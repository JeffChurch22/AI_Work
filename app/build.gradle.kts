plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.trifold.xrealxr"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.trifold.xrealxr"
        minSdk = 31
        targetSdk = 34
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            isDebuggable = true
        }
    }

    buildFeatures {
        viewBinding = true
        aidl = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    lint {
        // ShizukuSpatialManager deliberately reaches MotionEvent.setDisplayId
        // through HiddenApiBypass runtime exemptions (addHiddenApiExemptions
        // runs at init). Lint cannot see runtime exemptions, so it flags the
        // reflective access as BlockedPrivateApi and fails the release build.
        // Disable that single check; every other lint check stays on.
        disable += setOf("BlockedPrivateApi")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // Shizuku API for privileged VirtualDisplay creation & InputManager injection without root
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")

    // HiddenApiBypass to invoke MotionEvent.setDisplayId() & IInputManager.injectInputEvent()
    implementation("org.lsposed.hiddenapibypass:hiddenapibypass:4.3")
}
