# RocketMQ 读写队列与消费机制

## 一、读写队列是什么
写队列表示Producer可以写的ConsumeQueue数量
读队列表示Consumer可以读的ConsumeQueue数量。

每个 Topic 在每个 Broker 上有两个队列数参数：

- **`writeQueueNums`**：Producer 可见的写队列数，决定消息能写入哪些 `queueId`
- **`readQueueNums`**：Consumer 可见的读队列数，决定消息能从哪些 `queueId` 被消费

**默认 `writeQueueNums == readQueueNums == 4`**，所以平时感受不到两者的区别。

---

## 二、重要澄清：读写队列 = 同一批物理队列

很多人会误以为"读队列"和"写队列"是两套物理队列，其实**不是**。

- **磁盘上实际创建的 ConsumeQueue 数量 = `max(writeQueueNums, readQueueNums)`**
- `writeQueueNums` 和 `readQueueNums` 只是对**同一批物理队列**的两张**可见性掩码**
  - 一张给 Producer 看 → 决定能写到哪些 queueId
  - 一张给 Consumer 看 → 决定能从哪些 queueId 消费

### 举例：writeQueueNums=8, readQueueNums=4

磁盘上会创建 **8 个 ConsumeQueue**：

```
consumequeue/OrderTopic/
  ├── 0/  ├── 1/  ├── 2/  ├── 3/   ← 既能写又能读（正常消费）
  └── 4/  └── 5/  └── 6/  └── 7/   ← 能写但 Consumer 看不到（孤儿消息！）
```

| queueId | Producer 能写吗？ | Consumer 能读吗？ |
|:---:|:---:|:---:|
| 0 ~ 3 | ✅ | ✅ |
| 4 ~ 7 | ✅ | ❌ |

> ⚠️ **生产环境禁止 `writeQueueNums > readQueueNums`**（除非正在做缩容的中间阶段），否则消息进去就出不来。

### 反过来：writeQueueNums=4, readQueueNums=8

| queueId | Producer 能写 | Consumer 能读 |
|:---:|:---:|:---:|
| 0 ~ 3 | ✅ | ✅ |
| 4 ~ 7 | ❌ | ✅ |

queueId 4~7 不会有新消息进来，Consumer 只能消费这些队列里的历史残留。**这正是"缩容的第一阶段"所需的状态**。

---

## 三、读写队列分离的核心价值：安全缩容

单独设计这两个参数，**唯一的核心目的**就是：**让 Topic 的 queue 数量能够安全地缩容**。

### 场景：Topic 从 16 个队列缩到 8 个

如果读写队列是同一个参数，直接从 16 改成 8，会发生灾难：

- queueId 8~15 立刻对 Producer 和 Consumer 都不可见
- 但这些队列里还残留着大量未消费的消息
- 这些消息变成**孤儿消息**，永远消费不到

### 正确的两阶段缩容

**第 1 步：先缩"写"，保留"读"**

```
writeQueueNums: 16 → 8
readQueueNums:  16 (不变)
```

- Producer 只能写 queueId 0~7 → 8~15 不再有新消息进来
- Consumer 仍然能从 queueId 0~15 读 → 把 8~15 的存量消费干净

**第 2 步：等存量消费完**（监控 `consumerOffset.json` 确认进度）

**第 3 步：再缩"读"**

```
writeQueueNums: 8
readQueueNums:  16 → 8
```

至此队列数从 16 安全缩到 8，**零消息丢失**。

### 扩容为什么不需要分离

扩容（4 → 8）时新队列既能写又能读，不会产生孤儿消息。所以**分离设计只为缩容而生**。

---

## 四、其他冷门用法

### 1. 临时"停写不停读"（Topic 下线前收尾）

```
writeQueueNums: 0     # Producer 发送报错 "No route info of this topic"
readQueueNums:  4     # Consumer 仍能消费完队列里的存量消息
```

### 2. 读多写少的流量控制

理论上可以 `writeQueueNums=2, readQueueNums=4`，让写集中、读分散。实战价值不大，了解即可。

---

## 五、消费者是怎么从 ConsumeQueue 读消息的

### 5.1 Consumer 看到的队列数 = readQueueNums

Consumer 启动时从 NameServer 拉路由，它感知到的队列数就是 `readQueueNums`（Producer 看到的是 `writeQueueNums`）。

### 5.2 队列分配：Rebalance

Consumer Group 内多个实例通过 **Rebalance** 算法分配队列（默认 `AllocateMessageQueueAveragely` 平均分配）。

举例：2 主 × 4 queue = 8 个读队列，3 个 Consumer 实例：

```
Consumer-1 → broker-a:0, broker-a:1, broker-b:0
Consumer-2 → broker-a:2, broker-a:3, broker-b:1
Consumer-3 → broker-b:2, broker-b:3
```

**同一时刻，一个队列在一个 Consumer Group 内只会被一个实例消费**。

### 5.3 真正的拉消息流程：两次文件访问

Consumer 拉消息时，Broker 内部要做两次 IO：

```
Step 1: 先查 ConsumeQueue（找消息在 CommitLog 的位置）
         │
         ▼
   ┌─────────────────────────────────────────────────────────┐
   │  consumequeue/OrderTopic/2/...                          │
   │                                                         │
   │  offset=100: [CL偏移=1234567][size=512][tag=0xABC]      │ ← 20 字节
   │  offset=101: [CL偏移=1235079][size=256][tag=0xDEF]      │
   │  offset=102: [CL偏移=1235335][size=1024][tag=0xABC]     │
   └─────────────────────────────────────────────────────────┘
         │
         │ 拿到 CommitLog 偏移 = 1234567, size = 512
         ▼
Step 2: 再读 CommitLog（读真实消息体）
         │
         ▼
   ┌─────────────────────────────────┐
   │  commitlog/00000000001073741824 │
   │                                 │
   │  在物理 offset 1234567 处读 512 字节 → MessageExt │
   └─────────────────────────────────┘
         │
         ▼
     返回给 Consumer
```

### 5.4 核心要点

1. **ConsumeQueue 是"索引"，不是消息本身**
   - 每条只有 20 字节（8 字节 CommitLog 偏移 + 4 字节 size + 8 字节 Tag hashCode）
   - 单文件约 5.72MB（30 万条），容易全部驻留 PageCache

2. **Tag 过滤在这里生效（服务端过滤）**
   - 8 字节的 Tag hashCode 让 Broker 在传输前用 hash 快速过滤，节省网络
   - hashCode 可能冲突 → Consumer 端还要用**真实 Tag 字符串**再精确过滤一次（客户端兜底）

3. **顺序读 ConsumeQueue + 随机读 CommitLog**
   - ConsumeQueue 顺序读，PageCache 命中率接近 100%
   - CommitLog 虽是随机读，但近期消息仍在 PageCache 里，IOPS 很高
   - 磁盘真正的随机 IO 压力主要出现在**消费堆积严重、需要读冷数据**时

4. **消费进度保存在哪里**
   - **集群消费**：保存在 Broker 的 `consumerOffset.json`（Consumer 定期上报）
   - **广播消费**：保存在 Consumer 本地 `offsets.json`
   - 保存的是**ConsumeQueue 的逻辑偏移量**，不是 CommitLog 的物理偏移量

---

## 六、整体关系图

```
                    ┌──────────────────────────────┐
                    │         NameServer           │
                    │  TopicA: writeQueueNums=8    │
                    │         readQueueNums=8      │
                    └──────────────────────────────┘
                          ↑拉路由          ↑拉路由
        ┌─────────────────┘                └──────────────────┐
        │                                                     │
   ┌─────────┐                                          ┌─────────┐
   │Producer │──只写 writeQueues──▶ [broker-a/b 主]     │Consumer │
   └─────────┘                           │              └─────────┘
                                         ▼                  ▲
                            ┌───────────────────────┐       │
                            │   CommitLog (顺序写)   │       │
                            └───────────────────────┘       │
                                         │                  │
                              ReputMessageService 异步分发  │
                                         ▼                  │
                            ┌───────────────────────┐       │
                            │  ConsumeQueue (索引)   │──────┘
                            │   按 readQueueNums     │  只读 readQueues
                            │   的数量暴露给消费者    │  两次访问：
                            └───────────────────────┘  1. 读索引 → 拿 CommitLog 偏移
                                                       2. 读 CommitLog → 拿消息体
```

---

## 七、一句话总结

> **磁盘上永远只有一批 ConsumeQueue**（数量 = `max(read, write)`），`writeQueueNums` 和 `readQueueNums` 不过是盖在这批队列上的两张"可见性掩码" —— 一张给 Producer，一张给 Consumer。正常情况下两张掩码完全重叠；缩容时先收缩"写掩码"、等存量消费完再收缩"读掩码"，就能避免孤儿消息。**Consumer 消费时走"ConsumeQueue 索引 → CommitLog 消息体"的两次访问路径**，ConsumeQueue 的 20 字节设计让索引可以完全驻留 PageCache，是 RocketMQ 消费高性能的关键。
