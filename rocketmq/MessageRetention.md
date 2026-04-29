# RocketMQ 消息保存时长与 ConsumeQueue 文件清理机制

> 本文讲清楚两个紧密关联的存储问题：
> 1. **消息在 Broker 端到底能保存多久？谁决定？怎么清理？**
> 2. **ConsumeQueue 文件是什么布局？什么时候删？怎么判断哪些文件可以删？**
>
> 这两个点在面试里常被合起来问，因为 ConsumeQueue 的清理是跟着 CommitLog 走的，不理解 CommitLog 的保留机制就讲不清 ConsumeQueue 的清理。

---

## 一、消息在 Broker 端的保存时长

RocketMQ 的消息保存不是"每条消息独立判断过期"，而是**以 CommitLog 文件为单位批量清理**。核心机制是 `DefaultMessageStore#cleanFilesPeriodically` 每 10 秒扫描一次 CommitLog，满足条件的整个文件会被删除。

### 1.1 关键配置参数

这些都是 Broker 端配置（`broker.conf`）：

| 参数 | 默认值 | 含义 |
|:---|:---:|:---|
| `fileReservedTime` | **72（小时）** | CommitLog 文件保留时间，默认 **3 天** |
| `deleteWhen` | `04` | 触发清理的**小时点**（每天执行一次，按 **Broker 机器本地时区**解析，生产环境多为 `Asia/Shanghai`） |
| `diskMaxUsedSpaceRatio` | 75 | 磁盘使用率阈值（75%），超过后**立即触发清理**（不等 `deleteWhen`），但**仍只清理已过期文件**（尊重 `fileReservedTime`） |
| `diskSpaceWarningLevelRatio` | 90 | 磁盘告警阈值（90%），超过后 Broker **拒绝写入** |
| `diskSpaceCleanForciblyRatio` | 85 | 强制清理阈值（85%），超过后**无视 `fileReservedTime`**，即便未过期也从最老文件开始强制删 |
| `cleanResourceInterval` | 10000（ms） | 清理任务扫描周期，默认 10 秒 |

### 1.2 清理触发的三种条件

每 10 秒扫描一次，**任一条件满足**就触发清理：

```
条件 1：时间触发（正常清理）
  - 当前时间到了 deleteWhen（默认 04:00）
  - 且 CommitLog 文件的最后修改时间距今已超过 fileReservedTime（默认 72h）
  - 一次最多删 10 个文件（源码常量 DELETE_FILES_BATCH_MAX = 10，避免 IO 压力过大）

条件 2：磁盘压力触发（加速清理）
  - 磁盘使用率 > diskMaxUsedSpaceRatio（默认 75%）
  - 不等 deleteWhen，立即触发清理
  - 但此触发【仍要求文件已过期】（lastModifiedTimestamp 超过 fileReservedTime），
    也就是"只是把清理时间点提前了"，不会删没过期的文件
  - 会在后续每轮 10s 扫描里连续触发，直到磁盘占用降下来

条件 3：紧急触发（兜底保护）
  - 磁盘使用率 > diskSpaceCleanForciblyRatio（默认 85%）
  - 此触发【无视 fileReservedTime】，即便文件还没过期也强制删最老的文件
  - 这是防止磁盘写满导致 Broker 宕机的保命机制
```

**额外的拒写保护**：一旦磁盘使用率 > `diskSpaceWarningLevelRatio`（90%），Broker 会直接拒绝新消息写入（返回 `SERVICE_NOT_AVAILABLE`），等待清理任务降低磁盘占用后才恢复。

### 1.3 几个容易被误解的细节

**细节 1：消息级别的"过期"不存在**

- 一条消息能否保留 72 小时，取决于它所在的 **CommitLog 文件整体**是否被删
- 判断依据是**文件的 `lastModifiedTimestamp`（最后修改时间，来自 `File#lastModified()`）距今是否超过 `fileReservedTime`**
- CommitLog 文件写满后会被新文件替代，老文件不再被写入，`lastModifiedTimestamp` 基本停在"文件最后一次写入/flush 的那一刻"，近似等价于"文件内最新一条消息的写入时间"
- 所以对于一个 1 GB 的 CommitLog 文件，只要最新一条消息（也就是写满时刻）不超过 72h，整个文件就会保留；即使文件里最老的消息已经写入 100 小时也不会被单独清理
- 因此消息实际存活时间 **≥ `fileReservedTime`**，额外的"搭便车"时长 = **写满一个 CommitLog 文件所花的时间**（取决于业务写入 TPS，低流量场景可能是几小时，高流量场景只有几分钟）

**细节 2：消费进度和保留时长无关**

- RocketMQ **不会因为"这条消息已被所有消费组消费过"就提前删除**（这点和 Kafka 一致）
- 就算所有 Consumer 都消费完了，消息依然按 `fileReservedTime` 保留
- 这是为了支持**消费者回溯消费**（`CONSUME_FROM_TIMESTAMP`、`CONSUME_FROM_FIRST_OFFSET` 等）

**细节 3：消费者长时间离线会丢消息**

- 消费者下线时长超过 `fileReservedTime`（比如 72h）后重新上线，发现自己的 offset 指向已被删除的 CommitLog → Broker 返回 `PULL_OFFSET_MOVED`
- Broker 把 offset 重置到**当前最老的消息位置**（该 queue 的 minOffset）
- **消费者会丢失"未被 Broker 保留"的那部分消息**：丢失范围是从"消费者最后提交的 offset"到"当前 queue minOffset"之间的所有消息，具体条数取决于期间 CommitLog 清理了多少个文件
- 规避办法：`broker.conf` 里调大 `fileReservedTime`，或监控消费堆积不要接近保留期

**细节 4：ConsumeQueue / IndexFile 跟随清理**

- CommitLog 删除会推进 `CommitLog.minOffset`，**ConsumeQueue** 的清理（见本文第三部分）就是跟随这个 minOffset 变化、由 `CleanConsumeQueueService` 被动执行
- **IndexFile** 有自己的清理逻辑：由 `IndexService#deleteExpiredFile(long offset)` 扫描，入参 `offset` 就是 **CommitLog 的 minOffset**（物理偏移）。每个 IndexFile header 里记录了 `beginPhyOffset`（首条消息的 CommitLog 物理偏移）和 `endPhyOffset`（末条消息的 CommitLog 物理偏移）。判断条件是 **`indexFile.getEndPhyOffset() < CommitLog.minOffset`** —— 即该 IndexFile 里所有消息都已被 CommitLog 清掉时，整文件删除
- IndexFile header 里还额外保存了 `beginTimestamp` / `endTimestamp`，但这两个字段用于"按时间查消息"的业务查询，不用于清理判断
- 三类文件（CommitLog / ConsumeQueue / IndexFile）的清理由同一个调度入口 `DefaultMessageStore.cleanFilesPeriodically()` 驱动，但各自有独立的 Service 执行，互不阻塞

**细节 5：Master 和 Slave 的 fileReservedTime 各自独立配置**

- `fileReservedTime` 是 Broker 级别的独立配置，Master 和 Slave 各读自己的 `broker.conf`
- 生产常见做法：**Slave 配得比 Master 更长**（比如 Master 72h、Slave 168h），用于冷备份、历史查询、灾难恢复等场景
- 注意：Slave 配得短、Master 配得长也没问题，只是没什么实际意义

### 1.4 生产环境推荐配置

| 业务场景 | 推荐 `fileReservedTime` | 理由 |
|:---|:---:|:---|
| 订单/支付等核心业务 | 72h（默认）~ 168h（7 天） | 支持回溯排查、补偿 |
| 日志/监控消息 | 24~48h | 量大但价值衰减快 |
| 对回溯能力要求高 | 168h | 配合磁盘扩容 |
| 容器化部署磁盘紧张 | 24h | 但要监控 `diskSpaceCleanForciblyRatio` 是否频繁触发 |
| 金融级审计 | 720h（30 天）以上 | 配合单独的大容量存储磁盘 |

### 1.5 与 Kafka 的对比

| 维度 | RocketMQ | Kafka |
|:---|:---|:---|
| 清理粒度 | 按 CommitLog 文件（默认 1 GB） | 按 segment 文件（默认 1 GB） |
| 默认保留时长 | 72 小时 | 168 小时（7 天） |
| 清理维度 | 时间 + 磁盘空间 | 时间、大小、log compaction（按 key 只保留最新值） |
| 存储模型 | **所有 Topic 共用一个 CommitLog** | 每个 partition 独立 log 文件 |
| 消费完是否删除 | 不删除 | 不删除 |

RocketMQ "所有 Topic 共享 CommitLog" 的设计，使得单机能支持海量 Topic（几万+），但也决定了清理只能按文件粒度，不能按 Topic 粒度。

---

## 二、ConsumeQueue 的物理布局与清理

### 2.1 ConsumeQueue 不是"一个 queue 一个文件"

很多人以为 `ConsumeQueue` 就是一个文件，**这是错误的**。实际结构是：**每个 queue 对应一个目录，目录下有多个固定大小的文件**。

```
${storePathRootDir}/consumequeue/
  │
  ├─ OrderTopic/                          ← Topic 目录
  │     │
  │     ├─ 0/                             ← queueId = 0 的目录
  │     │   ├─ 00000000000000000000       ← 第 1 个文件，存索引 0 ~ 299999
  │     │   ├─ 00000000000006000000       ← 第 2 个文件，起始字节偏移 6000000
  │     │   ├─ 00000000000012000000       ← 第 3 个文件
  │     │   └─ ...
  │     │
  │     ├─ 1/                             ← queueId = 1 的目录
  │     │   ├─ 00000000000000000000
  │     │   └─ ...
  │     │
  │     └─ 7/                             ← queueId = 7 的目录
  │         └─ ...
  │
  └─ PayTopic/                            ← 另一个 Topic
        └─ 0/
             └─ ...
```

### 2.2 每个索引条目固定 20 字节

```
┌──────────────────────┬──────────────┬───────────────────────┐
│  CommitLog 物理偏移    │   消息长度    │     Tag hashCode      │
│      8 字节           │    4 字节     │       8 字节          │
└──────────────────────┴──────────────┴───────────────────────┘
```

- **CommitLog 物理偏移（8 字节）**：消息体在 CommitLog 中的绝对字节位置，Broker 根据它去 CommitLog 捞真实消息
- **消息长度（4 字节）**：消息体大小，用于 CommitLog 读取时的边界控制
- **Tag hashCode（8 字节）**：消息 Tag 的哈希值，用于服务端做 Tag 过滤（见 `MessageFilter.md`）

### 2.3 单文件固定 5.72 MB

- 每个 ConsumeQueue 文件固定存 **300,000 条索引**（由 `mapedFileSizeConsumeQueue` 控制）
- 单文件字节数 = 300000 × 20 = **6,000,000 字节 ≈ 5.72 MB**
- 文件名是**起始索引对应的字节偏移**（用 20 位数字表示），便于通过算术运算 O(1) 直接定位目标文件（详见 2.5 节）

### 2.4 文件滚动规则

当前文件写满 300,000 条索引后，Broker 会新建下一个文件：

```
文件 1: 00000000000000000000   起始字节偏移 = 0          （索引 0 ~ 299,999）
文件 2: 00000000000006000000   起始字节偏移 = 6000000    （索引 300,000 ~ 599,999）
文件 3: 00000000000012000000   起始字节偏移 = 12000000   （索引 600,000 ~ 899,999）
...
```

### 2.5 O(1) 定位算法（核心优势）

给定一个 `queueOffset`（比如 450,000），Broker 可以**零扫描**直接定位（**前提：无文件被删除，或通过 MappedFileQueue 维护的文件列表遍历定位**）：

```
Step 1: 计算字节偏移
  byteOffset = queueOffset × 20 = 450,000 × 20 = 9,000,000

Step 2: 定位文件（理想场景：无文件被删）
  目标文件起始偏移 = byteOffset - (byteOffset % mappedFileSize)
                  = 9,000,000 - (9,000,000 % 6,000,000)
                  = 6,000,000
  → 文件名 = 00000000000006000000

Step 3: 文件内偏移
  fileInnerOffset = byteOffset - 文件起始偏移
                 = 9,000,000 - 6,000,000
                 = 3,000,000
  → mmap 到这个位置直接读 20 字节拿到索引
```

**关于"取模定位"的重要前提**：上面 Step 2 用 `byteOffset % mappedFileSize` 的简化算法，**只在所有 ConsumeQueue 文件都未被清理时成立**（即第一个文件起始偏移为 0）。一旦发生清理（比如第一个保留文件变为 `00000000000012000000`），取模定位会错。

**真实源码实现**（`MappedFileQueue#findMappedFileByOffset`）：

- 维护一个按起始偏移有序的 `MappedFile` 列表（`CopyOnWriteArrayList`）
- 设首个 MappedFile 的 `fileFromOffset`（即起始物理偏移）为 `firstOffset`，目标 offset 在列表中的索引为：
  ```
  index = (int)(offset / mappedFileSize) - (int)(firstOffset / mappedFileSize)
  ```
- 再用 `mappedFiles.get(index)` 直接拿到目标 MappedFile
- 这样不管首个文件起始偏移是 0 还是 `00000000000012000000`，都能 O(1) 定位
- 若算术计算出的 `index` 越界，或定位到的 MappedFile 不满足 `fileFromOffset <= offset < fileFromOffset + fileSize`（极少数并发删除场景会发生），源码 fallback 到遍历 `mappedFiles` 列表，逐个按此区间条件比对，找到真正包含该 offset 的 MappedFile

核心优势仍然是**零扫描**，这也是 RocketMQ 能支持高 TPS 消费的关键。

### 2.6 ConsumeQueue 为什么是"多文件"而不是"单文件"

| 设计决策 | 原因 |
|:---|:---|
| **多文件** | ① 定时清理方便，整个文件一起删；② 和 CommitLog 结构保持一致，mmap 管理代码可复用；③ 避免单个超大文件占用过多进程虚拟地址空间 |
| **单文件固定 5.72 MB** | 足够小 → ① 清理粒度细，悬空索引浪费最多只有几 MB；② 单文件 mmap 建立/销毁开销低，不会因 page fault 集中爆发导致延迟尖刺 |
| **文件名 = 起始字节偏移** | 通过算术运算直接定位文件，无需额外的元数据索引 |
| **索引条目固定 20 字节** | 定长索引使得"`queueOffset × 20` 直接定位"成为可能 |

### 2.7 存储成本估算

ConsumeQueue 本身非常轻量。假设 `OrderTopic` 有 8 个 queue，累计写入 2000 万条消息（平均分布）：

```
每个 queue 约 250 万条索引
每个 queue 文件数 = ceil(2,500,000 / 300,000) = 9 个文件
  其中 8 个是装满的（每个 300,000 条），1 个装了约 100,000 条（约 1/3 满）
每个 queue 占用 = 8 × 5.72 MB + (100,000 × 20 字节 / 1,024 / 1,024) 
              ≈ 45.76 MB + 1.91 MB
              ≈ 47.67 MB
8 个 queue 总占用 ≈ 381 MB
```

对比 CommitLog 里 2000 万条消息（假设每条 1 KB）= 20 GB，**ConsumeQueue 的存储成本只占 CommitLog 的约 2%**。这正是 RocketMQ 能支持海量 Topic 的原因 —— 索引本身成本极低。

---

## 三、ConsumeQueue 文件的清理机制

### 3.1 清理周期和入口

- 由 `DefaultMessageStore.CleanConsumeQueueService` 负责
- 该服务的扫描间隔参数是 **`deleteLogicsFilesInterval`**（默认 **100 ms**），通过 `ServiceThread#waitForRunning` 循环等待——注意这和 CommitLog 清理服务的 `cleanResourceInterval`（10s）是**两个不同的参数**
- 但实际的**删除动作是被动的**：只有当 CommitLog 清理推进了 minOffset，ConsumeQueue 这边才会有"可删文件"；CommitLog 没删，ConsumeQueue 这边扫描一圈也不会做任何事
- 所以扫描虽然很频繁（100ms 一次），但实战中观测到的 ConsumeQueue 文件删除频率 ≈ CommitLog 文件的删除频率（默认每日 `deleteWhen`/04:00 附近批量触发，或磁盘压力触发）

### 3.2 判断条件（核心）

**一个 ConsumeQueue 文件可以删除当且仅当：该文件内最后一条索引的 CommitLog offset < CommitLog 当前最小 offset**。

#### 为什么只看"最后一条"？

ConsumeQueue 文件内部的条目是**按写入顺序追加**的，而 CommitLog 的物理 offset 是**全局单调递增**的。所以：

> **ConsumeQueue 文件内所有条目的 CommitLog offset 也是单调递增的**

于是"所有条目都 < CommitLog 最小 offset" 等价于 "**最大（最后一条）条目的 CommitLog offset < CommitLog 最小 offset**"。

判断只需 O(1)：读文件末尾 20 字节拿最后一条索引的 CommitLog offset 即可。

### 3.3 清理逻辑伪代码

`ConsumeQueue` 的清理入口是 `ConsumeQueue#destroy()`，内部调用 `this.mappedFileQueue.deleteExpiredFileByOffset(offset, CQ_STORE_UNIT_SIZE)`，真正的判断逻辑在 `MappedFileQueue#deleteExpiredFileByOffset(long offset, int unitSize)`。

核心逻辑伪代码（便于理解，不是可运行代码）：

```
minCommitLogOffset = CommitLog.getMinOffset()

foreach file in ConsumeQueue.mappedFiles:          # 按起始偏移从小到大
    # 定位到文件倒数第 20 字节，读取最后一条索引
    lastEntry = readEntryAt(file, file.size - 20)
    lastEntryCommitLogOffset = lastEntry.commitLogOffset

    if lastEntryCommitLogOffset < minCommitLogOffset:
        # 文件里最"新"的那条索引都已经指向被删除的消息了
        # → 整个文件所有条目都失效，可以删除
        file.destroy()
    else:
        # 最后一条还有效，停止扫描
        # （ConsumeQueue 按序追加，前面的文件更老，已在上一轮迭代里处理）
        break
```

> 注：以上是**简化的过程示意**，不是可运行源码。真实源码 `MappedFileQueue#deleteExpiredFileByOffset(long offset, int unitSize)` 会传入 `unitSize=20`（`CQ_STORE_UNIT_SIZE`），通过 `SelectMappedBufferResult` 读取文件末尾 unit，比较其 CommitLog offset 后决定是否删除。

### 3.4 具体例子

假设 CommitLog 刚清理完，当前最小 offset = `15,000,000,000`（表示前 150 亿字节的 CommitLog 已被删）：

```
ConsumeQueue/OrderTopic/0/ 下有以下文件：

文件 A: 00000000000000000000  （含索引 0 ~ 299,999，共 30 万条）
  索引 0        CommitLog offset = 0
  索引 1        CommitLog offset = 1024
  ...
  索引 299,999  CommitLog offset = 3,000,000,000   ← 最后一条
  
  判断：3,000,000,000 < 15,000,000,000 ✓ → 整个文件可删

文件 B: 00000000000006000000  （含索引 300,000 ~ 599,999）
  索引 300,000  CommitLog offset = 3,000,000,000
  ...
  索引 599,999  CommitLog offset = 12,000,000,000  ← 最后一条
  
  判断：12,000,000,000 < 15,000,000,000 ✓ → 整个文件可删

文件 C: 00000000000012000000  （含索引 600,000 ~ 899,999）
  索引 600,000  CommitLog offset = 12,000,000,000
  ...
  索引 899,999  CommitLog offset = 20,000,000,000  ← 最后一条
  
  判断：20,000,000,000 < 15,000,000,000 ✗ 
  → 停止扫描（文件 C 及之后都保留）
  → 文件 C 前 1/3（CommitLog offset 落在 12,000,000,000 ~ 15,000,000,000 的那部分索引）
     其实已经是悬空索引，但不会单独清理——粒度就是"整个 ConsumeQueue 文件"
```

### 3.5 悬空索引的处理

由于清理粒度是"整文件"，**未到清理条件的 ConsumeQueue 文件内部可能存在"悬空索引"**（指向已删除的 CommitLog 消息）。

**Consumer 如果 pull 到这些悬空索引会怎样？**

要分清两个不同层面的 offset 校验：

1. **ConsumeQueue 层面的 offset 校验**（发生在 `DefaultMessageStore#getMessage`）
   - Consumer pull 请求里的 `queueOffset` 会先和当前 ConsumeQueue 的 `[minOffset, maxOffset]` 做比较
   - 如果 `queueOffset < minOffset`（这意味着前面的 ConsumeQueue 文件已被整体删除），Broker 内部返回 `GetMessageStatus.OFFSET_TOO_SMALL`
   - `PullMessageProcessor` 把它转换为客户端响应码 `ResponseCode.PULL_OFFSET_MOVED`
   - 客户端 `PullAPIWrapper` 收到后会触发 offset 纠正，重置为 Broker 返回的 `nextBeginOffset`（= 当前 queue 的 minOffset），**自动跳过已删除部分**

2. **CommitLog 层面的兜底**（极少数情况，ConsumeQueue 文件未删但索引指向的 CommitLog 已删）
   - Broker 按 ConsumeQueue 里的物理偏移去读 CommitLog，发现 `offsetPy < CommitLog.minOffset`
   - 源码 `DefaultMessageStore#getMessage` 会把本次 `GetMessageResult` 的 status 先置为 `MESSAGE_WAS_REMOVING`，跳过这条索引（不放入返回 buffer），继续读 ConsumeQueue 的下一条索引
   - 后续若在同一批次中读到了有效消息（`offsetPy >= minPhyOffset`），status 会被覆盖为 `FOUND`；若整批索引都是悬空，最终保留 `MESSAGE_WAS_REMOVING` 作为返回 status
   - 无论哪种 status，Broker 返回的 `nextBeginOffset` 都指向下一条待查询的 queueOffset，客户端继续下一轮 pull 直到命中真实消息

所以 ConsumeQueue 文件内的悬空索引**不需要主动清理**，Broker + Consumer 通过 offset 纠正和索引跳过两层机制自动容错，等整文件过期时再统一删。

### 3.6 设计取舍

ConsumeQueue 清理逻辑"只看文件末尾"的设计取舍：

| 优点 | 代价 |
|:---|:---|
| **简化**：不需要维护"失效索引位图"或做索引压缩 | ConsumeQueue 文件内可能存在悬空索引，浪费少量磁盘空间 |
| **性能**：O(1) 判断，每个文件只读 20 字节 | 依赖 Consumer 自动跳过悬空索引的容错能力 |
| **对称**：和 CommitLog 清理粒度一致 | 清理时效性略低（最坏情况下要多等一个文件周期） |

由于 ConsumeQueue 本身很轻（每条 20 字节，单文件 5.72 MB），悬空部分最多浪费一个文件的空间（几 MB 量级），相对 CommitLog 的 GB 级体量可忽略。这是典型的"空间换简单"设计。

---

## 四、整体清理流程时序图

```
                    Broker 启动后
                        │
        ┌───────────────┴────────────────┐
        │                                │
        ▼                                ▼
  每 10s 扫描一次                   每 100ms 扫描一次
  CleanCommitLogService          CleanConsumeQueueService
  (cleanResourceInterval)        (deleteLogicsFilesInterval)
        │                                │
        │                                │
   ┌────┴─────┐                          │
   │          │                          │
时间触发    磁盘压力                       │
(04:00)   (>75%)                          │
   │          │                          │
   │          │                          │
   └────┬─────┘                          │
        ▼                                │
  删除过期的 CommitLog 文件                 │
  → CommitLog minOffset 推进                │
                                          │
                                          ▼
                                  读取每个 ConsumeQueue 文件的最后一条索引
                                          │
                                  最后一条 CommitLog offset < minOffset?
                                          │
                          ┌───────────────┴────────────────┐
                          │                                │
                         是                                否
                          │                                │
                          ▼                                ▼
                  删除该 ConsumeQueue 文件             停止扫描该 queue
                          │                                
                  继续检查下一个文件
```

两个清理服务的节奏差异：
- `CleanCommitLogService` 每 10s 扫描（`cleanResourceInterval`），**主动**删文件（时间/磁盘压力触发）
- `CleanConsumeQueueService` 每 100ms 扫描（`deleteLogicsFilesInterval`），**被动**跟进（CommitLog minOffset 不变就不删）
- 看起来 ConsumeQueue 扫描频率高很多，但因为是被动的，实际删除次数很少——绝大多数扫描只是"读一下 CommitLog.minOffset 发现没变，然后直接退出"

清理期间的并发安全：

- 文件从 MappedFileQueue 中移除后，会先 `MappedFile#shutdown` 等待引用计数归零（有正在读的线程时需等待），然后才真正 `destroy` 删文件
- Consumer pull 请求若 offset 小于 queue minOffset（请求的是已删部分），Broker 内部返回 `GetMessageStatus.OFFSET_TOO_SMALL`，外部响应码为 `PULL_OFFSET_MOVED`，客户端按 `nextBeginOffset` 自动纠正

---

## 五、一句话总结

> **保存时长**：RocketMQ 按 **CommitLog 文件**为单位清理，默认保留 72 小时（`fileReservedTime`），清理由**定时**（`deleteWhen=04:00`）和**磁盘压力**（`diskMaxUsedSpaceRatio=75%`）双重触发，**和消费进度无关**——消费完也照常保留。
>
> **ConsumeQueue 布局**：每个 queue 对应**一个目录**，目录下有**多个 5.72 MB 的固定大小文件**，每条索引 20 字节（CommitLog offset + 消息长度 + Tag hashCode），文件名是起始字节偏移。通过 `MappedFileQueue#findMappedFileByOffset` 做算术定位（列表索引 = `(offset - firstFileFromOffset) / mappedFileSize`），即使有文件被清理也能 O(1) 定位。
>
> **ConsumeQueue 清理**：由 `CleanConsumeQueueService` 每 100ms 扫描（被动跟进 CommitLog），判断条件是 **"文件最后一条索引的 CommitLog offset < CommitLog 最小 offset"** 就整文件删除。文件内的悬空索引不主动清理，Consumer 自动跳过。这是典型的"空间换简单"设计。
