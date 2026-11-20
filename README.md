# 问舟 AI

问舟是旅人手册的游戏玩法助手。Forge 模组 ID 为 `wenzhouai`。客户端使用 Hub 的原生绘制、主题和输入，服务端通过当前整合包查询玩法资料，再调用 DeepSeek。服主通过 Manager 账号管理准入、服务参数、协议和聊天记录。

## 使用

Forge 1.20.1 客户端和专用服务端均安装问舟与 PixelMC Hub 0.5.5-dev。PixelMC Manager 0.9.0-dev 安装在专用服务端。服主配置服务并在「玩家权限」授权后，玩家从手册左侧的「问舟 BETA」进入。历史可续聊，输入栏发送按钮旁显示当前会话累计费用，悬停可查看各项用量。

在 Manager 的「问舟AI → 服务管理」配置模型和本服协议，再从「玩家权限」授予访问权限。Manager Service 0.9.0-dev.web.6 支持已打开的玩家列表与详情自动更新在线状态。配置模板见 [wenzhouai-admin.properties.example](wenzhouai-admin.properties.example)。可选的模组代码查阅环境见 [沙箱安装说明](sandbox/README.md)。

## 玩法查询和共享笔记

代码查阅可按关键词返回附近的指令与同类回调，支持查看字段访问和直接的 CompoundTag 调用。回执保留原始符号并附带能确定的 Minecraft 1.20.1 名称。同一会话追问时可重用最近四轮中的最多三个代码位置，服务端会核对已安装 JAR 的 SHA-256。模型在给出最终正文前须成功保存笔记，或明确选择跳过并给出原因。

服务端可查生效物品标签，也可从已知物品反查标签。标签搜索接受最多六个注册名关键词，根据标签名称和本服实际成员排序。精确 ID 未注册时会附带候选。模组代码定位结果支持按方法编号读取实现。整合包修改查询列出已加载的数据资源及相关脚本引用，按来源计数。显式引用的物品会在提问时预读当前固定产物配方。

问舟用 `search_memory` 按关键词分页返回编号和概况，用 `read_memory` 读取所选笔记，用 `save_memory` 凭本次工具证据保存。每条以资源 ID 和玩法方面作为唯一键，服务端记录日期，并按 Manager 的服务器、存档、整合包名称和版本隔离。服务端数据位于 `config/wenzhouai/memory/<档案摘要>/memory.md`，每行一条 JSON 笔记。备份时保留整个目录。Manager 未确认当前包版本时，玩法查询仍可使用，笔记暂停访问。

玩家的查阅时间线使用工具参数和执行回执生成标题。记忆写入成功后区分新增和更新。DeepSeek 的公开正文通过 SSE 接收，走问舟现有玩家事件通道，`answer.delta` 按请求追加正文，`answer.reset` 撤回改为工具调用的临时文字。客户端逐字显示，结尾用服务端确认的富文本和配方卡收尾。历史沿用完整回包，计费采用服务端用量回执。

问舟可读取当前生效的新生魔艺灌注配方，反查矿工乐事铜锅的杯装产物，还可按实体查自然生成群系、InControl 指定实体的规则和 Bad Mobs 的生成开关。生成配置按文件来源展示，实际生成仍取决于地点和运行条件。查询时间线显示对应的图标、目标和匹配数量。

玩家界面的物品名取自客户端物品栈。要让服务端也能用整合包译名搜索，可将已启用资源包中的语言文件按原目录结构放入 `config/wenzhouai/pack-language/assets/<命名空间>/lang/zh_cn.json`；多个资源包按客户端优先级合并同名键。重载数据或重启后使用新索引，旧译名仍可检索。

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
