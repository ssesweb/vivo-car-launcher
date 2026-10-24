# vivo 车载投屏启动器

面向 vivo 车联 / Jovi inCar 的手机端应用启动器。应用名为 **车启 CarStarter**，包名为 `top.quietphoenix.carstarter`。

在手机上选择已安装的应用，通过 Shizuku 提供的 shell 身份，将启动请求发送到车载虚拟屏。项目目标是让应用在车机上正确显示，并支持底部安全区和按需全屏。

当前为研究与开发阶段。**能够枚举应用、发出启动命令，不代表所有应用都能正确投屏；安全区和按需全屏尚未完成整体验收。**

## 当前已有

- 手机端应用列表、图标、搜索、A-Z 排序和最近使用排序。
- 检测 vivo 车载虚拟屏，通过守护进程执行直接启动。
- Shizuku 授权与 UserService 接入，授权流程不依赖是否连接车机。
- 检测 Shizuku 是否运行，并提供服务检查与恢复按钮。
- 后台服务异常退出后，重新打开车启可恢复服务。
- 已移除从手机启动时自动左右翻页、截图找图标的调用。

源码对应 v15 / 1.5。已在一台 vivo V2359A、Android 16、vivo 车联和车载桌面 7.0.0.8、MG4 场景进行部分验证，兼容范围尚未建立。

## 还没完成

- 直接启动路径下，底部安全区在不同应用上的稳定表现。
- 用户可选择的全屏切换，以及切换后的真实画面验收。
- 断开重连、系统升级及更多机型的兼容验证。
- 不依赖预先部署文件的地图桥接自动安装。

历史 v13 的图标点击路径曾在番茄冷启动和热启动中验证底部避让；该路径会干扰车机页面，已在 v14 停用，**不能把它的验证结果直接算给 v15 的直接启动路径**。

## 使用前提

手机安装并运行 Shizuku，然后打开车启完成授权。Shizuku 没运行时，车启只能引导打开 Shizuku；它不会自行获得 shell 权限。非 root 场景下，手机重启后需要重新启动 Shizuku。

授权及后台服务准备可以在未连车时完成。连接车机之后，才执行目标应用的上屏启动。最近使用排序另外需要系统的使用情况访问权限。

## 实际截图

v15 手机端实际界面，Shizuku 服务和守护进程已就绪:

<img src="docs/images/v15-phone-shizuku-ready.png" alt="v15 手机端界面与 Shizuku 服务状态" width="320">

v13 番茄冷启动车载画面，底部导航位于 dock 上方:

![v13 番茄车载冷启动历史截图](docs/images/v13-car-fanqie-cold-start.png)

车载截图属于已停用的 v13 原生图标点击路径，不代表 v15 直接启动已经完成安全区或按需全屏验收。详见 [截图与版本说明](docs/SCREENSHOTS.md)。

## 构建

本项目保留当前手工 Android 构建方式，尚未迁移 Gradle。需要 JDK 17、Python 3.9+、Android SDK Platform 34 和 Build Tools 34。依赖从 Maven Central 获取并校验 SHA-256。

root@device:/#
```bash
python3 scripts/build.py --sdk "$ANDROID_SDK_ROOT"
```

生成 `build/CarStarter-v15-unsigned.apk` 和 `build/car-bridge.jar`。APK 需自行签名再安装，项目不提供作者的签名密钥。

详见 [构建与部署](docs/BUILD.md)。

## 资料

- [当前状态与验证边界](docs/STATUS.md)
- [结构与调用关系](docs/ARCHITECTURE.md)
- [研究记录](docs/RESEARCH.md)
- [实际截图与版本说明](docs/SCREENSHOTS.md)
- [构建与部署](docs/BUILD.md)
- [第三方依赖说明](third_party/NOTICE.md)

这是个人研究项目，与 vivo、Shizuku 或车辆厂商无隶属关系。仓库收录自编写代码和整理后的研究记录，不包含厂商 APK、厂商反编译源码、私人日志、设备连接标识或签名密钥。
