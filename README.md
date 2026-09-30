# Folia APK

Folia 的 Android WebView 混合壳客户端，加载 [Folia Web](https://music.aikun-bili.top)。

- 网页端源码（二次开发）：[aikun-China/folia-major-web](https://github.com/aikun-China/folia-major-web)
- 上游项目：[chthollyphile/folia-major](https://github.com/chthollyphile/folia-major)

## 特性

- **零第三方依赖**：WebView / DownloadManager / WebChromeClient 均为系统 API，安装包仅约 102 KB
- 内置网页返回键、文件下载（系统 DownloadManager）、图片/文件上传、离线提示页
- 应用名 `Folia`，自适应图标 + 圆形图标（API 26+），传统图标兼容 API 24-25

## 系统要求

Android 7.0+（minSdk 24 / targetSdk 33 / compileSdk 33）

## 构建

技术栈：AGP 7.4.2 + Gradle 7.5.1 + JDK 17，纯 Java 无 AndroidX。

```powershell
# 推荐：本机构建脚本（自动设定 JAVA_HOME 并调用本地 Gradle 发行版）
powershell -ExecutionPolicy Bypass -File tools\build-local.ps1

# 或：Gradle Wrapper（需可访问 services.gradle.org）
.\gradlew.bat assembleRelease
```

产物：`app/build/outputs/apk/release/app-release.apk`

> [!NOTE]
> release 包默认使用 debug keystore 签名（自用分发）；正式上架需替换为独立签名配置。`local.properties` 已被 gitignore，克隆后需按本机 SDK 路径自行创建（`sdk.dir=...`）。

## 安装

从 [Releases](https://github.com/aikun-China/folia-major-apk/releases) 下载 APK 安装，需允许安装未知来源应用。

## 目录结构

```
app/src/main/java/com/aikun/folia/MainActivity.java   # WebView 壳全部逻辑
app/src/main/res/                                      # 图标与资源
tools/gen_icons.ps1                                    # 图标生成脚本（源图自动裁切 → 全密度 mipmap）
tools/build-local.ps1                                  # 本机一键构建脚本
```

## 许可

跟随上游项目（AGPL-3.0）。
