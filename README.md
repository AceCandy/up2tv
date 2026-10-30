# Up2TV

[![Android CI](https://github.com/AceCandy/up2tv/actions/workflows/android.yml/badge.svg)](https://github.com/AceCandy/up2tv/actions/workflows/android.yml)

通过局域网，将任意设备上的 APK 上传到 Android 电视，并在电视端确认安装。

不限制电视品牌或型号，不需要手机配套 App、账户或云服务。不提供静默安装或绕过固件限制的能力。

正式安装包：[GitHub Releases](https://github.com/AceCandy/up2tv/releases/latest)。下载 APK 即可，`.sha256` 文件可用于校验下载完整性。

## 使用

1. 用 U 盘或电视支持的其他方式，首次安装 Up2TV。
2. 电视与手机 / 平板 / 电脑连接同一局域网，电视也可以使用网线。
3. 打开电视上的 Up2TV，页面自动开启接收并显示二维码。
4. 手机扫码；电脑在浏览器输入电视显示的 HTTP 地址，再输入六位连接码。
5. 选择 APK 并上传，可连续上传多个。上传时保持网页在前台。
6. 电视端按最新接收时间在前显示文件列表及电视本机接收时间，可逐个「安装」或「删除」。首次安装可能需要系统授权，之后仍需在系统安装器确认。
7. 确认安装结果后，按需回到列表删除安装包。不会自动卸载或删除其他应用。

安装失败不会自动删除 APK，可重试或手动删除。界面显示「已打开系统安装界面」不表示安装成功。

## 支持范围

- 接收端：Android 6.0（API 23）及以上，能安装 APK、有可用的系统安装器。
- 上传端：支持文件选择、Fetch、XMLHttpRequest 的现代浏览器；二维码只是地址入口。
- 网络：IPv4 局域网；访客网络、客户端隔离、VPN、浏览器的 HTTPS-only 模式可能阻止访问。
- 文件：每次上传一个非空、最大 1 GiB 的 `.apk`，可连续保存多个。不支持 `.xapk` / `.apkm` / split APK 集合。
- 可保存多个安装包，数量受电视剩余存储空间限制。
- 电视端全程使用方向键、确认键和返回键；没有触屏操作要求。

安装包解析成功不代表应用安全或与电视兼容。Android 版本、ABI、签名冲突、系统空间和厂商策略仍可能导致安装失败。

## 安全与生命周期

- 进入页面自动监听当前网络的 IPv4 地址；离开页面、按 Home、网络地址变化或手动关闭都会停止接收，旧二维码失效。返回页面会重新开启并生成新二维码。
- 二维码携带每次会话随机生成的临时凭证，放在 URL fragment，不进入 HTTP 路径或查询参数；页面读取后清除 fragment。
- 手动连接码连续错五次后锁定 60 秒。关闭并重新开启接收会生成新凭证，旧链接失效。
- 拒绝非本机 Host、跨源 Origin、未授权上传、非法文件名、无长度或分块传输请求。
- 最多同时处理八个连接；读取空闲超时 30 秒，上传总时限约 30 分钟。
- 流式写盘，检查剩余空间并保留 32 MiB 余量；上传中断清理临时文件。
- APK 存放在应用私有目录，不进入备份；仅通过 FileProvider 给系统安装器临时读取权限。
- HTTP **没有加密**，连接码不能防止同网段窃听或恶意网络控制。仅用于可信家庭网络，不做端口转发。

## 开发与构建

工具链锁定为 Gradle 8.14、Android Gradle Plugin 8.11.1、Kotlin 2.2.0；
compileSdk 36、targetSdk 35，JVM 字节码目标为 17。CI 使用 JDK 17，推荐本地保持一致。

```sh
git clone https://github.com/AceCandy/up2tv.git
cd up2tv
```

在 Android Studio 打开项目，或配置本机 `JAVA_HOME`、`ANDROID_HOME`。也可在不入仓的 `local.properties` 中配置 `sdk.dir`。
安装 Android SDK Platform 36 和 Build Tools 35.0.0，接受 SDK 许可后执行：

```sh
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
```

Windows 使用 `gradlew.bat`。修改网页时，另执行 `node --check app/src/main/assets/app.js`。

测试 APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。
这是本地 debug 签名构建。正式分发使用下面的 release 构建。

## 正式签名构建

本地发布密钥和密码保存在不入仓的 `.signing/` 目录。首次在其他机器配置时，将
`signing.properties.example` 复制为 `.signing/signing.properties`，填写自己的密码，并放入对应的 PKCS12 密钥文件。
`storeFile` 相对于项目根目录；使用示例路径时为 `.signing/up2tv-release.p12`。
配置使用 Java Properties 格式，密码中的反斜杠需写为 `\\`。在 Linux/macOS 上，将目录权限设为 `700`，其中的密钥及密码文件设为 `600`。
已有正式版本的维护者应恢复原有签名备份，不要重新生成密钥。
CI 可通过 `UP2TV_STORE_PASSWORD`、`UP2TV_KEY_PASSWORD` 环境变量传入密码，优先于本地配置，不需要把密码写进 Runner 的配置文件。

```sh
./gradlew --no-daemon :app:testReleaseUnitTest :app:lintRelease :app:assembleRelease
```

输出：`app/build/outputs/apk/release/app-release.apk`。缺少签名资料时正式构建会失败，不会回退到调试签名；未配置签名文件的克隆仍可构建调试包。

- 完整备份 `.signing/` 到受保护的异地或离线位置，密码文件也属于敏感资料；不要通过公开仓库、Issue 或聊天发送。
- 发布密钥应长期保持不变；遗失后无法再用原签名发布更新。后续升级同时递增 `versionCode` 并更新 `versionName`。
- 从调试版切换到此正式签名通常需要卸载旧版。卸载会清除 Up2TV 保存的 APK，但不会卸载其他已安装应用。
- GitHub 的 `release` 环境保存正式签名 Secrets；普通调试 CI 不使用这些 Secrets。公开仓库中的代码和下载附件不包含密钥或密码。

## 自动发布正式版

推送 `v主版本.次版本.修订号` 标签时，[Android Release](https://github.com/AceCandy/up2tv/actions/workflows/release.yml) 会执行 release 测试、Lint、签名构建及 APK 校验，全部通过后创建 GitHub Release，附上 APK 和 SHA-256 校验文件。

发布步骤：

1. 在 `app/build.gradle.kts` 更新 `versionName` 并递增 `versionCode`，提交到 `main`。
2. 确认普通 CI 通过，再为该提交创建并推送对应版本标签，例如发布下一版：

   ```sh
   git tag -a v0.1.1 -m '发布 v0.1.1'
   git push origin v0.1.1
   ```

标签必须指向已合入 `main` 的提交，且必须与 APK 的 `versionName` 一致。已发布版本不要移动标签或覆盖附件；修复应递增版本后发布。

`release` 环境仅允许 `v*` 标签，配置三个环境 Secrets：

- `UP2TV_KEYSTORE_BASE64`：现有 `.signing/up2tv-release.p12` 的 Base64 编码。
- `UP2TV_STORE_PASSWORD`：该密钥库密码。
- `UP2TV_KEY_PASSWORD`：别名 `up2tv` 对应的密钥密码。

Base64 只用于传输二进制，不是加密。通过 GitHub Secrets 保存，勿放入 Git、Issue 或日志。
工作流固定校验现有正式证书的 SHA-256 指纹，构建后清理 Runner 的签名文件，且不回写 Gradle 缓存。签名密钥仍需自行安全备份。
只有可信维护者应拥有工作流修改和版本标签推送权限；环境的标签限制不能替代对维护者的信任。后续增加协作者时，应一并审查标签权限和环境审批设置。

## 自动检查与测试包

推送到 `main`、提交 PR 或手动运行 [Android CI](https://github.com/AceCandy/up2tv/actions/workflows/android.yml) 时，自动检查 Gradle Wrapper、网页脚本语法、单元测试和 Android Lint，并生成调试 APK。
打开成功运行记录，在 Artifacts 中下载 `up2tv-debug-<提交 SHA>`；测试和 Lint 报告单独保存，保留 14 天。

CI 调试签名可能随运行变化，不能保证覆盖本地或其他构建的安装。日常安装请优先使用 Releases 中的正式签名包。

参与开发见 [CONTRIBUTING.md](CONTRIBUTING.md)，产品范围见 [PRODUCT.md](PRODUCT.md)。

## 代码入口

- `app/src/main/java/dev/up2tv/MainActivity.kt`：遥控器界面、网络生命周期、二维码、权限和系统安装器。
- `app/src/main/java/dev/up2tv/UploadServer.kt`：可独立 JVM 测试的 HTTP 接收与会话授权。
- `app/src/main/assets/`：离线内置上传网页，无 CDN 或外部请求。
- `app/src/test/java/dev/up2tv/UploadServerTest.kt`：使用真实 loopback socket 的协议、授权、并发和清理测试。

## 真机验收

自动化测试无法代表厂商电视的安装行为。发布前在目标电视逐项检查：

- 首次启动、方向键焦点、返回键、Home 键、1080p / 4K 和大字体。
- 安卓、iOS 和桌面浏览器扫码 / 输入地址，中文文件名、大文件及后台中断。
- 未授权、有效 APK、无效 APK、空间不足、取消、断网和网络切换。
- 首次安装授权页、正常安装、取消安装、签名冲突和不兼容 APK。
- 退出后旧链接不可用；已有安装包重启后仍可安装和删除。

目标电视仍需按以上清单完成验收。

## 当前验证记录

- `testReleaseUnitTest`：16 项通过；`lintRelease`：0 错误；正式 APK 已校验独立签名证书、v1/v2 签名、文件对齐和非调试标记。缺少签名资料时正式构建按预期失败。
- `testDebugUnitTest`：16 项通过，包括连续保存多个安装包、按接收时间倒序、重启后恢复列表、校验阶段关闭接收和旧会话不能覆盖新会话文件。
- `assembleDebug`：成功；`lintDebug`：0 错误，31 警告，1 提示。警告主要为中文文案未资源化、版本升级建议及兼容性建议，未通过关闭检查来隐藏。
- Chromium：精简单列上传页已验证连接码、扫码入口、连续两次真实 loopback 上传和文件 SHA-256 一致；1366px / 390px / 320px（含长文件名）均无横向溢出。
- 网页可访问性扫描未发现违规；渐变背景人工计算的辅助文字对比度最低 5.20:1。
- 通过可控浏览器事件验证上传超时后的重试、重新连接及焦点恢复。
- 浏览器测试复用生产 `UploadServer`，但 APK 元数据校验使用测试替身；不代表 Android 安装包解析或系统安装已经通过。
- 当前环境无 KVM 使用权限，本次软件模拟器安装和截图超时，未完成原生界面与安装流程验收。安卓手机、iOS、目标电视仍需实测。

## 许可证

项目代码采用 [MIT License](LICENSE)，版权归 AceCandy 所有。第三方依赖及 Gradle Wrapper 保留各自的许可证。
