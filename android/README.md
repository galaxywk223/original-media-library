# Android 素材下载器

独立 Android 客户端，最低支持 Android 8.0（API 26）。客户端使用应用内 WebView 保存抖音登录状态，使用 WorkManager 执行下载和音频提取任务，Room 保存本地媒体库。内置 FFmpegKit/LAME 从视频生成 MP3，固定码率为 192 kbps。

## 原生依赖

FFmpegKit 官方预编译包已退役，项目从固定提交 `d6be56d7aec286eb3c292d6b23ff07a6b70d8693`（v6.0）构建本地 AAR。FFmpeg 使用 n6.0、LAME 使用 RELEASE__3_100、libiconv 使用 v1.17。构建启用 GPL、LAME 和 MediaCodec，ABI 为 arm64-v8a 与 x86_64。

构建环境为 Linux/WSL Ubuntu、Java 17、Linux Android NDK r25c、Android SDK 35。Windows NDK 无法直接用于 WSL 原生编译。Linux 源码和 NDK 应位于 Linux 文件系统，避免 Windows 大小写及符号链接差异。

```bash
sudo apt-get install -y build-essential autoconf automake libtool pkg-config nasm yasm gperf groff gettext texinfo bison flex openjdk-17-jdk
export ANDROID_SDK_ROOT=/path/to/linux/android-sdk
export ANDROID_NDK_ROOT=/path/to/android-ndk-r25c
export FFMPEG_BUILD_DIR=/path/to/linux/ffmpeg-kit-build
bash android/scripts/build-ffmpeg.sh
```

脚本将产物复制到 `android/app/libs/ffmpeg-kit.aar`。AAR、原生构建缓存、签名文件和本机配置均被 Git 忽略，CI 从源码构建同一依赖。AAR 缺失时不能完成 APK 构建。

## 构建

```powershell
.\gradlew.bat test
.\gradlew.bat connectedCheck
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

签名发布构建通过 `keystore.properties` 或 GitHub Actions Secrets 提供，签名文件不进入仓库。`keystore.properties` 需要包含 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。debug APK 名称为 `MediaDownloader-Android-1.0.0-debug.apk`，release APK 名称为 `MediaDownloader-Android-1.0.0.apk`。

`assembleDebug` 使用 Android 调试签名；`assembleRelease` 在配置发布密钥后生成正式签名 APK。缺少密钥时输出未签名 release APK，不能直接安装。发布后的应用更新必须使用同一签名密钥，密钥及配置需要单独备份。

GitHub Secrets 名称为 `ANDROID_KEYSTORE_BASE64`、`ANDROID_STORE_PASSWORD`、`ANDROID_KEY_ALIAS` 和 `ANDROID_KEY_PASSWORD`。未配置 Secrets 时 CI 仍运行 JVM 测试并构建调试包和未签名发布包，不会把未签名包标记为正式发行版。

## 音频流程与验证

音频提取通过 WorkManager 串行队列运行，任务状态保存为 queued、extracting、completed、failed 或 cancelled。命令使用参数数组，路径中的空格、引号、井号和中文不参与命令行拼接。临时输出为 `xxx.part.mp3`，成功后重命名为 `xxx.mp3` 并登记为 `audio/mpeg`；原作品类型更新为 mixed。重复提取复用原资产，已有未登记文件不会被覆盖。

`connectedCheck` 包含设备端真实 FFmpeg 测试：生成带音轨视频、通过音频 Worker 生成 MP3、FFprobe 检查 192000 bit/s、重复提取去重，以及无效视频失败清理。登录抖音需要交互登录，自动化音频测试不验证线上抖音登录和下载能力。

## 数据边界

Android 客户端使用独立 Room 数据库和应用专属媒体目录，不与 Windows Electron 端同步。抖音 Cookie 只保存在 Android WebView 的本地 Cookie 存储中。

## 第三方组件

FFmpegKit GPL 构建用于 MP3 编码，相关许可证随发布包和仓库声明提供。Android APK 内不包含可执行命令行文件，AAR 中的原生库按 GPL 条款分发。
