plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

/**
 * Phase 8d: generates the app's baseline profile (startup, maximize, swap, settings, layout switch)
 * and measures frame timing for the same moves. Generate: `./gradlew :app:generateBaselineProfile`.
 * Measure: `./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`.
 */
android {
    namespace = "com.nativeauto.baselineprofile"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // Profile generation without root needs API 33+; 28 is the macrobenchmark floor.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Runs on the emulator (no phone install, by the workspace rules). Numbers there are only
        // relative: compare before / after on the same emulator.
        testInstrumentationRunnerArguments["androidx.benchmark.suppressErrors"] = "EMULATOR"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    targetProjectPath = ":app"
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
