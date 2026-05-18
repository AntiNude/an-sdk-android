# AN SDK Android (v0.1, mock)

Mock SDK without a real model. Simulates responses and streaming.

## Install via JitPack

In your app's root `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

In the module's `build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.AntiNude:an-sdk-android:0.1.0")
}
```

> Replace `AntiNude` with your GitHub username/org. The version matches the git tag.

## Usage

```kotlin
import com.an.sdk.ANClient
import kotlinx.coroutines.flow.collect

val client = ANClient(apiKey = "demo")

// suspend
val reply = client.send("Hello")

// stream
client.stream("Tell me a joke").collect { chunk ->
    print(chunk)
}
```

## Releasing a new version

1. `git tag 0.1.0 && git push --tags`
2. Open `https://jitpack.io/#AntiNude/an-sdk-android/0.1.0` — JitPack will build the artifact.
