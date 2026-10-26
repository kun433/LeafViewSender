# LeafViewSender

Leaf / Paper 1.21.11 插件：只把玩家**看得到**的东西发给客户端，砍掉纯浪费的下行流量。

- 版本：`1.1.0`（服务端 `1.21.11`）
- 产物：`1.21.11/dist/LeafViewSender-1.1.0-1.21.11.jar`
- 依赖：无（不使用 ProtocolLib / PacketEvents，直接编译对接服务端）

---

## 一、它做了三件事

下行带宽的浪费分三层，插件对应三条独立可开关的路径：

### 1. 地形区块：按视野发送

原版会把玩家周围**一整圈**区块都发过去，不管玩家朝哪看；视野内实际只有前方一个扇形。

插件在玩家连接的网络管道上挂一个出站过滤器（位置在区块包被序列化之前）：

- 落在**水平视野扇形**（默认 140°）或**近身缓冲**（默认 3 区块 ≈ 48 格）内 → 放行；
- 其余 → 直接丢弃，客户端收不到，也就不占带宽；
- 玩家转身/移动后，主线程按「由近到远」把应该出现、但客户端还没有的区块补发回去，
  走服务端原生发送路径 `PlayerChunkSender.sendChunk(...)`，抗透视、高度图、方块实体、
  `PlayerChunkLoadEvent` 的表现都与原版一致。

### 2. 孤儿更新：不再给「客户端根本没有的区块」发更新

这是第 1 项的配套收益，很多人会忽略：既然那个区块我们从没发过，服务端之后在这些区块上
继续发的**方块更新、多方块更新、光照更新、方块实体数据、挖掘动画、方块事件、非全局世界事件**，
客户端全部会忽略——100% 是浪费。

插件把这些包直接丢掉（只在「我们确实丢过该区块」时才丢，不会误伤玩家已有的区块）。
红石机、大型挖掘/爆炸场景里这部分流量相当可观。

### 3. 装饰流量：粒子（可选声音）按距离 + 视野裁剪

- **粒子**：原版只按 32 格半径广播，但对身后的玩家也照发；插件再按视野裁一次，纯装饰、无感知。
  `/particle ... force` 这类强制显示的粒子不会被裁；近身缓冲范围内的粒子永远保留。
- **声音**：默认**关闭**。声音是听声辨位（背后苦力怕、脚步、箭矢）的重要来源，裁掉会影响玩法，
  只在确实要压带宽时再打开。

## 二、为什么这能让人「感觉更流畅」

下行带宽本身不直接决定帧率，但它决定了**排队延迟**：链路一旦被无意义流量占满，
真正重要的数据（玩家自身动作、实体位置、区块）也要排队，表现出来就是操作延迟、实体抖动、卡顿。

所以省带宽的两条策略是：

1. **少发**：把客户端根本不会渲染的东西（视野外区块、孤儿更新、身后粒子）不发；
2. **早发**：把玩家**马上就会看到**的东西提前发出去。插件为此做了两件事：
   - **转向预测**：按玩家转视角的速度，提前补发「1~3 tick 后就会看到」的区块，甩鼠标回头时不再先看到空白；
   - **补发不让等**：如果一轮补发被 `max-sends-per-tick` 配额打断（转身、传送后很常见），
     下一 tick 立刻接着补，而不是等 `idle-rescan-ticks`（默认 0.5 秒）之后才继续。

## 三、它做不到什么（重要）

**竖向裁剪做不到，这不是插件没写，是协议限制。**

区块包（`ClientboundLevelChunkWithLightPacket`）按整柱发送：一个区块 = 16×16×世界高度（主世界 384 格）
的所有 section 一次性发给客户端，客户端按固定 section 数量解析。只发「脚底下 3 格」这种竖向切片，
客户端会解析错位或直接断开；就算把被裁掉的 section 写成空气，玩家抬头/低头/飞行时也会看到凭空出现的空洞。

所以竖向用**近身缓冲**来保证：`near-radius-chunks: 3` 表示玩家脚下和身边 48 格一定完整发送。
也就是你要的「脚底下 3 格」，用区块粒度实现成了「脚下和身边 48 格一定在」。

另外两点需要知道：

1. **省的是带宽，不是序列化 CPU。** 区块包在服务端仍然会被构建，只是到了网络层被丢掉——
   Leaf 的发送链路（Moonrise → `PlayerChunkSender` → Netty）没有给插件留「发之前先问一句」的钩子。
2. **快速转身的瞬间仍可能有短暂空洞**（被预测与补齐缓解）。嫌明显就把 `fov-degrees` 调大、
   把 `predictive-lookahead-ticks` 调大，或把 `near-radius-chunks` 加 1。

## 四、安装

1. 把 `LeafViewSender-1.1.0-1.21.11.jar` 放进服务端 `plugins/`（旧的 1.0.0 请删掉）；
2. 启动服务器（首次会生成 `plugins/LeafViewSender/config.yml`）；
3. 改完配置执行 `/leafviewsender reload`。

## 五、配置

### 地形区块

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `enabled` | `true` | 总开关；`false` 时所有模块都放行 |
| `fov-degrees` | `140` | 水平视野总角度，左右各一半。`360` = 关闭方向过滤 |
| `near-radius-chunks` | `3` | 近身永远完整的半径（区块）；`0` = 关闭（不建议） |
| `max-sends-per-tick` | `4` | 每个玩家每 tick 最多补发多少区块，控制转身/传送的带宽峰值 |
| `max-view-distance` | `12` | 参与计算与补发的最大半径（区块），**必须不小于服务器 `view-distance`** |
| `yaw-threshold-degrees` | `10` | 朝向变化超过该角度就重新扫描 |
| `idle-rescan-ticks` | `10` | 静止时也每隔这么多 tick 重扫一次，补发「上次扫描时还没加载完」的 |
| `keep-previous-view` | `true` | 保留上一轮扫描视野做缓冲 |
| `predictive-lookahead-ticks` | `3` | 转向预测提前量（tick），`0` = 关闭（最省流量） |
| `predictive-lookahead-max-degrees` | `45` | 预测提前角度的上限，防止甩视角时一次铺太多 |

### 下行流量裁剪

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `traffic.orphan-updates` | `true` | 丢弃「客户端没有的区块」上的方块/光照/方块实体等更新 |
| `traffic.particles.enabled` | `true` | 粒子裁剪开关 |
| `traffic.particles.max-distance` | `32` | 超过该距离的粒子不发送 |
| `traffic.particles.respect-view-cone` | `true` | 是否同时按视野扇形过滤粒子 |
| `traffic.sounds.enabled` | `false` | 声音裁剪开关（开之前先考虑听声辨位） |
| `traffic.sounds.max-distance` | `64` | 超过该距离的声音不发送 |
| `traffic.sounds.respect-view-cone` | `false` | 声音是否也按视野扇形过滤 |
| `traffic.wire-stats` | `true` | 统计真实下行字节数（用于 `/lvw status` 显示 KB/s） |

### 其它

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `worlds.blacklist` | `[]` | 不参与过滤的世界名（不区分大小写） |
| `debug` | `false` | 更详细的日志 |

### 调参方向

- **更省流量**：`fov-degrees: 110~130`、`near-radius-chunks: 2`、`predictive-lookahead-ticks: 0`、开启 `traffic.sounds`
- **更稳的画面**：`fov-degrees: 180~220`、`near-radius-chunks: 4`、`max-sends-per-tick: 6`、`predictive-lookahead-ticks: 4~5`
- **高负载/高移动玩家（PvP、飞行）**：优先加大 `predictive-lookahead-ticks` 与 `max-sends-per-tick`，`fov` 保持 140 左右

## 六、指令与权限

| 指令 | 说明 |
| --- | --- |
| `/leafviewsender status`（别名 `/lvw`、`/lvs`） | 各模块状态 + 每个玩家的已知区块/拦截/补发/丢弃数与**真实下行 KB/s** |
| `/leafviewsender hints` | 检查 `server.properties`，给出配置层面的省流量建议 |
| `/leafviewsender reload` | 重载配置 |

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `leafviewsender.admin` | op | 使用上述指令 |
| `leafviewsender.bypass` | 无 | 不参与过滤，始终完整接收（管理员、录屏、调试） |

`/lvw status` 里的「下行」是**压缩加密后**的线路字节换算来的，可以直接用来对比开关前后的效果。
`/lvw hints` 会检查压缩阈值与视距——比如 `network-compression-threshold: -1`（压缩没开）
往往比本插件带来的节省还大，值得先修。

## 七、实现要点

- **拦截位置**：`Connection.channel.pipeline()` 中 `packet_handler` 之前（拿到的还是 `Packet` 对象）。
  若该名字不存在，自动退回 `encoder` 之后。
- **统计位置**：另一个只处理出站的处理器放在管道最前端（`addFirst`），
  出站方向它是最后一个被经过的，因此拿到的是压缩+加密后的真实字节。
- **过滤判定**：区块中心相对玩家的方向角与 yaw 比较（MC 约定 yaw=0 朝 +Z、90 朝 -X），
  落在 `±fov/2` 内即放行；近身缓冲用区块坐标距离判定。
- **记账**：服务端自己的 `sentChunks` 里包含被我们丢掉的区块，所以插件自己维护两套集合：
  `clientChunks`（客户端真正持有）与 `droppedChunks`（我们明确丢过的，才允许裁孤儿更新）。
- **补发**：`PlayerChunkSender.sendChunk(ServerGamePacketListenerImpl, ServerLevel, LevelChunk)`；
  只对 `ServerChunkCache.getChunkNow(...)` 已加载的区块补发，未加载的留到下一轮重试。
- **线程**：网络线程只读不可变快照（`volatile`）与并发集合，补发与注入都在主线程 / 事件循环上完成。
- **启动自检**：分两级。核心项（区块过滤依赖的 `PlayerChunkSender.sendChunk`、`connection`/`channel` 字段、
  `getChunkNow`、`getX/getZ`）对不上 → 整体退化成「不过滤」，保证地形显示不受影响；
  可选模块（孤儿更新/粒子/声音）对不上 → 只关掉对应模块并在日志里说明。

### 我刻意没做的两件事

- **实体移动包节流**（对远处实体降频发移动包）：省流量明显，但会让远处实体「卡住/瞬移」，
  在 PvP 里还可能造成误判；原版已有 `spigot.yml` 的 `entity-tracking-range` 可以整体收缩，更可控。
- **合并 flush（把一 tick 内的小包攒到 tick 末再刷）**：Leaf 自身已经有
  `optimize-non-flush-packet-sending`（补丁 `0212`）在做这件事，插件再做一遍容易与它打架，
  收益小而风险高。

### 版本耦合

插件直接用服务端类（`net.minecraft.*`、`org.bukkit.craftbukkit.*`）编译，属于**版本专用**插件：

- 编译基线：Paper 1.21.11 服务端 jar（`lib/server/paper-1.21.11.jar`，仅编译期使用，不会打进产物）；
- 运行目标：Leaf 1.21.11。已对照 Leaf 源码核对：Leaf 的 `0198-async-chunk-sender` 只是在
  `PlayerChunkSender` 上**新增**一个 4 参数重载，3 参数版本仍然存在；`ServerChunkCache.getChunkNow`、
  `Connection.channel`、`ServerCommonPacketListenerImpl.connection`、`ClientboundLevelChunkWithLightPacket#getX/getZ`
  均未被 Leaf 改动。

升级服务端版本后如果报 `NoSuchMethodError` / `NoClassDefFoundError`，需要用新版服务端 jar 重新编译一次。

## 八、构建

```powershell
cd LeafViewSender\1.21.11
powershell -ExecutionPolicy Bypass -File build.ps1
```

- 需要 JDK 21（默认 `C:\Program Files\Java\jdk-21`，可用环境变量 `LEAFVIEWSENDER_JDK` 覆盖）；
- 编译期依赖放在 `lib/compile`（Paper API、Adventure、Netty）与 `lib/server`（Paper 服务端 jar）；
- 产物输出到 `dist/`，`lib` 里的任何内容都不会被打进 jar。

## 九、验证情况

- 已完成：
  - `javac --release 21` 零错误编译；产物含 12 个类与 `plugin.yml`、`config.yml`；
  - 所有用到的 NMS 签名逐条对照 Paper 1.21.11 服务端 jar（`javap`）与 Leaf `ver/1.21.11` 补丁源码核对
    （`PlayerChunkSender.sendChunk` 三参数版、`Connection.channel`、`ServerCommonPacketListenerImpl.connection`、
    `ServerChunkCache.getChunkNow`、`ClientboundLevelChunkWithLightPacket#getX/getZ` 均未被 Leaf 改动）；
  - 离线几何断言 29/29 通过（`tests/GeometryTest.java`）：yaw=0/90/180/270 的正前方分别为 +Z/-X/-Z/+X、
    近身缓冲、`fov=360` 关闭过滤、角度归一化、区块键打包、点坐标的扇形与距离判定（粒子/声音用）。
    运行：`powershell -ExecutionPolicy Bypass -File tests\run-geometry-test.ps1`。
- 未做：真机进服观感验证（按约定不默认跑冒烟测试）。建议第一次上线时先给自己的账号开
  `leafviewsender.bypass`，另开一个测试号，配合 `/lvw status` 观察「下行 KB/s」与「补发/丢弃」计数是否符合预期。
