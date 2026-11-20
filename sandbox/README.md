# 问舟玩法查询沙箱

启用后，DeepSeek 可以在一次性 Linux 环境中使用 shell 管道读取玩法资料。Forge 的配方与标签查询仍提供游戏运行态依据。服务端 JVM 通过标准输入把脚本交给固定的本机运行器，每次查询使用独立的进程、网络、挂载命名空间和临时目录。

## 运行环境

1. 在独立的 Linux 查询环境安装 Google nsjail 和面向玩家的工具。参考配置已按 nsjail 3.6 验证。使用普通账号启动运行器。将 `wenzhou-read` 安装到 `/usr/local/libexec/`，将 `shell.cfg` 安装到 `/etc/wenzhouai/`。文件与根文件系统由系统管理员维护。
2. 将无账号资料、凭据或宿主配置的 Linux 工具文件系统置于 `/opt/wenzhouai/rootfs`，预建 `/game`、`/proc`、`/tmp`、`/dev/null`、`/dev/urandom` 挂载位置。提供 bash、rg、jq、unzip、jar 和 javap。安装 CFR 后，可将 JAR 放入根文件系统的 `/opt/tools/cfr.jar` 并安装 `mod-source`，按类反编译到标准输出。
3. 在 `/opt/wenzhouai/game` 准备当前整合包的玩法资料副本，并运行 `python3 catalog.py /opt/wenzhouai/game` 生成物品翻译检索表。可保留任意模组自有的玩法目录及相对路径；混合目录只复制确认可公开给模型的文件。不要复制服主凭据、聊天、玩家存档、系统路径、设备或套接字。更新模组或魔改脚本时同步更新此视图和索引。
4. 在系统启动时用管理员账号运行 `wenzhou-limits <执行查询的账号>`，为共享查询工作组配置 384 MiB 内存和 40 个进程上限。cgroup v2 需要在已委派的可写层级配置进程迁移权限。若使用已有的容器资源管理，请在专用查询环境内配置；不要为游戏容器开放特权。隔离或资源控制不可用时运行器返回失败。
5. Linux 测试服务以 JVM 参数 `-Dwenzhouai.shell.enabled=true` 开启。Windows 本地测试可额外设置 `-Dwenzhouai.shell.wsl=<专用发行版名称>`，WSL 以 `wenzhou` 用户启动相同运行器。模型密钥留在发出 API 请求的游戏服务进程。沙箱没有网络，命令的输出与返回码交给模型解释。

## 游戏服运行在 Docker 中时

Linux 服务端也支持实例中的 Unix 套接字 `config/wenzhouai/gameplay.sock`。宿主通过 systemd 的 Accept=yes 服务将连接的标准输入和输出交给 `container-worker.py`，设置 `WENZHOU_GAME` 指向游戏目录。套接字仅授予游戏服务账号访问，限制同时连接数。游戏容器保持原有权限。

在宿主的 `/opt/pixelmc-wenzhou` 放置查询脚本、实际工具镜像的 SHA-256 ID 文件 `image-id` 以及经服主核准的玩法文件指纹清单。运行 `prepare-container-view.py GAME APPROVED_JSON /opt/pixelmc-wenzhou`，生成只读快照和翻译索引。整合包更新后刷新。查询器核对快照版本，在无网络、无额外 capability 的临时容器中挂载该快照，限制内存、CPU、进程、输出和运行时间。
