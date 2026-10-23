import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Only credentials supplied for the permanent certificate can sign Release builds.
// Missing credentials never fall back to the Android debug keystore.
val unsignedRelease = providers.gradleProperty("unsignedRelease").isPresent
val releaseKeystore = providers.environmentVariable("ANDROID_RELEASE_KEYSTORE_FILE").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_RELEASE_STORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_RELEASE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_RELEASE_KEY_PASSWORD").orNull
val releasePin = groovy.json.JsonSlurper().parse(rootProject.file("config/release-signing.json")) as Map<*, *>

android {
    namespace = "com.tanhaowen.contextenglish"
    compileSdk = 35
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "com.tanhaowen.contextenglish"
        minSdk = 26
        targetSdk = 35
        versionCode = 8
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true
    }

    signingConfigs {
        create("permanentRelease") {
            storeFile = releaseKeystore?.let { file(it) }
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            storeType = "JKS"
        }
    }

    buildTypes {
        release {
            signingConfig = if (unsignedRelease) null else signingConfigs.getByName("permanentRelease")
            isDebuggable = false
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

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    androidResources { noCompress += listOf("mp3", "wav", "ogg") }

    testOptions { unitTests.isIncludeAndroidResources = true }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// AGP can omit validateSigningRelease when credentials are incomplete, so this
// independent task guards preReleaseBuild even when AGP would build unsigned.
val verifyPermanentReleaseSigning = tasks.register("verifyPermanentReleaseSigning") {
    doLast {
        if (!unsignedRelease) {
            val required = mapOf(
                "ANDROID_RELEASE_KEYSTORE_FILE" to releaseKeystore,
                "ANDROID_RELEASE_STORE_PASSWORD" to releaseStorePassword,
                "ANDROID_RELEASE_KEY_ALIAS" to releaseKeyAlias,
                "ANDROID_RELEASE_KEY_PASSWORD" to releaseKeyPassword
            )
            val missing = required.filterValues { it.isNullOrBlank() }.keys
            check(missing.isEmpty()) {
                "Permanent Release signing credentials missing: ${missing.joinToString()}. Restore the existing v1.0 key; do not generate a replacement. Use -PunsignedRelease=true only for explicitly unsigned validation builds."
            }
            check(releaseKeyAlias == releasePin["key_alias"]) { "Release alias does not match the permanent signing identity." }
            val keyFile = file(requireNotNull(releaseKeystore))
            check(keyFile.isFile) { "Permanent Release keystore is unavailable. Restore the existing backup." }
            val keystore = KeyStore.getInstance("JKS")
            try {
                keyFile.inputStream().use { keystore.load(it, requireNotNull(releaseStorePassword).toCharArray()) }
                check(keystore.getKey(releaseKeyAlias, requireNotNull(releaseKeyPassword).toCharArray()) is PrivateKey)
                val certificate = requireNotNull(keystore.getCertificate(releaseKeyAlias))
                val actual = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
                    .joinToString("") { "%02x".format(it) }
                check(actual == releasePin["certificate_sha256"]) { "Release certificate differs from the permanent v1.0 certificate. Signing stopped." }
            } catch (error: Exception) {
                throw GradleException("Permanent Release key verification failed. Check the existing key, passwords and pinned public certificate; no fallback key will be used.")
            }
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyPermanentReleaseSigning)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    testImplementation("org.robolectric:robolectric:4.14.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}
