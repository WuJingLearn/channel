# RocketMQ 高可用全景详解

> 本文系统讲清楚 RocketMQ 的高可用体系，覆盖：
> 1. **集群部署形态** —— 从单机到生产级的五种部署模式
> 2. **主从复制机制** —— 同步 / 异步复制的权衡
> 3. **NameServer 的高可用** —— 为什么无状态就够了
> 4. **Producer 端高可用** —— 重试 + 故障规避（latencyFaultTolerance）
> 5. **Consumer 端高可用** —— ALLOW_CONSUME_FROM_SLAVE 机制
> 6. **Master 故障场景全链路分析** —— 两主两从下一个 Master 挂了会发生什么
> 7. **消息不丢失保障** —— 刷盘 + 复制的组合拳
> 8. **DLedger 模式** —— 基于 Raft 的秒级自动主备切换
> 9. **RocketMQ 5.x Controller 模式** —— 更轻量的自动主备切换
> 10. **实战配置建议** —— 不同业务场景的推荐配置

---

## 一、集群部署形态

RocketMQ 从单机到生产级，有 5 种典型部署模式，逐级递进：

### 1.1 单机模式（Single Master）

```
┌─────────────┐
│ NameServer  │
└──────┬──────┘
       │
┌──────▼──────┐
│   Master    │
└─────────────┘
```

- **特点**：一个 NameServer + 一个 Master Broker
- **可用性**：无任何冗余，Master 挂了整个系统不可用、消息不可写
- **使用场景**：开发测试环境、Demo
- **严禁用于生产**

### 1.2 多 Master 无 Slave

```
┌─────────────────────┐
│  NameServer 集群    │
└──────┬───────┬──────┘
       │       │
┌──────▼┐   ┌─▼─────┐
│Master-A│  │Master-B│
└────────┘  └────────┘
```

- **特点**：多个 Master，每个 Master 独立存储自己的消息、**没有副本**
- **可用性**：单个 Master 挂了不影响其他 Master 继续写入（Topic 层面高可用），但**挂掉那台的未消费消息暂时不可读**，磁盘坏了则永久丢失
- **优点**：写入性能最高（没有主从同步开销）
- **缺点**：单点故障下有数据丢失风险
- **使用场景**：对消息丢失不敏感的业务，比如日志采集、监控数据

### 1.3 多 Master 多 Slave（异步复制）—— 生产最常见

```
┌─────────────────────┐
│  NameServer 集群    │
└──┬─────┬─────┬───┬──┘
   │     │     │   │
┌──▼─┐ ┌─▼──┐ ┌▼──┐ ┌▼──┐
│M-A │ │S-A │ │M-B│ │S-B│
│id=0│ │id=1│ │=0 │ │=1 │
└────┘ └────┘ └───┘ └───┘
   │     ▲     │     ▲
   └ async ────┘─ async ──
     复制          复制
```

- **特点**：每个 Master 配一个或多个 Slave，Master 写完就返回给 Producer，**异步**把数据推给 Slave
- **可用性**：Master 挂了 Slave 可以接管**读请求**（Consumer 继续消费历史消息），但 **Slave 不会自动升 Master**，挂掉那部分的写入暂时不可用
- **数据安全**：极端情况下（Master 挂时尚未同步到 Slave），可能丢失少量消息
- **性能**：写入延迟低，TPS 高
- **使用场景**：**绝大多数生产业务**，比如订单、支付的业务消息

### 1.4 多 Master 多 Slave（同步复制 / 同步双写）

```
┌──▼─┐           ┌─▼──┐
│M-A │──sync──→ │S-A │
│id=0│ ← ack ── │id=1│
└────┘           └────┘
```

- **特点**：Master 收到消息后，**必须等 Slave ACK** 才返回 Producer 成功
- **数据安全**：**不丢消息**（Master 磁盘坏了 Slave 上还有完整副本）
- **性能**：写入延迟增加（要走一次网络往返），TPS 降低约 30~50%
- **可用性**：仍然**不支持自动主备切换**
- **使用场景**：金融级业务，对一致性要求极高、能接受延迟略高

### 1.5 DLedger 模式（基于 Raft）

```
       Broker-A 组（3 个节点）
  ┌───────┬───────┬───────┐
  │ Node1 │ Node2 │ Node3 │
  │Leader │Follow │Follow │
  └───────┴───────┴───────┘
  通过 Raft 协议选举 Leader
```

- **特点**：一个 broker 组由 **至少 3 个节点**组成（一般 3 或 5 个），通过 Raft 协议选举 Leader
- **可用性**：**Leader 挂了自动选举新 Leader**（通常 10 秒内切换完成），真正意义上的高可用
- **数据安全**：多数派 ACK 才返回成功，不丢消息
- **性能**：每次写入要半数以上节点 ACK，延迟略高于 ASYNC_MASTER
- **使用场景**：4.5+ 版本，对可用性要求高且能接受"一个 broker 组要 3+ 节点"的资源成本
- **注意**：每个 broker 组是独立的 Raft 集群，**不同 broker 组之间无关联**

### 1.6 Controller 模式（RocketMQ 5.x）

```
┌─────────────┐  ┌─────────────┐
│ NameServer  │  │ Controller  │ ← 5.x 新组件
└─────────────┘  └──────┬──────┘
                        │ 监控 Broker 健康状态
                        │ 触发自动主备切换
        ┌───────────────▼────────────┐
        │  Master / Slave 普通主从集群  │
        └────────────────────────────┘
```

- **特点**：在 NameServer 旁边加一个 Controller 集群（也用 Raft 做内部高可用），负责监控 Broker、触发主备切换
- **优点**：比 DLedger 更轻量（**一个 broker 组仍然是 1 主多从，不需要 3 个节点起**）
- **可用性**：Master 挂了 Controller 检测到之后**自动把某个 Slave 切成新 Master**
- **使用场景**：RocketMQ 5.x 推荐方案，兼顾资源成本和自动切换

### 1.7 五种模式对比

| 模式 | 副本 | 自动切换 | 不丢消息 | 资源成本 | 推荐场景 |
|:---|:---:|:---:|:---:|:---:|:---|
| 单 Master | 无 | ❌ | ❌ | 最低 | 开发测试 |
| 多 Master 无 Slave | 无 | ❌ | ❌ | 低 | 日志/监控 |
| 多主多从（异步） | 有 | ❌ | 弱保证 | 中 | **大部分生产业务** |
| 多主多从（同步） | 有 | ❌ | ✓ | 中 | 金融级 |
| DLedger | 有（Raft） | ✓（秒级） | ✓ | 高（3 节点起） | 4.5+ 高可用 |
| Controller (5.x) | 有 | ✓ | ✓ | 中 | 5.x 推荐 |

---

## 二、主从复制机制

主从复制是 RocketMQ 高可用的基石，核心参数是 `brokerRole`：

### 2.1 三种 brokerRole

```text
// BrokerRole 枚举（org.apache.rocketmq.store.config.BrokerRole）
ASYNC_MASTER   // 异步复制的 Master
SYNC_MASTER    // 同步复制的 Master（同步双写）
SLAVE          // Slave
```

### 2.2 ASYNC_MASTER（异步复制）

```
Producer ──send──▶ Master ──persist CommitLog──▶ 立即返回 SEND_OK
                      │
                      └──(异步后台线程)──▶ Slave pull 数据
```

- **流程**：Master 写完本地 CommitLog 立即返回给 Producer，**不等 Slave**
- **复制方式**：Slave 主动用 `HAClient` 长连接**拉取** Master 的新数据（不是 Master 推）
- **延迟**：正常情况下主从数据差距在**毫秒级**
- **风险**：Master 挂的瞬间如果 Slave 还没拉到最新的几条消息，**这几条可能丢**
- **TPS**：高（默认推荐配置）

### 2.3 SYNC_MASTER（同步双写）

```
Producer ──send──▶ Master ──persist CommitLog──▶ 等 Slave ACK ──▶ 返回 SEND_OK
                      │                              ▲
                      └──push 到 Slave──▶ Slave ack──┘
```

- **流程**：Master 写完本地 CommitLog 后，**等至少一个 Slave 把数据也写入成功**才返回 Producer
- **复制方式**：Master 的 `HAService` 把数据通过长连接推给 Slave（实际实现仍然是 Slave pull，但有类似"推"的效果——通过 `CommitLog#handleHA` 阻塞等待）
- **数据安全**：不丢（Master 磁盘坏了 Slave 上有副本）
- **延迟**：比 ASYNC_MASTER 高 1~5 ms（一次网络 RTT）
- **TPS**：比 ASYNC_MASTER 低 30~50%

### 2.4 复制流程源码视角

```
Master 端：
  CommitLog#asyncPutMessages()
    ├─ 写入本地 MappedFile
    ├─ submitFlushRequest() —— 刷盘（同步/异步）
    └─ submitReplicaRequest() —— 提交给 HAService
            │
            └─ SYNC_MASTER 模式下会 CompletableFuture.get() 阻塞等待
              → HAConnection 线程把数据推给 Slave
              → 收到 Slave 返回的 ackOffset 后 complete Future
              → 主流程继续，返回 Producer

Slave 端：
  HAClient 线程循环：
    ├─ reportSlaveMaxOffset()  —— 上报自己的进度给 Master
    ├─ 从 SocketChannel 读 Master 推来的数据
    └─ 写入本地 CommitLog
```

### 2.5 刷盘策略（flushDiskType）

独立于复制策略，还有**刷盘策略**：

| 刷盘策略 | 含义 |
|:---|:---|
| `ASYNC_FLUSH`（默认） | 消息写入 PageCache 就返回，后台线程定期 fsync 到磁盘 |
| `SYNC_FLUSH` | 消息必须 fsync 到磁盘才返回 |

**组合效果**：

| `brokerRole` + `flushDiskType` | 丢消息风险 | 延迟 | 适用场景 |
|:---|:---|:---:|:---|
| ASYNC_MASTER + ASYNC_FLUSH | 宕机丢秒级数据 | 最低 | 日志、监控 |
| ASYNC_MASTER + SYNC_FLUSH | 宕机不丢（磁盘坏丢） | 中 | 一般业务 |
| SYNC_MASTER + ASYNC_FLUSH | 宕机不丢（Slave 有副本） | 中 | **推荐** |
| SYNC_MASTER + SYNC_FLUSH | 全保障，极致安全 | 最高 | 金融级 |

> 生产一般用 **SYNC_MASTER + ASYNC_FLUSH** 的组合：主备同步保证不丢，异步刷盘保证延迟低。只有磁盘也坏的小概率场景会丢，这时候 Slave 上还有完整数据。

---

## 三、NameServer 的高可用

### 3.1 NameServer 的角色

NameServer 是 RocketMQ 的**路由注册中心**，负责：
- 维护 Broker 列表（哪些 Broker 存活、各自负责哪些 Topic）
- 响应 Producer / Consumer 的路由查询请求
- 不涉及任何消息存储

### 3.2 无状态 + 各节点独立的设计

```
         Broker1                Broker2                Broker3
           │                      │                      │
           └──┬───────┬───────────┬───────┬──────────────┘
              │       │           │       │
              ▼       ▼           ▼       ▼
          NameServer1  NameServer2  NameServer3
          （数据独立，不互相同步）
```

- **每个 Broker 向所有 NameServer 都注册**（每 30s 心跳一次）
- **NameServer 节点之间不通信**，各自独立维护一份完整路由数据
- 所以任意一个 NameServer 活着就能提供路由服务
- **Client 端配置多个 NameServer 地址**，轮询或随机选一个连：

```text
producer.setNamesrvAddr("ns1:9876;ns2:9876;ns3:9876");
```

### 3.3 为什么不用 ZooKeeper？

| 维度 | NameServer | ZooKeeper |
|:---|:---|:---|
| 一致性模型 | AP（最终一致） | CP（强一致） |
| 节点通信 | 节点互不通信 | Zab 协议同步 |
| 写入性能 | 高（本地直写） | 中（多数派确认） |
| 路由延迟 | 30s 心跳窗口 | 会话超时感知 |
| 运维复杂度 | 低（无状态） | 高 |

**RocketMQ 选 AP 的原因**：路由信息允许短暂不一致（客户端拿到过期路由会重试切换），不需要强一致性。详见 `NameServer.md`。

### 3.4 NameServer 挂了会怎样？

- **单个 NameServer 挂**：Client 切到下一个 NameServer，业务无感
- **全部 NameServer 挂**：
  - 存量 Producer / Consumer / Broker 都**缓存**了路由表，短时间内**能继续工作**
  - 但无法感知 Broker 上下线变化，也无法发现新建的 Topic
  - 重启的 Client 拉不到路由会报 `No route info of this topic`

---

## 四、Producer 端高可用

Producer 端是**最关键**的一环，因为它直接决定"Broker 挂了用户还能不能发消息"。

### 4.1 整体流程

```
业务代码 send(msg)
   │
   ▼
DefaultMQProducerImpl#sendDefaultImpl
   │
   ├─ for (int times = 0; times < 1 + retryTimes; times++):
   │      │
   │      ├─ selectOneMessageQueue(tpInfo, lastBrokerName)
   │      │    ├─ 如果开启 sendLatencyFaultEnable → 选不在故障表里的 broker
   │      │    └─ 否则 → 轮询，跳过上次失败的 broker
   │      │
   │      ├─ sendKernelImpl(msg, mq) —— 真正发送
   │      │
   │      └─ 失败处理：
   │           ├─ updateFaultItem() —— 更新故障表
   │           └─ 记录 lastBrokerName，下次重试避开
   │
   └─ 最终成功 → 返回 SendResult
      最终全部失败 → 抛 MQClientException
```

### 4.2 重试机制

```text
// 默认重试配置
producer.setRetryTimesWhenSendFailed(2);       // 同步发送重试 2 次（共尝试 3 次）
producer.setRetryTimesWhenSendAsyncFailed(2);  // 异步发送重试 2 次
producer.setRetryAnotherBrokerWhenNotStoreOK(false); 
// ↑ Broker 返回 FLUSH_DISK_TIMEOUT/FLUSH_SLAVE_TIMEOUT/SLAVE_NOT_AVAILABLE 时，
//   是否换个 broker 重试（默认 false——只在这些场景下认为"已写入但不完全"，不重试避免重复）
```

**关键点**：重试时会**避开上次失败的 broker**：

```java
String lastBrokerName = mq == null ? null : mq.getBrokerName();
MessageQueue mq = this.selectOneMessageQueue(topicPublishInfo, lastBrokerName);
// selectOneMessageQueue 内部会轮询，跳过 lastBrokerName 对应的所有 queue
```

### 4.3 故障规避机制（LatencyFaultTolerance）

**这是生产必开的特性**：

```text
producer.setSendLatencyFaultEnable(true);  // 默认 false，强烈建议 true
```

开启后，`MQFaultStrategy` 会维护每个 broker 的故障状态：

```text
// 发送耗时阈值 → 规避时长（源码默认值）
long[] latencyMax           = {  50L,  100L,   550L,  1000L,  2000L,  3000L, 15000L};
long[] notAvailableDuration = {   0L,    0L, 30000L, 60000L,120000L,180000L,600000L};
```

含义：

| 发送耗时或失败 | 规避时长 |
|:---|:---:|
| < 100ms | 不规避 |
| 100~550ms | 不规避 |
| 550~1000ms | 30 秒 |
| 1~2 秒 | 60 秒 |
| 2~3 秒 | 2 分钟 |
| 3~15 秒 | 3 分钟 |
| ≥ 15 秒 或 发送失败 | **10 分钟** |

**效果**：
- 第一次发到挂掉的 broker 失败 → 该 broker 被标记为"不可用 10 分钟"
- 后续 10 分钟内 Producer **完全不选**这个 broker 的 queue
- 等 NameServer 心跳剔除（最长 120 秒）+ 重新下发路由后，该 broker 从路由表消失，规避表里的条目也就失效

### 4.4 同步 / 异步 / Oneway 的容错差异

| 发送方式 | 异常处理 | 重试 | 吞吐 | 适用场景 |
|:---|:---|:---:|:---:|:---|
| **同步 `send(msg)`** | 抛异常 | 默认 2 次 | 中 | 核心业务，确保送达 |
| **异步 `send(msg, callback)`** | 回调 `onException` | 默认 2 次 | 高 | 高吞吐场景 |
| **Oneway `sendOneway(msg)`** | **无异常抛出** | **不重试** | 最高 | 日志类（可丢） |

> Oneway 模式完全没有可靠性保证，只在"消息丢了也无所谓"的场景用。

### 4.5 Producer 发送超时配置

```text
producer.setSendMsgTimeout(3000);  // 默认 3 秒
```

- Broker 侧处理慢时，这个超时决定多久放弃
- 太短：误判 Broker 故障，频繁重试造成重复
- 太长：Broker 真挂时感知慢，业务响应超时
- **推荐**：核心业务 3 秒，对延迟敏感的 1 秒

---

## 五、Consumer 端高可用

### 5.1 允许从 Slave 消费（ALLOW_CONSUME_FROM_SLAVE）

RocketMQ 的 Consumer **默认从 Master 拉消息**，但有**从 Slave 拉的 fallback 机制**。

#### 触发时机：

1. **Master 挂了**：Consumer 连不上 Master，尝试去 Slave 拉
2. **Master 消费慢**（Master 上堆积的消息超过内存能缓存的量）：Broker 主动建议 Consumer 下次去 Slave 拉

#### 关键字段：

```properties
# Broker 端 broker.conf
slaveReadEnable=true                 # 默认 true，允许 Consumer 从 Slave 读
whichBrokerWhenConsumeSlowly=1       # 消费慢时建议的 brokerId，默认 1 = Slave
accessMessageInMemoryMaxRatio=40     # Master 内存缓存的 message 占 PageCache 的最大比例（默认 40%）
```

#### 源码流程：

```
Consumer pull msg
   │
   ▼
Broker 处理 pull 请求（PullMessageProcessor）
   │
   ├─ 读 ConsumeQueue + CommitLog 拿消息
   │
   ├─ 计算 diff = CommitLog.maxOffset - message.offset（未消费数据量）
   │
   ├─ if (diff > memory * accessMessageInMemoryMaxRatio):
   │    // 这条消息不在内存 PageCache 里，需要回盘读，性能差
   │    suggestWhichBrokerId = whichBrokerWhenConsumeSlowly  // = 1 (Slave)
   │    告诉 Consumer：下次去 Slave 拉
   │
   └─ 返回响应（含 suggestWhichBrokerId）

Consumer 收到响应后：
   PullAPIWrapper#processPullResult 更新本地缓存的 brokerId
   下次 pull 时走新的 brokerId
```

### 5.2 消费进度存储的高可用

集群消费模式下，**消费进度存在 Broker 端**（不是 Consumer 本地）：

- Master 每 5 秒持久化一次 `consumerOffset.json`
- Master 到 Slave 会**每 10 秒同步**一次消费进度
- Master 挂了之后 Consumer 切到 Slave，读到的 offset 是 Slave 上的备份（最多晚 10 秒）
- **风险**：Master 挂前 10 秒内提交的进度可能丢，导致**重复消费少量消息**——所以业务必须自己保证幂等

### 5.3 Rebalance 对高可用的影响

- Broker 挂了之后，Consumer Group 里的成员列表可能变化（该 broker 上的 consumer 连接断开）
- 所有 Consumer 会触发 Rebalance，重新分配 queue
- Rebalance 期间（典型 20 秒内）**消费可能短暂暂停**
- **业务影响**：消费延迟毛刺，但不丢消息

---

## 六、Master 故障场景全链路分析

以**两主两从架构**为例，分析 Broker-A-Master 挂掉后各环节的行为。

### 6.1 架构前提

```
NameServer 集群（3 节点）

Broker-A 组：                    Broker-B 组：
  Broker-A-Master (brokerId=0)    Broker-B-Master (brokerId=0)
  Broker-A-Slave  (brokerId=1)    Broker-B-Slave  (brokerId=1)

Topic: OrderTopic
  writeQueueNums=4, readQueueNums=4
  → Broker-A-Master 上 4 个 queue（queueId 0~3）
  → Broker-B-Master 上 4 个 queue（queueId 0~3）
```

### 6.2 时间线：Broker-A-Master 宕机后

#### T+0 秒：Broker-A-Master 崩溃

- TCP 连接断开
- NameServer 还认为它活着（心跳未超时）

#### T+0 ~ T+120 秒：NameServer 感知期

- Producer 本地路由缓存仍包含 Broker-A-Master
- 发送时按 queue 轮询，**有 50% 概率选中 Broker-A 的 queue**
- 选中后：`RemotingConnectException` 或 `RemotingTimeoutException`
- Producer 重试机制接管：
  - 标记 Broker-A 为故障（`sendLatencyFaultEnable=true` 时规避 10 分钟）
  - 选 Broker-B 的 queue → 成功
- **业务感知**：部分消息延迟增加几百 ms，但最终发送成功

#### T+30 秒：Broker 心跳周期

- Broker-A-Master 应发心跳但没发
- NameServer 仍保留它（心跳超时阈值是 120 秒）

#### T+120 秒：NameServer 剔除

- NameServer 扫描 brokerLiveTable，发现 Broker-A-Master 超过 120 秒无心跳
- 从路由表中移除
- 此刻的路由表：
  ```
  OrderTopic:
    Broker-B-Master (writable, id=0)
    Broker-A-Slave  (readable only, id=1)
    Broker-B-Slave  (readable only, id=1)
  ```

#### T+120 ~ T+150 秒：Producer 感知

- Producer 每 30 秒从 NameServer 拉最新路由
- 拉到新路由后，Broker-A-Master 的 queue **从 publish 列表里消失**
- 之后发送 100% 成功（全部走 Broker-B-Master）

#### Consumer 侧

- T+0 ~ T+120 秒：Consumer 连接 Broker-A-Master 失败，Broker 返回的 `suggestWhichBrokerId=1` 让 Consumer 去 Broker-A-Slave 拉
- **Broker-A-Slave 上的历史消息可以继续消费**（读 ok）
- T+120 秒后：Consumer Rebalance，重新分配队列
- **业务感知**：Broker-A 上的消息消费延迟可能增加，但不中断

### 6.3 挂掉期间的写入能力

| 阶段 | 集群整体写入能力 |
|:---|:---|
| T+0 ~ T+路由更新 | ~50%（一半请求打到挂掉的 broker 后重试） |
| 路由更新后 | ~50%（只剩 Broker-B 一组 master 写，整体容量减半） |

**关键**：两主两从架构下单 Master 宕机，**整体写入容量会降到 50%**。对吞吐敏感的业务必须提前做容量规划，保证**单组 Master 能扛住整体流量**（即资源 1.5~2 倍冗余）。

### 6.4 消息丢失风险

| 复制配置 | 宕机时已同步到 Slave 的消息 | 未同步的消息 |
|:---|:---|:---|
| `ASYNC_MASTER` + Master 磁盘完好 | 不丢（恢复后在 Master 上） | 不丢（恢复后在 Master 上） |
| `ASYNC_MASTER` + Master 磁盘损坏 | 不丢（Slave 有） | **丢失** |
| `SYNC_MASTER` | 不丢 | 不存在这种情况（Master 没等到 Slave ACK 不会返回成功） |

### 6.5 恢复流程

Broker-A-Master 修好重启后：

1. 启动时加载本地 CommitLog / ConsumeQueue
2. 向所有 NameServer 注册（每 30 秒心跳）
3. Producer 下一次拉路由时（最长等 30 秒）感知到它重新上线
4. 流量重新均衡到两个 Master

---

## 七、消息不丢失保障

消息不丢要从**三个阶段**看：发送阶段、存储阶段、消费阶段。

### 7.1 发送阶段不丢

| 保障 | 机制 |
|:---|:---|
| 发送失败有感知 | 同步 send / 异步 callback，**Oneway 不保证** |
| 失败自动重试 | `retryTimesWhenSendFailed=2`，重试时避开失败 broker |
| 故障 broker 规避 | `sendLatencyFaultEnable=true` |
| Broker 半可用状态 | `retryAnotherBrokerWhenNotStoreOK=true`（慎用） |

### 7.2 存储阶段不丢

| 配置 | 效果 |
|:---|:---|
| `brokerRole=SYNC_MASTER` | Master 等 Slave ACK，宕机不丢 |
| `flushDiskType=SYNC_FLUSH` | 每条消息 fsync 才返回，掉电不丢 |
| 两者组合 | 金融级不丢保障 |

### 7.3 消费阶段不丢

关键：**只有业务处理成功后才提交 offset**，并保证**消费幂等**。

#### 正确的消费代码模板：

```java
public class OrderConsumerDemo {

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("order_consumer_group");
        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(
                    List<MessageExt> msgs,
                    ConsumeConcurrentlyContext context) {

                for (MessageExt msg : msgs) {
                    try {
                        // 1. 业务处理（必须幂等）
                        handleBusiness(msg);
                    } catch (Exception e) {
                        // 2. 处理失败，让 RocketMQ 稍后重试
                        log.error("consume failed", e);
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                // 3. 全部处理成功才返回 SUCCESS
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
    }

    private static void handleBusiness(MessageExt msg) {
        // 业务逻辑 + 幂等校验
    }
}
```

#### 幂等实现的常见方式：

- **业务主键去重**：DB 插入用唯一索引
- **消息去重表**：用 Message Key 或 msgId 记录已处理消息
- **状态机保护**：用业务状态判断是否已处理过

### 7.4 端到端不丢的完整链路

```
Producer                Broker                 Consumer
   │                      │                        │
   │ 1. send(sync) ─────▶│                        │
   │                      │ 2. CommitLog 写入       │
   │                      │ 3. 同步到 Slave          │
   │                      │ 4. 落盘 fsync           │
   │ ◀──── SEND_OK ───────│                        │
   │                      │                        │
   │                      │ 5. ─────── pull ───────▶│
   │                      │                        │ 6. 业务处理（幂等）
   │                      │                        │
   │                      │ ◀── ACK (offset 提交)──│ 7. SUCCESS
   │                      │                        │
   失败重试 ✓              主从+刷盘 ✓              重试+幂等 ✓
```

---

## 八、DLedger 模式

### 8.1 为什么需要 DLedger

传统主从架构的致命缺陷：**Master 挂了不会自动切换**，需要人工介入（修改 Slave 配置把 brokerId 改为 0 后重启）。

DLedger 引入 **Raft 协议**，让一个 broker 组自动完成主备切换。

### 8.2 架构

```
一个 broker 组（3 或 5 个节点）

    ┌─────────┐
    │ Leader  │ ← 客户端读写都通过 Leader
    └────┬────┘
         │
    ┌────┴────┬─────────┐
    │         │         │
    ▼         ▼         ▼
┌────────┐ ┌────────┐
│Follower│ │Follower│
└────────┘ └────────┘
```

### 8.3 写入流程（Raft 共识）

```
1. Client 把消息发给 Leader
2. Leader 把消息 append 到本地 log（但不 commit）
3. Leader 并行把消息发给所有 Follower（AppendEntries RPC）
4. Follower 写入本地 log，返回 ACK
5. Leader 收到 超过半数 ACK 后，commit 这条消息
6. Leader 返回 Client SEND_OK
7. Leader 在下一次 AppendEntries 里告诉 Follower 提交到哪个位置
```

**核心保证**：任何已返回成功的消息，**至少在半数以上节点上持久化**。

### 8.4 选举流程

```
Follower 超过 election timeout 没收到 Leader 心跳
   │
   ▼
变为 Candidate，任期号 +1，给自己投票
   │
   ▼
并行给其他节点发 RequestVote RPC
   │
   ▼
收到多数派投票 → 成为 Leader
未收到多数派 → 超时后新一轮选举
```

### 8.5 关键配置

```properties
# broker.conf
enableDLegerCommitLog=true
dLegerGroup=broker-A-group          # broker 组名
dLegerPeers=n0-ip1:40911;n1-ip2:40911;n2-ip3:40911
dLegerSelfId=n0                     # 本节点 ID
storePathRootDir=/data/rocketmq/data
```

### 8.6 DLedger vs 传统主从

| 维度 | 传统主从 | DLedger |
|:---|:---|:---|
| 节点数 | 1 主 1 从（2 个） | **3 个起** |
| 自动切换 | ❌ | ✓（秒级） |
| 写入延迟 | 低 | 中（要等多数派） |
| 数据一致性 | 可能丢（异步） | 强一致（Raft） |
| 资源成本 | 低 | 高（多 50% 节点） |
| 运维复杂度 | 低 | 中 |

### 8.7 DLedger 的代价

- **必须 3 个节点起**：1 主 2 从是最小配置，资源成本比普通主从高 50%
- **写入延迟略增**：每次写都要等超过半数节点 ACK
- **脑裂保护**：3 个节点最多容忍 1 个挂，5 个节点容忍 2 个挂（不如普通主从"容忍 Slave 全挂"）

---

## 九、RocketMQ 5.x Controller 模式

### 9.1 设计目标

- 保留传统主从架构的**低资源成本**（1 主 1 从即可）
- 同时获得**自动主备切换**能力
- 比 DLedger 更轻量

### 9.2 架构

```
┌──────────────┐        ┌──────────────────┐
│ NameServer 集群 │       │ Controller 集群   │ ← 新增组件
│ (路由中心)     │        │ (主备切换仲裁)    │
└──────────────┘        └────────┬─────────┘
                                 │ 监控 broker 心跳、
                                 │ 仲裁 master 选举
                                 ▼
            ┌───────────┬─────────────────┐
            │           │                 │
         Broker-A     Broker-A          Broker-B
         Master       Slave             Master ...
```

### 9.3 工作原理

1. Broker 向 Controller **注册并定期汇报心跳**
2. Controller 内部用 Raft 维护 broker 组的状态（谁是 Master / 谁是 Slave）
3. Master 心跳超时 → Controller 从存活 Slave 里选一个升为 Master
4. Controller 更新状态 → 通过 NameServer 下发新路由
5. Producer / Consumer 拉到新路由后切到新 Master

### 9.4 核心配置

```properties
# Controller 自身
controllerDLegerGroup=controller-group
controllerDLegerPeers=n0-ip1:9878;n1-ip2:9878;n2-ip3:9878
controllerDLegerSelfId=n0

# Broker 侧启用 Controller 模式
enableControllerMode=true
controllerAddr=ctrl1:9878;ctrl2:9878;ctrl3:9878
```

### 9.5 Controller 模式 vs DLedger

| 维度 | DLedger | Controller (5.x) |
|:---|:---|:---|
| 最小节点数 | 3 个 broker | 1 主 1 从即可（3 个 Controller 独立部署） |
| 一致性协议 | broker 组内 Raft | Controller 用 Raft，broker 组用传统主从 |
| 写入延迟 | 要等多数 broker ACK | 传统主从复制延迟 |
| 运维侵入 | 改 broker.conf | 新增 Controller 集群 |
| 切换速度 | 秒级（10s 内） | 秒级（10s 内） |

Controller 模式本质是**把自动切换的"大脑"从 broker 组内部抽出来，放到独立的 Controller 集群里**，让 broker 组继续用简单的主从架构。

---

## 十、实战配置建议

### 10.1 配置清单（SYNC_MASTER + 异步刷盘，大部分业务推荐）

```properties
# Broker 角色
brokerClusterName=DefaultCluster
brokerName=broker-a
brokerId=0
brokerRole=SYNC_MASTER
flushDiskType=ASYNC_FLUSH

# NameServer
namesrvAddr=ns1:9876;ns2:9876;ns3:9876

# 存储
storePathRootDir=/data/rocketmq/data
storePathCommitLog=/data/rocketmq/data/commitlog
fileReservedTime=72

# 从 Slave 读支持
slaveReadEnable=true
whichBrokerWhenConsumeSlowly=1

# 磁盘水位
diskMaxUsedSpaceRatio=75
diskSpaceCleanForciblyRatio=85
diskSpaceWarningLevelRatio=90
```

### 10.2 Producer 侧

```java
public class OrderProducerDemo {

    public static void main(String[] args) throws Exception {
        DefaultMQProducer producer = new DefaultMQProducer("order_producer_group");
        producer.setNamesrvAddr("ns1:9876;ns2:9876;ns3:9876");

        // 重试
        producer.setRetryTimesWhenSendFailed(2);
        producer.setRetryTimesWhenSendAsyncFailed(2);

        // 故障规避 —— 生产必开
        producer.setSendLatencyFaultEnable(true);

        // 超时
        producer.setSendMsgTimeout(3000);

        producer.start();
    }
}
```

### 10.3 Consumer 侧

```java
public class OrderConsumerStartup {

    public static void main(String[] args) throws Exception {
        DefaultMQPushConsumer consumer = new DefaultMQPushConsumer("order_consumer_group");
        consumer.setNamesrvAddr("ns1:9876;ns2:9876;ns3:9876");
        consumer.subscribe("OrderTopic", "*");

        consumer.registerMessageListener(new MessageListenerConcurrently() {
            @Override
            public ConsumeConcurrentlyStatus consumeMessage(
                    List<MessageExt> msgs, ConsumeConcurrentlyContext context) {
                for (MessageExt msg : msgs) {
                    try {
                        // 幂等消费（Message Key 去重 / DB 唯一索引 / 状态机）
                        handleMessageIdempotent(msg);
                    } catch (Exception e) {
                        return ConsumeConcurrentlyStatus.RECONSUME_LATER;
                    }
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
        });
        consumer.start();
    }

    private static void handleMessageIdempotent(MessageExt msg) {
        // 基于 Message Key 或业务主键做去重
    }
}
```

### 10.4 不同业务场景的配置决策

| 业务场景 | Broker 架构 | brokerRole | flushDiskType | sendLatencyFaultEnable |
|:---|:---|:---|:---|:---:|
| 日志采集、监控 | 多主无从 | ASYNC_MASTER | ASYNC_FLUSH | 可开可不开 |
| 一般业务消息 | 多主多从 | ASYNC_MASTER | ASYNC_FLUSH | **开** |
| 订单、支付业务 | 多主多从 | **SYNC_MASTER** | ASYNC_FLUSH | **开** |
| 金融级核心业务 | DLedger / Controller | —— | SYNC_FLUSH | **开** |

### 10.5 容量规划原则

- **N 主 N 从架构**下，单 Master 挂掉整体容量降为 `(N-1)/N`
- **预留冗余**：按单组 Master 能扛 **整体 1.5~2 倍流量**规划
- **避免所有 Master 同机房**：跨机房部署，机房级故障不全军覆没

### 10.6 监控指标

生产环境必须监控的指标：

| 指标 | 告警阈值 | 说明 |
|:---|:---|:---|
| Broker TPS | 下降超 50% | 可能有 broker 挂 |
| 消息堆积量 | 业务相关 | Consumer 消费慢 |
| 发送失败率 | > 0.1% | Broker/网络异常 |
| 磁盘使用率 | > 75% | 即将触发清理 |
| 主从同步延迟 | > 10MB | 同步堵塞或网络慢 |
| Slave 是否落后 | HA slaveAckOffset | SYNC_MASTER 下关键 |

---

## 十一、一句话总结

> RocketMQ 的高可用是**分层设计**的组合拳：
>
> - **架构层**：NameServer 集群（无状态易扩展）+ 多主多从（容忍单点故障）+ DLedger / Controller（自动切换）
> - **复制层**：SYNC_MASTER 保证数据安全，ASYNC_MASTER 保证低延迟，业务按需选择
> - **客户端层**：Producer 重试 + 故障规避，Consumer slave fallback + Rebalance，保证单 broker 故障对业务近乎无感
> - **运维层**：容量冗余 + 跨机房部署 + 完善监控
>
> 记住一个核心判断：**传统主从架构（4.x 最常见）在 Master 挂时不会自动切换写入**，写入容量会降低到 `(N-1)/N`；真正想要秒级自动切换要用 **DLedger（4.5+）** 或 **Controller 模式（5.x 推荐）**。
