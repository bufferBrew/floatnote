// Generates the app's Baseline Profile and measures startup with it.
//   Generate: ./gradlew :app:generateBaselineProfile   (needs a device or emulator, API 28+)
//   Measure:  ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
plugins {
    id("com.android.test")
    id("androidx.baselineprofile")
}

android {
    namespace = "com.bufferbrew.perch.baselineprofile"
    compileSdk = 37

    defaultConfig {
        // Baseline Profile generation needs API 28+; the app itself still supports 26.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.3.0")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.5.0")
}
