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

离线时，玩家列表来自全局档案，每页 50 名，授权修改仍保存到 Manager。会话列表、正文和累计用量来自游戏已经保存的文件。查询继续执行 30 天保留周期与问答优化授权过滤。在线状态由 Manager 的桥接心跳更新。协议和服务密钥状态可查阅，修改需要游戏服连接。

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

## 构建

问舟管理页源码在 `admin-web/src`。Manager 的 `managerServiceJar` 任务同时编译问舟管理数据读取器和后台页面，复用 Manager 的共用组件。将两个工程置于约定目录或传入 `-PwenzhouAgentDir`。问舟 Forge 工程的 `build` 任务生成游戏模组。

额度的原生 SQL 适配器在本仓库 `manager-service/src/main/java`。Manager Service 从上述两个源码目录编译，同一插件库保存全局与个人额度、永久累计和幂等回执。仅更新游戏模组无法启用这个接口。

知识记录读取游戏已有的分包玩法笔记，每页 50 条，可按关键词查找并查看首次保存和最近更新的时间。点击一条记录可读正文与证据来源。
