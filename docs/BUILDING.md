# Сборка и проверка

[← README](../README.ru.md) · [English](../README.md)

Команды ниже выполняются из корня репозитория в Linux или WSL. Нужны JDK 17, Android SDK 35, build-tools 35, Python 3, Git, curl и unzip. Путь к SDK задаётся в игнорируемом `local.properties` либо через `ANDROID_HOME`.

```sh
git clone https://github.com/whoxllm/OrcaMicro.git
cd OrcaMicro
```

## Быстрая debug-сборка

```sh
ORCAMICRO_ORCA_DIR=/path/to/orca bash native/scripts/fetch-orca.sh --profiles-only
# Быстрый вариант: опубликованный ARM64-движок OrcaMicro, проверяется по SHA-256.
bash scripts/fetch-prebuilt-engine.sh
sh ./gradlew :core:test :app:assembleDebug :app:assembleDebugAndroidTest \
  :app:lintDebug -PorcaResourcesDir=/path/to/orca/resources \
  -PsourceUrl=https://github.com/whoxllm/OrcaMicro --max-workers=2
```

## Сборка движка из исходников

Опубликованный native-артефакт может истечь. Тогда соберите движок из сохранённых исходников; дополнительно нужны CMake, Ninja, компилятор C/C++, NDK r27c и несколько часов. Используйте полную копию OrcaSlicer в отдельной папке, а не sparse checkout из быстрой сборки:

```sh
export ANDROID_NDK_ROOT=/path/to/ndk/27.2.12479018
export ORCAMICRO_ORCA_DIR=/path/to/orca-full
export ORCAMICRO_JOBS=2
bash native/scripts/fetch-orca.sh
bash native/scripts/build-deps.sh
bash native/scripts/build-engine.sh app/src/main/jniLibs
sh ./gradlew :app:assembleDebug \
  -PorcaResourcesDir="$ORCAMICRO_ORCA_DIR/resources" --max-workers=2
```

Исходники native bridge сохранены без изменений. Опубликованный движок собран в CI OrcaMicro на коммите `d92230eafe9a876baafa6ce623451d57ffa8fb5f`; `scripts/prebuilt-native.sha256` проверяет, что дерево `native/` с тех пор не менялось. SHA-256 ZIP: `58988793c158e9e117557958ead7e9571c9cad70ca2be1ceec8afc066cd5e7d3` (проверен по GitHub API).

## Каталог настроек и тесты

Перегенерация каталога и русских сообщений читает исходники через Git и работает также с profiles-only checkout (дополнения переводов хранятся в `scripts/ru-overrides.json`):

```sh
python3 scripts/generate-options.py /path/to/orca
```

Чистые JVM-тесты без Android SDK:

```sh
sh ./gradlew :core:test -PcoreOnly -PorcaResourcesDir=/path/to/orca/resources
```

Тесты настоящей нарезки на Android 14 ARM64 или x86_64 Google APIs с трансляцией ARM64:

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w \
  io.github.whoxllm.orcamicro.test/androidx.test.runner.AndroidJUnitRunner
```

Пакет приложения — `io.github.whoxllm.orcamicro`, как и Kotlin/JNI namespace. Опциональный `-PsourceUrl=https://github.com/OWNER/REPO` включает ссылку на исходники в экране «О программе».

## Где искать APK

После сборки файл находится в `app/build/outputs/apk/debug/app-debug.apk`. В GitHub Actions подписанный release-APK лежит в артефакте `OrcaMicro-apk`.

## Подпись и обновления

Локальные debug-сборки и сборки разных CI-запусков могут иметь разные подписи. Для обновления поверх установленного приложения нужна та же подпись; `versionCode` не должен уменьшаться. Для постоянных обновлений используйте один ключ. Конфигурация release-подписи в `app/build.gradle.kts` принимает `ORCAMICRO_KEYSTORE`, `ORCAMICRO_KEYSTORE_PASSWORD` и `ORCAMICRO_KEY_ALIAS`. Сам ключ остаётся вне репозитория. Без этих переменных release-сборка использует debug-ключ.

## Жесты

`SceneGestureTest` отправляет события в настоящие Android GestureDetector и ScaleGestureDetector: длинный перенос после двойного тапа вверх/вниз не должен менять матрицу камеры; щипок двумя пальцами должен менять масштаб, обычный свайп — вращать камеру.

## Публикация релиза
Релизы публикует workflow `build.yml` после тестов и проверки нарезки на эмуляторе. Ключ подписи хранится только в секретах репозитория (`ORCAMICRO_KEYSTORE_BASE64`, `ORCAMICRO_KEYSTORE_PASSWORD`); без них APK подписывается временным ключом и не публикуется.

- Каждый push в `main` обновляет пре-релиз `latest` с файлом `OrcaMicro.apk`.
- Тег `vВЕРСИЯ` публикует стабильный релиз с файлом `OrcaMicro-arm64-vВЕРСИЯ.apk`. Только такие релизы предлагает встроенный обновлятор.

```sh
git tag v1.1.0
git push origin v1.1.0
```

Версия внутри APK берётся из тега, `versionCode` — из номера запуска Actions, поэтому он всегда растёт. Обновлятор использует SHA-256 `digest` GitHub Release asset и проверяет подпись относительно установленного приложения.
