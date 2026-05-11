# 📺 红果短剧 DLNA 投屏助手

> 通过无障碍服务 + DLNA协议实现红果短剧视频投屏到电视

## ✨ 功能特性

- **自动检测**：监控红果短剧播放界面，自动提取视频真实URL
- **DLNA投屏**：将视频推送到局域网内的DLNA设备（电视/盒子/投影仪）
- **支持格式**：m3u8（HLS）、mp4等主流视频格式
- **分享链接**：支持从其他App直接分享链接到本App进行投屏
- **悬浮控制**：前台服务运行，不中断播放

## 🏗️ 技术架构

```
┌─────────────────────────────────────────────┐
│              MainActivity (UI)               │
│  - 启动/停止无障碍服务                        │
│  - 显示检测到的视频URL                        │
│  - 扫描和选择DLNA设备                         │
│  - 控制投屏开始/停止                          │
└──────────────┬──────────────────────────────┘
               │ LocalBroadcast
┌──────────────▼──────────────────────────────┐
│      HongguoAccessibilityService             │
│  (Android Accessibility Service)            │
│                                              │
│  - 监控红果短剧窗口状态变化                    │
│  - 遍历UI树提取视频URL (m3u8/mp4)             │
│  - 正则匹配 + URL有效性验证                   │
│  - 广播通知MainActivity更新界面              │
└──────────────┬──────────────────────────────┘
               │ LocalBroadcast
┌──────────────▼──────────────────────────────┐
│          DlnaCastService                     │
│        (Android Foreground Service)         │
│                                              │
│  ┌─────────────┐   ┌─────────────────────┐  │
│  │ DeviceSearch│   │  CastController     │  │
│  │ (UPnP SSDP) │→ │  SetAVTransportURI  │  │
│  └─────────────┘   │  Play / Stop        │  │
│                    └─────────────────────┘  │
│  使用cling库实现DLNA/UPnP控制                │
└──────────────────────────────────────────────┘

备用方案：VideoUrlExtractor（网络代理方式）
- 解析分享链接获取真实播放地址
- 跟踪重定向链
- 从HTML/JS/API响应中提取视频URL
```

## 🚀 使用方法

### 第一步：安装APK
```bash
# 安装到手机（需要允许未知来源）
adb install app/build/outputs/apk/release/app-release.apk
```

### 第二步：配置权限
1. 打开「设置」→「无障碍」
2. 找到「**红果投屏助手**」并开启
3. 授予悬浮窗权限（可选，用于显示控制面板）

### 第三步：开始使用
1. 打开**红果投屏助手**
2. 点击「**启动监听服务**」（确认无障碍服务已开启）
3. 切换到**红果短剧**App并播放任意视频
4. 返回投屏助手，会显示检测到的视频URL
5. 点击「**扫描设备**」发现电视
6. 选择电视设备并点击「**开始投屏**」

### 备用方法：通过分享链接
1. 在红果短剧中复制当前播放页面的链接
2. 打开浏览器访问该链接
3. 浏览器中点击分享 → 选择「红果投屏助手」
4. App会自动解析并准备投屏

## 📱 支持的DLNA设备

理论上支持所有符合DLNA标准的Media Renderer设备：
- 智能电视（小米、海信、TCL、Sony、Samsung等）
- 电视盒子（当贝、天猫魔盒、华为盒子等）
- 投影仪（支持DLNA的型号）
- PC端媒体服务器（Kodi、Plex、Emby）

## 🔧 开发环境

- Android Studio Hedgehog | 2023.1.1+
- minSdk: 24 (Android 7.0)
- targetSdk: 34 (Android 14)
- Kotlin: 1.9.x
- Gradle: 8.5

### 核心依赖

| 库名 | 版本 | 用途 |
|------|------|------|
| cling (UPnP/DLNA) | 2.1.2 | 设备发现和控制 |
| OkHttp | 4.12.0 | 网络请求 |
| Gson | 2.10.1 | JSON解析 |
| Coroutines | 1.7.3 | 异步处理 |

## ⚠️ 注意事项

### 关于视频URL提取
红果短剧的视频URL可能经过加密或动态生成，无障碍服务的UI遍历方式不一定能100%成功。如果遇到以下情况：

1. **无法自动提取URL** → 使用「分享链接」功能手动输入
2. **URL过期或无效** → 需要重新在红果短剧中刷新页面
3. **提取到的是广告URL** → 等待几秒后重试，系统会过滤CDN域名

### 关于DLNA兼容性
- 部分老旧电视可能不支持m3u8格式（HLS）
- 如果投屏失败，可以尝试：
  - 更新电视固件
  - 使用第三方DLNA接收App（如BubbleUPnP）
  - 在路由器上开启IGMP组播

### 性能影响
- 无障碍服务会增加一定的CPU占用（约2-5%）
- 建议在不使用时关闭服务以节省电量

## 🔄 编译构建

```bash
# Clone项目
git clone https://github.com/acaiblog/CastTV-DLNA.git
cd CastTV-DLNA

# Debug版本（用于测试）
./gradlew assembleDebug

# Release版本
./gradlew assembleRelease

# APK输出路径
app/build/outputs/apk/release/app-release.apk
```

## 📄 License

MIT License

---

## 🛠️ 故障排除

| 问题 | 原因 | 解决方案 |
|------|------|----------|
| 服务无法启动 | 未开启无障碍权限 | 设置→无障碍→开启本应用 |
| 无法检测到视频URL | UI结构变化 | 尝试手动分享链接 |
| 扫描不到设备 | 网络隔离 | 确保手机和电视在同一WiFi |
| 投屏失败 | 格式不支持 | 检查电视是否支持HLS/m3u8 |
| 视频有声音无画面 | 编码问题 | 尝试降低清晰度 |

## 🤝 Contributing

欢迎提交Issue和PR！

## 📞 联系方式

- GitHub Issues: https://github.com/acaiblog/CastTV-DLNA/issues
