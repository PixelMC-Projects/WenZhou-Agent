# 问舟 AI

问舟是旅人手册的游戏玩法助手。Forge 模组 ID 为 `wenzhouai`。客户端使用 Hub 的原生绘制、主题和输入，服务端通过当前整合包查询玩法资料，再调用 DeepSeek。服主通过 Manager 账号管理准入、服务参数、协议和聊天记录。

## 使用

Forge 1.20.1 客户端和专用服务端均安装问舟与 PixelMC Hub 0.5.5-dev。PixelMC Manager 0.9.0-dev 安装在专用服务端。服主配置服务并在「玩家权限」授权后，玩家从手册左侧的「问舟 BETA」进入。历史可续聊，输入栏发送按钮旁显示当前会话累计费用，悬停可查看各项用量。

在 Manager 的「问舟AI → 服务管理」配置模型和本服协议，再从「玩家权限」授予访问权限。独立后台需要兼容的 Manager Service 0.9.0-dev.web.5；配置模板见 [wenzhouai-admin.properties.example](wenzhouai-admin.properties.example)。可选的模组代码查阅环境见 [沙箱安装说明](sandbox/README.md)。

## 构建

使用 JDK 17。先构建相应版本 Hub 和 Manager 的 Forge 工程，再构建问舟。默认目录结构如下。

```text
WenZhou-Agent/
PixelMCPlayerHub/minecraft/
pixelmcmanager/forge/pixelmcmanager-forge-1.20.1/
```

```powershell
.\gradlew.bat build
```

可用 Gradle 属性 `hubProject`、`managerProject` 指定其它源码目录。可安装的重映射 JAR 在 `build/libs/` 中。`admin-web/src/` 的三个管理页由配套 Manager 工程的 `managerServiceJar` 任务编译。登录 Manager，在手册管理下方展开「问舟AI」即可进入。

[许可和素材说明](NOTICE.md)

## English

WenZhou is a Minecraft gameplay assistant for the PixelMC handbook. It checks recipes, tags and installed mod implementations and answers in the game UI. Install WenZhou and [PixelMC-Hub](https://github.com/PixelMC-Projects/PixelMC-Hub) on both sides of Forge 1.20.1. Dedicated servers also require [PixelMC Manager](https://github.com/PixelMC-Projects/pixelmcmanager-forge). Configure the provider and grant player access in Manager. Publish a server-specific agreement with your contact channel before opening access.

The Forge mod lives in `src/main`, the Manager React views in `admin-web`, and the Manager quota store in `manager-service`. Optional Linux read-only tooling and its installation guide are in `sandbox`. Use JDK 17 and the Gradle wrapper. The Java mod, admin frontend and bundled assets have separate license notices.
