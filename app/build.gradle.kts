plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.example.carapp"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.carapp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.core.ktx)
    implementation(libs.material)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // 开源虚拟摇杆（横屏控制用）。该库最新发布版本为 1.10.1，1.10.2 并未在 JCenter/Maven Central 发布过
    implementation("io.github.controlwear:virtualjoystick:1.10.1")
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}