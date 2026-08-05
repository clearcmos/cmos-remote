import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

// Release signing is opt-in. With android/keystore.properties present (never
// committed - see android/.gitignore) `assembleRelease` produces a signed APK;
// without it the release build stays unsigned, so a clean clone still builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.clearcmos.cmosremote"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.clearcmos.cmosremote"
        minSdk = 26
        targetSdk = 35
        // Overridable from the release workflow (-PversionCode / -PversionName)
        // so a tagged release stamps its own version without editing this file.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            // Null when no keystore.properties is present: unsigned release build.
            signingConfig = signingConfigs.findByName("release")
            // R8 stays off: Compose and Glance survive it only with rules this
            // project has never tested on a device. Turn it on deliberately.
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests.all {
            // The wire format is pinned by files under spec/ that the server
            // test suite asserts against too; hand the directory to the tests
            // as an absolute path so they do not depend on the working
            // directory Gradle happens to pick.
            it.systemProperty("spec.dir", rootProject.file("../spec").absolutePath)
            it.testLogging {
                events("failed", "skipped")
            }
        }
    }
}

// Replaces the android { kotlinOptions { jvmTarget } } block: that DSL is
// deprecated in Kotlin 2.1 and a hard error from 2.4, which a dependabot PR
// bumping the Kotlin plugin surfaced before it could bite.
kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }
}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Jetpack Glance for widgets
    implementation("androidx.glance:glance-appwidget:1.1.1")
    implementation("androidx.glance:glance-material3:1.1.1")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // DataStore for settings persistence
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Kotlin Serialization for JSON
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.0")

    // OkHttp for HTTP requests
    implementation("com.squareup.okhttp3:okhttp:5.4.0")

    // WorkManager for background sync
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Unit tests (JVM, no device or emulator)
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:5.4.0")
}
