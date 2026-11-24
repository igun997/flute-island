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
        // CI derives both from the release tag (v1.2.3 -> 1.2.3 / 10203); local builds fall back to the defaults.
        versionCode = providers.gradleProperty("fluteVersionCode").map(String::toInt).getOrElse(10000)
        versionName = providers.gradleProperty("fluteVersionName").getOrElse("1.0.0")
        // Only the ABIs real phones ship with; keeps the apk small.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }

        // Public Jira OAuth settings (client id + token-proxy address). Both are set in gradle.properties;
        // the client SECRET never lives in the app, only in the proxy (backend/jira-auth-proxy).
        val jiraClientId = providers.gradleProperty("fluteJiraClientId").orElse("").get()
        val jiraAuthProxy = providers.gradleProperty("fluteJiraAuthProxy").orElse("").get()
        buildConfigField("String", "JIRA_CLIENT_ID", "\"$jiraClientId\"")
        buildConfigField("String", "JIRA_AUTH_PROXY", "\"$jiraAuthProxy\"")
    }

    // A real keystore is used when FLUTE_KEYSTORE_FILE (+ _PASSWORD, FLUTE_KEY_ALIAS, FLUTE_KEY_PASSWORD) are set, which is
    // what the release workflow does. Without them the build is signed with the machine's debug key: fine to sideload and
    // test, but it cannot update an app that was signed with the real key.
    val releaseKeystore = System.getenv("FLUTE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() && file(it).exists() }
    if (releaseKeystore != null) {
        signingConfigs.create("release") {
            storeFile = file(releaseKeystore)
            storePassword = System.getenv("FLUTE_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("FLUTE_KEY_ALIAS")
            keyPassword = System.getenv("FLUTE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
