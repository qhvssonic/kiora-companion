# Kiora Companion

独立的 Android 应用，作为宿主集成 Kiora SDK。当前应用版本为 0.1.82，依赖 SDK `0.3.0-SNAPSHOT`。界面提供可搜索的会话侧边栏、顶部模型选择入口和底部附件输入区，使用 Kiora 自有名称与图标。

许可证：[Apache-2.0](LICENSE)。上游模型、Android AAR 和 DSH 插件分别遵循其各自的许可证。

## 功能

- 在设置页选择 DeepSeek、OpenAI、LiteRT-LM 本地模型、MNN 本地模型或其他兼容服务。聊天顶部显示当前模型；云端预置模型可直接切换。
- LiteRT-LM 可导入 `.litertlm` 文件并选择 CPU/GPU。MNN 可导入包含 `config.json`、权重和 tokenizer 的整个模型目录；两种模型都复制到应用私有存储，并在 Companion 进程中推理，无需 MNN Chat 应用或本机 API 服务。MNN 支持文本与流式输出；包含视觉配置、`visual.mnn` 和 `visual.mnn.weight` 的模型可接收图片。工具选择通过受限 JSON 提示协议实现；原生库目前只打包 arm64-v8a。
- 相册检索使用 SDK 的 EmbeddingGemma 2 适配器。首次使用时，在「设置 → 手机文件与相册」导入官方文字＋视觉 440M `.litertlm` 模型，通过已有的「相册访问」入口授予完整相册或系统选定照片的读取权限，并在设置中分批建立索引，每批最多 100 张。之后直接在聊天中描述想找的照片；首页和侧边栏的「搜索本地相册」会在输入框填入“搜索相册中的图片：”，供用户补充条件后发送。Agent 调用只读 `search_album_photos`，最多返回 3 张排序候选；单张照片直接展示，多张照片默认叠放，可展开逐张查看和放大，不会把已索引总数当作匹配数。仅当用户要求分析照片内容时，Agent 才需要调用 `read(gallery:gallery_<数字ID>)`，将该照片交给视觉模型。索引位于应用私有 SQLite 数据库，只保存照片 ID、版本信息和向量，原图仍由系统相册管理；搜索前会剔除当前已不可访问或已修改照片的旧向量。搜索不会自动扫描或上传照片；若聊天使用云端模型，查询文字及工具返回的照片名称会进入该模型的请求。模型不进入仓库或 APK；模型文件说明及 SHA-256 见 SDK 的 `docs/embeddinggemma2-integration.md`。

Android 不允许应用直接读取另一个应用的 `Android/data` 目录。已有模型若存放在那里，先移到系统文件选择器可见的位置再导入；调试时也可以通过 ADB 复制到 Kiora 的私有 `files/models` 目录，设置页会自动发现该目录中的 `.litertlm` 文件。MNN 模型可通过目录选择器导入；调试时完整复制到私有 `files/mnn-models/<模型目录>/` 后，设置页也会发现其中的 `config.json`。
- 相册搜索结果可展开并勾选要发送的照片；单张结果可直接点「发送到电脑」。选择已启用且设有触发别名的 A2A 连接后，照片先加入输入框附件，默认指令为保存到电脑桌面，用户可修改并点击发送。此路径把照片当作文件附件，不占用本机模型的单图视觉输入名额；实际上传仍遵循 A2A 工具授权与合计 10 MiB 限制。
- 必须配置手机端使用的模型；电脑端 A2A Agent Card 是可选的工具来源，不是聊天模型或独立聊天模式。自定义模型站点地址可只填 `https://example.com`，SDK 会补全 `/v1/chat/completions`。
- 设置页可手动添加或扫码添加多个 A2A Agent Card，分别命名、设置唯一的触发别名、启用、停用、编辑和删除；旧版单连接会自动迁移。旧连接若名为 `DSH Agent`，会自动得到别名 `dsh`。聊天中写“通过 dsh”时，只向模型提供该连接的 A2A 技能。接受 `type: "a2a-agent-connect"`、`v: 1` 的二维码 JSON，校验 `agentCardUrl` 与 `token` 后打开连接编辑框；`user`、`scopes` 供核对，扫码本身不授予工具权限。每个连接的 Bearer Token 单独加密存储。不同连接公布的 skill 使用不同工具名，模型与授权界面可分清目标 Agent。
- 设置页显示每个 A2A 连接的状态，页面打开时和停留期间约每 15 秒检测一次，也可手动立即检测。检测会读取 Agent Card 并检查其公布的任务端口，不创建远端任务；“已连通”表示网络和 Card 可达，不保证远端工具执行成功。若聊天 Agent 此前发现连接失败，从设置页返回时会重新发现已启用连接，使网络恢复后的状态同步到聊天。
- 聊天中的文件指代由智能体结合会话判断。宿主只提供本会话最近成功写入、且仍存在的工作区文件 ID 作为候选；候选不自动上传。A2A 技能要求模型说明文件位于手机、已经位于远端，或本任务不涉及文件。手机文件必须通过 `files` 参数附上，文件路径由宿主核验；仅在回复中提到文件名不视为已创建文件。
- 纯文字消息通过 Kiora `AgentRuntime` 运行，模型可按 Agent Card 中的 skill 调用电脑端能力。
- 任务运行时，继续发送纯文字会直接加入 follow-up 队列。当前任务正常完成后，App 会逐条启动排队指令并重新解析各自的连接别名与文件引用。输入框上方显示队列计数胶囊，点开可查看待处理指令；点某条的“引导”会取消当前手机端运行，并从该队列项立即启动新一轮，且不会重复执行它；点“×”可移除未开始的指令。停止后队列保留，点“继续”从首条恢复。发送和停止共用右下角按钮：输入框有文字时用于发送，输入框为空且任务运行中、队列非空时才显示停止图标。排队暂不支持附件；停止不会撤销已经执行的工具操作，也不等同于取消电脑端 A2A 任务。
- 内置 `get_local_time` 小工具，可在端侧模型模式下验证模型选择工具、参数校验、执行工具和继续回答的完整循环。
- 默认启用 `read`、`write`、`edit`；`shell` 需在设置页单独启用。`read` 可读取工作区相对路径、`selected:<index>` 指向的本轮已选文本或图片、`authorized:<folderId>/<path>` 指向的已授权文本或图片，以及 `gallery:<photoId>` 指向的照片；图片作为工具结果直接提供给当前视觉模型。`browse_phone_files` 负责发现已授权目录、文件和相册照片；`manage_authorized_file` 负责复制、移动、重命名、删除已授权文件，并在默认授权档逐次询问。底层仍通过 Android 系统接口核验目录与相册权限。系统只授予部分照片时不会声称能够浏览完整相册；聊天中手动选择的照片可直接用于本轮识别，无需逐张授权。
- 电脑端 Agent Card 公布的每项 skill 才会生成对应 A2A 工具；不再预制笼统的附件发送工具。skill 声明可接收文件 MIME 类型后，模型可在该 skill 的 `files` 参数中选择本轮已选文件、已授权文件或应用工作区文件，并通过 `fileSource` 区分手机文件、远端已有文件和无文件任务。本轮选择的文件用 `selected:<index>`；工作区文件用 `workspace:<相对路径>` 或相对路径。`workspace:0` 不能代替 `selected:0`。宿主会校验本机文件确实附上，远端已有文件则保持对先前远端工作的引用；不会按固定中文词组改写用户任务。App 在弹出上传授权前核验文件 ID；无效 ID 作为参数错误返回模型，不显示可允许的授权弹窗。声明 `application/json` 后才会出现 `form` 参数。只有电脑端 Agent 实际公布并实现了相应能力，文件和表单调用才可成功。
- 模型调用工具时，对话中会显示可折叠的工具记录，展开可查看参数与结果；调用状态写入 Room，重新打开会话仍可查看。
- 设置页提供三档工具授权：**请我批准**（每次询问）、**帮我批准**（默认，自动执行工作区、已授权目录及已开放相册的只读工具；修改、命令和上传每次询问）、**完全访问权限**（自动执行已配置的工具）。电脑端 A2A 能力目前按风险未知处理；普通远端工具可按会话允许，附件上传工具在默认档每次都需确认目标、文件名和任务。会话授权只在当前应用进程中有效，重新保存连接设置后会清除；Android 权限与文件授权仍然生效。
- 助手回复以 Android 原生文本渲染 Markdown，支持标题、列表、链接、引用、代码块、表格、删除线和任务清单；流式回复与历史会话使用同一排版。
- 聊天中通过「＋」选择或直接拍摄的照片可在发送前或发送后全屏预览，支持双指缩放、拖动和双击复位；发送时会缩放后作为图片内容送入支持视觉的当前模型；本机 LiteRT-LM 最长边 512 像素，MNN 视觉模型和在线兼容接口最长边 1024 像素。当前 Kiora 的 LiteRT 接入每轮只传一张图片，MNN 视觉模型和在线模型一次最多八张；对应模型包必须包含视觉能力。本机模型会在较早对话达到宿主的资源预算时调用 SDK 持久摘要，保留原始会话与分支；MNN 不必手填上下文窗口。设置页的可选上限只控制 Kiora 超限保护，不修改模型自身的上下文能力；Gemma 4 E2B 当前测试包默认 4096 tokens。自动摘要无法完成或单轮过长时，已设置窗口的模型仍使用输入裁剪作为后备保护。OpenAI 预置模型使用已知窗口，其他兼容服务可手动填写。非图片附件的内容不进入模型首轮请求；智能体可用 `read(selected:<index>)` 查看受支持的文本，或在文件能力存在且任务需要时通过该 skill 发送。
- 聊天输入栏的「＋」可选择“扫描文档”：连续拍摄页面或一次导入多张图片，使用 Kiora SDK 的 PP-OCRv6 small 适配器在本机逐页离线识别。扫描页可切换、调整顺序、删除，点按原图可放大查看，逐页校对文字并保存草稿；进入聊天时按页次填入校对后的原文。多页扫描的原图保留在扫描草稿中，不作为同一轮视觉输入；单页扫描在没有其他待发图片时仍附上原图。旧版单页草稿仍可读取。也可单独“拍照”进行视觉提问；这一路径的照片临时存于缓存，发送成功后清理。
- 「选择文件」只列出应用工作区和设置中已授权目录里的文件，不提供任意手机文件选择器；「选择照片」和拍照是独立的用户主动选择入口。纯照片观察问题直接使用随消息附上的视觉输入；要求保存、发送等操作时仍提供相应工具。
- 扫描文字进入聊天时，工具列表仍由宿主配置、Android 授权和电脑端 Agent Card 决定，不因扫描或附图场景缩减。工作区写入只作用于应用私有工作区，并继续遵循工具授权方式。
- 输入栏麦克风支持按键录音（最长 30 秒），使用 Kiora SDK 的 `speech-sherpa-onnx` 在本机做 SenseVoice Small int8 最终转写。设置页需分别导入同一 sherpa-onnx 模型包的 `model.int8.onnx` 与 `tokens.txt`；权重只保存在应用私有存储，不进入 APK。转写文字先进入输入框，用户核对或修改后才发送。原始录音不保存到会话。
- 显示远端处理进度和结果；电脑端要求补充信息时，下一条消息继续同一远端任务。
- 同一个手机会话对同一个 A2A 连接复用服务端返回的 `contextId`，让多次已完成的任务进入同一电脑端会话；需要补充信息或授权的未完成任务仍用 `taskId` 续接。不同手机会话、不同连接分别保存上下文。升级前的会话会优先恢复最早任务的上下文；若历史结果未保存上下文且服务端已清除任务记录，会停止发送并明确提示，避免悄悄创建第三个会话。
- 使用同一个 Room 数据库保存会话列表、界面文本记录、SDK 会话树、A2A 连接上下文及待继续的任务 ID；附件内容不保存在会话中。模型 API Key 按提供商、Bearer Token 单独用 Android Keystore 加密后保存在应用私有存储，重启应用仍可使用；在设置中留空并保存可清除。

会话数据直接写入 Room，不读取旧版 SharedPreferences 对话或 JSONL 会话。Room 会保存 SDK 返回的实际模型 ID 和可用的 token 用量；回复后和重开会话时可在状态栏查看最近一次模型调用。OpenAI、DeepSeek 预置云端接口请求流式用量，其他兼容服务仅在自身返回用量时显示；LiteRT-LM 只记录模型身份，MNN 记录原生引擎报告的输入与生成 token 数。A2A 任务需要输入或授权时，工具记录和状态栏显示等待状态，并保存远端 task ID 供下一轮继续。数据库当前为版本 6，提供从版本 1 到 6 的逐级迁移；结构定义保存在 `app/schemas/`。

附件总大小上限为 10 MiB。电脑端 Agent 必须能接收 A2A 1.x HTTP+JSON 或 JSONRPC，并处理 `raw` 附件。Debug APK 允许局域网 HTTP；正式构建应使用 HTTPS。

### 连接 DSH A2A

在 DSH `@ryubyte/dsh-a2a` 的「对外服务」中创建 Agent，默认端点路径为 `/a2a`。保存并上线后，复制完整的 Agent Card URL，在 Companion 设置的「连接的 A2A Agent」中添加；手机与电脑应能通过该 URL 互访。若 DSH 开启入站 Bearer 鉴权，在 Companion 对应连接中填同一令牌。DSH 的 Base URL 应指向手机可访问的电脑地址；仅当自动推断的地址可从手机访问时才可留空。`localhost` 和 `127.0.0.1` 在手机上指向手机自己。

安装版 `@ryubyte/dsh-a2a` 0.3.1 默认忽略 raw 文件 Part。本仓库 `scripts/patch-dsh-a2a-attachment-bridge.mjs` 在入站执行器调用 DSH Agent 前校验并暂存附件，仅把用户指令与本地文件路径、MIME、大小和 SHA-256 交给模型；文件的保存目标或分析方式仍由 DSH Agent 按用户指令决定。`scripts/configure-dsh-a2a-delegate.mjs` 配置通用 `delegate_task` 技能及文件输入类型，保留原 Bearer Token 和出站连接。本机的独立副本、原版与桥接版快照、设计及升级说明保存在仓库外的 `../DSH-A2A-通用附件桥接/`；插件升级后须先核对新版本，再移植补丁。这些 DSH 脚本不属于 Companion 的构建步骤。

## 构建与运行

需要 JDK 17、Android SDK（`compileSdk 35`），以及与本仓库同级检出的 `kiora` SDK。先在 `../kiora` 发布 SDK 0.3.0-SNAPSHOT 到本机 Maven。Paddle OCR 适配器还需要按 SDK 的 OCR 文档构建官方 AAR；若 AAR 不在脚本的默认路径，给 `install-paddle-ocr.ps1` 传入 `-Aar` 绝对路径。SenseVoice 模型权重不进入仓库或 APK，运行时由用户导入。

```powershell
cd ..\kiora
.\gradlew.bat :core:publishToMavenLocal :a2a:publishToMavenLocal :model-chat:publishToMavenLocal :model-mnn:publishToMavenLocal :model-litert:publishToMavenLocal :embedding:publishToMavenLocal :embedding-mediapipe:publishToMavenLocal :android-attachments:publishToMavenLocal :android-storage-tools:publishToMavenLocal :document-ocr:publishToMavenLocal
.\scripts\install-sherpa-onnx.ps1
.\gradlew.bat :speech:publishToMavenLocal :speech-sherpa-onnx:publishToMavenLocal -PsherpaLocalRepo=..\artifacts\maven
.\scripts\install-paddle-ocr.ps1
.\gradlew.bat :document-ocr-paddle:publishToMavenLocal -PpaddleLocalRepo=..\artifacts\maven
```

再构建独立应用：

```powershell
cd ..\kiora-companion
python -m pip install --target ..\artifacts\py-lief lief==1.0.0
$env:PYTHONPATH = (Resolve-Path ..\artifacts\py-lief).Path
python .\scripts\install-onnxruntime-shared.py
.\gradlew.bat :app:assembleDebug -PkioraLocalRepo=..\artifacts\maven
```

脚本校验官方 ONNX Runtime 1.28.0 Android AAR 和 sherpa-onnx 1.13.8 AAR 的 SHA-256，借助 LIEF 将 OCR Java/JNI 桥接库的 ELF 符号版本要求改为 `VERS_1.28.2`，并移除其自带的 ONNX Runtime 原生库。本地 Maven 生成 `1.28.0-kiora-ort1.28.2`，使 PP-OCRv6 和 SenseVoice 共用 sherpa-onnx 提供的 ONNX Runtime 1.28.2；Java/JNI 代码仍来自官方 1.28.0 AAR。不能以 Gradle 的 `pickFirst` 随机选一份同名库。独立预览版已在同一手机进程中通过 OCR → SenseVoice → OCR 测试。默认调试 APK 含四种 ABI。

只在 arm64 手机测试时，可给构建命令加 `-PocrCompareArm64Only`，显著减小 APK；这份包不能安装到 x86 模拟器。

需要在同一手机上验证新界面而保留已安装应用数据时，可在构建命令中加 `-PcompanionPreview`。这会生成包名为 `dev.kiora.companion.preview` 的独立调试版；它有自己的私有数据和模型目录，不会读取正式版的数据。验证结束后去掉该参数重新构建，产物恢复为正式包名。

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。在聊天输入栏点「＋」→「扫描文档」，拍摄或导入多页后，可在页码导航中逐页查看原图和 PP-OCRv6 结果、修改文字、调整页序，再用于聊天。安装后点右上角设置，选择提供商和模型，填写密钥；如需电脑端能力，再配置 A2A Agent Card。聊天顶部可切换当前提供商的预置模型，左上角可搜索与切换历史会话。只有 Agent Card 声明了相应文件输入能力、模型调用该 skill 并通过工具授权时，App 才会上传文件。

设备测试应使用独立测试机或模拟器。Android Gradle 的 `connectedDebugAndroidTest` 在本项目的一次真机测试后卸载了目标 App；其私有会话、设置、授权信息和已导入模型随卸载清除。日常更新已配置的手机应使用 `adb install -r` 覆盖安装，并在测试前另行备份需要保留的数据。

## SDK 集成位置

`app/build.gradle.kts` 引用 Kiora SDK 模块；`settings.gradle.kts` 先查找 `mavenLocal()` 中最新发布的 SDK，再使用 `-PkioraLocalRepo` 指定的本地仓库补齐第三方依赖。修改 SDK `core` 后先运行 `:core:publishToMavenLocal`，再构建 Companion。`CompanionAgent` 将模型、A2A、会话存储、工具授权和文件读取组合起来；`MainActivity` 负责界面、系统选择器及发送确认。

当前版本处理文本与小型内联附件；较大文件、远端返回文件下载和后台任务通知留待后续版本。

聊天中的“选择文件”只浏览应用工作区和“连接设置 → 授权目录”里仍有效的目录授权。工作区默认可选；其他目录须先在设置中通过 Android 授权。拍照和单次选择照片是独立的用户主动选择入口。发送后输入框上的附件立即清空，图片在发送前和会话记录中显示缩略图；记录中的缩略图保存在本机应用私有目录。
