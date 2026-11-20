import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    id("app.cash.paparazzi") version "1.3.5"
}

android {
    namespace = "com.disp06.rc.mobile"
    compileSdk = 35

    defaultConfig {
        // Must match the phone app: the Wearable Data Layer only connects apps with the
        // same applicationId AND the same signing certificate.
        applicationId = "com.disp06.researchcompanion"
        minSdk = 28
        targetSdk = 34
        versionCode = 2
        versionName = "0.1.1"
    }

    // CI injects a stable keystore (RC_KEYSTORE_*) so watch + phone APKs are always signed
    // with the same key; otherwise the local debug key is used for both.
    val ks = System.getenv("RC_KEYSTORE_FILE")?.let { file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (ks != null) create("shared") {
            storeFile = ks
            storePassword = System.getenv("RC_KEYSTORE_PASSWORD")
            keyAlias = System.getenv("RC_KEY_ALIAS")
            keyPassword = System.getenv("RC_KEY_PASSWORD")
        }
    }

    buildTypes {
        debug { if (ks != null) signingConfig = signingConfigs.getByName("shared") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName(if (ks != null) "shared" else "debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    buildFeatures { buildConfig = true; compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    lint { abortOnError = false; checkReleaseBuilds = false }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.work.runtime)
    implementation(libs.play.services.wearable)
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.play)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    testImplementation(libs.junit)
}
