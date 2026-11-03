# Contributing to Kiora Companion

English and Chinese issues are welcome. The app is experimental; small reproducible reports are useful even when you are not ready to change code.

## Try the app first

- Download the [preview APK](https://github.com/qhvssonic/kiora-companion/releases/tag/v0.1.109) and read its model/import requirements.
- Watch the [demo](https://www.bilibili.com/video/BV1txn26eEo2/) for the intended document/photo workflows; it shows an earlier build.
- Use the device/model report form for both successful and failed setups. Only claim the behavior you observed on the configuration you describe.

## Useful first contributions

- Clarify a model import step or fix a broken guide link.
- Translate a small section with its code/permission boundaries intact.
- Report an app lifecycle, permission or tool-result problem with non-private sample data.
- Propose a user task and its local/remote boundary before suggesting a new model.

## Build and check a change

Follow the [English build guide](docs/app-guide.en.md#build-and-run) or [中文构建指南](docs/app-guide.zh-CN.md#构建与运行). SDK snapshots must be published locally and the speech/OCR native artifacts prepared before building this app. From the app repository, with those dependencies ready:

```powershell
./gradlew.bat :app:testDebugUnitTest :app:assembleDebug '-PkioraLocalRepo=../artifacts/maven'
```

For changes to runtime/model interfaces, see [Kiora SDK](https://github.com/qhvssonic/kiora) and its [contributing guide](https://github.com/qhvssonic/kiora/blob/main/CONTRIBUTING.md). Discuss large API or workflow changes in an issue first.

Prefer overwrite installation (`adb install -r`) when updating a same-signed test build. Do not run `connectedDebugAndroidTest` on your only configured phone: a previous run uninstalled the target and erased its private data. Use a disposable device/profile or a deliberately planned manual test installation.

Do not commit model weights, APKs, signing keys or local configuration. Remove keys, tokens, private media, face templates and unrelated conversations from reports. Use mock transports for protocol tests rather than paid APIs or real remote tasks.
