# 构建与部署

## 环境

需要 JDK 17、Python 3.9+、Android SDK Platform 34 和 Build Tools 34。本轮在 macOS 使用 Android Build Tools 实际完成构建，Linux 尚未实测。

不依赖本机历史目录、预置 Shizuku JAR 或作者签名文件。首次构建从 Maven Central 下载三个 Shizuku AAR，校验固定 SHA-256 后提取 classes.jar；后续使用 `.local/dependencies` 缓存。

标准 SDK 目录布局下运行:

root@device:/#
```bash
export JAVA_HOME=/path/to/jdk-17
export ANDROID_SDK_ROOT=/path/to/android-sdk
python3 scripts/build.py --sdk "$ANDROID_SDK_ROOT"
```

默认使用 `build-tools/34.0.0` 与 `platforms/android-34`。非标准目录可显式指定:

root@device:/#
```bash
python3 scripts/build.py --build-tools /path/to/build-tools --android-jar /path/to/android.jar --framework-aidl /path/to/framework.aidl
```

生成 `build/CarStarter-v15-unsigned.apk` 和 `build/car-bridge.jar`。生成物和依赖缓存均被 .gitignore 排除。

## 签名与安装

首次使用自己的本地密钥。以下命令会交互询问密钥密码，密钥保存在忽略目录 .local。示例假定 Android SDK 命令已加入 PATH。

root@device:/#
```bash
mkdir -p .local
keytool -genkeypair -keystore .local/carstarter.keystore -alias carstarter -keyalg RSA -keysize 2048 -validity 10000
zipalign -f 4 build/CarStarter-v15-unsigned.apk build/CarStarter-v15-aligned.apk
apksigner sign --ks .local/carstarter.keystore --out build/CarStarter-v15.apk build/CarStarter-v15-aligned.apk
apksigner verify build/CarStarter-v15.apk
adb install build/CarStarter-v15.apk
```

如果手机已有其他密钥签名的同包名版本，覆盖安装会被拒绝。不要为了通过安装而直接清数据；先确认要保留的设置和安装来源，再决定迁移方式。

## Shizuku

1. 安装 Shizuku，按其应用内说明通过无线调试或电脑 ADB 启动。
2. 打开车启，按提示完成 Shizuku 授权。
3. 查看状态中的 Shizuku 服务和守护进程是否就绪。
4. 后台服务异常时使用“检查 / 恢复服务”，或重新打开车启。

未连车也可以准备授权。非 root 启动方式下，手机重启之后 Shizuku 本身需要重新启动；这不等同于每次连车都需要连接电脑。[Shizuku 官方指南](https://shizuku.rikka.app/guide/setup/)

## 地图桥接

当前 FLIPMAP 命令依赖设备上的桥接文件，还没有自动从 APK 部署。普通直接启动不需要手动部署 card.jar，CarD 已编入 APK，由 Shizuku UserService 启动。

如需试验现有地图桥接，部署构建产物:

root@device:/#
```bash
adb push build/car-bridge.jar /data/local/tmp/car-bridge.jar
```

桥接使用 vivo 私有接口，只针对运行时 map 中已有应用，不保证兼容其他系统版本，也不代表名单开放或全屏已经通过画面验收。

## 从历史 ADB 守护进程迁移

如果已经运行旧版 card_d / 看门狗，先保存旧启动方式和状态，再暂停旧看门狗并结束旧进程。否则旧进程可能继续占用 7878 端口，使 Shizuku 服务检测到的是旧服务。

当前现场使用 `/data/local/tmp/card.stop` 暂停旧看门狗。该标记只适用于本项目旧脚本，并非通用 Android 服务设置。新安装本仓库不需要创建该标记。
