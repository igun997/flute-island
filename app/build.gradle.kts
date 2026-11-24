plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.igun997.fluteisland"
    compileSdk = 35

    defaultConfig {
        applicationId = "dev.igun997.fluteisland"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        // Only the ABIs real phones ship with; keeps the apk small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // Public Jira OAuth settings (client id + token-proxy address). Both are set in gradle.properties;
        // the client SECRET never lives in the app, only in the proxy (backend/jira-auth-proxy).
        val jiraClientId = providers.gradleProperty("fluteJiraClientId").orElse("").get()
        val jiraAuthProxy = providers.gradleProperty("fluteJiraAuthProxy").orElse("").get()
        buildConfigField("String", "JIRA_CLIENT_ID", "\"$jiraClientId\"")
        buildConfigField("String", "JIRA_AUTH_PROXY", "\"$jiraAuthProxy\"")
    }

    buildTypes {
        release {
            // Signed with the debug key so a release build can be sideloaded for testing performance.
            // Swap in a real keystore (signingConfigs) before distributing.
            signingConfig = signingConfigs.getByName("debug")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    // "staging" is release-grade (minified, not debuggable, so it performs like the real thing) but keeps the
    // debug-only remote control from src/devtools, which scripts/e2e.sh and scripts/jank.sh need.
    buildTypes {
        create("staging") {
            initWith(getByName("release"))
            matchingFallbacks += "release"
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    listOf("debug", "staging").forEach { name ->
        sourceSets.getByName(name) {
            java.srcDir("src/devtools/kotlin")
            res.srcDir("src/devtools/res")
            manifest.srcFile("src/devtools/AndroidManifest.xml")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/*.version", "DebugProbesKt.bin", "kotlin-tooling-metadata.json")
    }
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-service:2.8.7")
    implementation("androidx.savedstate:savedstate-ktx:1.2.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.airbnb.android:lottie-compose:6.6.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
    testImplementation("org.json:json:20240303")
}
