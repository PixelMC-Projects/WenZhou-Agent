# 问舟 AI 管理页面

问舟使用 Manager 的主导航、主题和登录会话。进入侧栏「问舟AI」后，在右侧工作区切换玩家权限、聊天记录、知识记录和服务管理。四个页面分别支持以下书签。
 
- `/#/wenzhou-players`
- `/#/wenzhou-chats`
- `/#/wenzhou-knowledge`
- `/#/wenzhou-service`

配套网页服务为 `pixelmc-manager-service-0.9.0-dev.web.8.jar`（本地候选，尚未部署）。管理请求沿用 Manager 的同源会话、CSRF 和服主玩家 UUID 校验。

## 启用

1. 保持 Manager 的统一平台存储。问舟通过原游戏桥接注册跨档案的准入与协议回执字段。
2. 在与游戏同主机的独立 Manager 数据目录中创建 `wenzhou-admin.properties`。其中的服主玩家 UUID 与游戏 `config/wenzhouai-admin.properties` 一致。将游戏的 `config/wenzhouai` 通过只读挂载提供给 Manager，在该视图中遮蔽 `credential-local-key.json`。挂载目录需要跟随原文件的原子替换。
3. Manager 原有同源 `/api/` 路径承接游戏停机时的玩家授权、会话查询和设置查阅。保持原 `/extensions/wenzhouai/` HTTPS 同源转发，以支持运行中的会话、设置修改和历史书签。示例代理配置和旧书签说明见下一节。
4. 安装配套 Manager Service 和问舟 0.1.26-dev，重启后从手册管理下方展开「问舟AI」。

```properties
ownerPlayerUuid=00000000-0000-0000-0000-000000000001
sourceDirectory=/wenzhou-saved
```

离线时，玩家列表来自全局档案，每页 50 名，授权修改仍保存到 Manager。会话列表、正文和累计用量来自游戏已经保存的文件。查询继续执行 30 天保留周期与问答优化授权过滤。在线状态由 Manager 的桥接心跳更新。DeepSeek 密钥和协议的修改需要游戏服连接。ChatGPT 接口的项目密钥、思考强度、速度和美元限额保存在 Manager，游戏服离线时也可以修改。

游戏服停机时仍可在 Manager 修改全服额度、个人额度和重置周期。权限列表最右列的累计 API 消耗来自数据库，采用每次模型调用的峰谷计价回执，独立于会话正文的保留周期。

## 页面更新与草稿

三页复用 Manager 既有约一秒一次的 SSE 快照中的轻量变更标识；相关标识变化时重新获取当前可见数据，在线玩家变化也会更新相关显示。打开的运行中会话仍有界核对用量和正文版本，正文变化或运行状态改变时才重新读取正文。旧服务缺少修订标识时保留兼容对账。服务管理收到外部提交后刷新已保存值；本地未保存的协议草稿保留并提示版本冲突，不自动覆盖。

查阅会话使用已鉴权管理路径并记录操作者、时间及对象，不要求填写查阅事由。研发查询仍受玩家问答优化授权与授权生效时间限制；撤回授权后不能继续研发查阅。协议发布和协议展示开关是独立操作，展示开关控制玩家开屏协议与输入栏下方入口。

## 游戏服的同源转发

Forge 的 `config/wenzhouai-admin.properties` 配置示例。

```properties
publicOrigin=https://manager.example.com
managerBase=http://127.0.0.1:18801
listenPort=18727
ownerPlayerUuid=00000000-0000-0000-0000-000000000001
```

```nginx
location = /extensions/wenzhouai {
    return 301 /extensions/wenzhouai/;
}
location ^~ /extensions/wenzhouai/ {
    client_max_body_size 256k;
    proxy_pass http://127.0.0.1:18727;
    proxy_http_version 1.1;
}
```

反代需访问 Forge 的回环监听器。分容器部署沿用现有本地转发。旧地址的 `?page=chats`、`?page=service` 分别跳转到 Manager 对应页面。

## ChatGPT 账号

使用 ChatGPT 方案需要运行在绑定服主本人控制的本机或专用远端环境。服主在游戏里发起、由本人授权的问舟请求可以使用自己的 ChatGPT 方案。其他玩家仍通过服务配置的独立 API 凭据使用问舟。多人共管、托管方可访问凭据或希望全服共用服主方案的环境不适用。此功能使用 OpenAI 官方开源客户端授权。账号资格、工作区和模型可用性以 OpenAI 返回为准。[Sign in with ChatGPT 条款](https://openai.com/policies/sign-in-with-chatgpt-terms/)

1. 为 Forge 进程设置 `WENZHOU_CHATGPT_STORAGE_KEY`。生成 32 字节随机密钥并使用标准 Base64 编码，通过该进程的私有环境注入。保管密钥。凭据状态以 AES-GCM 存入 Forge 专用目录，随服主 UUID 绑定。该目录和密钥均应排除在 Manager 的挂载、对外日志、源码和托管备份之外。若需要网络代理，仅给 Forge 进程设置 `WENZHOU_CHATGPT_PROXY=http://代理主机:端口`。代理只用于此授权与 ChatGPT 请求。
2. 若系统浏览器和 Forge 在同一台电脑、同一网络命名空间，直接使用回环回调。若从自己的电脑登录远程游戏服，打开 SSH 会话并保持到登录完成。`ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:1455:127.0.0.1:1455 user@host`。如果浏览器电脑端口已被占用，可通过 `WENZHOU_CHATGPT_CALLBACK_PORT` 指定其他端口，两端统一使用该值。
3. 对于 Forge 在独立 Docker 网络命名空间的部署，回环地址指向容器。通过同一网络命名空间的 SSH 入口使用上面的转发，或在自管宿主机经 `nsenter --net` 与 `socat` 创建权限为 `0600`、归 SSH 登录用户所有的临时 Unix 套接字，把请求送到 Forge 容器的 `127.0.0.1:1455`。例如，宿主机里运行 `sudo nsenter --target "$(docker inspect --format '{{.State.Pid}}' forge)" --net socat UNIX-LISTEN:/run/user/1000/wenzhou-siwc.sock,mode=0600,user=operator,unlink-close,fork TCP4:127.0.0.1:1455`，浏览器电脑运行 `ssh -N -o ExitOnForwardFailure=yes -L 127.0.0.1:1455:/run/user/1000/wenzhou-siwc.sock operator@host`。按部署替换容器名、宿主机用户和 UID。授权完成后关闭 SSH 和临时转发。
4. 用预先绑定的服主账号打开 Manager 的「问舟 AI → 服务管理 → 模型服务」，在「ChatGPT 账号」中点击「使用 ChatGPT 登录」，再点击 `Continue with ChatGPT`。在 OpenAI 页面选择自己的账号和工作区，完成授权后回到账户页查看状态和可用模型。管理页仅显示经过验证的账号信息与授权状态。临时回环监听在一次回调、主动取消或五分钟超时后停止。
5. 可在账号页撤销授权。若页面提示 OpenAI 撤销未确认，可到 ChatGPT 的应用设置取消连接。可用额度和账号用量由 ChatGPT 管理。[ChatGPT 用量](https://chatgpt.com/settings/usage)

## Manager 调用 OpenAI 官方 API

接口由独立的 Manager Service 提供，使用 OpenAI 项目密钥。启用问舟管理扩展后，即使游戏服停机，已登录的绑定服主也能调用。两个接口均使用同源 POST、JSON 请求体、Manager 会话及 `X-CSRF-Token`。

在「问舟 AI → 服务管理 → 模型服务 → ChatGPT 接口」输入 OpenAI 项目 API Key，点击页底「保存设置」。配置或更换后，下一次调用即生效。管理页显示来源和最近修改时间。网页设置优先，移除后恢复已配置的启动环境密钥。网页密钥单独保存在 Manager 私有数据目录 `wenzhou/private/openai-project.key`，不进入标准玩家数据、管理网页资源或平台数据库备份。

也可以在 **Manager Service 进程或容器** 中配置 `OPENAI_API_KEY_FILE`，指向只读挂载的密钥文件绝对路径。每次请求重新读取该文件，轮换后下次调用生效。也可使用进程变量 `OPENAI_API_KEY`。同时设置时以文件为准，文件读取失败会报告配置错误。需要中转时，设置 `WENZHOU_OPENAI_PROXY` 为 Manager 可达的 HTTP 代理地址。省略该变量时沿用同一进程的 `WENZHOU_CHATGPT_PROXY`，显式设为空值则直连。密钥仅随 HTTPS 请求发往 OpenAI 官方地址。

| 接口 | 输入 | 返回 |
| --- | --- | --- |
| `/api/wenzhouai/openai.status` | `{}` | 密钥是否已配置、是否走代理、接口支持的模型、速度和思考档位。此操作不访问 OpenAI。 |
| `/api/wenzhouai/openai.respond` | 必填文本 `input`，其余参数见示例 | 回复文本、完成状态、上游报告的模型、实际服务档位和 token 用量。调用按项目计量。 |

~~~json
{
  "input": "请回复 OK。",
  "model": "gpt-6.1-terra",
  "service_tier": "default",
  "reasoning_effort": "low",
  "max_output_tokens": 256
}
~~~

默认模型为 `gpt-6.1-terra`，也支持 `gpt-6.2-sol`。速度默认 `default`，可选 `fast`。思考强度默认为 `low`，其余可选值由状态接口返回。单次输入最多 32768 个 UTF-16 代码单元，输出预算为 16 至 4096 个 token，默认 1024，包含推理 token。

请求固定发送到 `https://api.openai.com/v1/responses`，使用 `store: false`。同一实例同时接受一个管理调用。调用方应检查 `status`，预算耗尽等情况会返回 `incomplete` 及上游提供的 `incomplete_details`，模型拒答通过 `refusal` 返回。超时不会自动重试，上游可能已经计量。此接口不写入玩家会话、限额或共享知识。

## 游戏内 Terra 项目服务

若 OpenAI 对项目请求明确返回额度耗尽或支出达到上限，普通玩家的模型选择器会收起，问舟使用 DeepSeek 的凭据和额度。Manager 会在重启后保留暂停状态。充值后，在「服务管理 → 模型服务 → ChatGPT 接口」点击「充值后恢复使用」。下一次正常项目请求会验证恢复结果。临时限流、网络失败及玩家自己的限额独立处理。

在「玩家权限」选中玩家后，可单独授予 Terra。玩家需要当前游戏连接已准入、已获问舟基础访问权限且已获 Terra 授权，才能在游戏内选择 Terra。新玩家默认使用 DeepSeek。开通 Terra 权限后仍使用 DeepSeek，直到玩家主动切换。关闭基础访问立即阻止新请求，原 Terra 授权可在权限页撤销。

在「服务管理 → 模型服务」设置 Terra 思考强度与速度。强度可选高（high）、极高（xhigh）和最高（max），速度可选标准与高速。默认高、标准。Manager 在每次调用预留预算时保存本次策略，之后的调档适用于后续调用。权限和策略写入使用修订号，冲突时重新读取当前值。

在同页「使用限额」的 GPT 一行填写默认个人周限额、默认个人月限额、全服月预算。初始分别为 $2、$8、$160。个人额度与全服预算可独立开关，已启用的金额设为 0 会暂停相应请求。进入「玩家权限」内的玩家详情可选跟随全局、自定义周/月或不限额。个人不限额的玩家仍受已开启的全服预算约束。变更立即按本期已用与预留重新计算剩余额度。美元账期按北京时间计算，每周一 00:00 开新周，每月 1 日 00:00 开新月。一次调用使用预留时所属周、月，三个窗口独立累计。出网前按输入上界和最大输出预留，预算不足时缩小输出上限；剩余预算无法承接最小输出时拒绝。扣费单位为十亿分之一美元。

| 标准档计费项 | 美元／百万 token |
| --- | --- |
| 普通输入 | 1 |
| 缓存读 | 0.05 |
| 30 分钟缓存写 | 1.25 |
| 输出（含推理） | 5 |

高速档按上述单价的两倍计量。输入超过 272,000 token 时，输入各项乘 2，输出乘 1.5。已确认的用量按上游报告的实际档位和 token 结算，同一操作的相同回执重复提交只记一次。已确认零消费时释放预留，超时或用量不完整时保留预留至相关窗口结束。Manager 先将确认金额写入数据目录下的 `wenzhou/terra-settlements`。数据库恢复后自动重放，成功入账后清理对应文件。相同操作 ID 的桥接重试可继续查询已有结果，Manager 不会为同一 ID 重发上游请求。

### 上线顺序

1. 备份 Manager 统一平台数据，并保留 Manager 数据目录中的 Terra 待结算文件。先更新独立 Manager Service，使其支持问舟存储清单 v6 和 Terra 原生 SQL。按上节在 Manager Service 中配置项目密钥。
2. 更新 Forge 1.20.1 的 Manager 桥接和问舟模组。游戏服务器上线后确认扩展注册与玩家准入正常。新 Service 可接收 v2–v5 问舟存储清单；升级到 v6 后，回退前需核对旧 Service 的存储兼容性。
3. 用绑定的服主账号给测试玩家授予问舟与 Terra 权限。在游戏中选用 Terra，核对答复、真实项目用量回执和额度显示，再扩大授权。已独立验证本地 SQL、管理鉴权和桥接准入；部署、实际项目计量和游戏联机验收待完成。

## 构建

问舟管理页源码在 `admin-web/src`。Manager 的 `managerServiceJar` 任务同时编译问舟管理数据读取器和后台页面，复用 Manager 的共用组件。将两个工程置于约定目录或传入 `-PwenzhouAgentDir`。问舟 Forge 工程的 `build` 任务生成游戏模组。

额度的原生 SQL 适配器在本仓库 `manager-service/src/main/java`。Manager Service 从上述两个源码目录编译，同一插件库保存全局与个人额度、永久累计和幂等回执。仅更新游戏模组无法启用这个接口。

知识记录读取游戏已有的分包玩法笔记，每页 50 条，可按关键词查找并查看首次保存和最近更新的时间。点击一条记录可读正文与证据来源。
