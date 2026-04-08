# Day 15-16：分布式理论与一致性

> 复习目标：掌握 CAP/BASE 理论、一致性算法、分布式 ID/锁/事务的常见方案。

---

## 一、分布式理论

**Q1：CAP 定理是什么？为什么三者不可兼得？**

A：CAP 定理由 Eric Brewer 于 2000 年提出，指分布式系统中以下三个特性**最多同时满足两个**：

| 字母 | 特性 | 含义 |
|------|------|------|
| **C** | Consistency（一致性） | 所有节点在同一时刻看到的数据完全相同 |
| **A** | Availability（可用性） | 每个请求都能收到响应（不保证是最新数据） |
| **P** | Partition Tolerance（分区容错性） | 网络发生分区（节点间通信中断）时，系统仍能继续运行 |

**为什么不可兼得**：网络分区（P）在分布式系统中客观存在、无法避免，所以实际上系统只能在 C 和 A 之间做取舍：：
因为网络分区在分布式系统中是客观存在、无法避免的（网络抖动、机房故障等），所以放弃 P 在实际中不现实。这就是为什么现实系统只在 CP 和 AP 之间选择。
- **CP**：分区时拒绝不一致的请求 → 牺牲可用性（如 ZooKeeper、etcd、HBase）
- **AP**：分区时继续响应 → 可能返回旧数据（如 Eureka、Cassandra、CouchDB）

> **注意**：CAP 是系统架构设计时的取舍，但三者不可兼得的**核心矛盾在网络分区发生时才被迫显现**。正常运行时，强一致性系统（如 ZooKeeper）也可能因 Leader 选举、写入同步等原因短暂影响可用性。

---

**Q2：BASE 理论是什么？与 ACID 的关系？**

A：BASE 是 eBay 架构师 Dan Pritchett 于 2008 年提出的，是对 **CAP 中 AP 方案的工程实践补充**，是大规模分布式系统的设计指导原则。

**BA — Basically Available（基本可用）**：系统出现故障时，允许损失部分功能，但必须保证核心功能可用：
- **响应时间延长**：正常 100ms，故障时允许 500ms（但不能完全不响应）
- **功能降级**：电商大促时关闭推荐功能，保证下单核心链路可用
- **限流熔断**：拒绝部分请求，保护系统不被压垮

**S — Soft State（软状态）**：允许系统中存在中间状态，这个中间状态不影响系统整体可用性
- 数据在不同节点之间允许短暂不一致（同步延迟）：
- 如订单支付后，库存扣减和积分增加可以不在同一时刻完成

**E — Eventually Consistent（最终一致性）**：不保证实时一致，但在没有新的更新后，经过一段时间所有节点数据最终会达到一致。常见变体：
- **读己之写一致性**：自己写的数据，自己能立刻读到
- **单调读一致性**：不会读到比之前更旧的数据
- **因果一致性**：有因果关系的操作保证顺序

**与 CAP 的关系**：BASE 是选择 AP 后的工程实践答案：
```
CAP → 选择 AP → 如何处理不一致？→ BASE 理论给出答案
```

**BASE vs ACID 对比**：

| 维度 | ACID | BASE |
|------|------|------|
| **一致性** | 强一致性 | 最终一致性 |
| **可用性** | 完全可用 | 基本可用 |
| **中间状态** | 不允许 | 允许软状态 |
| **适用场景** | 传统关系型数据库、金融交易 | 大规模分布式系统、互联网业务 |
| **代表系统** | MySQL、Oracle | Cassandra、DynamoDB |

实际系统往往是两者的折中：核心交易用 ACID，非核心业务用 BASE。

---

## 二、一致性算法

**Q2.5：为什么需要一致性算法？**

A：单机系统数据只有一份，读写天然一致。但分布式系统为了**高可用**和**高性能**，必须把数据复制到多个节点，一旦有多个副本，就面临三个核心难题：

**难题一：网络不可靠**
- 网络会丢包、延迟、分区，节点之间无法可靠通信
- 你**永远无法确定**对方是"挂了"还是"网络慢了"

**难题二：节点宕机导致数据丢失**
```
场景：3 个节点，Leader 写入数据后宕机
- 节点 A（Leader）：已写入 x=1，已提交
- 节点 B：收到了 x=1
- 节点 C：没收到（网络延迟）

Leader 挂了，谁来接管？
- 如果选 C 当新 Leader → x=1 的数据丢失！
- 如果选 B 当新 Leader → 数据完整
```
没有一致性算法，就无法安全地决定"谁有资格当 Leader"。

**难题三：脑裂（Split-Brain）**
```
[节点A, 节点B] ←网络断→ [节点C, 节点D]
      ↓                        ↓
  选出 Leader1            选出 Leader2
  写入 x=1                写入 x=2
```
两个 Leader 同时接受写入，数据产生不可调和的冲突。

**一致性算法的本质：用"多数派"打破不确定性**

Raft/Paxos 的核心思想：**只要超过半数节点确认，就认为操作成功**。

精妙之处在于：任意两个多数派必然有交集（至少 1 个节点重叠），这个重叠节点作为"见证者"保证信息不丢失。
```
5 个节点的集群：
多数派 A = {1, 2, 3}
多数派 B = {3, 4, 5}
交集 = {3} ← 节点 3 见证了两次决策，保证连续性
```
发生网络分区时，少数派那边永远无法凑够多数票，无法选出新 Leader，从根本上避免脑裂。

> **一句话总结**：一致性算法解决的核心问题是——在节点可能宕机、网络可能丢包的不可靠环境中，让多个节点就"某个值/某个决策"达成**不可撤销的共识**。

---

**Q3：Raft 算法的核心原理？**

A：Raft 将一致性问题分解为三个子问题：

**1. Leader 选举**：
- 每个节点有三种状态：Leader、Follower、Candidate
- Follower 超时没收到心跳 → 变成 Candidate → 发起投票
- 获得**多数票**（> N/2）→ 成为 Leader
- 每个任期（Term）最多一个 Leader

**2. 日志复制**：
- 客户端请求发给 Leader → Leader 写入本地日志 → 并行发给所有 Follower
- **超过半数** Follower 确认 → Leader 提交该日志并返回客户端
- Follower 收到 Leader 提交通知后也提交

**3. 安全性**：
- 新 Leader 必须包含所有已提交的日志（投票时 Candidate 的日志必须至少和 Follower 一样新）
- 保证已提交的日志不会丢失

---

**Q4：Paxos 和 Raft 的区别？**

A：
| 维度 | Paxos | Raft |
|------|-------|------|
| 理解难度 | 极高（著名的"难以理解"） | 专为可理解性设计 |
| Leader 选举 | 无固定 Leader（Multi-Paxos 有优化） | 强 Leader，所有写操作经过 Leader |
| 日志 | 可以乱序提交 | 严格顺序提交 |
| 工程实现 | ZooKeeper（ZAB 是 Paxos 变种） | etcd、Consul、TiKV |

---

## 三、分布式 ID

**Q5：分布式 ID 生成方案对比？**

A：
| 方案 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| **UUID** | 128 位随机数 | 本地生成，无网络开销 | 无序（B+ 树插入性能差）、太长（36 字符）、不可读 |
| **数据库自增** | AUTO_INCREMENT | 简单有序 | 单点瓶颈，扩展性差 |
| **号段模式** | 每次从 DB 取一段 ID（如 1-1000），本地自增 | 减少 DB 访问 | 重启可能浪费号段 |
| **Snowflake** | 64 位：1 位符号 + 41 位时间戳 + 10 位机器 ID + 12 位序列号 | 有序、高性能、去中心化 | 时钟回拨问题 |
| **Leaf（美团）** | 号段模式 + Snowflake 双模式 | 生产验证，高可用 | 依赖外部组件（DB 或 ZK） |

**Snowflake 时钟回拨问题**：
- 机器时钟调回，可能生成重复 ID
- 解决：检测回拨 → 拒绝生成（抛异常）或等待时钟追上

---

## 四、分布式锁

**Q6：三种分布式锁方案对比？**

A：
| 方案 | 实现 | 优点 | 缺点 |
|------|------|------|------|
| **Redis** | SETNX + EX + Lua 释放 | 性能高、实现简单 | 主从切换可能丢锁、需处理续期 |
| **ZooKeeper** | 临时顺序节点 + Watch | 强一致、自动释放（会话断开节点删除） | 性能较低（创建/删除节点有 ZAB 协议开销） |
| **MySQL** | SELECT ... FOR UPDATE / 唯一索引 INSERT | 简单，无额外组件 | 性能最差，不适合高并发 |

**选型建议**：
- 一般业务场景：Redis（Redisson）
- 强一致性要求（如金融）：ZooKeeper（Curator）
- 简单场景且已有 MySQL：MySQL 行锁

---

**Q7：ZooKeeper 分布式锁的实现原理？**

A：
1. 在锁路径下创建**临时顺序节点**（`/lock/lock-0000000001`）
2. 获取该路径下所有子节点并排序
3. 如果自己的节点是**最小的** → 获取锁成功
4. 否则 → 对**前一个节点**注册 Watcher（监听删除事件）→ 阻塞等待
5. 前一个节点删除（释放锁/会话超时） → 收到通知 → 再次判断是否最小
6. 释放锁：删除自己的节点

**优势**：
- 会话断开 → 临时节点自动删除 → 不会死锁
- 顺序节点避免惊群效应（只监听前一个节点）

---

## 五、分布式事务

**Q8：分布式事务有哪些解决方案？**

A：
| 方案 | 一致性 | 性能 | 复杂度 | 适用场景 |
|------|--------|------|--------|---------|
| **2PC（两阶段提交）** | 强一致 | 低（同步阻塞） | 中 | 数据库层面（XA 事务） |
| **TCC** | 强一致 | 中 | 高（需实现 Try/Confirm/Cancel） | 对一致性要求高的业务 |
| **SAGA** | 最终一致 | 高 | 中 | 长事务、跨多服务 |
| **本地消息表** | 最终一致 | 高 | 低 | 异步场景 |
| **事务消息（MQ）** | 最终一致 | 高 | 中 | RocketMQ 事务消息 |
| **最大努力通知** | 最终一致 | 高 | 低 | 跨平台（如支付回调） |

---

**Q9：2PC（两阶段提交）的原理和问题？**

A：
**阶段一（Prepare）**：协调者问所有参与者"你们能提交吗？"→ 参与者执行事务（不提交），写 redo/undo 日志，回复 Yes/No

**阶段二（Commit/Rollback）**：
- 所有回复 Yes → 协调者发 Commit → 参与者提交
- 任一回复 No → 协调者发 Rollback → 参与者回滚

**问题**：
1. **同步阻塞**：所有参与者在 Prepare 后锁定资源等待，阻塞时间长
2. **单点故障**：协调者挂了，参与者一直阻塞
3. **数据不一致**：协调者发 Commit 后宕机，部分参与者没收到 → 数据不一致

---

**Q10：TCC 的原理？每一步做什么？**

A：TCC（Try-Confirm-Cancel）是业务层面的两阶段提交：

| 阶段 | 说明 | 示例（转账 A→B 100 元） |
|------|------|------------------------|
| **Try** | 资源检查和预留（不真正执行） | A 账户冻结 100 元（可用余额 -100，冻结金额 +100） |
| **Confirm** | 确认执行（幂等） | A 冻结金额 -100，B 余额 +100 |
| **Cancel** | 取消预留（幂等） | A 冻结金额 -100，可用余额 +100（解冻） |

**TCC 的难点**：
1. **空回滚**：Try 未执行就收到 Cancel → Cancel 需要识别并跳过
2. **悬挂**：Cancel 先到，Try 后到 → Try 需要判断是否已 Cancel
3. **幂等性**：Confirm 和 Cancel 可能重试，必须幂等

---

**Q11：SAGA 模式的原理？**

A：将长事务拆分为多个本地事务，每个事务有对应的补偿事务：

```
T1 → T2 → T3 → ... → Tn
如果 Ti 失败 → 执行补偿：Ci-1 → Ci-2 → ... → C1（反向补偿）
```

**两种执行方式**：
1. **编排式（Choreography）**：每个服务监听事件，完成后发布事件触发下一步。去中心化，但流程复杂时难以追踪
2. **协调式（Orchestration）**：有一个中央协调器按步骤调用各服务。流程清晰，便于管理

**Seata 的 SAGA 模式**：通过状态机引擎编排，支持正向重试和反向补偿。

---

**Q12：本地消息表方案的原理？**

A：
1. 业务操作和写消息表在**同一个本地事务**中（保证原子性）
2. 定时任务扫描消息表，将未发送的消息发送到 MQ
3. 下游消费消息并处理业务
4. 处理成功后回调或确认，更新消息状态

```
服务 A（本地事务）：
  BEGIN
    UPDATE account SET balance = balance - 100 WHERE id = 1;
    INSERT INTO message_table (id, content, status) VALUES (uuid, '扣款100', 'NEW');
  COMMIT

定时任务：
  SELECT * FROM message_table WHERE status = 'NEW';
  → 发送到 MQ → 更新 status = 'SENT'

服务 B（消费者）：
  消费消息 → 执行业务 → 返回 ACK
  → 回调服务 A 更新 status = 'DONE'
```

---

## 六、Seata

**Q13：Seata 的 AT 模式原理？**

A：Seata AT 模式是**自动化的两阶段提交**，对业务代码无侵入：

**阶段一（自动）**：
1. 解析 SQL，查询修改前的数据（before image）
2. 执行 SQL
3. 查询修改后的数据（after image）
4. 生成 undo log（回滚日志）
5. 本地事务提交（业务 SQL + undo log 在同一事务中）
6. 向 TC（Transaction Coordinator）注册分支事务

**阶段二**：
- **Commit**：TC 通知各分支删除 undo log（异步，效率高）
- **Rollback**：TC 通知各分支根据 undo log 做逆向 SQL 回滚

**优势**：业务无侵入（只需加 `@GlobalTransactional` 注解）
**限制**：依赖数据库支持、行锁粒度、undo log 存储开销

---

**Q14：Seata 的 AT、TCC、SAGA 模式如何选型？**

A：
| 模式 | 一致性 | 侵入性 | 性能 | 适用场景 |
|------|--------|--------|------|---------|
| **AT** | 最终一致 | 无侵入（注解） | 中 | 大部分业务场景（默认推荐） |
| **TCC** | 强一致 | 高（需实现三个接口） | 高 | 金融核心链路、对一致性要求极高 |
| **SAGA** | 最终一致 | 中（定义补偿） | 高 | 长事务、跨公司/遗留系统 |

---

## 七、雪花算法（Snowflake）深度解析

**Q15：雪花算法的结构和原理？**

A：雪花算法生成的是一个 **64 位长整型（Long）**，结构如下：

```
0 | 0000000000 0000000000 0000000000 0000000000 0 | 00000 | 00000 | 000000000000
↑                  41位毫秒时间戳                  ↑  5位  ↑  5位  ↑   12位序列号
符号位                                           数据中心  机器ID
```

| 段 | 位数 | 最大值 | 说明 |
|----|------|--------|------|
| **符号位** | 1 | 0 | 恒为 0，保证 ID 为正数 |
| **时间戳** | 41 | 2^41-1 ≈ 69年 | 相对于自定义起始时间的毫秒差 |
| **数据中心ID** | 5 | 31 | 最多 32 个数据中心 |
| **机器ID** | 5 | 31 | 每个数据中心最多 32 台机器 |
| **序列号** | 12 | 4095 | 同毫秒内自增，每毫秒最多 4096 个 ID |

**理论最大 QPS**：4096 × 1000 = **400万/秒**

**核心位运算拼装公式**：
```java
long id = ((timestamp - START_EPOCH) << 22)  // 时间戳左移22位（10+12）
        | (datacenterId << 17)                // 数据中心ID左移17位（5+12）
        | (workerId << 12)                    // 机器ID左移12位
        | sequence;                           // 序列号填入低12位
```

**START_EPOCH 的作用：延长 ID 可用年限**

`START_EPOCH` 是自定义的时间戳起点（纪元），核心目的是**用相对时间差代替绝对时间戳存储**。

- Unix 时间戳从 1970 年开始，到 2026 年已过去约 56 年，对应毫秒数约 1.74 万亿
- 41 位最大值为 2^41-1 ≈ 2.19 万亿 ms ≈ 69.7 年
- 若从 1970 年起算，41 位时间戳到 **2039 年**就会溢出

```
// 存储的是相对差值，而非绝对时间戳
long relativeTimestamp = currentTimestamp - START_EPOCH;

// 示例：
// START_EPOCH = 2020-01-01 = 1,577,836,800,000 ms
// 当前时间   = 2026-04-06 ≈ 1,744,000,000,000 ms
// 相对差值   ≈ 166,163,200,000 ms → 只需约 38 位，远小于 41 位
// 从 2020 年起算，可用至 2020 + 69 = 2089 年
```

> ⚠️ **注意**：`START_EPOCH` 一旦设定**不能修改**，否则历史 ID 解析会出错。

---

**Q16：雪花算法完整 Java 实现？**

A：

```java
/**
 * 雪花算法 ID 生成器
 * 生成结构：1位符号 + 41位时间戳 + 5位数据中心 + 5位机器ID + 12位序列号
 */
public class SnowflakeIdWorker {

    // ==================== 各段位数定义 ====================
    private static final long SEQUENCE_BITS      = 12L;
    private static final long WORKER_ID_BITS     = 5L;
    private static final long DATACENTER_ID_BITS = 5L;

    // ==================== 最大值（位运算，避免魔法数字）====================
    /** 机器ID最大值：31 */
    private static final long MAX_WORKER_ID      = ~(-1L << WORKER_ID_BITS);
    /** 数据中心ID最大值：31 */
    private static final long MAX_DATACENTER_ID  = ~(-1L << DATACENTER_ID_BITS);
    /** 序列号最大值：4095 */
    private static final long MAX_SEQUENCE       = ~(-1L << SEQUENCE_BITS);

    // ==================== 位移量 ====================
    private static final long WORKER_ID_SHIFT     = SEQUENCE_BITS;
    private static final long DATACENTER_ID_SHIFT = SEQUENCE_BITS + WORKER_ID_BITS;
    private static final long TIMESTAMP_SHIFT     = SEQUENCE_BITS + WORKER_ID_BITS + DATACENTER_ID_BITS;

    /** 起始时间戳（2020-01-01 00:00:00 UTC），自定义越晚可用年限越长 */
    private static final long START_EPOCH = 1577836800000L;

    private final long workerId;
    private final long datacenterId;
    private long lastTimestamp = -1L;
    private long sequence = 0L;

    public SnowflakeIdWorker(long workerId, long datacenterId) {
        if (workerId < 0 || workerId > MAX_WORKER_ID) {
            throw new IllegalArgumentException(
                String.format("workerId 必须在 [0, %d] 范围内，当前值：%d", MAX_WORKER_ID, workerId));
        }
        if (datacenterId < 0 || datacenterId > MAX_DATACENTER_ID) {
            throw new IllegalArgumentException(
                String.format("datacenterId 必须在 [0, %d] 范围内，当前值：%d", MAX_DATACENTER_ID, datacenterId));
        }
        this.workerId = workerId;
        this.datacenterId = datacenterId;
    }

    /** 生成下一个唯一ID（线程安全） */
    public synchronized long nextId() {
        long currentTimestamp = System.currentTimeMillis();

        // 时钟回拨检测
        if (currentTimestamp < lastTimestamp) {
            long clockBackwardMs = lastTimestamp - currentTimestamp;
            if (clockBackwardMs <= 5) {
                // 容忍5ms以内的回拨，等待时钟追上
                currentTimestamp = waitUntilNextMillis(lastTimestamp);
            } else {
                throw new RuntimeException(
                    String.format("时钟回拨异常！拒绝生成ID，回拨时间：%d ms", clockBackwardMs));
            }
        }

        if (currentTimestamp == lastTimestamp) {
            // 同一毫秒内：序列号自增
            sequence = (sequence + 1) & MAX_SEQUENCE;
            if (sequence == 0) {
                // 序列号溢出，等待下一毫秒
                currentTimestamp = waitUntilNextMillis(lastTimestamp);
            }
        } else {
            // 新的毫秒：序列号重置
            sequence = 0L;
        }

        lastTimestamp = currentTimestamp;

        return ((currentTimestamp - START_EPOCH) << TIMESTAMP_SHIFT)
                | (datacenterId << DATACENTER_ID_SHIFT)
                | (workerId << WORKER_ID_SHIFT)
                | sequence;
    }

    private long waitUntilNextMillis(long lastTimestamp) {
        long timestamp = System.currentTimeMillis();
        while (timestamp <= lastTimestamp) {
            timestamp = System.currentTimeMillis();
        }
        return timestamp;
    }

    
    /** ID 解析工具（调试用） */
    public static void parseId(long id) {
        long sequence     = id & MAX_SEQUENCE;
        long workerId     = (id >> WORKER_ID_SHIFT) & MAX_WORKER_ID;
        long datacenterId = (id >> DATACENTER_ID_SHIFT) & MAX_DATACENTER_ID;
        long timestamp    = (id >> TIMESTAMP_SHIFT) + START_EPOCH;
        System.out.printf("ID: %d%n  时间: %s%n  数据中心: %d%n  机器ID: %d%n  序列号: %d%n",
            id, new java.util.Date(timestamp), datacenterId, workerId, sequence);
    }
}
```

---

**Q17：雪花算法的关键问题及解决方案？**

**问题1：时钟回拨（最核心问题）**

原因：NTP 时间同步、闰秒调整、虚拟机漂移等导致系统时间倒退，可能生成重复 ID。

| 策略 | 适用场景 | 说明 |
|------|---------|------|
| **等待时钟追上** | 回拨时间极短（≤5ms） | 自旋等待，已在上方代码实现 |
| **抛出异常拒绝** | 回拨时间较长 | 告警 + 降级，由上层重试 |
| **单调时钟** | 对时钟敏感的场景 | 用 `System.nanoTime()` 替代，不受 NTP 影响 |

```java
// 单调时钟方案：基于纳秒计算毫秒，彻底规避时钟回拨
private final long startNanoTime = System.nanoTime();
private final long startEpochMs  = System.currentTimeMillis();

private long currentTimeMillis() {
    return startEpochMs + (System.nanoTime() - startNanoTime) / 1_000_000;
}
```

**问题2：workerId 唯一性保证（分布式部署）**

| 方案 | 适用场景 | 说明 |
|------|---------|------|
| **配置文件静态配置** | 固定少量实例 | 简单，但多实例需手动维护 |
| **IP 末段计算** | 容器IP固定的场景 | `((ip[2] & 0xFF) << 8 | (ip[3] & 0xFF)) % 1024` |
| **Redis 原子自增分配** | 已有 Redis 的分布式项目 | 服务重启可复用同一 workerId |
| **ZooKeeper 持久顺序节点** | 大规模微服务（美团 Leaf） | 强一致，持久化防重启丢失 |

**问题3：ID 在 JavaScript 中精度丢失**

JS 的 `Number` 最大安全整数为 2^53-1，64 位雪花 ID 会丢失精度。

```java
// 解决：JSON 序列化时将 Long 转为 String
@JsonSerialize(using = ToStringSerializer.class)
private Long id;

// 或全局配置（Jackson）
@Bean
public Jackson2ObjectMapperBuilderCustomizer jsonCustomizer() {
    return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
}
```

---

**Q18：雪花算法与其他 ID 方案对比？**

| 方案 | 优点 | 缺点 | 推荐场景 |
|------|------|------|---------|
| **UUID** | 无需协调，简单 | 无序、存储大、索引性能差 | 日志追踪、非主键场景 |
| **数据库自增** | 简单有序 | 单点瓶颈，分库分表困难 | 单库小规模 |
| **雪花算法** | 高性能、趋势有序、去中心化 | 依赖时钟，workerId 需协调 | 大多数分布式业务 |
| **美团 Leaf** | 解决时钟回拨，workerId 自动分配 | 依赖 ZooKeeper/DB | 大规模微服务 |
| **百度 UidGenerator** | 支持时钟回拨，可自定义位数 | 依赖 DB 分配 workerId | 高并发、定制化需求 |
