# RocketMQ 消息过滤机制：Tag / Key / SQL92

## 一、为什么需要消息过滤

一个 Topic 通常承载多种语义相近的消息（如 `OrderTopic` 下的创建、支付、取消），Consumer 只关心其中一部分。如果把所有消息都拉到 Consumer 端再过滤，会带来：

- **网络浪费**：不相关消息也在网络上传输
- **Consumer 压力大**：需要反序列化所有消息再判断
- **消费进度虚高**：Consumer 看起来消费了，但大部分被丢弃

RocketMQ 提供了三种过滤机制，**把过滤尽量前置到服务端**：

| 过滤方式 | 过滤位置 | 过滤载体 | 性能 | 灵活性 |
|:---|:---|:---|:---:|:---:|
| **Tag 过滤** | ConsumeQueue（索引层） | Tag 的 hashCode | ⭐⭐⭐⭐⭐ | ⭐ |
| **SQL92 属性过滤** | CommitLog（消息体层） | User Properties | ⭐⭐⭐ | ⭐⭐⭐⭐⭐ |
| **Message Key** | 不是过滤，是**按 Key 查询** | IndexFile 哈希索引 | — | — |

> ⚠️ **Message Key 严格来说不是"过滤"机制**，它是用来**按业务 ID 反查单条消息**的（如排查线上问题时按订单号查消息），但面试和实战中经常和过滤一起讨论，所以放在一起讲清楚。

---

## 二、Tag 过滤（最常用、最高效）

### 2.1 使用方式

```java
// Producer
Message msg = new Message("OrderTopic", "order_paid", body);
producer.send(msg);

// Consumer：订阅多个 Tag 用 || 连接，订阅全部用 *
consumer.subscribe("OrderTopic", "order_paid || order_refund");
consumer.subscribe("OrderTopic", "*");
```

### 2.2 底层原理：hashCode 服务端过滤

回顾 ConsumeQueue 的 20 字节结构：

```
┌──────────────────┬─────────────┬──────────────┐
│ CommitLog Offset │  Msg Size   │ Tag HashCode │
│     8 bytes      │  4 bytes    │   8 bytes    │
└──────────────────┴─────────────┴──────────────┘
                                       ↑
                              Tag 过滤就靠这 8 字节
```

Consumer 订阅时 Broker 在内存里把订阅的 Tag 列表转成 hashCode 集合 `[hashA, hashB]`，拉消息时：

```
for 每条 ConsumeQueue 索引:
    if 索引里的 TagHashCode ∈ [hashA, hashB]:
        读 CommitLog → 传给 Consumer
    else:
        跳过（不读 CommitLog，极快）
```

**Consumer 收到消息后，还会用真实 Tag 字符串再精确过滤一次**（兜底 hash 冲突）。

### 2.3 优势

- **过滤在 ConsumeQueue 层完成，不读 CommitLog**：大量不匹配的消息直接在索引扫描阶段就被跳过
- **20 字节索引极易命中 PageCache**：过滤本身几乎零 IO 成本
- **实现简单**：无需额外配置，开箱即用

### 2.4 限制

- 只支持 **`||`（或）** 逻辑，不支持 `&&`、`NOT`、通配符
- 一条消息**只能设置一个 Tag**
- 高基数维度不适用（详见下面的设计规范）

---

## 三、Tag 设计规范：一个 Topic 设多少 Tag 合适

### 3.1 推荐数量

| 场景 | 推荐 Tag 数量 | 说明 |
|:---|:---:|:---|
| **理想** | **≤ 20 个** | 无 hash 冲突风险 |
| **常规可接受** | **≤ 100 个** | 绝大多数业务的合理上限 |
| **警戒线** | **> 200 个** | 冲突概率上升，需评估方案 |
| **明确不推荐** | **> 1000 个** | 应改用 SQL92 或拆 Topic |

> 经验法则：**如果你在给一个 Topic 设计几百个 Tag，大概率是设计出了问题。**

### 3.2 为什么是这个范围：hash 冲突概率

Java `String.hashCode()` 返回 32 位 int，按生日悖论：

| Tag 数量 | 任意两个 Tag 冲突的概率 |
|:---:|:---:|
| 20 | ≈ 0.0000044% |
| 100 | ≈ 0.00011% |
| 1000 | ≈ 0.011% |
| 10000 | ≈ 1.16% |

看起来 1000 个冲突概率才 0.011%，为什么还不推荐？

1. **订阅越多 Tag，命中冲突的边际概率叠加**
2. **业务 Tag 命名高度相似**（如 `order_xxx_vip`、`order_xxx_normal`），**实际冲突率远高于理论值**

### 3.3 hash 冲突的后果

- 服务端把冲突的消息也传给 Consumer → **网络浪费**
- Broker 要读这些本该跳过的 CommitLog → **PageCache 污染**
- Consumer 客户端做字符串兜底过滤 → **CPU 消耗**

### 3.4 Tag 过多的其他危害

- **订阅表达式爆炸**：`"TagA || TagB || ... || TagN"` 难维护
- **订阅关系一致性难保证**：同一 Consumer Group 内所有实例必须订阅完全一致的 Tag 集合，否则消费混乱
- **Tag 不是物理隔离**：冷热 Tag 混在同一批 queue 里，冷 Tag 消费仍要扫大量索引

### 3.5 适合 / 不适合用 Tag 的场景

**✅ 适合**：

- 同一业务实体的不同事件：`订单创建` / `订单支付` / `订单取消`
- 优先级区分：`urgent` / `normal` / `low`
- 少量业务分类：`VIP用户` / `普通用户`
- 共同点：**可枚举、稳定、单一维度**

**❌ 不适合**：

| 反模式 | 反例 | 正确方案 |
|:---|:---|:---|
| 高基数维度 | 用户 ID、订单 ID 做 Tag | 用 Message Key |
| 多维度组合 | `华东_VIP_大促` | SQL92 属性过滤 |
| 业务域不同 | 订单/物流/支付塞一个 Topic | 拆 Topic |
| 频繁新增 | 每上活动加 Tag | 属性字段或独立 Topic |

### 3.6 设计 Tag 前自问 4 个问题

1. **可枚举吗？** —— 取值能一眼列出来？
2. **稳定吗？** —— 未来半年不会频繁新增？
3. **数量 < 20 吗？** —— 超过 100 就要重新评估
4. **单一维度吗？** —— 多维度上 SQL92

---

## 四、SQL92 属性过滤（灵活但有性能代价）

### 4.1 使用方式

```java
// Producer：通过 putUserProperty 设置任意属性
Message msg = new Message("OrderTopic", "order_paid", body);
msg.putUserProperty("region", "east");
msg.putUserProperty("level", "vip");
msg.putUserProperty("amount", "5000");
producer.send(msg);

// Consumer：SQL 表达式过滤
consumer.subscribe("OrderTopic", MessageSelector.bySql(
    "region = 'east' AND level = 'vip' AND amount > 1000"
));
```

**服务端需开启**：`broker.conf` 中配置 `enablePropertyFilter=true`

### 4.2 支持的语法

| 类别 | 支持的操作符 |
|:---|:---|
| **数值比较** | `=`, `<>`, `>`, `>=`, `<`, `<=` |
| **逻辑运算** | `AND`, `OR`, `NOT` |
| **区间判断** | `BETWEEN ... AND ...` |
| **集合判断** | `IN (...)` |
| **空值判断** | `IS NULL`, `IS NOT NULL` |
| **字符串** | 仅支持 `=`, `<>`, `IN`（**不支持 LIKE**） |

**常量类型**：数值、字符串（单引号）、布尔值（TRUE/FALSE）、NULL

**内置系统字段**（SQL 表达式中可直接引用）：

| 字段 | 含义 |
|:---|:---|
| `TAGS` | 消息的 Tag（Producer 通过 `setTags()` 设置的值） |
| `KEYS` | 消息的 Key |
| `MSGID` | 消息 ID |
| `BORNHOST` | 发送方 IP |
| `BORNTIMESTAMP` | 消息产生时间戳 |

### 4.3 底层原理：必须读 CommitLog

SQL92 过滤**无法在 ConsumeQueue 层完成**，因为属性数据在消息体里：

```
Step 1: 扫 ConsumeQueue 拿到每条消息在 CommitLog 的偏移
Step 2: 按偏移读 CommitLog，解析出 User Properties
Step 3: 用 SQL 表达式求值
Step 4: 命中才传给 Consumer
```

**性能特点**：

- 比 Tag 慢很多（每条消息都要读 CommitLog）
- Broker 本地有表达式缓存（BloomFilter 优化），重复相同订阅的表达式不会反复解析
- 过滤比例低（大部分消息会命中）时尚可接受，过滤比例高（只有 1% 命中）会有大量无用 IO

### 4.4 优缺点

**✅ 优点**：

- 灵活度极高，支持多维度组合
- 支持数值比较、区间、集合等复杂逻辑
- 适合高基数属性（如金额、时间范围）

**❌ 缺点**：

- 性能明显低于 Tag
- 需要 Broker 端配置开启
- 不支持 LIKE 模糊匹配（字符串只能精确匹配）
- 表达式写错不会编译期报错，容易上线后才发现

### 4.5 Tag 和 SQL92 能同时用吗

**能，但不是分层加速，而是"在 SQL 表达式里引用 TAGS 字段"。**

#### 关键限制：subscribe API 互斥

RocketMQ 客户端不允许在一次 `subscribe` 调用里同时传 Tag 表达式和 SQL 表达式，只能二选一：

```java
// 方式一：纯 Tag 模式（走 ConsumeQueue hashCode 过滤）
consumer.subscribe("OrderTopic", "order_paid || order_refund");

// 方式二：SQL92 模式（走 CommitLog 属性过滤，可引用 TAGS 字段）
consumer.subscribe("OrderTopic", MessageSelector.bySql(
    "TAGS IN ('order_paid', 'order_refund') " +
    "AND region = 'east' AND amount > 1000"
));
```

想要"Tag + 属性"的组合过滤效果 → **只能走方式二**，在 SQL 里用内置字段 `TAGS` 参与表达式。

#### ⚠️ 重要真相：SQL92 模式下 TAGS 不享受 hashCode 加速

很多资料（包括部分博客）会说"Tag + SQL92 组合 = ConsumeQueue hashCode 刷一层 + CommitLog 精细过滤"，**这是不准确的**。

RocketMQ 开源版的实现里，过滤路径是**互斥**的：

| 订阅方式 | 过滤路径 | 是否走 Tag hashCode 快速跳过 |
|:---|:---|:---:|
| `subscribe(topic, "tagA \|\| tagB")` | 仅 ConsumeQueue 层 | ✅ |
| `subscribe(topic, bySql("TAGS = 'tagA' AND ..."))` | 全程 CommitLog 层 | ❌ |

也就是说，一旦用了 `bySql(...)`，即使表达式里有 `TAGS = 'xxx'`，Broker 也会**逐条读 CommitLog 取出属性再对整条表达式求值**，不会走 ConsumeQueue 那 8 字节 hashCode 的短路优化。`TAGS` 在 SQL 里和 `region`、`amount` 等普通属性是**同一个等级**的过滤条件。

#### 那什么时候该用这种组合

- 确实需要**多维度条件**（除了 Tag 还要按 region、amount 等过滤）
- 且**能接受 SQL92 的性能代价**（比纯 Tag 慢，因为要读 CommitLog）

如果只是"Tag 单维度"就能解决的场景，**继续走纯 Tag 订阅，性能最好**。

#### 一条决策建议

- **单维度过滤** → 纯 Tag 订阅
- **多维度过滤** → SQL92 订阅（TAGS 和其他属性一起写进表达式）
- **不要迷信"Tag+SQL 组合能分层加速"**，它只是让你能在一次订阅里同时表达多个维度的条件而已

---

## 五、Message Key：按业务 ID 反查消息

### 5.1 使用方式

```java
// Producer：设置 Key（通常是订单号、用户 ID 等业务主键）
Message msg = new Message("OrderTopic", "order_paid", body);
msg.setKeys("ORDER_20260428_001");  // 多个 Key 用空格分隔
producer.send(msg);

// 查询：通过控制台或 API 按 Key 反查
List<MessageExt> msgs = consumer.viewMessage("OrderTopic", "ORDER_20260428_001");
```

### 5.2 底层原理：IndexFile 哈希索引

Key 不是过滤机制，它是为**按 Key 反查**服务的。Broker 侧维护了第三种文件 **IndexFile**：

```
${ROCKETMQ_HOME}/store/index/
  ├── 20260428150000     ← 文件名 = 创建时间戳
  ├── 20260428160000
  └── ...
```

**IndexFile 结构**（每个文件约 400MB，最多存 2000w 条索引）：

```
┌──────────────┬────────────────────────────────────────────────┐
│  IndexHeader │                Index Slot + Index Linked List   │
│    40 bytes  │                                                  │
└──────────────┴────────────────────────────────────────────────┘

IndexHeader: 起止时间戳、起止物理偏移、有效索引数
HashSlot   : 500w 个 slot，每个 4 字节，存最新一条 IndexItem 的位置
IndexItem  : 20 字节 × 2000w 条，每条记录：
    - keyHash（4 字节）
    - CommitLog 物理偏移（8 字节）
    - 时间差（4 字节，相对 BeginTimestamp）
    - 前一条相同 slot 的 IndexItem 位置（4 字节，形成链表）
```

### 5.3 查询流程

```
1. 计算 key 的 hashCode → 映射到某个 HashSlot
2. HashSlot 指向该 hash 对应的链表头（最新的 IndexItem）
3. 沿链表遍历，比对 keyHash + 时间范围
4. 匹配成功 → 取 CommitLog 物理偏移 → 读真实消息
```

**注意**：

- IndexFile 存的是 **key 的 hashCode**，所以查询结果**可能有 hash 冲突的噪声**，需要客户端再用真实 Key 精确比对
- 查询时**必须带时间范围**（beginTime / endTime），否则全表扫
- 主要用途是**排查问题时按业务 ID 查消息**，不是消费链路上的过滤

### 5.4 Key 反查能拿到什么信息

通过 Key 反查拿到的是一条完整的 `MessageExt` 对象（消息本体 + Broker 注入的元数据）：

**消息本身**：

| 字段 | 说明 |
|:---|:---|
| `body` | 消息体（byte[]） |
| `topic` | 所属 Topic |
| `tags` | Tag |
| `keys` | Keys（原样返回，多个用空格分隔） |
| `properties` | 所有用户自定义属性（`putUserProperty` 设的那些） |

**Broker 注入的元数据**：

| 字段 | 说明 |
|:---|:---|
| `msgId` | 全局消息 ID |
| `queueId` | 落在哪个 MessageQueue |
| `queueOffset` | 在 ConsumeQueue 里的逻辑偏移 |
| `commitLogOffset` | 在 CommitLog 里的物理偏移 |
| `storeTimestamp` | 消息**写入 Broker 的时间** |
| `bornTimestamp` | 消息**在 Producer 端生成的时间** |
| `bornHost` | Producer 的 IP:Port |
| `storeHost` | 收到消息的 Broker IP:Port |
| `sysFlag` | 系统标志位（事务消息、压缩等） |
| `reconsumeTimes` | 该消息对象当前的重试次数 |

### 5.5 ❌ Key 反查不能查消费状态

这是面试高频追问点：**Key 反查无法告诉你"这条消息有没有被消费、被谁消费、消费成功还是失败"**。

**根本原因：RocketMQ 的消费状态是"按 Queue 粒度的水位线"，不是按每条消息记录的。**

各文件的职责分工：

| 文件 | 存什么 | 和消费状态的关系 |
|:---|:---|:---|
| **CommitLog** | 消息本体 | ❌ 不存消费状态 |
| **ConsumeQueue** | 消息索引（20 字节） | ❌ 不存消费状态 |
| **IndexFile** | Key 的 hash 索引 | ❌ 不存消费状态 |
| **`consumerOffset.json`** | 消费进度 | ✅ 但按 ConsumerGroup + Topic + QueueId 维度存水位 |

消费进度文件 `~/store/config/consumerOffset.json` 结构示例：

```json
{
  "offsetTable": {
    "OrderTopic@order_consumer_group": {
      "0": 1523,
      "1": 1480,
      "2": 1551,
      "3": 1492
    }
  }
}
```

**Broker 只知道"某个 Consumer Group 在某个 Queue 上消费到 offset=1523 了"，不知道某条具体消息的消费结果。**

#### 为什么这样设计

1. **性能考虑**：每条消息记"被哪些组消费过/失败过" → 元数据爆炸
2. **消费模型决定**：消费是顺序推进的水位，消费完 offset=100 意味着 0~99 都消费过了
3. **职责分离**：消息存储归 Broker，消费状态归 Consumer Group

#### 能间接推断吗：可以，但不可靠

通过 Key 查到消息后拿到 `queueId + queueOffset`，再对比当前该 Queue 的消费水位：

```
查到 msg 在 queueId=2, queueOffset=1500
查 consumerOffset.json → queueId=2 当前消费水位 = 1523
1500 < 1523 → 这条消息"理论上已被消费过"
```

但**只能推断水位已跨过，不能证明消费成功**：

- 消息可能消费失败进了**重试队列**（`%RETRY%ConsumerGroup`），原 Queue 的 offset 照样前进
- 消息可能最终进了**死信队列**（`%DLQ%ConsumerGroup`）
- 广播模式下消费进度存在 Consumer 本地，Broker 根本看不到

### 5.6 要查消费状态必须开启消息轨迹

RocketMQ 为可观测性专门设计了**消息轨迹（Message Trace）**机制，独立于 Key 反查。

**开启方式**（开源版 4.4+ 支持）：

```java
// Producer 端开启
DefaultMQProducer producer = new DefaultMQProducer("producer_group", true);  // 第二个参数 enableMsgTrace

// Consumer 端开启
DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("consumer_group", true);
```

开启后，全链路事件会被写入系统 Topic `RMQ_SYS_TRACE_TOPIC`：

| 事件 | 记录时机 |
|:---|:---|
| `Pub` | Producer 发送时 |
| `SubBefore` | Consumer 开始消费前 |
| `SubAfter` | Consumer 消费完成（含 SUCCESS / RECONSUME_LATER） |
| `EndTransaction` | 事务消息提交/回滚 |

通过轨迹可以查到：

- 消息被哪些 Consumer Group 消费了
- 每次消费的结果（成功/失败/重试）
- 从发送到消费的总耗时
- 被哪个 Consumer 实例处理的

#### 排查一条消息全链路的推荐流程

```
① mqadmin queryMsgByKey -t OrderTopic -k ORDER_001
    → 拿到 msgId、queueId、queueOffset、storeTimestamp
② mqadmin queryMsgById -i <msgId>
    → 按 msgId 精确查，避免 Key hash 冲突
③ mqadmin consumerProgress -g order_consumer_group
    → 对比消费水位，初步判断是否被跨过
④ 查 %RETRY%order_consumer_group 和 %DLQ%order_consumer_group
    → 是否进了重试/死信
⑤ 查消息轨迹（需开启 Trace）
    → 看完整的消费事件链
```

### 5.7 一张表总结 Key 的查询能力

| 想知道什么 | Key 反查能答吗 |
|:---|:---:|
| 消息内容是什么 | ✅ |
| 消息什么时候到 Broker | ✅（storeTimestamp） |
| 消息落在哪个 Queue | ✅（queueId + queueOffset） |
| 消息发送成功了吗 | ✅（能查到即成功） |
| 消息被消费了吗 | ❌ 仅能间接推断 |
| 消息被哪个 Consumer 消费了 | ❌ 需开启消息轨迹 |
| 消息消费成功/失败 | ❌ 需开启消息轨迹 |
| 消息重试了几次 | ⚠️ 原消息不更新，重试是新投递（看 `%RETRY%` 或 Trace） |
| 消息进死信了吗 | ❌ 需去 `%DLQ%ConsumerGroup` 单独查 |

### 5.8 Key 适合放什么

| 场景 | 示例 |
|:---|:---|
| **业务主键** | 订单号、支付流水号、用户 ID |
| **唯一标识** | 请求 ID、TraceId |
| **排查线索** | 任何能唯一定位问题的业务字段 |

**不适合放**：

- 取值相同的字段（如 region=east），会导致同一 hash slot 链表极长
- 超长字符串（Key 会存入消息元数据，占用空间）

### 5.5 和 Tag、SQL92 的区别

| 维度 | Tag | SQL92 属性 | Key |
|:---|:---|:---|:---|
| **用途** | 消费时过滤 | 消费时过滤 | **运维时反查** |
| **在消费链路上生效** | ✅ | ✅ | ❌（不参与消费） |
| **支持的查询方式** | 订阅时指定 | SQL 表达式 | 控制台/API 按 Key 查 |
| **底层数据结构** | ConsumeQueue | CommitLog | IndexFile |

---

## 六、三种机制对比总览

### 6.1 定位与场景

| 机制 | 本质 | 典型场景 |
|:---|:---|:---|
| **Tag** | 消费订阅过滤 | 业务事件类型区分（创建/支付/退款） |
| **SQL92** | 消费订阅过滤（多维度） | 复杂条件组合（地区+级别+金额） |
| **Key** | 运维反查索引 | 按订单号查单条消息排查问题 |

### 6.2 性能对比

| 机制 | 过滤位置 | IO 成本 | 吞吐影响 |
|:---|:---|:---|:---|
| **Tag** | ConsumeQueue（20 字节索引） | 几乎零成本 | 最高 |
| **SQL92** | CommitLog（要读消息体） | 读 CommitLog | 中等 |
| **Key 查询** | IndexFile + CommitLog | 按 hash 定位，单次查询 | 不影响消费吞吐 |

### 6.3 选型决策树

```
需要按什么维度过滤/查询？
│
├─ 单一维度、可枚举、< 100 个取值
│     └─▶ Tag
│
├─ 多维度组合、数值比较、高基数
│     └─▶ SQL92 属性过滤
│
├─ 主维度 + 次级精细过滤
│     └─▶ Tag + SQL92 组合
│
└─ 按业务 ID 排查单条消息（非消费场景）
      └─▶ Message Key
```

---

## 七、实战最佳实践

### 7.1 推荐组合拳

```java
Message msg = new Message("OrderTopic", body);

// 1. Tag：主维度，业务事件类型（可枚举、稳定）
msg.setTags("order_paid");

// 2. Key：业务主键，用于事后排查（高基数）
msg.setKeys("ORDER_20260428_001");

// 3. Property：多维度细粒度属性（用于 SQL92 过滤）
msg.putUserProperty("region", "east");
msg.putUserProperty("level", "vip");
msg.putUserProperty("amount", "5000");

producer.send(msg);
```

### 7.2 订阅端推荐写法

```java
// 简单场景：只用 Tag
consumer.subscribe("OrderTopic", "order_paid || order_refund");

// 复杂场景：Tag + SQL92 组合过滤
consumer.subscribe("OrderTopic", MessageSelector.bySql(
    "TAGS IN ('order_paid', 'order_refund') " +
    "AND region = 'east' " +
    "AND amount > 1000"
));
```

### 7.3 常见坑

1. **同一 Consumer Group 内所有实例订阅关系必须完全一致**，否则 Rebalance 会乱
2. **SQL92 需要 Broker 端开启 `enablePropertyFilter=true`**
3. **Key 查询必须带时间范围**，否则扫描成本高
4. **Tag 不支持通配符和 AND**，不要试图用字符串拼接模拟
5. **属性值都按字符串存储**，SQL92 里 `amount > 1000` 需要 Broker 自动转换，注意类型一致性

---

## 八、一句话总结

> RocketMQ 提供了 **"三驾马车"** 式的消息过滤体系：
> - **Tag** 在 ConsumeQueue 层做 hashCode 过滤，**性能最好但只适合可枚举的单一维度**，推荐数量 ≤ 100
> - **SQL92 属性过滤** 在 CommitLog 层做表达式求值，**灵活但性能较低**，适合多维度组合查询
> - **Message Key** 通过 IndexFile 哈希索引支持**按业务 ID 反查单条消息**，是运维排查利器而非消费过滤
>
> 三者定位清晰、各司其职：**Tag 管"类"，SQL92 管"条件"，Key 管"找"**。实战中的最佳组合是 **Tag 做主维度 + SQL92 做精细过滤 + Key 做业务主键索引**。
