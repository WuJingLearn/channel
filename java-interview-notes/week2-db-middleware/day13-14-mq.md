# Day 13-14：消息队列

> 复习目标：掌握 MQ 核心价值、RocketMQ/Kafka 架构、消息可靠性/顺序性/事务消息原理。

---

## 一、消息队列基础

**Q1：消息队列的核心价值是什么？有什么缺点？**

A：
**三大核心价值**：
1. **解耦**：上游不关心有多少下游消费者，只管发消息。新增消费者不用改上游代码
2. **异步**：耗时操作异步处理（如发短信、推送、日志），降低主链路响应时间
3. **削峰**：突发流量先堆积在 MQ 中，消费者按自身能力匀速消费

**缺点**：
- 系统复杂度增加（多了 MQ 组件的运维、监控）
- 消息丢失/重复/乱序的可靠性问题
- 数据一致性问题（异步导致最终一致性而非强一致性）

---

**Q2：RocketMQ 和 Kafka 的定位和核心区别？**

A：
| 维度 | RocketMQ | Kafka |
|------|---------|-------|
| 定位 | 业务消息（电商、金融） | 大数据/日志流处理 |
| 开发语言 | Java | Scala + Java |
| 消息模型 | 发布订阅 + 集群/广播消费 | 发布订阅 + Consumer Group |
| 顺序消息 | 原生支持（MessageQueueSelector） | 分区级别有序 |
| 事务消息 | 原生支持（Half Message） | 支持（Kafka Transactions，0.11+） |
| 延迟消息 | 原生支持（18 个级别） | 不原生支持 |
| 消息回溯 | 支持按时间回溯 | 支持（按 offset 或时间） |
| 吞吐量 | 十万级 TPS | 百万级 TPS |
| 适用场景 | 可靠业务消息、事务消息 | 日志采集、流处理、大数据管道 |

---

## 二、RocketMQ

**Q3：RocketMQ 的架构组件和职责？**

A：
```
Producer → NameServer（路由注册中心）→ Broker（存储和转发）→ Consumer
```

| 组件 | 职责 |
|------|------|
| **NameServer** | 轻量级注册中心（无状态，互相不通信）。Broker 注册路由信息，Producer/Consumer 拉取路由 |
| **Broker** | 消息存储与转发。分 Master/Slave，Master 负责写，Slave 同步备份 |
| **Producer** | 消息生产者。从 NameServer 拉取路由，向 Broker 发送消息 |
| **Consumer** | 消息消费者。Pull 模式（长轮询），支持集群消费和广播消费 |

**存储模型**：
- **CommitLog**：所有 Topic 消息追加写入同一个日志文件（顺序写，性能高）
- **ConsumeQueue**：每个 Topic 的每个 Queue 一个索引文件，存储 CommitLog offset + 消息大小 + Tag hashcode
- 消费流程：ConsumeQueue（索引）→ CommitLog（数据）

---

**Q4：RocketMQ 的事务消息原理？**

A：基于**半消息（Half Message）+ 事务回查**的两阶段提交：

```
1. Producer 发送半消息（Half Message）→ Broker 收到但对 Consumer 不可见
2. Broker 返回半消息发送成功
3. Producer 执行本地事务
4. 根据本地事务结果：
   - 成功 → 发送 Commit → Broker 让消息对 Consumer 可见
   - 失败 → 发送 Rollback → Broker 删除半消息
5. 如果 Producer 没发 Commit/Rollback（宕机等）：
   → Broker 定期回查 Producer 的本地事务状态（最多回查 15 次）
   → Producer 返回事务状态 → Broker 做相应处理
```

---

**Q5：RocketMQ 如何保证消息不丢失？**

A：三个环节都要保证：

| 环节 | 方案 |
|------|------|
| **生产者** | 同步发送 + 重试机制（默认重试 2 次，发不同 Broker）。`send()` 返回 SEND_OK 才算成功 |
| **Broker** | 同步刷盘（`flushDiskType=SYNC_FLUSH`）+ 同步复制（`brokerRole=SYNC_MASTER`）。性能略降但不丢消息 |
| **消费者** | 手动 ACK（消费完业务逻辑后再返回 `CONSUME_SUCCESS`），不要自动 ACK |

---

**Q6：RocketMQ 如何保证消息顺序性？**

A：
- **全局有序**：整个 Topic 只设 1 个 Queue → 完全串行 → 吞吐量极低（不推荐）
- **分区有序**（推荐）：相同业务 key 的消息发到同一个 Queue

```java
// 生产者：相同 orderId 的消息发到同一个 Queue
producer.send(msg, new MessageQueueSelector() {
    public MessageQueue select(List<MessageQueue> mqs, Message msg, Object arg) {
        Long orderId = (Long) arg;
        int index = (int) (orderId % mqs.size());
        return mqs.get(index);
    }
}, orderId);

// 消费者：使用 MessageListenerOrderly（单线程顺序消费同一 Queue）
consumer.registerMessageListener(new MessageListenerOrderly() { ... });
```

---

## 三、Kafka

**Q7：Kafka 的架构和核心概念？**

A：
| 概念 | 说明 |
|------|------|
| **Broker** | Kafka 节点，多个 Broker 组成集群 |
| **Topic** | 消息主题，逻辑分类 |
| **Partition** | Topic 的分区，每个 Partition 是一个有序的日志文件。分区是并行度的基本单位 |
| **Replica** | 分区副本，分 Leader 和 Follower。读写只走 Leader，Follower 同步备份 |
| **Consumer Group** | 消费者组，组内每个消费者消费不同的 Partition（一个 Partition 只被组内一个消费者消费） |
| **Offset** | 消费位移，记录消费者消费到 Partition 的哪个位置 |
| **ZooKeeper/KRaft** | 元数据管理（Kafka 3.x 开始用 KRaft 替代 ZooKeeper） |

---

**Q8：Kafka 为什么性能这么高？**

A：四个核心原因：

1. **顺序写磁盘**：消息追加写入 Partition 日志文件末尾，顺序写磁盘的速度接近内存写入（600MB/s vs 随机写 100KB/s）

2. **零拷贝（Zero-Copy）**：使用 `sendfile()` 系统调用，数据直接从文件系统缓存（Page Cache）→ 网卡，跳过用户空间拷贝
   - 传统方式：磁盘 → 内核缓冲 → 用户缓冲 → Socket 缓冲 → 网卡（4 次拷贝）
   - 零拷贝：磁盘 → 内核缓冲 → 网卡（2 次拷贝）

3. **Page Cache**：充分利用 OS 的页面缓存，将磁盘 IO 转为内存 IO

4. **批量压缩**：Producer 端将消息批量压缩后发送（Snappy/LZ4/Zstandard），减少网络和磁盘 IO

---

**Q9：Kafka 如何保证消息不丢失？**

A：
| 环节 | 方案 |
|------|------|
| **生产者** | `acks=all`：等待所有 ISR 副本确认。`retries > 0`：发送失败自动重试。`enable.idempotence=true`：幂等 Producer 防重复 |
| **Broker** | `min.insync.replicas=2`：至少 2 个副本确认才算成功。`replication.factor=3`：3 副本 |
| **消费者** | 手动提交 offset（`enable.auto.commit=false`），消费完业务再 `commitSync()` |

**关键参数组合（不丢消息）**：
```properties
# Producer
acks=all
retries=3
enable.idempotence=true

# Broker
min.insync.replicas=2
replication.factor=3
unclean.leader.election.enable=false  # 禁止非 ISR 副本选举为 Leader
```

---

**Q10：Kafka 的 ISR 机制是什么？**

A：
- **ISR（In-Sync Replicas）**：与 Leader 保持同步的副本集合
- Follower 如果落后太多（超过 `replica.lag.time.max.ms`，默认 30 秒没赶上 Leader）→ 被踢出 ISR
- Leader 宕机时，只从 ISR 中选举新 Leader（保证数据不丢）
- `unclean.leader.election.enable=false`：禁止非 ISR 副本成为 Leader（牺牲可用性换数据安全）

---

**Q11：Kafka 的消费者组和 Rebalance 机制？**

A：
**消费者组规则**：
- 同一 Consumer Group 内，一个 Partition 只能被一个消费者消费
- 不同 Consumer Group 可以重复消费同一消息（发布/订阅模式）
- 消费者数 > Partition 数 → 多余的消费者空闲

**Rebalance（重平衡）**触发条件：
1. 消费者加入/离开组
2. Topic 的 Partition 数量变化
3. 消费者心跳超时（`session.timeout.ms`）

**Rebalance 问题**：重平衡期间**所有消费者停止消费**，影响可用性。

**优化**：
- 增大 `session.timeout.ms` 和 `heartbeat.interval.ms`（减少误判）
- 使用 `CooperativeStickyAssignor`（增量 Rebalance，只迁移必要的 Partition）

---

## 四、通用问题

**Q12：消息积压怎么处理？**

A：
**紧急处理**：
1. 快速扩容消费者数量（同时增加 Partition/Queue 数量使新消费者能分到）
2. 如果消费逻辑有 bug 导致消费失败 → 修复 bug → 重新部署
3. 临时方案：写一个转发程序，将积压消息转发到更多 Queue/Partition → 更多消费者并行消费

**根本解决**：
- 优化消费者处理逻辑（批量处理、异步化）
- 增加消费并发度
- 监控消费延迟，设置告警

---

**Q13：如何保证消息消费的幂等性？**

A：由于网络重试、Rebalance 等原因，消息可能被重复投递，消费端必须做幂等处理：

| 方案 | 说明 |
|------|------|
| **唯一 ID + 去重表** | 每条消息带唯一 ID，消费前查数据库去重表，已存在则跳过 |
| **Redis SETNX** | 用消息 ID 做 key，`SETNX` 成功才处理 |
| **数据库唯一约束** | INSERT 时利用唯一索引去重（`INSERT IGNORE` 或 `ON DUPLICATE KEY`） |
| **乐观锁/版本号** | UPDATE 时带版本号条件（`WHERE version = ?`） |
| **业务逻辑幂等** | 如"设置状态为已完成"本身就是幂等的 |

---

**Q14：Kafka 的事务消息原理？**

A：Kafka 0.11+ 支持 Exactly-Once 语义（EOS），基于**幂等 Producer + 事务**：

**幂等 Producer**（`enable.idempotence=true`）：
- Broker 为每个 Producer 分配 PID，每条消息带 Sequence Number
- Broker 检查 Sequence Number 是否连续，重复则丢弃

**事务**（跨 Partition 原子写入）：
```java
producer.initTransactions();
try {
    producer.beginTransaction();
    producer.send(record1);  // Topic A
    producer.send(record2);  // Topic B
    producer.sendOffsetsToTransaction(offsets, consumerGroup);  // 提交消费 offset
    producer.commitTransaction();
} catch (Exception e) {
    producer.abortTransaction();
}
```
- 通过 Transaction Coordinator 和 `__transaction_state` 内部 Topic 管理事务状态
- 消费端设置 `isolation.level=read_committed` 只读已提交事务的消息

---

**Q15：如何选择消息队列？**

A：
| 场景 | 推荐 | 原因 |
|------|------|------|
| 日志采集 / 大数据管道 | **Kafka** | 超高吞吐量，生态完善（Flink/Spark 集成） |
| 业务消息（电商/金融） | **RocketMQ** | 事务消息、延迟消息、消息回溯原生支持 |
| 物联网 / 即时通讯 | **EMQX / MQTT** | 轻量协议，适合海量设备 |
| 简单异步解耦 | **RabbitMQ** | 轻量、易部署、功能全面（路由、死信、延迟插件） |
| 云原生 / Serverless | **Pulsar** | 存算分离，弹性扩缩容 |
