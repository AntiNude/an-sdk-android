plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    `maven-publish`
}

android {
    namespace = "com.an.sdk"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // ONNX Runtime Android — same major as the iOS SDK uses (1.24.x), so
    // model behavior stays comparable. `api` so consumers don't have to add
    // the dep themselves to catch `OrtException`.
    api("com.microsoft.onnxruntime:onnxruntime-android:1.24.2")
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.github.AntiNude"
                artifactId = "an-sdk-android"
                version = "0.9.0"
            }
        }
    }
}
