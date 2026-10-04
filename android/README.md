# Android 素材下载器

独立 Android 客户端，最低支持 Android 8.0（API 26）。客户端使用应用内 WebView 保存抖音登录状态，使用 WorkManager 执行下载任务，Room 保存本地媒体库，使用 Android 原生媒体管线提取 AAC/M4A 音频。

## 构建

```powershell
.\gradlew.bat test
.\gradlew.bat assembleDebug
.\gradlew.bat assembleRelease
```

签名发布构建通过 `keystore.properties` 或 GitHub Actions Secrets 提供，签名文件不进入仓库。`keystore.properties` 需要包含 `storeFile`、`storePassword`、`keyAlias` 和 `keyPassword`。默认 APK 名称为 `MediaDownloader-Android-1.0.0.apk`。

## 数据边界

Android 客户端使用独立 Room 数据库和应用专属媒体目录，不与 Windows Electron 端同步。抖音 Cookie 只保存在 Android WebView 的本地 Cookie 存储中。

## 第三方组件

FFmpegKit GPL 构建用于 MP3 编码，相关许可证随发布包和仓库声明提供。
