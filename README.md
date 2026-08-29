# 📺 VLCDLNA

<div align="center">

**基于 libVLC 的 Android DLNA DMR 投屏接收端**

[![Platform](https://img.shields.io/badge/platform-Android-brightgreen.svg)](https://www.android.com/)
[![SDK](https://img.shields.io/badge/DLNA%20SDK-org.dlna-blue.svg)](https://gitee.com/)
[![Player](https://img.shields.io/badge/Player-libVLC-orange.svg)](https://www.videolan.org/vlc/libvlc.html)

</div>

---

## 📖 项目介绍

**VLCDLNA** 是一款运行在 Android 设备上的 **DLNA DMR（Digital Media Renderer）** 应用，允许同一局域网内的设备通过 DLNA 协议将音视频无线投射到该设备播放。底层使用 **libVLC** 作为解码引擎，兼容最新 `tv.dlna` SDK。

---

## ✨ 核心特性

| 特性 | 说明 |
|------|------|
| 🔍 自动发现 | 同一 WiFi 下可被系统投屏/视频 App 搜索到 |
| 📡 协议支持 | 完整实现 UPnP AVTransport / RenderingControl |
| 🎬 强大解码 | 基于 libVLC，支持 RTSP / HTTP / HLS / RTMP 等 |
| 📊 进度同步 | 每 800ms 向控制点上报播放进度与状态 |
| 🔒 UDN 持久化 | 设备唯一标识持久化，避免控制端频繁刷新 |

---

## 🏗 软件架构

- x86_64 arm64