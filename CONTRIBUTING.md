# 参与开发

## 本地环境

使用 JDK 17、Android SDK Platform 36 和 Build Tools 35.0.0。
通过项目的 Gradle Wrapper 构建；SDK 路径和构建命令见 [README](README.md#开发与构建)。
Windows 使用 `gradlew.bat`。

## 提交前

- 从 `main` 创建工作分支，保持一次提交或 PR 聚焦一个问题。
- 延续现有 Kotlin、原生 Android Views 和无框架网页结构；界面约定见 [DESIGN.md](DESIGN.md)。
- 修复行为问题时添加针对性测试，尤其是上传授权、文件保留和临时文件清理。
- 执行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`。
- 修改上传网页后执行 `node --check app/src/main/assets/app.js`，并验证窄屏、键盘、连续上传和失败重试。
- 修改电视界面或安装流程后，在目标设备检查遥控器焦点、返回、Home、授权和系统安装器；自动化检查不能代替真机验收。

PR 请说明改动原因、验证结果及未验证项。报告问题时附复现步骤、应用版本、系统版本和必要的脱敏截图。

## 不应提交的文件

不要提交 SDK 路径、APK、构建目录、签名密钥、连接凭证、用户上传文件或调试转储。
测试报告和 APK 由 Actions 保存为短期构建产物；正式发行签名密钥不进入仓库。
