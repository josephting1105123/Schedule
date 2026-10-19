plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

android {
    namespace = "com.EntropicSoftwareLab.Schedule"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.EntropicSoftwareLab.Schedule"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "1.3.0"
        // CI stamps the commit onto the version ("1.1.0 (f8ac917)"), shown in Settings → About, so
        // the phone can say which build it is running — the version alone stays the same across
        // many builds. Local builds carry no suffix.
        System.getenv("GITHUB_SHA")?.let { versionNameSuffix = " (${it.take(7)})" }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing comes from the environment, so no keystore or password lives in the repo.
    // With any of the four unset, release falls back to the debug signing config.
    val releaseStoreFile = System.getenv("SIGNING_STORE_FILE")
    val releaseStorePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val releaseKeyAlias = System.getenv("SIGNING_KEY_ALIAS")
    val releaseKeyPassword = System.getenv("SIGNING_KEY_PASSWORD")
    val hasReleaseSigning = listOf(
        releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword,
    ).all { !it.isNullOrEmpty() }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(releaseStoreFile!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.kotlinx.coroutines.android)

    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    debugImplementation(libs.androidx.ui.tooling)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.glance.appwidget)
    implementation(libs.androidx.glance.material3)

    testImplementation(libs.junit)
    testImplementation(libs.org.json)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
