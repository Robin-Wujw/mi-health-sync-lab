import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Tag-driven releases: -PversionName=1.2.3 -PversionCode=1002003
// or env VERSION_NAME / VERSION_CODE. Local defaults identify the current HealthLab build.
fun appVersionProp(name: String, envKey: String, default: String): String =
    (findProperty(name) as String?)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }
        ?: default

val appVersionName: String = appVersionProp("versionName", "VERSION_NAME", "1.0.6-healthlab.40")
val appVersionCode: Int = appVersionProp("versionCode", "VERSION_CODE", "1000046").toInt()
val releaseStorePath = providers.environmentVariable("ANDROID_RELEASE_STORE_FILE").orNull.orEmpty()
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull.orEmpty()
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull.orEmpty()
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull.orEmpty()
val hasReleaseSigning = listOf(releaseStorePath, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it.isNotBlank() }

android {
    namespace = "com.bettermifitness.sync"
    // API 37 is a preview platform published as android-37.0; minorApiLevel
    // points AGP at platforms/android-37.0 instead of android-37.
    compileSdk {
        version = release(37) {
            minorApiLevel = 0
        }
    }

    defaultConfig {
        applicationId = "com.bettermifitness.sync.healthlab"
        minSdk = 28
        targetSdk = 36
        versionCode = appVersionCode
        versionName = appVersionName
        testApplicationId = "com.bettermifitness.sync.healthlab.test"
        testInstrumentationRunner = "com.bettermifitness.sync.lab.HealthLabInstrumentation"
    }

    // A separately installed, same-signer test APK operates on the user's release
    // installation. The production APK stays non-debuggable and gains no exported
    // test Activity/receiver. Tests run only through an explicit adb am instrument.
    testBuildType = "release"

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(releaseStorePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }
    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    // Keep all moko locales in the APK/AAB (system language can switch without redownload).
    bundle {
        language {
            enableSplit = false
        }
    }
}

abstract class RequireAndroidReleaseSigning : DefaultTask() {
    @get:Input abstract val signingConfigured: Property<Boolean>

    @TaskAction
    fun requireSigning() {
        check(signingConfigured.get()) { "Release signing requires all ANDROID_RELEASE_* environment variables." }
    }
}

tasks.register<RequireAndroidReleaseSigning>("requireReleaseSigning") {
    signingConfigured.set(hasReleaseSigning)
}

tasks.matching { task ->
    task.name in setOf("preReleaseBuild", "preReleaseAndroidTestBuild", "validateSigningRelease")
}.configureEach {
    dependsOn("requireReleaseSigning")
}

// AGP 9 built-in Kotlin: configure JVM target without kotlin-android plugin.
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":composeApp"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.health.connect)
    androidTestImplementation(libs.koin.core)
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.kotlinx.datetime)
    androidTestImplementation(libs.datastore.preferences.core)
    androidTestImplementation("com.squareup.okio:okio:3.18.2")
    androidTestImplementation(project(":miclient"))
}
