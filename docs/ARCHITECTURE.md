# 结构与调用关系

## 当前启动路径

手机车启 MainActivity -> Shizuku 授权 -> CarUserService -> 内嵌 CarD -> 本机 socket -> am start -> vivo 车载虚拟屏。

Shizuku UserService 以 shell 或 root 身份运行，取决于 Shizuku 的启动方式。本轮实测是 shell UID 2000。[官方 UserService 文档](https://github.com/RikkaApps/Shizuku-API#userservice)

## 文件

| 文件 | 用途 |
| --- | --- |
| app/AndroidManifest.xml | 应用权限、Activity、ShizukuProvider |
| MainActivity.java | 应用列表、搜索、授权、服务连接、启动请求 |
| ICarService.aidl | UserService 的启动与销毁接口 |
| CarUserService.java | 在授权进程中启动或检查 CarD |
| daemon/top/quietphoenix/card/CarD.java | 本机命令服务，默认绑定 127.0.0.1:7878 |
| daemon/CarSessionBridge.java | vivo 私有车载多窗口接口的实验桥接 |
| scripts/build.py | 下载依赖、编译 AIDL/Java、打包未签名 APK 与桥接 JAR |

## 生命周期

车启接收 Shizuku Binder 后检查授权；尚未授权时请求权限，已授权时绑定 UserService。该逻辑在检查车载屏之前执行，所以准备授权不要求连车。

UserService 的 ensureDaemon 先检查已有服务，再按需在自身进程启动 CarD。UserService 设置为 daemon 模式；当前测试中强制停止手机车启不会结束它。后台进程被终止后，重新打开车启会重新绑定和启动。此处不是开机自启动承诺，Shizuku 自身仍需运行。

## 命令与保留代码

应用的当前启动路径只使用 PING、FLIPMAP 和 LAUNCH。FLIPMAP 针对地图多窗口状态，不是屏幕手势翻页；它依赖设备上预先部署的 `/data/local/tmp/car-bridge.jar`。

守护进程仍保留历史 LAUNCHICON、MATCHDBG 等调试实现，便于整理现有研究资料。**当前车启不会调用它们**。后续可以单独清理，不将它们作为当前启动方案。

## 当前实现限制

- 守护进程采用单连接串行处理；长操作会影响后续请求。
- 本机 socket 尚无按调用应用身份鉴权；127.0.0.1 并不隔离同机其他应用。当前为实验代码，尚未完成面向广泛分发的安全加固。
- 目标屏选择及历史调试代码仍含设备相关假设，不能保证适配全部机型。
- 直接启动成功不证明应用已经可见、底部布局正确或全屏切换成功。
- Shizuku 不会自动提供厂商系统应用的签名权限，也不等于绕过厂商适配机制。
