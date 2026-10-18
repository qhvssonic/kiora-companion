# Kiora Companion

独立的 Android 应用，作为宿主集成 Kiora SDK。界面采用简洁的单列会话、顶部历史入口和底部附件输入区，使用 Kiora 自有名称与图标。

## 功能

- 配置 Chat Completions 兼容模型和电脑端 A2A Agent Card；模型站点地址可只填 `https://example.com`。
- 纯文字消息通过 Kiora `AgentRuntime` 运行，模型可按 Agent Card 中的 skill 调用电脑端能力。
- 通过系统选择器选照片或文件，确认目标 Agent 和文件名后，使用 Kiora 附件模块读取并发送 A2A `raw` Part。
- 显示远端处理进度和结果；电脑端要求补充信息时，下一条消息继续同一远端任务。
- 本地保存会话列表与文本记录；附件内容不保存在会话中。模型密钥和 A2A Token 只在当前进程中保留。

附件总大小上限为 10 MiB。电脑端 Agent 必须能接收 A2A 1.x HTTP+JSON 或 JSONRPC，并处理 `raw` 附件。Debug APK 允许局域网 HTTP；正式构建应使用 HTTPS。

## 构建与运行

先在 `../kiora` 发布 SDK 0.3.0-SNAPSHOT 到本机 Maven：

```powershell
cd ..\kiora
.\gradlew.bat :core:publishToMavenLocal :a2a:publishToMavenLocal :model-chat:publishToMavenLocal :android-attachments:publishToMavenLocal
```

再构建独立应用：

```powershell
cd ..\kiora-companion
.\gradlew.bat :app:assembleDebug
```

APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。安装后点右上角设置，填写模型地址与 ID、或电脑端 Agent Card 地址。选择附件时必须配置电脑端 Agent Card。发送前应用会展示目标与所选文件，确认后才上传。

## SDK 集成位置

`app/build.gradle.kts` 从 `mavenLocal()` 引用 Kiora 四个模块。`CompanionAgent` 将模型、A2A、会话存储、工具授权和文件读取组合起来；`MainActivity` 只负责界面、系统选择器及发送确认。

当前版本处理文本与小型内联附件；较大文件、远端返回文件下载、后台任务通知与跨进程安全保存密钥留待后续版本。
