# AndroidAVBaseLibrary

适用于安防行业的 Android 高性能音视频基础库，专注提供低延迟的采集、硬编解码、动态 OSD (时间水印)、MP4 录制与优化等常见功能。

A high-performance Android Audio/Video base library tailored for the security and surveillance industry. It provides low-latency capture, hardware encoding/decoding, dynamic OSD (timestamp watermarking), MP4 recording, and stream optimization features.

## 🖼 预览效果 / Preview

<p align="center">
  <img width="50%" height="50%" alt="preview" src="https://github.com/user-attachments/assets/9094ba34-e3b6-4eae-a1b9-6e94fb44fa49" />
</p>

## 🌟 特性 / Features

- **高性能渲染与编码 / High-Performance Rendering & Encoding**
  - 基于 EGL 与 FBO 的全链路零拷贝 (Zero-copy) 图像处理。
  - 支持动态切换软硬件视频编码器 (AVC/HEVC)。
- **动态 OSD 水印 / Dynamic OSD Watermarking**
  - 支持高效的时间戳及自定义文字水印实时渲染合成。
- **MP4 实时录制与优化 / MP4 Recording & Optimization**
  - 内置高性能 `MediaMuxer` 实时封装。
  - **FastStart (moov 前置) 秒开优化**: 纯 Java 层利用 `FileChannel` 零拷贝实现 `moov` Box 前置，保证录制的 MP4 在网络流媒体环境下支持极速秒开。
- **媒体流解析 / Media Demuxing**
  - 提供超轻量的 `Mp4Demuxer`，基于 DirectBuffer 高效解析并剥离 H264/AAC 等裸流。

## 📦 引入 / Installation

本项目已发布至 **JitPack**，可通过 Gradle 轻松引入。
This project is available on **JitPack** and can be easily included via Gradle.

### 1. 配置仓库 / Add Repository

在您项目的根 `settings.gradle`（或项目级的 `build.gradle`）中添加 JitPack 仓库：
Add the JitPack repository to your root `settings.gradle` or project `build.gradle`:

```gradle
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        mavenCentral()
        maven { url 'https://jitpack.io' }
    }
}
```

### 2. 添加依赖 / Add Dependency

在您的 app 模块 `build.gradle` 中添加以下依赖：
Add the dependency to your app module's `build.gradle`:

```gradle
dependencies {
    implementation 'com.github.lkmio:AndroidAVBaseLibrary:v1.0.0-alpha'
}
```

## 🚀 快速开始 / Quick Start

具体的使用方法和完整集成流程（例如相机采集预览、编码器选择、开启录制、FastStart 优化和流媒体抽帧），请直接参考本仓库的完整 Demo 代码：
[app/src/main/java/com/github/lkmio/androidavbaselibrary/examples/MainActivity.java](app/src/main/java/com/github/lkmio/androidavbaselibrary/examples/MainActivity.java)

For detailed usage examples and integration flows (such as camera capture preview, codec selection, recording, FastStart optimization, and stream demuxing), please refer directly to the comprehensive Demo code provided in this repository:
[app/src/main/java/com/github/lkmio/androidavbaselibrary/examples/MainActivity.java](app/src/main/java/com/github/lkmio/androidavbaselibrary/examples/MainActivity.java)
