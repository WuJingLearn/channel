# RocketMQ 顺序消息实现原理详解

> 本文系统讲清楚 RocketMQ 顺序消息的实现原理，覆盖：
> 1. **顺序消息的本质问题** —— 为什么"顺序"这么难
> 2. **两种顺序级别** —— 全局顺序 vs 分区顺序
> 3. **三端协作原理** —— Producer / Broker / Consumer 缺一不可
> 4. **核心三把锁** —— Broker 分布式锁 + ProcessQueue 锁 + MessageQueue 对象锁
> 5. **失败处理机制** —— 阻塞重试 vs 丢到重试队列
> 6. **Rebalance 与故障场景** —— 顺序性的致命威胁
> 7. **常见陷阱与生产建议**

---

## 一、先看问题本质：为什么"顺序"这么难？

RocketMQ 默认是**分区并行**的消息队列：

- 一个 Topic 有多个 MessageQueue（分区）
- Producer 轮询写到不同 queue
- Consumer 多线程并行消费多个 queue

这种架构天然**无法保证全局顺序**——同一个订单的"创建 → 支付 → 发货 → 完成"四条消息被发到 4 个不同的 queue，消费端 4 个线程同时拉取、同时消费，执行顺序完全不可预测。

**核心矛盾**：高吞吐（并行） ↔ 严格顺序（串行）

RocketMQ 的解法：**放弃全局顺序，只做分区顺序（局部顺序）**。

---

## 二、顺序消息的两个级别

| 级别 | 含义 | 实现难度 | 吞吐 |
|:---|:---|:---:|:---:|
| **全局顺序** | Topic 内所有消息严格有序 | 极高 | 极低（只能 1 个 queue） |
| **分区顺序**（Partial Order） | 同一业务 Key 的消息有序，不同 Key 之间无序 | 中 | 高 |

**生产上几乎只用分区顺序**：订单 A 和订单 B 之间不需要有序，只要每个订单自己内部的消息有序就够了。

如果真的需要全局顺序，把 Topic 的 `writeQueueNums=1, readQueueNums=1` 即可，但代价是**整个 Topic 退化为单队列、单线程消费**，吞吐极低，只在极少数场景使用（如 Binlog 同步单表）。

---

## 三、实现原理：三端协作

顺序消息不是某个单点功能，而是 **Producer + Broker + Consumer 三端配合**的结果，缺一不可。

```text
Producer 端         Broker 端            Consumer 端
─────────          ────────            ──────────
相同业务 Key        按写入顺序           一个 queue 只被
路由到同一       → append 到同一      → 一个线程串行消费
  MessageQueue     MessageQueue
     (1)              (2)                   (3)
```

### 3.1 环节一：Producer 端 —— 让相同 Key 的消息进同一 queue

核心工具：`MessageQueueSelector` 接口。

```java
public class OrderProducerDemo {

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("order_producer_group");
        producer.setNamesrvAddr("ns1:9876");
        producer.start();

        String orderId = "ORDER_10086";
        String[] tags = {"CREATE", "PAY", "DELIVER", "FINISH"};

        for (String tag : tags) {
            Message msg = new Message("OrderTopic", tag, orderId, ("body-" + tag).getBytes());

            // 关键：传入 MessageQueueSelector，根据 orderId 路由到固定 queue
            producer.send(msg, new MessageQueueSelector() {
                @Override
                public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
                    String id = (String) arg;
                    int index = Math.abs(id.hashCode()) % mqs.size();
                    return mqs.get(index);
                }
            }, orderId);
        }
    }
}
```

**关键点**：

- `MessageQueueSelector#select` 接收**当前 Topic 所有的 MessageQueue 列表**和业务 key（shardingKey）
- 用 `hash(orderId) % queueCount` 把相同 orderId 的消息**稳定路由**到同一个 queue
- 这一步在 Producer 客户端本地完成，**不经过 Broker**

**源码路径**：`DefaultMQProducerImpl#sendSelectImpl`

```text
业务代码 send(msg, selector, arg)
   │
   ▼
sendSelectImpl
   ├─ 拉取 Topic 路由，拿到 topicPublishInfo（含所有 MessageQueue）
   ├─ selector.select(mqs, msg, arg)  ← 业务自定义选择器
   ├─ sendKernelImpl(msg, selectedMq) ← 按选中的 mq 发送
   └─ 返回 SendResult
```

#### ⚠️ Producer 侧的重试陷阱

顺序消息发送失败时，**不能自动切换到其他 queue 重试**（否则顺序就破坏了）。所以：

- `sendSelectImpl` 里 `retryTimesWhenSendFailed` 默认**仍然只在同一个 queue 上重试**
- 如果这个 queue 所在的 Broker 挂了，顺序消息会**直接失败**，业务必须感知
- 这是**顺序性 vs 可用性**的天然取舍

### 3.2 环节二：Broker 端 —— 天然保证单 queue 内的顺序

Broker 这一环其实**不需要特殊处理**，因为 RocketMQ 的存储结构天然支持：

```text
Producer 发消息到 queue-0
   │
   ▼
Broker 按到达顺序 append 到 CommitLog
   │
   ▼
ReputMessageService 异步为 queue-0 生成 ConsumeQueue 索引
   （按 CommitLog 中的顺序，追加到 queue-0 的 ConsumeQueue 文件）
   │
   ▼
queue-0 的 ConsumeQueue 里索引条目的顺序 = 消息发送顺序
```

**同一个 queue 的 ConsumeQueue 文件是顺序追加的**，Consumer 按 offset 递增拉取，天然就是发送顺序。

**但有一个前提**：同一个 orderId 的消息要在同一个 queue 上**按发送顺序到达 Broker**。这要求 Producer 端：

- **同步发送**（`producer.send(...)` 等返回），不能用异步 / oneway
- 不能有多个 Producer 实例并发发同一个 orderId 的消息（否则网络乱序）

### 3.3 环节三：Consumer 端 —— 队列级别串行消费

这是最关键的一环。默认的 `MessageListenerConcurrently` 会用**线程池并发处理同一个 queue 拉到的消息**，顺序就乱了。所以必须用 **`MessageListenerOrderly`**：

```java
public class OrderConsumerDemo {

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("order_consumer_group");
        consumer.setNamesrvAddr("ns1:9876");
        consumer.subscribe("OrderTopic", "*");

        // 关键：MessageListenerOrderly
        consumer.registerMessageListener(new MessageListenerOrderly() {
            @Override
            public ConsumeOrderlyStatus consumeMessage(
                    List<MessageExt> msgs, ConsumeOrderlyContext context) {
                for (MessageExt msg : msgs) {
                    try {
                        handleBusiness(msg);
                    } catch (Exception e) {
                        // 顺序消费失败：挂起当前 queue，稍后重试
                        // 返回 SUSPEND_CURRENT_QUEUE_A_MOMENT 会让这个 queue 暂停一段时间再拉
                        return ConsumeOrderlyStatus.SUSPEND_CURRENT_QUEUE_A_MOMENT;
                    }
                }
                return ConsumeOrderlyStatus.SUCCESS;
            }
        });
        consumer.start();
    }

    private static void handleBusiness(MessageExt msg) {
        // 业务处理
    }
}
```

---

## 四、核心机制：三把锁保证串行

`ConsumeMessageOrderlyService` 依赖**三层锁**来保证同一个 queue 的消息在 Consumer 集群里**独占且串行**被处理。

### 4.1 🔒 锁 1：Broker 端的分布式 Queue 锁（跨进程锁）

```text
Consumer Rebalance 分到 queue-0
   │
   ▼
向 Broker 发 LOCK_BATCH_MQ 请求
   │
   ▼
Broker 端 RebalanceLockManager：
   mqLockTable: Map<Group, ConcurrentHashMap<MessageQueue, LockEntry>>
   │
   ├─ 如果 queue-0 没被锁 → 记录 (clientId, expireTime=60s)，返回锁成功
   ├─ 如果被当前 clientId 锁着 → 续期 60s，返回成功
   └─ 如果被其他 clientId 锁着 且未过期 → 返回失败
```

**目的**：防止 Rebalance 过渡期（旧 Consumer 还没释放、新 Consumer 已经拿到 queue）的**并发消费**。

**细节**：

- Consumer 每 **20 秒**向 Broker 续锁一次（`RebalanceImpl#lockAll`）
- Broker 端锁有效期 **60 秒**
- Consumer 正常 shutdown 会主动 unlock（`UNLOCK_MQ_MB`），Rebalance 时移除的 queue 也会 unlock
- 如果 Consumer 进程异常挂掉 → 锁在 60 秒后自然过期，新 Consumer 才能拿到

### 4.2 🔒 锁 2：客户端 ProcessQueue 的 JVM 级锁（进程内队列锁）

```text
// ProcessQueue
private final ReentrantLock consumeLock = new ReentrantLock();
```

同一个 Consumer 实例内，如果线程池有多个线程都拿到了 queue-0 的消息批次，它们**都要抢这把锁**，同时只有一个线程能进入消费逻辑。

### 4.3 🔒 锁 3：MessageQueue 维度的对象锁（细粒度对象锁）

源码 `ConsumeMessageOrderlyService#ConsumeRequest#run`：

```text
run()
 │
 ├─ 1. 检查 processQueue.isDropped() → 被 rebalance 移除的 queue 直接退出
 │
 ├─ 2. 获取 Broker 分布式锁状态：必须 processQueue.isLocked() 且锁未过期
 │      否则调度 tryLockLaterAndReconsume() 稍后重试
 │
 ├─ 3. synchronized (objLock) { ... }
 │      // objLock = messageQueueLock.fetchLockObject(mq)
 │      │
 │      ├─ 循环从 processQueue 拿一批消息（takeMessages，最多 batchSize 条）
 │      │
 │      ├─ processQueue.getConsumeLock().lock();  ← ReentrantLock，消费独占
 │      ├─ 调用业务 MessageListenerOrderly.consumeMessage(msgs, ctx)
 │      ├─ processQueue.getConsumeLock().unlock();
 │      │
 │      └─ 处理结果：
 │            ├─ SUCCESS: commitOffset（从 msgTreeMap 移除这批消息，更新 offset）
 │            ├─ SUSPEND_CURRENT_QUEUE_A_MOMENT:
 │            │     把消息放回 consumingMsgOrderlyTreeMap，
 │            │     延迟 1s 后 submitConsumeRequest 重投
 │            │     ⚠️ 整个 queue 暂停消费后续消息，不会跳过当前这批
 │            └─ 超过 maxReconsumeTimes（默认 Integer.MAX_VALUE）：
 │                  投递到 %RETRY%group 死信处理
 │
 └─ 4. 如果还有未处理消息、没超时 → submitConsumeRequest 继续下一批
```

### 4.4 三把锁协作的效果

- **锁 1（Broker 分布式锁）**：确保**整个 Consumer 集群中**只有一个 Consumer 实例在消费这个 queue
- **锁 2（consumeLock）**：确保**同一个 Consumer 实例内**只有一个线程在消费这个 queue
- **锁 3（objLock）**：MessageQueue 维度的对象锁，和 consumeLock 配合保证 `takeMessages` + 消费的原子性

---

## 五、顺序消费失败的处理：阻塞而不是跳过

这是顺序消费和并发消费最大的区别：

| 场景 | `MessageListenerConcurrently`（并发） | `MessageListenerOrderly`（顺序） |
|:---|:---|:---|
| 消费成功 | 提交 offset，继续后续消息 | 提交 offset，继续后续消息 |
| 消费失败 | 返回 `RECONSUME_LATER` → 消息进 `%RETRY%group` 重试队列，**不阻塞**后续消息 | 返回 `SUSPEND_CURRENT_QUEUE_A_MOMENT` → 整个 queue **暂停 1 秒**后重试这批消息，**阻塞**后续消息 |
| 重试上限 | 16 次后进 `%DLQ%group` 死信 | 默认 `Integer.MAX_VALUE` 次，会**一直卡住**直到成功 |

**坑点**：顺序消费如果业务逻辑有 bug 导致某条消息永远处理失败，**这个 queue 后面的所有消息都会堵住**，必须通过：

- 调小 `consumer.setMaxReconsumeTimes(N)`
- 监控消费堆积 + 告警
- 业务层为重试次数过多的消息兜底（如移到人工处理队列）

来及时发现和处理。

---

## 六、顺序性的致命威胁：Rebalance 与宕机

顺序消息的"三把锁"能解决稳态下的并发问题，但**集群成员变化时**依然可能破坏顺序。

### 6.1 Rebalance 场景

```text
T0: Consumer-A 持有 queue-0 的 Broker 锁，正在消费 offset=100 的消息
T1: 新增 Consumer-B，触发 Rebalance
T2: Consumer-A 的 rebalance 算出 queue-0 被分给 Consumer-B
    → Consumer-A 把 queue-0 对应的 processQueue 标记 dropped
    → 停止拉取、立即 unlock Broker 端的锁
T3: Consumer-B 收到 queue-0 的分配
    → 向 Broker 申请 LOCK_BATCH_MQ
    → 从 Broker 查上次提交的 offset（比如 100）
    → 从 offset=100 开始拉消息
```

**潜在问题**：如果 Consumer-A 在 T2 时还没把消费完的 offset 提交到 Broker，Consumer-B 从旧的 offset 拉就会**重复消费**。

**RocketMQ 的缓解措施**：

- `processQueue.setDropped(true)` 之后，`ConsumeMessageOrderlyService` 在进入 synchronized 块前会检查 dropped 状态，直接退出——避免消费后进行 offset 提交时 queue 已不属于自己造成错乱
- Consumer 在 unlock Broker 锁之前会**强制提交一次 offset**（`RebalanceImpl#removeUnnecessaryMessageQueue` 对 OrderlyConsumer 会调 `unlock(mq, true)`）

### 6.2 Consumer 宕机场景

- Consumer-A 进程 crash，没来得及释放 Broker 锁
- Broker 端锁 **60 秒后自然过期**
- Consumer-B 的 Rebalance 在 20 秒周期里尝试 `lockAll`，过期后能拿到
- 这 60 秒内 queue-0 **完全停止消费**，是必须付出的代价

### 6.3 Broker Master 挂了

顺序消息对 Broker 故障**极为敏感**：

- Master 挂后，顺序消息**不能切换到 Slave 消费**（Slave 上 offset 落后可能导致乱序）
- 必须等 Master 恢复，或者依赖 DLedger / Controller 模式的自动切换
- 切换期间该 queue 的所有消息**全部阻塞**

想对比普通消息在 Broker 故障下的行为，参见 `HighAvailability.md`。

---

## 七、常见陷阱与生产建议

### 7.1 readQueueNums 必须等于 writeQueueNums

如果 `writeQueueNums=8, readQueueNums=4`：

- Producer 按 `orderId % 8` 选 queue，可能选到 queue-5
- 但 Consumer 只订阅了 queue-0 ~ queue-3
- **消息永远消费不到**

顺序消息场景**必须保证读写队列数一致**。读写队列的详细原理参见 `ReadWriteQueue.md`。

### 7.2 queue 数量不能随意变动

如果运行中调整 `writeQueueNums`：

- `hash(orderId) % newQueueCount` 的结果会变
- 同一个订单**前后消息会打到不同 queue**
- 顺序彻底破坏

**生产建议**：顺序 Topic 在创建时一次性规划好 queue 数量（按峰值吞吐 / 单 queue 能力估算），**永远不要改**。

### 7.3 Hash 倾斜问题

如果业务 key 分布不均（比如 90% 订单都是大客户 A 的），会导致：

- queue-X 上堆积严重、处理慢
- 其他 queue 几乎空闲
- 单 queue 内部仍然串行，吞吐瓶颈

**缓解**：

- 选择更细粒度的 shardingKey（不用 `customerId` 用 `orderId`）
- 业务容忍的情况下在 shardingKey 里拼随机 slot：`orderId + "_" + (hash % 4)` 把同一批拆到多个 queue（前提是业务能接受这个粒度的并行）

### 7.4 顺序消费必须幂等

即便有各种锁保证，**重复消费依然可能发生**：

- Rebalance 过渡期
- Consumer 消费成功但 offset 提交前宕机
- 网络抖动

业务处理必须**幂等**，不要依赖"顺序消息就不会重复"这个错觉。幂等实现常见方式：

- 业务主键去重（DB 唯一索引）
- 消息去重表（Message Key / msgId）
- 状态机保护（前置状态校验）

### 7.5 严禁混用消费模式

一个 Consumer Group 里不能同时注册 `MessageListenerOrderly` 和 `MessageListenerConcurrently`。RocketMQ 以第一次 `registerMessageListener` 为准，后续调用无效，但代码看起来"生效"，是个很容易踩的坑。

### 7.6 发送端必须同步发送

```text
// ❌ 错误：异步发送，回调线程顺序不保证
producer.send(msg, new SendCallback() { ... });

// ❌ 错误：oneway 完全不保证送达
producer.sendOneway(msg);

// ✅ 正确：同步发送，等返回后再发下一条
SendResult result = producer.send(msg, selector, orderId);
```

异步发送时，虽然 `selector` 选 queue 是确定的，但业务代码 `for` 循环里**没等前一条返回就发下一条**，网络乱序可能导致消息到 Broker 的顺序和业务期望的顺序不一致。

---

## 八、实战配置建议

### 8.1 Topic 配置

```properties
# 创建 Topic 时指定（通过 mqadmin updateTopic）
topicName=OrderTopic
writeQueueNums=16          # 按峰值 TPS / 单 queue 能力规划，一旦定下严禁改
readQueueNums=16           # 必须等于 writeQueueNums
perm=6                     # 读写权限
```

### 8.2 Producer 配置

```text
// 同步发送（必须）
producer.setRetryTimesWhenSendFailed(2);

// 注意：顺序消息不要改 sendLatencyFaultEnable 的默认值 false
// 否则故障规避可能让消息跳到其他 broker，破坏顺序
// producer.setSendLatencyFaultEnable(false);  // 默认即 false
```

### 8.3 Consumer 配置

```text
consumer.setConsumeThreadMin(20);
consumer.setConsumeThreadMax(64);
// 顺序消费时，线程池大小虽设得大，但单个 queue 仍是串行
// 线程池主要服务于多个 queue 之间的并行

consumer.setMaxReconsumeTimes(16);
// 建议调小，避免某条坏消息永久阻塞 queue
// 达到上限后消息会进死信队列，业务需监控 DLQ
```

### 8.4 监控指标

| 指标 | 告警阈值 | 说明 |
|:---|:---|:---|
| 单 queue 消费堆积 | 持续 > 1000 条 | 可能有消息卡住 |
| 消费耗时 P99 | > 业务预期 2 倍 | 业务处理变慢，堆积风险 |
| `SUSPEND_CURRENT_QUEUE_A_MOMENT` 次数 | 持续出现 | 业务异常 |
| DLQ 消息数 | > 0 | 有消息重试到上限，需人工介入 |
| Broker 锁失败次数 | 持续 > 0 | Rebalance 异常或有 clientId 冲突 |

---

## 九、一句话总结

> RocketMQ 的顺序消息本质是**"分区顺序"**，通过三端配合实现：
>
> - **Producer**：`MessageQueueSelector` 用业务 Key 哈希，把相同 Key 的消息稳定路由到同一个 queue
> - **Broker**：CommitLog / ConsumeQueue 天然顺序追加，单 queue 内部顺序由存储模型免费保证
> - **Consumer**：`MessageListenerOrderly` + **Broker 分布式锁（60s）+ ProcessQueue 进程内锁 + MessageQueue 对象锁** 三层锁，确保同一个 queue 在整个集群里同一时刻只被一个线程串行消费
>
> **代价**：
>
> - 牺牲了**并发度**（单 queue 串行）
> - 牺牲了**容错性**（失败阻塞整个 queue、Master 挂了不能切 Slave）
> - 牺牲了**弹性**（queue 数不能调整）
>
> **生产判断**：真的需要顺序吗？能不能在业务层用版本号 / 状态机保护，改用并发消费？顺序消息是"最后手段"，不是"默认选项"。
