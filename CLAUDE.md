# 项目：CarApp 小车控制端

## 项目背景
Android App，通过局域网连接后端（Spring Boot），控制小车。

## 技术栈
- Kotlin
- Android SDK（最低 API 26）
- OkHttp（网络请求）
- WebSocket（后续接收图像流）

## 后端接口
基础地址：http://192.168.0.105:8080

- GET /car/command?action=forward
- GET /car/command?action=stop
- GET /car/command?action=left
- GET /car/command?action=right

## 目录结构
- MainActivity.kt：主界面
- res/layout/activity_main.xml：布局文件

## 编码规范
- 所有代码加中文注释
- 使用 OkHttp 发请求
- 网络请求必须 try-catch

## 常用命令
- 运行：Android Studio 里点绿色三角
- 打包 APK：Build -> Generate Signed Bundle/APK
