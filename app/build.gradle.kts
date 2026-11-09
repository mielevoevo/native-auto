import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.secrets)
    alias(libs.plugins.baselineprofile)
}

/**
 * True once a real Google Maps key is in local.properties. Without one the map pane uses
 * MapLibre + OpenFreeMap (free, no key), so the app has a working map either way.
 */
val hasGoogleMapsKey: Boolean = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val key = props.getProperty("MAPS_API_KEY", "").trim()
    key.isNotEmpty() && key != "DEFAULT_API_KEY"
}

/** 10a: true once a real TomTom key is in local.properties (WIP: the user adds it later). */
val hasTomTomKey: Boolean = run {
    val props = Properties()
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { props.load(it) }
    val key = props.getProperty("TOMTOM_API_KEY", "").trim()
    key.isNotEmpty() && key != "DEFAULT_TOMTOM_KEY"
}

android {
    namespace = "com.nativeauto"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.nativeauto"
        // 26 is the floor for Location.speedAccuracyMetersPerSecond.
        minSdk = 26
        targetSdk = 37
        versionCode = 18
        versionName = "3.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("boolean", "HAS_GOOGLE_MAPS_KEY", hasGoogleMapsKey.toString())
        buildConfigField("boolean", "HAS_TOMTOM_KEY", hasTomTomKey.toString())
    }

    buildTypes {
        release {
            // Personal app, sideloaded: signed with the debug key so a release build installs over
            // the debug one (same signature) on the emulator and the phone. See DECISIONS.md (8d).
            signingConfig = signingConfigs.getByName("debug")
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // Ferrostar uses java.time on API 26+ paths that need the desugared library.
        isCoreLibraryDesugaringEnabled = true
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        // MapLibre ships Timber, so lint suggests it for every Log call. We log via Log on purpose.
        disable += "LogNotTimber"
    }
}

secrets {
    // Real key lives in local.properties (never committed). The defaults file keeps
    // fresh checkouts and CI building with a placeholder key.
    propertiesFileName = "local.properties"
    defaultPropertiesFileName = "local.defaults.properties"
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    // Installs the baseline profile (8d) so hot paths are precompiled from the first launch.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.datastore.preferences)

    implementation(libs.maps.compose)
    implementation(libs.play.services.maps)
    implementation(libs.maplibre.android)
    implementation(libs.play.services.location)
    implementation(libs.ferrostar.core)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    // Ferrostar brings both; declared because the app calls them directly (search, routing client).
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.espresso.core)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
