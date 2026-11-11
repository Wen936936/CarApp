# CarApp · 智能小车 Android 控制端

> 基于局域网的小车远程控制系统移动端，通过 **HTTP + WebSocket** 与后端实时通信，集合体感驾驶、视觉图传、SLAM 地图、机械臂控制与 AI 对话于一体。

![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)
![Language](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)
![minSdk](https://img.shields.io/badge/minSdk-26%20(Android%208.0)-blue)

---

## 项目简介

CarApp 是一套智能小车的 **Android 控制端**。手机与小车后端（Spring Boot）处于同一局域网，App 通过 HTTP 下发控制指令，通过 WebSocket 接收摄像头实时图传与 AI 回复。

- **通信方式**：局域网内 HTTP 请求 + WebSocket 长连接
- **适配模式**：横屏 / 竖屏 双布局，旋转自动切换并保留现场状态
- **后端地址**：无需重新打包，可在 App 内随时配置

---

## 技术栈

| 技术 | 用途 |
| --- | --- |
| **Kotlin** | 全量开发语言 |
| **Android SDK** | minSdk 26 / targetSdk 37 |
| **OkHttp 4.12** | HTTP 指令下发、WebSocket 图传与消息接收 |
| **WebSocket** | 摄像头 Base64 图像帧推送、AI 回复推送 |
| **SensorManager** | 加速度传感器，实现手机倾斜体感控车 |
| **Material 3** | 深色科技风主题与控件样式 |
| **findViewById** | 布局控件绑定 |
| **Handler / Looper** | 动作回放调度、巡逻循环、模拟抓帧 |
| **MediaStore** | 拍照保存至系统相册 |
| **SharedPreferences** | 持久化后端 IP 配置 |
| **ConstraintLayout** | 横竖屏两套响应式布局 |

---

## 核心功能亮点

### 3D 体感控车（防抖 + 方向锁定）

- 基于 `SensorManager` 读取加速度传感器，通过 X / Y 轴倾斜角度判定前进、后退、左转、右转与停止。
- **指令防抖**：仅当方向判定结果发生变化时才发送请求，避免传感器高频回调刷爆后端。
- **方向锁定**：开启体感时自动锁定当前屏幕方向（`SCREEN_ORIENTATION_LOCKED`），杜绝横竖屏切换导致传感器轴向翻转、小车乱窜的问题。
- 关闭体感或退到后台时主动注销监听，并补发一次 `stop`，防止小车失联后继续行驶。

### 动作录制与回放

- 录制期间，小车指令、LED 颜色、机械臂姿态三类动作会按时间戳依次入队。
- 回放时依照录制的相对时间差 `postDelayed` 精确复现，机械臂滑动条同步跳转，画面状态与指令保持一致。
- 回放过程中按钮自动禁用，避免重复触发；页面销毁时清理全部排队任务，防止资源泄漏。

### 横竖屏双布局适配与状态保存

- `layout/` 与 `layout-land/` 两套独立布局，横竖屏各自优化操作区与图传区的比例。
- 通过 `onSaveInstanceState` 持久化：聊天记录、A/B/C 巡逻点、机械臂 6 轴滑动条、巡逻进度、已录制动作列表。
- 旋转重建后自动接力巡逻循环，录制 / 回放状态则安全中断，避免时间戳错乱。

### 动态 IP 设置

- 后端地址保存在 `SharedPreferences`，App 启动时动态读取，不写死在代码中。
- App 内点击「设置」即可修改，支持自动补全 `http://` 协议头，保存后重启生效。
- 所有请求发出前统一做 URL 合法性校验，地址错误时仅提示、不闪退。

### 实时图传与相机操作

- WebSocket 接收 Base64 图像帧，实时渲染至画面并支持 **0.5x ~ 3.0x 缩放**。
- 一键拍照保存到系统相册（适配 Android 10+ 分区存储）。
- 支持模拟录像（定时抓帧）与拍照直发 AI。

### SLAM 地图与多点巡逻

- 拉取后端栅格地图并本地绘制（障碍 / 空地 / 网格线）。
- 点击地图换算格子坐标，设置 A/B/C 三点后自动循环巡逻。
- 支持手动点击任意目标点下发导航请求。

### 机械臂 / 灯光 / 喇叭

- 机械臂 6 关节滑杆控制，松手才发送指令，避免拖动过程中产生大量请求。
- 一键归位，滑杆与后端状态同步复位至 90。
- LED 颜色（红 / 绿 / 蓝 / 白 / 关）与模式（常亮 / 流水灯 / 闪烁）。
- 喇叭响 / 停切换，仅在请求成功后更新 UI 状态。

### AI 对话

- WebSocket 接收 AI 回复，聊天记录自动滚动到最新一条。
- 支持纯文本或「图片 + 文本」发送，图片压缩为 JPEG 后 Base64 编码，JSON 请求体提交。

---

## 后端接口约定

基础地址可在 App 内配置，默认 `http://10.0.2.2:8080`（Android 模拟器访问宿主机）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/car/command?action=forward\|backward\|left\|right\|stop` | 小车运动控制 |
| POST | `/car/led?color=red\|green\|blue\|white\|off` | LED 颜色 |
| POST | `/car/led/mode?mode=steady\|flow\|blink` | LED 模式 |
| POST | `/car/buzzer?action=on\|off` | 喇叭开关 |
| POST | `/car/arm?p1=&p2=&p3=&p4=&p5=&base=` | 机械臂姿态 |
| POST | `/car/arm/reset` | 机械臂归位 |
| POST | `/car/image/process?enabled=true\|false` | YOLO / OpenCV 图像处理开关 |
| GET | `/car/map` | 拉取栅格地图 |
| POST | `/car/nav/goal?x=&y=` | 导航目标点 |
| POST | `/car/ai/chat` | AI 对话（JSON：`{"text","image"}`） |
| WS | `/ws/camera` | 摄像头图像帧推送 |
| WS | `/ws/chat` | AI 回复推送 |

---

## 安装说明

### 环境要求

- Android Studio（建议最新稳定版）
- JDK 11+
- Android 设备或模拟器：**Android 8.0（API 26）及以上**
- 手机与小车后端处于 **同一局域网**

### 方式一：Android Studio 运行

1. 克隆项目并用 Android Studio 打开：
   ```bash
   git clone https://github.com/Wen936936/CarApp.git
   ```
2. 等待 Gradle 同步完成。
3. 连接设备，点击右上角绿色三角 ▶ 运行。

### 方式二：打包 APK

1. 菜单栏选择 **Build → Generate Signed Bundle / APK**。
2. 选择 **APK**，创建或选择已有签名密钥（`.jks`）。
3. 选择 `release` 构建类型，点击 **Finish**。
4. 生成的 APK 位于：
   ```
   app/release/app-release.apk
   ```
5. 将 APK 传输到手机安装即可（需允许「安装未知来源应用」）。

也可以用命令行打包 Debug 版：

```bash
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

### 首次使用：配置后端 IP

1. 打开 App，点击界面上的 **「设置」** 按钮。
2. 在弹窗中输入后端地址，例如：
   ```
   http://192.168.0.105:8080
   ```
   > 可省略 `http://`，App 会自动补全。
3. 点击 **「保存」**，按提示 **重启 App** 使配置生效。
4. 重启后，摄像头与 AI 对话会自动建立 WebSocket 连接，界面提示「已连接」即可开始使用。

> **提示**：默认地址为 `10.0.2.2:8080`，仅适用于 Android 模拟器访问本机后端；真机请填写电脑或小车的局域网 IP。

---

## 项目结构

```
CarApp/
├── app/src/main/
│   ├── java/com/example/carapp/
│   │   └── MainActivity.kt          # 主界面逻辑（控制 / 图传 / 录制 / 巡逻）
│   ├── res/
│   │   ├── layout/activity_main.xml       # 竖屏布局
│   │   ├── layout-land/activity_main.xml  # 横屏布局
│   │   ├── values/                        # 深色主题、颜色、字符串
│   │   └── drawable/                      # 图标与输入框背景
│   └── AndroidManifest.xml          # 网络权限、屏幕方向、明文流量
├── gradle/libs.versions.toml        # 依赖版本目录
├── build.gradle.kts
└── settings.gradle.kts
```

---

## 注意事项

- App 已开启 `usesCleartextTraffic`，支持局域网 **HTTP 明文传输**，请仅在可信网络环境下使用。
- 修改后端地址后需重启 App，因为 WebSocket 连接在页面创建时建立。
- 体感控制依赖加速度传感器，不支持的设备会提示并自动关闭开关。
