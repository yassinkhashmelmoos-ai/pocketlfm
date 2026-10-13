plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.jetbrains.kotlin.android)
    id("com.google.devtools.ksp") version "1.9.22-1.0.17"
}

android {
    namespace = "com.thunder.agi"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.thunder.agi"
        minSdk = 26
        targetSdk = 36
        versionCode = 4          // FIX: incremented — must be higher than any previously installed APK
        versionName = "1.4"      // human-readable version shown in About / Play Store

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += "-O3 -fPIC -std=c++17"
                cFlags   += "-O3 -fPIC"
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    androidResources {
        noCompress += listOf("gguf", "zip", "bin", "onnx", "tflite", "pb", "pt", "gz")
    }

    signingConfigs {
        // FIX: both debug and release use the SAME keystore so APK updates never
        // conflict. Create thunder_agi.jks once and commit it (or store in GitHub secrets).
        // If thunder_agi.jks is absent the build falls back to the auto debug key.
        create("release") {
            val ksFile = rootProject.file("thunder_agi.jks")
            if (ksFile.exists()) {
                storeFile     = ksFile
                storePassword = System.getenv("KS_PASS") ?: "thunder2026"
                keyAlias      = System.getenv("KS_ALIAS") ?: "thunder"
                keyPassword   = System.getenv("KS_KEY_PASS") ?: "thunder2026"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val ksFile = rootProject.file("thunder_agi.jks")
            signingConfig = if (ksFile.exists())
                signingConfigs.getByName("release")
            else
                signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isMinifyEnabled = false
            val ksFile = rootProject.file("thunder_agi.jks")
            signingConfig = if (ksFile.exists())
                signingConfigs.getByName("release")   // same key as release → always updatable
            else
                signingConfigs.getByName("debug")
            externalNativeBuild {
                cmake { cppFlags += "-g" }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose     = true
        buildConfig = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.8" }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")

    val roomVersion = "2.6.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    annotationProcessor("androidx.room:room-compiler:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.json:json:20231013")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
