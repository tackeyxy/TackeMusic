# TackeMusic - 现代化音乐播放器

一个基于酷我音乐和网易云音乐API的原生Android音乐播放器应用。

## 功能特性

- 🎵 **双平台支持**：同时支持酷我音乐和网易云音乐搜索
- 🎧 **在线播放**：流畅的音乐在线播放体验
- 🎨 **现代化UI**：简洁美观的Material Design界面
- 🖼️ **专辑背景**：播放页面背景使用歌曲专辑封面
- 📥 **多种音质下载**：支持128k、320k、FLAC、FLAC 24bit等多种音质
- 🎚️ **播放控制**：支持播放、暂停、进度调整等功能

## 项目结构

```
TackeMusic/
├── app/
│   ├── src/
│   │   └── main/
│   │       ├── java/com/tacke/music/
│   │       │   ├── data/
│   │       │   │   ├── api/              # API接口定义
│   │       │   │   ├── model/            # 数据模型
│   │       │   │   └── repository/       # 数据仓库
│   │       │   ├── ui/
│   │       │   │   ├── adapter/          # RecyclerView适配器
│   │       │   │   ├── MainActivity.kt   # 主页面
│   │       │   │   └── PlayerActivity.kt # 播放页面
│   │       │   └── utils/                # 工具类
│   │       └── res/                      # 资源文件
│   └── build.gradle                       # app模块配置
├── build.gradle                           # 项目级配置
├── settings.gradle                        # 项目设置
└── gradle.properties                      # Gradle属性
```

## 技术栈

- **语言**：Kotlin
- **最低SDK**：API 24 (Android 7.0)
- **目标SDK**：API 34 (Android 14)
- **UI框架**：Material Design Components
- **网络请求**：Retrofit + OkHttp
- **图片加载**：Glide
- **音乐播放**：Media3 ExoPlayer
- **异步处理**：Kotlin Coroutines

## 使用说明

### 1. 环境要求

- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

### 2. 构建项目

1. 使用Android Studio打开项目
2. 等待Gradle同步完成
3. 连接Android设备或启动模拟器
4. 点击运行按钮或使用命令：
   ```bash
   ./gradlew installDebug
   ```

### 3. 主要功能

#### 搜索音乐
1. 在搜索框输入关键词
2. 选择音乐平台（酷我/网易云）
3. 点击搜索按钮

#### 播放音乐
1. 点击搜索结果中的歌曲
2. 进入播放页面
3. 自动开始播放

#### 下载音乐
1. 在歌曲列表点击更多按钮或在播放页面点击下载按钮
2. 选择音质
3. 开始下载

## 注意事项

- 本项目仅供学习交流使用
- 音乐版权归原平台所有
- 部分功能可能因API变更而失效

## 开发者

TackeMusic Team
