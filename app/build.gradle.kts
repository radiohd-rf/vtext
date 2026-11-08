plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "ru.vtext.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "ru.vtext.app"
        minSdk = 24
        targetSdk = 35
        // В CI версия приходит из тега (v0.2.0 → 0.2.0 / 200).
        versionCode = System.getenv("VTEXT_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("VTEXT_VERSION_NAME") ?: "0.1.0"

        ndk {
            // Нейродвижок (PyTorch Lite) поставляется только под arm64:
            // 32-битные библиотеки занимали ещё ~45 МБ APK.
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            val storePath = System.getenv("VTEXT_KEYSTORE_PATH")
            if (!storePath.isNullOrBlank()) {
                storeFile = file(storePath)
                storePassword = System.getenv("VTEXT_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("VTEXT_KEY_ALIAS")
                keyPassword = System.getenv("VTEXT_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (!System.getenv("VTEXT_KEYSTORE_PATH").isNullOrBlank()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
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

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs {
            // libc++_shared.so приходит и из libtorch, и из других AAR.
            pickFirsts += "**/libc++_shared.so"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("androidx.media:media:1.7.0")
    implementation("com.tom-roush:pdfbox-android:2.0.27.0")

    // TorchScript Lite для Silero v5.
    implementation("org.pytorch:pytorch_android_lite:2.1.0")

    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
