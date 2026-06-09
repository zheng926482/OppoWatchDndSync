# OPPO Watch DND Sync Module

这是一个 Xposed 模块，实现了 OPPO 手机与手表之间的勿扰模式（DND）和静音模式的双向同步。

## 功能特性

- ✅ **勿扰模式双向同步**：手机开启勿扰时自动同步到手表，手表操作也会同步到手机
- ✅ **静音模式双向同步**：支持普通、振动和静音三种铃声模式
- ✅ **自动作用域声明**：编译后的 APK 已包含 LSPosed 作用域声明，激活时无需手动勾选
- ✅ **多包名支持**：支持多个 OPPO 手表相关包名
- ✅ **自动 CI/CD 构建**：每次推送自动编译 APK

## 支持的目标包名

- `com.heytap.wearable.health`
- `com.oppo.watch`
- `com.oppo.health`
- `com.oplus.wearable.health`

## 构建说明

### 前置要求

- JDK 11+
- Android SDK 33+
- Gradle 8.0+

### 本地构建

```bash
# 使用 gradlew 构建
./gradlew :app:assembleDebug

# APK 输出位置
app/build/outputs/apk/debug/app-debug.apk
```

### 自动构建（GitHub Actions）

推送代码到 `main` 或 `master` 分支时，GitHub Actions 会自动构建 APK。

在 **Actions** 选项卡中可以找到构建结果和下载链接。

## 安装使用

1. **下载 APK**：从 [Releases](../../releases) 或 Actions 中下载最新的 APK
2. **安装模块**：在 LSPosed 中安装该 APK
3. **激活模块**：在 LSPosed 管理器中勾选要同步的应用（管理器会自动推荐必要的包名）
4. **重启手机**：使更改生效

## 技术实现

- 使用 Xposed Framework 进行 Hook
- 通过 ContentObserver 监听系统勿扰和铃声设置变化
- 反射调用 OPPO 设备管理器的方法进行同步
- 支持多种方法名称和广播方式的兼容性

## 日志调试

使用 Xposed Bridge 的日志功能，在 LSPosed 日志中搜索 `OppoWatchDndSync` 可以看到详细的同步日志。

## 许可证

MIT License
