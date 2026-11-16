# HubProfile

玩家从 Fabric 生存服回到 Paper 大厅后，右键快捷栏中的个人头像，就能看到自己在生存服积累的游玩时长、死亡次数、击杀生物数和挖掘方块数。

## 安装到大厅

1. 从本仓库 Releases 下载 `HubProfile-1.0.0.jar`，放入 Paper 1.21.11 大厅的 `plugins/` 目录，使用 Java 21 启动大厅。
2. 打开生成的 `plugins/HubProfile/config.yml`，将 `fabric-stats-directory` 设为大厅进程可读取的 Fabric 存档统计目录，例如 `/srv/minecraft/survival/world/stats`。如果修改过 `level-name`，请使用实际世界名。
3. 以 OP 执行 `/hubprofile reload`。用在 Fabric 玩过的账号返回大厅，右键头像或输入 `/hubprofile` 查看档案。

## 数据从哪里来

插件使用大厅收到的玩家 UUID，只读 Fabric 存档中的 `stats/<UUID>.json`。Velocity 转发给两个子服的 UUID 必须一致。两服部署在不同机器时，需把统计目录挂载到大厅能够读取的位置。客户端和 Fabric 端均无需安装插件。

| 菜单项 | 计算方式 |
| --- | --- |
| 游玩时长 | Fabric 的 `minecraft:play_time` 每 20 游戏刻折算为 1 秒，包含挂机时间 |
| 死亡次数 | `minecraft:deaths` |
| 击杀生物数 | `minecraft:mob_kills` |
| 挖掘方块数 | 累加 `minecraft:mined` 下的方块计数，包含已记录的模组方块 |

点击菜单中的刷新按钮会重新读取最近一次存盘的数据。若记录缺失，可用管理员命令查看当前 UUID、数据目录和读取结果。

| 命令 | 默认权限 | 用途 |
| --- | --- | --- |
| `/hubprofile` 或 `/myprofile` | 所有玩家 | 查看自己的生存档案 |
| `/hubprofile check` | OP | 诊断统计读取状态 |
| `/hubprofile reload` | OP | 应用配置更改 |

## 从源码构建

在仓库根目录使用 JDK 21 和 Maven 运行 `mvn clean package`。测试和构建通过后，安装 `target/HubProfile-1.0.0.jar`。标有 `original-` 的中间产物不含打包后的依赖。
