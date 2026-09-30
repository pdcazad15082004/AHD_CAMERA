plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    sourceSets {
        getByName("main") {
            jniLibs.srcDirs("libs")
        }
    }
    namespace = "com.example.ahdcamera"
    compileSdk = 37

    defaultConfig {
        ndk {
            abiFilters.addAll(setOf("arm64-v8a", "armeabi-v7a"))
        }
        applicationId = "com.example.ahdcamera"
        minSdk = 25
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("systemPlatform") {
            storeFile = file("platform.jks")
            storePassword = "zyz123456"
            keyAlias = "zyzsign"
            keyPassword = "zyz123456"
        }

        create("release") {
            val storeFilePath = providers.gradleProperty("RELEASE_STORE_FILE").orNull
            val storePass = providers.gradleProperty("RELEASE_STORE_PASSWORD").orNull
            val kAlias = providers.gradleProperty("RELEASE_KEY_ALIAS").orNull
            val kPass = providers.gradleProperty("RELEASE_KEY_PASSWORD").orNull

            if (!storeFilePath.isNullOrEmpty()) {
                storeFile = file(storeFilePath)
                storePassword = storePass
                keyAlias = kAlias
                keyPassword = kPass
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("systemPlatform")
        }
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }


    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    packaging {
        jniLibs {
            pickFirsts.addAll(setOf("**/lib*.so", "**/libc++_shared.so"))
        }
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(files("libs/gson-2.7.jar"))
    implementation(files("libs/qcar_encoder.aar"))
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation("androidx.compose.material:material-icons-extended")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Accompanist Permissions
    implementation("com.google.accompanist:accompanist-permissions:0.34.0")

    // Coil Image & Video Thumbnail Loader
    implementation("io.coil-kt:coil-compose:2.6.0")
    implementation("io.coil-kt:coil-video:2.6.0")

    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
