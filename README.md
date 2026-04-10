# AN SDK Android (v0.1, mock)

Мок-SDK без реальной модели. Имитирует ответы и стриминг.

## Подключение через JitPack

В корневом `settings.gradle.kts` приложения:

```kotlin
dependencyResolutionManagement {
    repositories {
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

В `build.gradle.kts` модуля:

```kotlin
dependencies {
    implementation("com.github.AntiNude:an-sdk-android:0.1.0")
}
```

> Замените `AntiNude` на ваш GitHub username/org. Версия = git-тег.

## Использование

```kotlin
import com.an.sdk.ANClient
import kotlinx.coroutines.flow.collect

val client = ANClient(apiKey = "demo")

// suspend
val reply = client.send("Привет")

// stream
client.stream("Расскажи анекдот").collect { chunk ->
    print(chunk)
}
```

## Релиз новой версии

1. `git tag 0.1.0 && git push --tags`
2. Открыть `https://jitpack.io/#AntiNude/an-sdk-android/0.1.0` — JitPack соберёт артефакт.
