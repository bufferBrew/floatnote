plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
    id("androidx.room")
}

// Exported schemas are committed so future migrations can be written and tested against them.
room {
    schemaDirectory("$projectDir/schemas")
}

android {
    namespace = "com.bufferbrew.floatnote"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.bufferbrew.floatnote"
        // 26 is the floor for TYPE_APPLICATION_OVERLAY.
        minSdk = 26
        targetSdk = 37
        // One place for the version: VERSION_CODE / VERSION_NAME in gradle.properties
        // (CI can override them with ORG_GRADLE_PROJECT_ env vars).
        versionCode = providers.gradleProperty("VERSION_CODE").get().toInt()
        versionName = providers.gradleProperty("VERSION_NAME").get()
    }

    // Signing secrets: read from ~/.gradle/gradle.properties locally, or ORG_GRADLE_PROJECT_*
    // env vars in CI. Never commit them. Release signing is configured only when all are present.
    val keystorePathProp = providers.gradleProperty("FLOATNOTE_KEYSTORE_PATH").orNull
    val storePasswordProp = providers.gradleProperty("FLOATNOTE_STORE_PASSWORD").orNull
    val keyAliasProp = providers.gradleProperty("FLOATNOTE_KEY_ALIAS").orNull
    val keyPasswordProp = providers.gradleProperty("FLOATNOTE_KEY_PASSWORD").orNull
    val hasSigningSecrets = keystorePathProp != null && storePasswordProp != null &&
        keyAliasProp != null && keyPasswordProp != null

    signingConfigs {
        if (hasSigningSecrets) {
            create("release") {
                storeFile = file(keystorePathProp!!)
                storePassword = storePasswordProp
                keyAlias = keyAliasProp
                keyPassword = keyPasswordProp
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Falls back to an unsigned APK if the secrets aren't configured.
            signingConfig = if (hasSigningSecrets) signingConfigs.getByName("release") else null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    // Robolectric tests (Room migrations and DAOs) need resources, and the exported schemas as
    // assets. Robolectric only sees the app's assets, so the schemas go into debug builds (a few
    // KB of JSON); release builds don't carry them.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    sourceSets {
        getByName("debug").assets.srcDir("$projectDir/schemas")
    }
}

dependencies {
    // Compose BOM keeps all Compose library versions in sync.
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)

    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.11.0")
    implementation("androidx.activity:activity-compose:1.12.4")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")

    implementation("androidx.room:room-runtime:2.8.5")
    implementation("androidx.room:room-ktx:2.8.5")
    ksp("androidx.room:room-compiler:2.8.5")

    implementation("com.squareup.okhttp3:okhttp:4.12.1")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("io.mockk:mockk:1.14.7")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.1")
    // Android's org.json is a stub in local unit tests; this is the real implementation.
    testImplementation("org.json:json:20260814")
    testImplementation("org.robolectric:robolectric:4.16")
    testImplementation("androidx.test:core-ktx:1.7.0")
    testImplementation("androidx.room:room-testing:2.8.5")
}
