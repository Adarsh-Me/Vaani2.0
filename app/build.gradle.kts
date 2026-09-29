plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.itantra.walkie"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.itantra.walkie"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "2.5-peer-proximity"
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
    }
    buildTypes {
        debug { isMinifyEnabled = false }
        release {
            isMinifyEnabled = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.4.8" }
    packagingOptions { jniLibs { useLegacyPackaging = false } }
    testOptions { unitTests.all { it.testLogging.showStandardStreams = true } }
    androidResources {
        // One suffix, not a glob: aapt2 matches noCompress against the end of the asset path, so
        // this stores exactly the SraVaani encoder and lets Bundled.mapped() map it out of the
        // APK instead of extracting a 477 MB second copy. Storing *every* .onnx would add 224 MB
        // to the download to save nothing, because the other six fit buffer() as they are.
        noCompress += "qdq.onnx"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.10.1")
    implementation("androidx.activity:activity-compose:1.7.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.6.2")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    testImplementation("junit:junit:4.13.2")
}
