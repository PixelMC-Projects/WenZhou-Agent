# 问舟 AI

问舟是旅人手册的游戏玩法助手。Forge 模组 ID 为 `wenzhouai`。客户端使用 Hub 的原生绘制、主题和输入，服务端通过当前整合包查询玩法资料，再调用 DeepSeek。服主通过 Manager 账号管理准入、服务参数、协议和聊天记录。

## 玩法查询

问舟通过服务端 Manager 查结构生成条件与宝箱、村民及游商交易、提问者的任务手册和 SDM 商店。玩家问「这个材料能直接买吗」时，可以沿用刚从配方查到的物品，核对商店实际交付的数量、付款方式和当前能否交易。查任务时，问舟先看可见条目，再展开前置、进度与每项奖励的领取状态。玩家可以在工作流程中查看各步查询。

## 使用

Forge 1.20.1 客户端和专用服务端均安装问舟与 PixelMC Hub 0.5.5-dev。PixelMC Manager 0.9.1-dev 安装在专用服务端。服主配置服务并在「玩家权限」授权后，玩家从手册左侧的「问舟 BETA」进入。查阅过程中可显示一两行阶段进展，回答完成后可以展开工作流程回看。历史可续聊，输入栏发送按钮旁显示当前会话累计费用，悬停可查看各项用量。

[部署和回滚](DEPLOYMENT.md) · [Manager 管理页面配置](ADMIN-WEB.md)

首次进入问舟时，本地候选显示开屏和五步引导；展示状态使用 Manager 标准全局玩家字段 `wenzhouai.intro_seen` 保存。首页文案随机显示。开屏引导与协议签署是两项不同状态。当前候选为问舟 0.1.26-dev，玩法查询优先使用运行中的配方、标签和战利品池。代码及事件候选会关联本轮物品、标签、地物、已加载的相关设置与脚本入口，并按实际条件作答。以下描述对应 Forge 1.20.1 的本地实现，尚未部署。

## 引用世界中的方块

从输入栏的工具箱选择「世界方块」，在游戏里对准方块后按右键。一条问题最多引用八块，每次选一块，按 Esc 取消。也可以在原版按键设置的「问舟」分类绑定「引用世界方块」，在游戏里直接进入选区。此快捷键默认未绑定，需要当前连接已获得问舟准入并完成适用的协议确认。

服务状态以 `blockReferences` 声明支持。`block.capture` 只接受 `pos`，由服务端用当前玩家视线核对已载入区块内最多八格的实际命中方块。每连接最多保存 32 个草稿凭据，有效期十分钟，绑定当前世界、权限代与方块。发送时模型只收到本轮短编号、方块身份、位置和维度。历史仍可显示方块名称和坐标，需要读取旧引用的数据时请重新选取。

模型按需调用 `list_block_fields` 列出游戏字段，再用 `read_block_field` 读取指定路径或库存页。`path` 使用 JSON Pointer。`/blockState` 提供可见的方块状态，`/nbt` 提供已经核对的玩法字段，例如刷怪笼的实体类型和时间、熔炉进度、酿造台燃料及信标效果。`/inventory` 提供已支持存储的物品 ID、数量和槽位，物品附加字段限于核对过的玩法数据。每页最多 32 项和 6 KiB，单轮方块工具回执合计最多 24 KiB。工具只读取已载入的数据，不借助放置、开箱或生成战利品。

每次读取额外数据都复核当前玩家、方块、领地保护及适用的容器锁。领地按服务器普通方块交互规则授权，包含允许他人打开的容器。原版容器锁按玩家主手钥匙匹配，钥匙名称属于权限校验数据。未生成的战利品显示为不可用。支持原版选定容器、箱盖可以打开的原色或染色潜影盒，以及两半均可访问的原版双箱，也支持农夫乐事橱柜、Handcrafted 和 Macaw's Furniture 已核对的柜子，以及可正常打开的 Expanded Storage 单箱。Expanded Storage 双箱、Refined Storage 网络、Lootr 私人战利品及其他未识别存储显示为不可用。Sophisticated Backpacks 读取方块中背包的实体槽，或已载入的持久化槽。持久化槽可能落后于界面，升级提供的虚拟槽不在统计范围内。

服务器遇到领地、容器锁或阻挡明确拒绝时停止本轮工具查询，由模型说明对应方块和拒绝原因。使用方块数据的轮次暂停共享玩法笔记。草稿凭据、原始字段回执、私密标识和自定义文本不进入会话历史。问舟按正常方式保存给玩家的回答。

## 玩法查询和共享笔记

代码查阅可按关键词返回附近的指令与同类回调，支持查看字段访问和直接的 CompoundTag 调用。回执保留原始符号并附带能确定的 Minecraft 1.20.1 名称。同一会话追问时可重用最近四轮中的最多三个代码位置，服务端会核对已安装 JAR 的 SHA-256。模型在给出最终正文前须成功保存笔记，或明确选择跳过并给出原因。

自然生成查询从物品关联的放置方块或指定方块反查运行中的群系、放置地物和配置地物，支持树木、矿石、常见状态提供器以及已加载的树木装饰器中能追到实际方块放置调用的注册方块。玩家时间线显示「查询自然生成地点」和群系数。生成条件、概率、结构和任意模组事件需要另查。知识笔记保存时一次列出各字段错误，保留本轮证据及长度约束。

「统计手册食物」使用当前物品注册表、生活调味料胡萝卜版的计数函数及本世界已加载的配置。回执分别给出手册分母、里程碑档数、最后一档与最低食物值。个人查询从当前提问连接读取已收集、剩余种类和下一档所差数量，时间线显示个人进度；仅查公共规则时可指定 global。未安装 solcarrot 和无法读取个人记录有不同状态。读取个人进度的轮次不保存共享笔记。

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

## English

Wenzhou AI is a Forge 1.20.1 gameplay assistant integrated with PixelMC Hub and Manager. Install Hub 0.5.5-dev and Wenzhou 0.1.26-dev on both client and dedicated server, with Manager 0.9.1-dev on the server. Grant access in Manager and configure the model service before use. These are local candidates, not a deployment.

The first visit shows an introduction and a five-step guide; its shown state uses the Manager global player field `wenzhouai.intro_seen`. The home screen chooses a random greeting. This introduction is separate from agreement acceptance.

Players can select up to eight world blocks individually. The model initially receives only their identities and coordinates; `list_block_fields` and `read_block_field` retrieve supported fields or bounded inventory pages on demand. Each read rechecks the current player, loaded target and applicable claim and lock protection. An explicit denial stops further block-tool queries in that turn and is explained to the player. Unsupported or unopened loot, incomplete pages and unavailable data are not empty inventories. See the Chinese section above for supported container classes, paging limits, privacy and storage boundaries.

The Manager pages manage access, agreements, conversations and service configuration. Build with JDK 17 as described above; see [deployment](DEPLOYMENT.md) and [admin configuration](ADMIN-WEB.md). Web previews use demo data and do not establish production deployment or in-game acceptance.

[许可和素材说明](NOTICE.md)

服务端按本服注册表查物品。重名、部分名称和全拼仅提供候选，1–3 件由玩家选择，其余请内联指定。已确定的物品可按注册名预查当前整合包的记忆摘要，每页最多六条。特殊玩法战利品表可反查所属模组中的表 ID 常量及同类调用者。
