# RocketMQ 消息消费机制详解

## 一、前置场景：多消费组 + 多消费者

```
Topic: OrderTopic（2 主 Broker，每主 4 个 queue → 共 8 个 MessageQueue）

┌─────────────────────────────────────────────────────┐
│  ConsumerGroup-A (订单核心处理)                      │
│    ├── Consumer-A1                                   │
│    ├── Consumer-A2                                   │
│    └── Consumer-A3                                   │
├─────────────────────────────────────────────────────┤
│  ConsumerGroup-B (订单数据分析)                      │
│    ├── Consumer-B1                                   │
│    └── Consumer-B2                                   │
├─────────────────────────────────────────────────────┤
│  ConsumerGroup-C (订单通知推送)                      │
│    └── Consumer-C1                                   │
└─────────────────────────────────────────────────────┘
```

### 核心规则 1：消费组之间互不影响

- **每条消息会被每个 ConsumerGroup 独立消费一次**
- 这是 RocketMQ 的 **"广播到组，组内竞争"** 模型
- 实现方式：Broker 为**每个 (Topic, ConsumerGroup)** 独立维护一份消费进度

### 核心规则 2：组内队列独占消费

- 同一个 ConsumerGroup 内，**一个 MessageQueue 同一时刻只被一个 Consumer 实例消费**
- 所以组内消费者通过 **Rebalance** 把 8 个 queue 分给多个实例
- **如果实例数 > queue 数，多余的实例会闲置**（没分到 queue）

### 上面场景的队列分配（默认平均分配）

| ConsumerGroup | 实例数 | 每个实例分到的 queue |
|:---|:---:|:---|
| A（3 实例） | 3 | A1 → 3 个，A2 → 3 个，A3 → 2 个 |
| B（2 实例） | 2 | B1 → 4 个，B2 → 4 个 |
| C（1 实例） | 1 | C1 → 8 个（全拿） |

---

## 二、完整消费链路：一条消息的一生

以 ConsumerGroup-A 的 Consumer-A1 消费 `broker-a:queue-0` 为例。

### 阶段 1：订阅与 Rebalance

```
Consumer 启动
  │
  ├─ 1.1 从 NameServer 拉取订阅 Topic 的路由（每 30s 刷新一次）
  │      updateTopicRouteInfoFromNameServer
  │      → 获知该 Topic 在哪些 Broker 上、有哪些 MessageQueue
  │      → 与对应的 Broker 建立长连接
  │
  ├─ 1.2 向所有已连接的 Broker 发送 HeartBeat（每 30s 一次）
  │      MQClientInstance.sendHeartbeatToAllBrokerWithLock
  │      心跳内容：ConsumerGroup、订阅的 Topic + 过滤表达式、消费模式（集群/广播）、clientId
  │      ⚠️ 注意：Consumer 的心跳发给 Broker，不发给 NameServer
  │             （NameServer 从不感知 Consumer 的存在）
  │
  ├─ 1.3 Broker 收到心跳 → 更新 ConsumerManager 中该 Group 的成员列表
  │      若 Group 成员列表发生变化 → 向本组所有成员广播 NOTIFY_CONSUMER_IDS_CHANGED
  │
  └─ 1.4 Consumer 触发 Rebalance（每 20s 一次，或收到 NOTIFY 通知后立即触发）
         │
         ├─ 向任一 Broker 发 GET_CONSUMER_LIST_BY_GROUP 查本 Group 所有成员 clientId 列表
         ├─ 从本地路由缓存拿 Topic 的所有 MessageQueue（1.1 已经拉过）
         ├─ 用 AllocateMessageQueueStrategy 计算"应该分给自己哪些 queue"
         └─ 对比上次分配结果：
              - 新增的 queue：向 Broker 查该 queue 的消费进度 → 以此为起点创建 PullRequest
              - 移除的 queue：标记 dropped，立即提交最终消费进度到 Broker
```

### 阶段 2：拉消息（Pull）

```
Consumer-A1 对分到的每个 queue 启动一个 PullRequest 循环：
  │
  ├─ 2.1 PullMessageService 从本地 pullRequestQueue 取出请求
  │
  ├─ 2.2 向对应 Broker 发起 PULL_MESSAGE 请求
  │      请求参数：Topic、queueId、offset、maxNums、订阅表达式
  │      其中 maxNums = pullBatchSize（客户端），默认 32 条
  │      Broker 侧根据每条消息能否命中 PageCache 区分冷热，额外做截断（取 min）：
  │      - 热数据（命中 PageCache）：
  │          maxTransferCountOnMessageInMemory（默认 32 条）
  │          maxTransferBytesOnMessageInMemory（默认 256 KB）
  │      - 冷数据（需读磁盘）：
  │          maxTransferCountOnMessageInDisk  （默认  8 条）← 更严格
  │          maxTransferBytesOnMessageInDisk  （默认  64 KB）
  │      冷数据限制更严是为了**保护 Broker 磁盘 IO 和 PageCache**：
  │      一次 pull 大量冷数据会引发随机读并挤占 PageCache，影响其他 Producer/
  │      Consumer 对热数据的访问（造成 PageCache 抖动）；限制为 8 条/64KB 能把
  │      磁盘 IO 平摊到多次请求，保证 Broker 整体稳定性
  │      实战表现：大量历史堆积时，拉取冷数据的吞吐约为热数据的 1/4（8 条 vs 32 条），
  │      初期消化速度明显慢于实时消费
  │
  ├─ 2.3 Broker 端 PullMessageProcessor 处理：
  │      ├─ 读 ConsumeQueue，从 offset 开始取索引（20 字节 × 32 条）
  │      ├─ 用 Tag hashCode 或 SQL92 做服务端过滤
  │      ├─ 按索引读 CommitLog 拿真实消息体
  │      └─ 返回给 Consumer
  │
  ├─ 2.4 如果当前没有新消息 → Broker 端长轮询挂起
  │      - 默认挂起时长 15s（brokerSuspendMaxTimeMillis）
  │      - 有新消息到达时由 ReputMessageService 通知 PullRequestHoldService 立即唤醒
  │      - 若 Broker 关闭 longPollingEnable，则退化为短轮询（挂 1s）
  │
  ├─ 2.5 Consumer 收到消息 → 放入 ProcessQueue（TreeMap<offset, Msg>）
  │      → 提交到消费线程池处理
  │      → 同时用返回的 nextBeginOffset 更新 PullRequest.nextOffset
  │      → 把 PullRequest 重新放回 pullRequestQueue，进入下一轮 pull
  │
  └─ 2.6 发起下一轮 pull 前会先做 ProcessQueue 水位检查（见下方 "拉取流控"）
         - 并发消费检查 3 项：条数、大小、offset 跨度
         - 顺序消费检查 2 项：条数、大小（跨度仅并发模式适用）
         任一维度超标 → 延迟 50ms 再放回 pullRequestQueue → 形成背压
```

> **RocketMQ 的"Push Consumer" 本质是 Pull 模式 + 长轮询**，不是真正的服务端推送。

#### 拉取流控：为什么 ConsumeQueue 堆 100 万条也不会撑爆 Consumer 内存

**单次 pull 最多 32 条**（见 2.2），那堆积 100 万条消息时，Consumer 会不会疯狂 pull 32 次 × 31250 轮把本地内存撑爆？**不会**。Consumer 侧对每个 queue 有**三层流控**，任一超标就暂停该 queue 的 pull：

| 流控维度 | 默认阈值 | 配置项 | 适用消费模式 | 触发后行为 |
|:---|:---:|:---|:---:|:---|
| 缓存消息**条数** | 1000 条 | `pullThresholdForQueue` | 并发 + 顺序 | 当前 queue 暂停 pull **50ms** 后重试 |
| 缓存消息**大小** | 100 MB | `pullThresholdSizeForQueue` | 并发 + 顺序 | 当前 queue 暂停 pull **50ms** 后重试 |
| **offset 跨度**（ProcessQueue 最大 offset - 最小 offset） | 2000 | `consumeConcurrentlyMaxSpan`（参数名里的 Concurrently 就说明了作用域） | **仅并发** | 当前 queue 暂停 pull **50ms** 后重试 |

触发流控时 Consumer 会打 WARN 日志（非每次都打，源码里用计数器控制打印频率避免日志刷屏），运维可通过 grep `the cached message count exceeds`、`the cached message size exceeds`、`the queue's messages, span too long` 三个关键字定位。

**为什么 offset 跨度流控只用于并发消费**（面试高频追问）：

- **并发消费**（`ConsumeMessageConcurrentlyService`）：消息被扔进线程池乱序处理，ProcessQueue 里可能出现"前面卡住、后面大量堆积"的空洞。跨度流控就是拦这种空洞
- **顺序消费**（`ConsumeMessageOrderlyService`）：同一 queue 只能被一个线程串行消费（通过 `MessageQueueLock` 保证），天然不存在跨度问题，不需要这项流控

**offset 跨度流控的独特意义**：前两项控的是"内存水位"，这一项控的是"消费空洞大小"。假设 ProcessQueue 有条慢消息卡在 offset=100 迟迟不处理完，即便整个 ProcessQueue 只有 100 条消息（远未到 1000 条），只要最新拉到的 offset 达到 2100，跨度就超了，必须停止 pull。原因见 4.3 节：**offset 提交语义是"最小未完成 offset"**，跨度太大意味着一旦 Rebalance/宕机，需要回退重消费的范围就太大。

**100 万条堆积的真实消化过程**（假设 Consumer 刚从 offset 152300 开始消费 queue-0）：

```
┌────────────────────────────────────────────────────────┐
│  ProcessQueue（单 queue，容量上限 1000 条 / 100MB）      │
│  [拉一批] ↓                           ↑ [消费完移除]     │
│  ┌───────────────────────────────────────────────────┐ │
│  │ offset=152300 152301 ...           152999         │ │
│  │ 跨度 = 152999 - 152300 = 699（< 2000，未触发跨度流控） │ │
│  └───────────────────────────────────────────────────┘ │
│                                                         │
│  PullMessageService                消费线程池（20 线程） │
│       │                                  │              │
│       │ 每次 pull 32 条                   │ 并发处理      │
│       │ 水位超了就停 50ms                 │ 处理完从 Tree │
│       ▼                                  ▼  Map 移除    │
│  [背压：拉一点 → 消费一点 → 水位下降 → 再拉一点]         │
└────────────────────────────────────────────────────────┘
```

单 queue 本地最多缓存 **1000 条 / 100 MB / 2000 offset 跨度**（先到者触发），**100 万条只会被慢慢分批消化，绝不会一次性拉到本地**。

**多 queue 场景的内存估算**（注意三个维度的触发条件不同）：流控按 queue 独立计算，不是全局。实际触发哪个维度，由**消息体大小**决定：

| 消息大小 | 1000 条对应字节数 | 先触发的维度 | 单 queue 实际缓存上限 |
|:---|:---:|:---|:---|
| 平均 1 KB（订单、日志等小消息） | ≈ 1 MB | **条数**（1000 条先到，离 100 MB 远） | ≈ 1 MB |
| 平均 100 KB（中等业务消息） | ≈ 100 MB | 条数和大小**接近同时触发** | ≈ 100 MB |
| 平均 200 KB（较大消息） | ≈ 200 MB | **大小**（约 500 条即到 100 MB） | ≈ 100 MB |

所以 M 个 queue 的**实际占用** = `M × min(1000 条对应字节数, 100 MB)`，不是固定 `M × 100 MB`。以 16 queue 为例：

- 小消息（1 KB）场景：16 × 1 MB ≈ **16 MB**
- 中消息（100 KB）场景：16 × 100 MB ≈ **1.6 GB**
- 大消息（200 KB）场景：16 × 100 MB ≈ **1.6 GB**

**结论**：小消息场景下本地内存开销很小，只有消息体 ≥ 100 KB 时才会接近 GB 级别。生产上若消息体 ≥ 100 KB，应考虑调小 `pullThresholdSizeForQueue` 或对大消息做拆分/对象存储化处理。

**常见调优参数**：

| 参数 | 默认 | 调大的收益 | 调大的风险 |
|:---|:---:|:---|:---|
| `pullBatchSize` | 32 | 减少 pull 次数、提升吞吐 | ① 单次请求体变大；② 单条慢消息放大影响；③ **若接近 `pullThresholdForQueue`，一次拉满即触发流控，形成"拉满→停50ms→再拉满"的抖动**，失去平滑背压效果 |
| `consumeMessageBatchMaxSize` | 1 | 单次回调批量处理多条，业务层可批量落库 | 返回 `RECONSUME_LATER` 时，framework 默认把 `ackIndex = -1`（即整批 sendMessageBack）；业务可在回调中显式 `context.setAckIndex(i)`，表示"前 i+1 条成功，第 i+2 条起失败"，实现批内部分失败重投 |
| `pullInterval` | 0（ms） | 两次 pull 之间强制等待，降低 Broker QPS 压力（Consumer 数 >> queue 数或消费很慢时有用） | 调太大会降低吞吐，造成本可以立刻拉到的消息被人为拖延 |
| `pullThresholdForQueue` | 1000 | 突发堆积时多吃数据、提高吞吐 | 内存上升，Rebalance 时重复消费范围变大 |
| `pullThresholdSizeForQueue` | 100 MB | 大消息场景下多缓存 | 内存占用直接放大 |

### 阶段 3：消费（业务处理）

```
ConsumeMessageService 从 ProcessQueue 取消息 → 提交到消费线程池：
  │
  ├─ 3.1 线程池默认 20 核心线程（PushConsumer）
  │
  ├─ 3.2 回调 MessageListener.consumeMessage(msgs)
  │      ├─ 返回 CONSUME_SUCCESS：正常消费成功
  │      └─ 返回 RECONSUME_LATER：消费失败，需要重试
  │
  └─ 3.3 根据返回结果：
         ├─ 成功 → 从 ProcessQueue.msgTreeMap 中移除这批消息
         │        ProcessQueue 的 firstKey 自然推进（下次提交 offset 的依据）
         │
         └─ 失败 → 走 SendMessageBack 机制：通过 CONSUMER_SEND_MSG_BACK 请求
                  把消息发回 Broker，Broker 把它写入 %RETRY%ConsumerGroup 这个
                  内部重试 Topic，按延迟级别（1s/5s/10s/30s/1m/2m/... 共 18 级）
                  定时重投递。Consumer 端仍从原 ProcessQueue 中移除这批消息
                  （即原 queue 的消费进度照常推进）
```

> **RocketMQ 没有单条消息级别的"ACK"概念**。消费成功就是本地从 ProcessQueue 移除，失败是通过 SendMessageBack 把消息重新投到重试 Topic，原 queue 的 offset 照常往前推进。

### 阶段 4：提交消费进度

```
Consumer 定时任务（MQClientInstance.persistAllConsumerOffset，默认 5s 一次）：
  │
  ├─ 4.1 扫描所有 ProcessQueue
  │
  ├─ 4.2 计算每个 queue 的"可提交 offset"
  │      规则：
  │        - 若 ProcessQueue.msgTreeMap 非空 → 取 firstKey()
  │          （即当前 ProcessQueue 中最小的、尚未完成的消息 offset）
  │        - 若 msgTreeMap 为空（全部处理完） → 取 PullRequest.nextOffset
  │          （即下次要拉的位置，表示水位已推进到这里）
  │      详见第三章 3.3
  │
  └─ 4.3 向 Broker 发起 UPDATE_CONSUMER_OFFSET 请求
         Broker 更新内存中的 ConsumerOffsetManager.offsetTable (ConcurrentHashMap)
         Broker 侧另有独立的定时任务每 5s 持久化到 consumerOffset.json
         （flushConsumerOffsetInterval=5000，可配置）
```

---

## 三、消费进度保存机制

### 3.1 保存在哪里

#### 集群消费（CLUSTERING，默认）—— 存 Broker

**为什么存 Broker**：同一个 ConsumerGroup 内多个实例要共享进度，一个实例挂了另一个要能接着消费。

**存储路径**：`${ROCKETMQ_HOME}/store/config/consumerOffset.json`

**数据结构**：

```json
{
  "offsetTable": {
    "OrderTopic@ConsumerGroup-A": {
      "0": 152300,
      "1": 148000,
      "2": 155100,
      "3": 149200,
      "4": 150800,
      "5": 151500,
      "6": 149900,
      "7": 152700
    },
    "OrderTopic@ConsumerGroup-B": {
      "0": 120000,
      "1": 118500
    },
    "OrderTopic@ConsumerGroup-C": {
      "0": 140000
    }
  }
}
```

**关键点**：

- Key = `Topic@ConsumerGroup`，**每个消费组独立一份**
- Value = 每个 queueId 的消费进度。**语义 = 当前 ProcessQueue 中最小未完成消息的 offset（若 ProcessQueue 已空则为下一条待拉 offset）**，Consumer 下次启动/接手该 queue 时就从这个 offset 开始拉消息
- **不同消费组的进度完全独立** —— 这就是为什么一条消息能被多个组各自消费一次

#### 广播消费（BROADCASTING）—— 存 Consumer 本地

**为什么存本地**：广播模式下每个实例都独立消费全量消息，进度天然是实例自己的事。

**存储路径**：`${user.home}/.rocketmq_offsets/{clientId}/{group}/offsets.json`

- `clientId` 格式为 `IP@instanceName`（如 `10.1.2.3@DEFAULT`）
- 文件内容结构和集群模式类似（按 `Topic@Group` → queueId → offset 组织）

### 3.2 Consumer 端的三个关键 offset（容易混淆）

Consumer 端并没有显式维护 "consumeOffset" 这种变量，实际参与消费过程的有以下三个位置，它们的语义千万别搞混：

```
ConsumeQueue 上的消息：
  [100][101][102][103][104][105][106][107][108][109]...
   ✓    ✓    ✓    ✗    ?    ✓    ✓    ?    ?    (待拉)
   └─已处理完──┘ └SendBack┘ └处理中┘└─已处理完─┘└处理中┘

  ① PullRequest.nextOffset   = 109
     下次要向 Broker 发起 pull 请求的起始 offset
     由上一次 pull 返回的 nextBeginOffset 更新

  ② ProcessQueue.msgTreeMap  = { 104 → msg, 107 → msg, 108 → msg }
     本地正在"处理中"的消息池（按 offset 排序的 TreeMap）
     消费成功/SendBack 成功后从这个 Map 里移除
     firstKey() 就是"最小未完成 offset"

  ③ 提交给 Broker 的 commitOffset
     = msgTreeMap.firstKey()         （若 msgTreeMap 非空）
     = PullRequest.nextOffset        （若 msgTreeMap 为空）
     由 MQClientInstance 每 5s 批量上报
```

**核心点**：Consumer 并不追踪"哪些消息成功了"，只追踪"哪些消息还在处理中"。水位靠 ProcessQueue 自然推进 —— 处理完的从 TreeMap 移除，剩下的最小 offset 就是当前安全提交点。

### 3.3 提交 offset 的准确规则（⚠️ 面试高频）

**规则**：Consumer 提交给 Broker 的 offset = **ProcessQueue 中所有未完成消息的最小 offset**（如果 ProcessQueue 已空，就是 pullOffset）。

#### 为什么不是"最后成功消费的 offset + 1"

因为消费是**多线程并发**的，可能出现：

```
提交到线程池的消息：100, 101, 102, 103, 104
消费完成顺序：     102, 100, 104 （101 和 103 还在处理）

如果提交 "最后成功 offset = 104" → 意味着 100~104 都消费完了
但实际 101、103 还没完成 → 如果此时 Consumer 崩溃
→ 新实例从 105 开始消费 → 101 和 103 丢失！

正确做法：提交 "最小未完成 offset = 101"
→ 如果崩溃，新实例从 101 开始重新消费
→ 100、102、104 会被重复消费（所以要求业务幂等）
→ 但不会丢
```

**这就是为什么 RocketMQ 的"至少一次"语义天然依赖这个机制，也是为什么业务必须保证幂等消费。**

### 3.4 提交时机

| 时机 | 触发动作 |
|:---|:---|
| **定时提交** | `MQClientInstance.persistAllConsumerOffset` 每 5s 批量上报（persistConsumerOffsetInterval=5000） |
| **Rebalance 时** | 某个 queue 被分配给别的实例前，**立即提交一次**当前进度，防止丢 |
| **Consumer 正常关闭** | `shutdown()` 会触发最后一次提交 |
| **拉消息时顺带提交** | PULL 请求 Header 会带上 commitOffset，Broker 顺便更新（减少一次 RPC） |

### 3.5 Broker 侧的持久化

```
Consumer 上报 offset
    │
    ▼
Broker 更新 ConsumerOffsetManager (内存 ConcurrentHashMap)
    │
    ├─ 定时任务每 5s 持久化 → consumerOffset.json
    └─ Broker 关闭时也会 flush 一次

注意：如果 Broker 宕机且没来得及持久化 → 最多丢失最近 5s 内的 offset 推进信息
      恢复后 Consumer 会从旧 offset 继续消费，这 5s 内已经被消费过的消息会被重复投递
      （重复量 = 这 5s 内该 queue 上实际推进的消息数，高 TPS 场景可能达数万条，再次强调业务必须幂等）
```

---

## 四、消费者变动：Rebalance 详解

### 4.1 Rebalance 触发时机

**客户端主动触发**：

| 触发时机 | 说明 |
|:---|:---|
| Consumer 启动 | 首次加入组，全量分配 |
| 定时触发 | 每 20s 自动跑一次 RebalanceService |
| 收到 Broker 通知 | 组内有实例上线/下线时 Broker 推送通知 |
| Consumer 订阅变化 | 新订阅 Topic 或取消订阅 |

**Broker 侧通知的触发**：

| 事件 | 触发动作 |
|:---|:---|
| Consumer 发来心跳，Group 成员列表变化 | 向本组所有成员**立即推送** NOTIFY_CONSUMER_IDS_CHANGED |
| Consumer 心跳超时（Broker 扫描发现） | 踢出成员列表并通知其他成员 |
| Consumer 主动 `unregister` | 立即通知 |

**Broker 侧心跳超时检测的实现细节**：

- Consumer 每 **30s** 发一次心跳（`MQClientInstance.sendHeartbeatToAllBrokerWithLock`）
- Broker 端 `ClientHousekeepingService` 启动一个定时任务，**每 10s 扫描一次**所有客户端连接
- 扫描时对比 `Channel.lastUpdateTimestamp`，若超过 **120s（`CHANNEL_EXPIRED_TIMEOUT`）** 未更新则判定该 Consumer 失联
- 判定失联后才触发"踢出成员 + 通知其他成员 rebalance"

这也是后面 4.4 节"崩溃后最长 120s 才被发现"的原因。

### 4.2 Rebalance 算法：AllocateMessageQueueStrategy

RocketMQ 提供 5 种分配策略，默认是 **AllocateMessageQueueAveragely**（平均分配）：

```
场景：8 个 queue，3 个 Consumer
Consumer 按 clientId 字典序排序：C1, C2, C3

平均 = 8 / 3 = 2 ... 余 2
前 2 个 Consumer 每人多拿 1 个：
  C1 → queue[0, 1, 2]      (3 个)
  C2 → queue[3, 4, 5]      (3 个)
  C3 → queue[6, 7]         (2 个)
```

其他策略：

| 策略 | 说明 |
|:---|:---|
| `AllocateMessageQueueAveragelyByCircle` | 环形平均：C1→0,3,6 / C2→1,4,7 / C3→2,5 |
| `AllocateMessageQueueByConfig` | 手动配置，按 IP 固定分配 |
| `AllocateMessageQueueByMachineRoom` | 按机房亲和性分配（跨机房优先本机房） |
| `AllocateMessageQueueConsistentHash` | 一致性哈希，Consumer 变动时扰动最小 |

### 4.3 Consumer 新增：完整时序

假设 Group-A 原有 C1, C2 共享 8 个 queue（各 4 个），现在 C3 启动加入：

```
T+0ms   C3 启动，向 NameServer 注册，向所有 Broker 发心跳
        心跳内容：GroupName=A, ClientId=C3

T+10ms  Broker 收到 C3 心跳 → ConsumerManager 中 Group-A 新增 C3
        → Broker 向 Group-A 所有成员（C1, C2, C3）广播 NOTIFY_CONSUMER_IDS_CHANGED

T+20ms  C1 收到通知 → 立即触发 Rebalance
        C2 收到通知 → 立即触发 Rebalance
        C3 收到通知 → 立即触发 Rebalance
        （三个实例并发进行，各自独立计算）

T+50ms  三个实例各自计算：
        C1: 之前 [0,1,2,3] → 现在 [0,1,2]        → 丢掉 queue 3
        C2: 之前 [4,5,6,7] → 现在 [3,4,5]        → 丢掉 6,7，新增 3
        C3: 之前 []        → 现在 [6,7]          → 新增 6,7

T+60ms  C1 丢掉 queue 3 的过程（RebalancePushImpl.removeUnnecessaryMessageQueue）：
        ├─ 标记 ProcessQueue[queue 3] 为 dropped=true
        │  （PullMessageService 下次循环检查到 dropped 就会停止该 queue 的拉取，
        │   消费线程池里已在处理的消息继续处理，成功后不再提交也不再 SendBack）
        ├─ 尝试获取 ProcessQueue.consumeLock（最多等 1s）
        │   ├─ 拿到锁 → 立即把 queue 3 当前 commitOffset 上报 Broker
        │   │            从 processQueueTable 中移除 queue 3
        │   └─ 拿不到锁（说明正在消费）→ 本次先不移除，下次 rebalance 再试

T+60ms  C2 同上处理 queue 6, 7
        C2 新增 queue 3：
        ├─ 向 Broker 查 queue 3 的最新消费进度（C1 刚提交完的那个）
        ├─ 以这个 offset 为起点创建 PullRequest
        └─ 开始拉 queue 3 的消息

T+80ms  C3 新增 queue 6, 7，流程同上

T+100ms 三个实例都稳定，开始按新分配消费
```

**关键保障**：

- 放弃 queue 的 Consumer 会**立即提交 offset**，避免新接手的 Consumer 重复消费太多
- 接手 queue 的 Consumer 会**从 Broker 查 offset**，不是从 0 开始
- **极端情况下仍会有少量消息重复消费**（放弃和接手的瞬间 ProcessQueue 里的消息可能还没完全处理完就被丢弃）→ 业务必须幂等

### 4.4 Consumer 减少

#### 主动下线

```
T+0ms   C2 调用 shutdown()
        ├─ 提交所有 queue 的 commitOffset
        ├─ 向所有 Broker 发 unregisterClient 请求
        └─ 关闭拉消息线程、消费线程池

T+10ms  Broker 从 Group-A 成员列表移除 C2
        → 向 C1, C3 广播 NOTIFY_CONSUMER_IDS_CHANGED

T+20ms  C1, C3 触发 Rebalance，重新分配 C2 原有的 queue
        从 Broker 查这些 queue 的 offset（C2 刚提交过）继续消费
```

#### Consumer 崩溃（无法主动通知）

```
T+0s     C2 进程崩溃，无法发心跳、无法 unregister
         但 C2 最后一次提交的 offset 已经在 Broker 上

T+30s    C2 本应发下一次心跳（心跳间隔 30s）→ 失败
T+60s    ...
T+90s    ...
T+120s   Broker 的 ClientHousekeepingService 扫描到 C2 超过 120s 无心跳
         → 将 C2 踢出 Group-A 成员列表
         → 通知 C1, C3 rebalance

T+121s   C1, C3 接手 C2 原有的 queue
         从 Broker 查 offset（C2 崩溃前最后一次提交的位置）开始消费
         → C2 崩溃前未提交的消息会被重复消费（至少一次语义）
```

**⚠️ 关键面试点：故障恢复延迟**

- Broker 检测 Consumer 失联需要 **最长 2 分钟**
- 这 2 分钟内该 queue 的消息会**堆积没人消费**
- 所以 RocketMQ 集群消费的故障恢复有**分钟级延迟**

### 4.5 部署期的 Rebalance 抖动（实战痛点）

这是 RocketMQ 4.x Push 消费模型在生产环境**最常被诟病的痛点**，也是面试高频追问点。

#### 4.5.1 为什么部署阶段会频繁 Rebalance

回顾触发条件：**Consumer Group 成员列表发生变化 → Broker 广播 NOTIFY → 全组 Rebalance**。

滚动发布（Rolling Update）下，假设 Consumer Group 有 **N 个实例**（一次停 1 个 → 重启）：

```
初始：10 个实例 C1~C10

Step 1: 停 C1       → 成员变 9 个 → 触发 1 次 Rebalance
Step 2: 启动 C1(新) → 成员变 10 个 → 触发 1 次 Rebalance
Step 3: 停 C2       → 成员变 9 个 → 触发 1 次 Rebalance
Step 4: 启动 C2(新) → 成员变 10 个 → 触发 1 次 Rebalance
...
```

**N 个实例滚动发布 ≈ 2N 次 Rebalance**。更坏的场景：

- **蓝绿发布（同 ConsumerGroup）**：新集群分批加入 + 老集群分批下线，每一步都会触发一次 Rebalance，累计接近 **2N 次**；新集群一次性全量加入的极端情况下才能压到 2 次
- **发布过程触发 HPA 扩缩容** → 触发次数叠加
- **发布失败回滚** → 再来一轮

#### 4.5.2 每次 Rebalance 的三层代价

**代价 1：消费短暂停顿（秒级）**

```
Rebalance 过程中：
  ├─ C1 丢掉原来的 queue-3
  │   → 停止拉 queue-3（标记 dropped）
  │   → 最多等 1s 获取 consumeLock
  │   → 提交 offset
  │
  └─ C2 接手 queue-3
      → 向 Broker 查 offset
      → 创建 PullRequest
      → 首次 pull（可能含 TCP 建连）
      → 消息才开始消费
```

期间该 queue 消费**停顿 几百 ms ~ 几秒**，RT 敏感业务（如实时订单）会明显感知抖动。

**代价 2：重复消费**

- C1 丢掉 queue 时，ProcessQueue 里**正在处理的消息**不再提交 offset（见 4.3 节 `consumeLock` 机制）
- C2 接手后从 C1 最后提交的位置开始拉 → 这部分消息被**重复投递**
- 10 实例滚动发布，每次少量重复累积下来不容忽视，**业务层必须幂等**

**代价 3：Consumer 强杀时的分钟级堆积**

如果不是优雅下线而是 `kill -9`（部署平台强杀、OOM 崩溃）：

- Broker 需 **最长 120s** 才感知失联（见 4.1 节心跳扫描机制）
- 这 120s 内被踢出的实例原负责的 queue **完全无人消费**
- 不同杀法的影响：
  - **分批强杀**（如滚动发布中每次杀 1 个）：每次杀一个就有一个 120s 窗口，但**只影响该实例原负责的 queue**（部分 queue 堆积），其他 queue 正常
  - **同时强杀**（如整个 Pod 被驱逐）：只有 **1 个 120s 窗口**，但**影响面是所有 queue**（全量堆积 2 分钟）
- 核心结论：无论分批还是同时，单次无法感知的窗口都是 120s，差异在于"影响范围 vs 累积次数"的权衡

#### 4.5.3 频繁 Rebalance 的连锁问题

| 问题 | 表现 | 影响 |
|:---|:---|:---|
| **消费延迟尖刺** | 监控图上发布期 RT 明显飙高 | 用户感知抖动 |
| **堆积告警误报** | 短暂无人消费 → consume lag 飙高 | 值班同学被折腾 |
| **顺序消息乱序** | queue 从 C1 转给 C2，未提交消息被 C2 重拉 | 同业务键消息可能乱序 |
| **成员视角不一致**（俗称"Rebalance 脑裂"） | 各 Consumer 独立计算，心跳未同步时看到的成员列表不一致 | 同 queue 短暂被 2 个 Consumer 同时认为归自己 |

"脑裂"的成因与收敛：RocketMQ 4.x 的 Rebalance 是**各 Consumer 独立计算的无中心模型**（每个 Consumer 自己调 `GET_CONSUMER_LIST_BY_GROUP` 拿成员列表，自己做分配），所以：

- 发布瞬间，不同 Broker 收到的心跳时间可能相差几秒
- 不同 Consumer 问到的成员列表可能不一致 → 分配结果短暂冲突
- 重叠期通常只持续一次 Rebalance 周期（≤ 20s），下次定时 Rebalance 后自动收敛
- 期间的副作用：**同 queue 的消息被两个 Consumer 重复拉取 + 消费**，顺序消息会乱序
- 注意这不是"真正的脑裂"（数据不一致无法恢复），而是**短暂的成员视角不一致**，会自愈

**与代价 2 中"重复消费"的区别**（面试追问点）：

| 维度 | 代价 2（正常切换） | 脑裂（成员视角不一致） |
|:---|:---|:---|
| **触发条件** | 每次 Rebalance 都会发生 | 只在成员变化瞬间偶发 |
| **重复范围** | ProcessQueue 里 C1 已拉取但未提交 offset 的消息（通常数十到数千条） | 脑裂持续期内（≤ 20s）该 queue 新产生的**所有**消息 |
| **谁来消费** | C1 停止拉取，C2 从 **C1 最后提交到 Broker 的 offset** 重拉 | C1 和 C2 **同时**拉取 + **同时**消费 |
| **持续时间** | Rebalance 切换期间（几百 ms ~ 几秒，切完即止） | ≤ 20s，下次定时 Rebalance 后自愈 |
| **应对方式** | 业务幂等（必做） | 业务幂等 + 顺序消息场景要特别警惕 |

#### 4.5.4 应对方案

**方案 1：优雅下线（必须做）**

保证 Consumer 主动 `shutdown()`，不被 kill -9：

```java
Runtime.getRuntime().addShutdownHook(new Thread(() -> {
    consumer.shutdown();  // 提交 offset、unregister、等待消费完
}));
```

K8s 部署关键配置：

- `preStop` hook 调用应用的 `/actuator/shutdown`
- `terminationGracePeriodSeconds` 按业务消费耗时设定（见下方）
- 先从服务注册中心摘除，再 kill

**`terminationGracePeriodSeconds` 的取值建议**（不能一刀切）：

| 业务特征 | 推荐值 | 计算逻辑 |
|:---|:---:|:---|
| 单条消息处理 < 1s 的轻量场景 | 30s | 够 shutdown 流程跑完 + 少量消息消费完 |
| 处理耗时较长或批量消费 | ≥ 2 × 最长单次消费耗时 + 20s | 留 2 轮消费时间 + Rebalance 收敛时间 |
| 严格要求优雅下线完成 | 120s+ | 几乎一定能跑完，但会拖慢部署速度 |

**效果**：故障感知从"分钟级"压到"秒级"，被下线实例原负责的 queue 不会经历 120s 真空期（主动 unregister 会触发 Broker 立即广播 NOTIFY，其他 Consumer 马上感知并接管）。

**方案 2：控制发布节奏（注意 maxSurge 的权衡）**

K8s 滚动发布参数要针对 Consumer 场景做权衡，这里有个**容易踩坑的点**：`maxSurge` 并非越大越好。

```yaml
# 推荐配置（Consumer 场景）
maxUnavailable: 1       # 最多只让 1 个旧实例下线
maxSurge: 0             # 不提前启动新实例（见下方权衡说明）
minReadySeconds: 30     # 新实例起来后等 30s 再发布下一个（让 rebalance 收敛）
```

**maxSurge 的实质差异**（都会触发 2 次 Rebalance，差异在中间状态）：

| 配置 | 每轮触发 Rebalance 次数 | 两次 Rebalance 之间的状态 | 典型问题 |
|:---|:---:|:---|:---|
| `maxSurge: 0` | 2 次（旧下线 1 次 + 新加入 1 次） | 成员 **少 1 个**，消费能力短暂下降 | 消费堆积短期上升，但不会出现双实例抢 queue |
| `maxSurge: 1` | 2 次（新加入 1 次 + 旧下线 1 次） | 成员 **多 1 个**，消费能力不降 | 可能出现成员视角不一致（4.5.3 节），同 queue 被新老实例短暂重复消费 |

**选择建议**：

- 消费能力不能下降的业务（如实时告警） → `maxSurge: 1`
- 对重复消费/乱序敏感的业务（顺序消息、RT 敏感） → `maxSurge: 0`
- 两者 **Rebalance 次数相同**，选择的本质是"短暂降能"和"短暂重复消费"之间的权衡

`minReadySeconds: 30` 是关键 —— 让每一轮 Rebalance 有足够时间收敛（默认 RebalanceService 20s 周期 + 心跳传播），避免还没稳定就发布下一个。

**方案 3：换一致性哈希分配策略（注意适用场景）**

默认的 `AllocateMessageQueueAveragely` 成员变化时**大部分 queue 会重新分配**。

换 `AllocateMessageQueueConsistentHash`：

```java
consumer.setAllocateMessageQueueStrategy(
    new AllocateMessageQueueConsistentHash());
```

**优点**：成员变化时只有少部分 queue 会迁移（一致性哈希特性），抖动范围小。

**⚠️ 适用限制，这个细节面试官常追问**：

- **不能消除 Rebalance 触发本身**，只是让分配结果更稳定。每次成员变化仍然会触发**全组 Rebalance 动作**，只是"大部分 Consumer 计算出的分配结果没变"，从而避免了 queue 搬迁
- **Consumer 数量较少时哈希环分布可能不均匀**，容易出现负载倾斜（例如 3 个 Consumer 分 8 个 queue，理想情况是 3/3/2，一致性哈希下可能变成 5/2/1）。这是一致性哈希本身的特性决定的，Consumer 数越少偏差越明显；实例数多（几十以上）时分布会趋于均匀
- **Consumer Group 内所有实例必须配置同一种分配策略**，否则分配结果会冲突

**方案 4：升级到 RocketMQ 5.x Pop 消费模式（根治但有代价）**

| 特性 | Push（4.x 经典） | Pop（5.x 新模式） |
|:---|:---|:---|
| **队列分配** | Rebalance 把 queue **静态**分给 Consumer，分配关系稳定 | **请求级动态分配**：Consumer 发 `POP_MESSAGE` 时 Broker 为这次请求选一个 queue，不与 Consumer 绑定 |
| **Consumer 变动** | 触发全组 Rebalance | **无需 Rebalance**，新实例加入后直接发 Pop 请求即可参与消费 |
| **Broker 记录** | queue 级 offset | **每条消息**的不可见时间戳（`InvisibleTime`）、消费进度、重试次数 |
| **发布抖动** | 秒级消费停顿 | **接近零抖动** |
| **客户端 API** | `DefaultMQPushConsumer` | `SimpleConsumer`（5.x 新 API） |

**Pop 的核心模型**：消息可见性从 **queue 级**变成**消息级** —— Consumer 通过 `POP_MESSAGE` 从 Broker 拿一批消息（Broker 端做队列选择），Broker 为每条被 Pop 出去的消息标记"不可见" N 秒（`InvisibleTime`）。N 秒内 Consumer 调用 `ACK_MESSAGE` 则删除，调用 `CHANGE_INVISIBLE_TIME` 可延长可见时间，超时未 ACK 则消息自动恢复可见，被后续 Pop 请求拿走。**这个模型和 AWS SQS 完全一致**。

**⚠️ Pop 的代价（面试追问必考）**：

- **RPC 开销增加**：Push 是批量提交 offset（每个 queue 周期性 1 次 `UPDATE_CONSUMER_OFFSET`），Pop 是**每条消息一次 `ACK_MESSAGE`** + 必要时的 `CHANGE_INVISIBLE_TIME`。`POP_MESSAGE` 本身是批量拉取（和 `PULL_MESSAGE` 开销相当），但 ACK 的"每条一次"在小消息高 QPS 场景下 RPC 开销占比显著增大
- **Broker 侧存储成本更高**：需要为 Pop 出去但未 ACK 的消息维护 `InvisibleTime`、重试次数等元数据（底层用 CheckPoint 文件 `CK_OFFSET` 记录），对比 Push 只需维护 queue 级 offset，存储和 IO 压力显著增大
- **顺序消息不适用**：Pop 本质是**抢占式并发消费**，同一 queue 的多条消息可能同时被不同 Consumer 拿走，**无法保证顺序** → 顺序消息场景仍需走 Push 模式
- **开源版成熟度**：开源 RocketMQ 5.x 的 Pop 模式仍在持续完善，4.x ~ 5.0 早期版本有已知问题，建议生产使用 **5.1+** 版本；商业版（阿里云 MQ 5.x）Pop 已规模化落地

**适用场景**：大量 Consumer 实例 + 对 Rebalance 抖动敏感 + **非顺序消息** + 能接受 RPC 开销增加。

**方案 5：queue 数配置为实例数的 2~4 倍**

极端场景下若**实例数 ≥ queue 数**（满员/溢出），任何一次下线都会让某 queue 暂时无人消费。让 **queue 数 = 实例数 × 2~4**：

- 下线时其他 Consumer 只是多接几个 queue，不会出现"无人消费"的空白期
- 扩容时也有足够的 queue 可以分配，不会出现"多余实例闲置"
- 代价：queue 数多了管理复杂度略升，顺序消息的并发度也会变高

#### 4.5.5 不同发布策略的 Rebalance 影响对比

| 发布方式 | Rebalance 次数（10 实例示例） | 抖动时长 | 推荐度 |
|:---|:---|:---|:---:|
| 滚动 + 优雅下线 + 一致性哈希 | ≈ 20 次，但每次**只少量 queue 迁移**，影响面最小 | 秒级 | ⭐⭐⭐⭐⭐ |
| 滚动 + 优雅下线 + 默认平均分配 | ≈ 20 次，每次**大部分 queue 重分配**，影响面大 | 秒级 | ⭐⭐⭐ |
| 滚动 + 强杀（非优雅） | ≈ 20 次，但每次下线多一个 120s 真空期 | 最长 120s × N 累积（按先后杀法） | ⭐ |
| 蓝绿发布（同 ConsumerGroup） | 接近 2N 次（新集群分批加入 + 老集群分批下线） | 新集群启动期 + 切换期都有抖动 | ⭐⭐ |
| 蓝绿发布（新老用不同 ConsumerGroup） | **0 次**（同组内无变化） | 无抖动（但要处理新老 Group 消息交接） | ⭐⭐⭐⭐ |
| 金丝雀发布（Canary） | 每上下线一个实例触发 1 次 | 秒级，范围可控 | ⭐⭐⭐⭐ |
| **5.x Pop 模式（非顺序消息）** | **0** | **接近 0** | ⭐⭐⭐⭐⭐ |

说明：滚动发布下 `maxSurge=0` 与 `maxSurge=1` 的**总 Rebalance 次数相同**（都是 ≈ 2N 次），差异在中间状态（见方案 2），所以本表合并了这两种。

#### 4.5.6 一句话总结

> RocketMQ 4.x 在**滚动发布期会触发约 2N 次 Rebalance**，每次带来秒级消费停顿、重复消费、甚至顺序消息乱序，是 Push 消费模型的固有痛点。实战缓解手段按优先级是 **优雅下线（避免 120s 真空期）→ 控制发布节奏 → 一致性哈希分配 → queue 数 > 实例数**，根治则要升级到 **5.x Pop 消费模式**（消息级可见性，无需 Rebalance，但顺序消息不适用且有 RPC 开销代价）。

---

## 五、整体时序图

```
┌──────────────────────────────────────────────────────────────────┐
│                    ConsumerGroup-A (3 实例)                       │
└──────────────────────────────────────────────────────────────────┘

  Consumer-A1                  Broker                   NameServer
      │                          │                          │
      ├──1. 注册 + 心跳(30s周期)────────────────────────────▶│
      │                          │                          │
      ├──2. 订阅 Topic────────────▶                          │
      │    (Heartbeat 中带订阅信息)                           │
      │                          │                          │
      ├──3. Rebalance (20s周期)──▶ 查 Group 成员列表          │
      │◀───────────返回成员列表───┤                          │
      │                          │                          │
      ├──────────拉取路由────────────────────────────────────▶│
      │◀────────返回路由───────────────────────────────────────
      │                          │                          │
      │   [本地计算：我应该负责 queue 0,1,2]                  │
      │                          │                          │
      ├──4. Pull Request (queue 0, offset=100)──▶            │
      │                          │                          │
      │   [Broker 读 ConsumeQueue + CommitLog + 过滤]         │
      │                          │                          │
      │◀──返回 32 条消息 + nextOffset=132────┤                │
      │                          │                          │
      │   [消费线程池并发处理 32 条]                          │
      │   [ProcessQueue 跟踪进度]                             │
      │                          │                          │
      ├──5. 更新 offset (5s周期)──▶                          │
      │    commitOffset = msgTreeMap.firstKey()              │
      │    （若 ProcessQueue 为空则取 nextOffset）           │
      │                          │                          │
      │                    [持久化到 consumerOffset.json]     │
```

---

## 六、核心要点总结（面试话术）

### 6.1 一条消息如何被多组多实例消费

> 一个 Topic 的消息在 Broker 上只存一份（CommitLog），消费时通过 **ConsumeQueue + consumerOffset.json** 实现：
>
> - **组间独立**：每个 ConsumerGroup 有自己独立的 offset 进度（`Topic@Group` 作为 key），所以每条消息会被每个组各消费一次
> - **组内独占**：同一组内通过 Rebalance 把 MessageQueue 平均分给各个 Consumer 实例，一个 queue 同一时刻只被一个实例消费

### 6.2 消费进度保存

> **集群模式存 Broker**（`consumerOffset.json`），**广播模式存 Consumer 本地**。提交的 offset 不是"最后成功的 offset + 1"，而是 **"ProcessQueue 中最小未完成消息的 offset"**（ProcessQueue 为空则取下一条待拉 offset）—— 这样即使并发消费有乱序，崩溃恢复时也不会因为 offset 被提前推进而让未处理的消息"视作消费完成"，代价是**崩溃前已经处理成功但未提交的消息会被重投、需要业务幂等**。

### 6.3 消费者变动的 Rebalance

> **Consumer 上线/下线**会触发 Rebalance：
>
> 1. Broker 通过心跳感知成员变化 → 广播通知组内所有成员
> 2. 每个成员独立用分配算法（默认平均分配）计算自己该负责哪些 queue
> 3. **丢弃 queue 前先提交 offset**，新接手 queue 的 Consumer **从 Broker 查 offset 继续消费**
> 4. **崩溃检测有最多 120s 延迟**，期间该 queue 暂时无人消费

### 6.4 为什么这种设计是"至少一次"

> 三个地方都会引入重复：
>
> - Broker offset 持久化间隔（5s）
> - Consumer offset 上报间隔（5s）
> - Rebalance 时 ProcessQueue 里正在处理的消息被丢弃后由新 Consumer 重拉
>
> 所以 RocketMQ 的消费语义天然是 **at-least-once**，要做到 exactly-once 必须在业务层实现幂等。

---

## 七、一句话总结

> RocketMQ 的消费机制是 **"Pull + 长轮询 + 组间独立 offset + 组内 Rebalance"** 的组合设计。**消费进度按 `Topic@ConsumerGroup` 维度独立保存在 Broker**，实现了多消费组互不干扰；**组内通过 Rebalance 算法把 MessageQueue 动态分配给多个 Consumer 实例**，实现负载均衡和故障转移。提交的 offset 采用"最小未完成位置"策略保证不丢消息，代价是重复消费，因此 **业务层必须幂等**。Consumer 故障检测依赖 120s 心跳超时，这决定了 RocketMQ 的故障恢复是**分钟级**而非秒级。
