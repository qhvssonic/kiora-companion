# Kiora Companion

**English** | [简体中文](README.md)

Kiora Companion is an Android host application built on the [Kiora SDK](https://github.com/qhvssonic/kiora). The current app version is **0.1.103** and uses SDK **`0.3.0-SNAPSHOT`**. It brings on-device models, document scanning, gallery search, local tools, authorization, and A2A collaboration into one conversation. The demonstration uses a computer-hosted DSH agent, but A2A peers are not limited to computers.

The code is licensed under [Apache-2.0](LICENSE). Model weights, native AARs, and the DSH plugin have separate upstream licenses.

## What the app does

- Local face enrollment, multiple samples per person, deletion, 1:N candidate search and face-based photo search are available from the sidebar or settings. Import the official `buffalo_sc` directory separately; its weights are for non-commercial research only. Templates stay in private storage and are never automatically added to chat or uploaded. Gallery indexing respects current photo permissions. Results require user verification; no liveness or calibrated authentication guarantee is provided. See [face integration](https://github.com/qhvssonic/kiora/blob/main/docs/face-recognition.md).

  Person and gallery queries support a live camera scan with an animated guide. One clear, centered face must remain consistent across multiple preview frames before querying automatically. Frames are processed in memory without taking or saving a photo. Enrollment still requires explicit capture or photo selection. Leaving the scanner releases the camera; temporal consistency is not liveness detection.

  The sidebar has a face-scan outline icon; the page uses the app's grouped cards and bottom sheets. Person templates and the multi-face photo index are stored in private SQLite `databases/faces.db`. Version 0.1.103 passed 52 JVM tests and three opt-in device regressions. Live front-camera person and photo queries and subsequent camera release were smoke-tested on V2509A / Android 16. These results do not establish population-level accuracy or large-gallery performance; see the [validation record](https://github.com/qhvssonic/kiora/blob/main/docs/face-recognition.md#验证).

- **Choose a model:** run a user-imported LiteRT-LM `.litertlm` model, an MNN model directory, or a Chat Completions-compatible API. LiteRT-LM offers CPU/GPU selection; MNN runs inside the app process without MNN Chat or a local HTTP service. MNN native libraries are currently packaged for `arm64-v8a` only.
- **Scan and prepare documents:** capture multiple pages or import several images, reorder and review pages, correct local PP-OCRv6 results, and save a draft. The agent can write corrected text into a Markdown file before sending that completed file to an A2A peer.
- **Search the phone gallery:** import the EmbeddingGemma 2 text-and-vision model, build a local vector index, and ask for photos in natural language. Chat shows the top three candidates as photos; the settings test search can inspect more results in score order. A photo is sent to the current vision model only when its content needs analysis.
- **Delegate to other agents:** configure multiple A2A connections by Agent Card URL or QR code. Each connection may have a unique trigger alias, enabled skills, its own Bearer Token, and a live connection status. The agent can send text and authorized small file attachments to a reachable peer.
- **Keep work continuous:** persist conversations, SDK session branches, tool traces, A2A `contextId`, and unfinished remote `taskId` in Room. Pure-text messages submitted while a run is active become queued follow-ups.
- **Use local speech input:** import SenseVoice Small int8 files and transcribe a short recording on-device. The transcript goes into the composer for review before it is sent.
- **Read replies aloud offline:** import the pinned MeloTTS directory (FP32 recommended; int8 also supported) in Settings, tap the speaker icon on an assistant reply; reading aloud is a manual action and is not exposed as an agent tool. Playback can be stopped; audio is neither saved nor sent to an A2A peer. FP32 synthesis has been measured on the V2509A; broader device and audio quality validation is pending.

The settings page separates on-device providers from API providers. The chat UI includes conversation search, a model switcher, an attachment composer, tool-call details, Markdown rendering, and image preview with pinch zoom. These are Companion features built on the SDK, not features supplied automatically by `AgentRuntime`.

## Models and local data

LiteRT-LM models are imported as `.litertlm` files. MNN models are imported as complete directories containing `config.json`, weights, and tokenizer files; configured vision models also need `visual.mnn` and its weight file. Imported models are copied to app-private storage. A model stored in another application's `Android/data` directory may not be selectable through Android's file picker; move it to a picker-visible location first.

The gallery model is imported under **Settings → Phone files and gallery**. It is an EmbeddingGemma 2 text-and-vision `.litertlm` model used through the SDK's MediaPipe Universal Embedder adapter. Gallery indexing reads photos covered by the current Android permission, in batches of at most 100, and stores IDs, version metadata, and vectors in an app-private SQLite database. Original images remain in the system gallery. Deleted, changed, or no-longer-accessible photos are excluded from stale search results. Indexing does not itself upload photos.

The chat model's vision capacity is independent of A2A attachment count. The current LiteRT integration passes one image per turn; MNN vision models and compatible online models can receive up to eight if their model packages and endpoints support them. A selected gallery photo may be transmitted as an A2A file without using a slot in the chat model's image input. The current Gemma 4 E2B test package has a default 4,096-token context. The optional context management limit in settings protects Kiora's input budget; it does not enlarge the model's actual context window. Earlier turns can be summarized through the SDK while original session records remain stored.

OCR and speech are separate on-device adapters. PP-OCRv6 requires a locally prepared Paddle Android AAR and ONNX model files. SenseVoice Small int8 requires separately imported `model.int8.onnx` and `tokens.txt`. MeloTTS uses a separate, pinned FP32 or int8 model directory prepared by the SDK's `scripts/prepare_melotts.py`; Companion checks the hashes during import. Weights are not part of the APK. See the SDK's [OCR guide](https://github.com/qhvssonic/kiora/blob/main/docs/document-ocr.md), [recognition guide](https://github.com/qhvssonic/kiora/blob/main/docs/speech-recognition.md), and [synthesis guide](https://github.com/qhvssonic/kiora/blob/main/docs/speech-synthesis.md) (currently in Chinese).

The models used by the project are listed in the [ModelScope bundle](https://www.modelscope.cn/models/vqhvqh/kiora_model_bundles). Check each model's own license and compatibility requirements.

## Document-to-A2A workflow

1. The user captures or imports one or more pages, checks their order, and corrects the OCR text on the phone.
2. The corrected text enters the chat. For a request to prepare a Markdown document, the agent uses the workspace `write` tool; the file must actually exist before it becomes an upload candidate.
3. Companion binds the generated file to a controlled ID and validates the file and the recipient skill's declared file input. An approval request is shown according to the chosen authorization mode.
4. The A2A skill sends the completed document as an inline attachment. The recipient's task result is returned to the same phone conversation.

This ordering matters: mentioning `document.md` in a reply does not create it, and sending the scan image to a peer is not equivalent to completing a requested phone-side document preparation step. For a single-page scan, the original image can remain available as local context; a multi-page draft does not send all page images as one vision-model turn. The original scan does not replace the finished Markdown attachment unless the user explicitly requests the image.

## Gallery search and file handoff

In chat, a request such as “find photos of cups in my gallery” can call the read-only `search_album_photos` tool. The conversation shows ranked images rather than a raw list of IDs and scores. Multiple results appear as a collapsible stack; users can expand, preview, zoom, and select one or more actual photos. They may then add the selection to the composer and ask an enabled A2A peer to save or process the files.

Companion keeps file references distinct: `gallery:<photoId>` addresses a gallery item for local reading, `selected:<index>` addresses an attachment selected for the current turn, and `workspace:<relativePath>` addresses an existing workspace file. A displayed filename is not a file ID. Before showing an upload approval, the host validates the IDs, current read permission, file existence, total size, and target skill input modes. User-selected files are bound by the app; the model is not trusted to invent or translate IDs. A2A inline attachments are limited to **10 MiB total**.

An Android `content://` URI cannot be sent as if it were a remotely readable path. After authorization, Companion reads the file locally and the SDK sends its bytes in an A2A `raw` part with a filename and media type. Once a recipient receives the attachment, that agent may read it according to its own task and permissions.

## A2A connections, permissions, and sessions

Add an Agent Card URL in **Settings → Connected A2A agents**, or scan a supported connection QR code. The app discovers the card's skills and creates a tool for each skill. A skill must advertise a file MIME input to accept `files`; `application/json` enables an optional `form` argument. An Agent Card skill is a capability declaration, not a separate RPC endpoint. A connection may be named and given a unique trigger alias: writing “through dsh” exposes the matching connection's skills rather than every configured peer.

The settings page checks the Agent Card and advertised task endpoint when opened and about every 15 seconds while visible. “Connected” means those endpoints were reachable at the time of the check; a real skill can still fail because of task-specific permissions, server errors, or network changes. The app preserves remote task status and displays errors rather than treating discovery as proof of execution.

For each local conversation and remote connection, Companion reuses the `contextId` returned by the peer so later completed tasks can share remote context. An unfinished task is resumed with its `taskId`. Keeping these identifiers separate avoids creating unrelated remote conversations when the user refers to “the previous file.” A2A needs a reachable network; on-device scanning, OCR, gallery indexing, and local model use can run without a remote connection when their models and data are already present.

The tool authorization settings offer three modes: ask for each call; the default mode, which automatically permits configured read-only local tools but asks before modification, commands, or uploads; and full access to configured tools. The OS permission boundary still applies in every mode. API keys and A2A Bearer Tokens are encrypted separately using Android Keystore before app-private persistence. If an online chat model is selected, prompts and some tool-returned metadata may be sent to that provider; local indexing does not make the entire app offline in every configuration.

### Connecting to a DSH A2A service

In DSH `@ryubyte/dsh-a2a`, create an external Agent service, publish it, and copy its full Agent Card URL into Companion. If inbound Bearer authentication is enabled, enter the matching token for that connection. The Base URL advertised by DSH must be reachable from the phone: `localhost` on the phone refers to the phone itself. The default DSH task endpoint path is `/a2a`.

The installed `@ryubyte/dsh-a2a` 0.3.1 plugin ignores incoming `raw` file parts by default. This repository contains `scripts/patch-dsh-a2a-attachment-bridge.mjs` to validate and stage such attachments before DSH invokes its agent, and `scripts/configure-dsh-a2a-delegate.mjs` to configure a general file-capable skill. These scripts change the **DSH installation**, are not part of the Companion build, and must be reviewed again after a plugin upgrade. DSH may receive readable local attachment files; Kiora's upload authorization does not prevent the recipient from examining their contents.

## Build and run

Requirements: **JDK 17**, Android SDK with `compileSdk 35`, a sibling checkout of the [Kiora SDK](https://github.com/qhvssonic/kiora), and the optional native artifacts used by OCR and speech. Publish SDK `0.3.0-SNAPSHOT` modules to `mavenLocal()` before building Companion. From the SDK directory:

```powershell
cd ..\kiora
.\gradlew.bat :core:publishToMavenLocal :a2a:publishToMavenLocal :model-chat:publishToMavenLocal :model-mnn:publishToMavenLocal :model-litert:publishToMavenLocal :embedding:publishToMavenLocal :face:publishToMavenLocal :face-onnx:publishToMavenLocal :embedding-mediapipe:publishToMavenLocal :android-attachments:publishToMavenLocal :android-storage-tools:publishToMavenLocal :document-ocr:publishToMavenLocal
.\scripts\install-sherpa-onnx.ps1
.\gradlew.bat :speech:publishToMavenLocal :speech-sherpa-onnx:publishToMavenLocal -PsherpaLocalRepo=..\artifacts\maven
.\scripts\install-paddle-ocr.ps1
.\gradlew.bat :document-ocr-paddle:publishToMavenLocal -PpaddleLocalRepo=..\artifacts\maven
```

Then build the app from its repository:

All `dev.kiora` SDK modules resolve from `mavenLocal()`. The optional `kioraLocalRepo` supplies third-party native dependencies and excludes old SDK snapshots, so it cannot silently replace a current LiteRT adapter with one compiled against an incompatible runtime.

```powershell
cd ..\kiora-companion
python -m pip install --target ..\artifacts\py-lief lief==1.0.0
$env:PYTHONPATH = (Resolve-Path ..\artifacts\py-lief).Path
python .\scripts\install-onnxruntime-shared.py
.\gradlew.bat :app:assembleDebug -PkioraLocalRepo=..\artifacts\maven
```

The ONNX Runtime preparation script verifies the expected upstream AARs, adapts the OCR bridge's ELF symbol requirement, and removes its duplicate runtime library. The resulting local Maven artifact lets PP-OCRv6 and SenseVoice use a shared sherpa-onnx ONNX Runtime 1.28.2 native library. It is not safe to resolve duplicate native libraries by letting Gradle arbitrarily `pickFirst` one. An arm64-only debug build may use `-PocrCompareArm64Only`; it will not install on an x86 emulator. `-PcompanionPreview` produces a separate preview package with its own app-private data, rather than upgrading the installed main app.

The APK is generated at `app/build/outputs/apk/debug/app-debug.apk`. In the installed app, configure a model first. Add an A2A Agent Card only if remote capabilities are needed. The UI can import local models and scan documents from the chat composer. Model files and some native artifacts must be supplied separately as described above.

For day-to-day updates to a configured test phone, use an overwrite install such as `adb install -r` and back up data you need to preserve. One instrumented-test run in this project uninstalled the target app, deleting its private conversations, settings, grants, and imported models. Running `connectedDebugAndroidTest` on the only configured device therefore requires care.

## Implementation and current limits

`CompanionAgent` assembles SDK models, tools, authorization, A2A connections, and session storage. `MainActivity` handles the user interface, pickers, and approvals. Room stores the conversation list, rendered messages, SDK session tree, remote contexts, and pending task IDs; the database has explicit migrations from version 1 through 6. Selected attachment bytes are not stored in the session record. The app includes JVM and Android instrumented tests for tool selection, file IDs, scanning handoff, storage, Room, A2A context recovery, OCR, speech, and model coexistence.

The current product is oriented toward text and small inline files. It does not implement large-file streaming, remote returned-file downloads, or background remote-task notifications. A target agent must support the advertised A2A 1.x transport, `raw` attachments when files are sent, and the requested operation. Debug builds permit local-network HTTP; production deployments should use HTTPS.
