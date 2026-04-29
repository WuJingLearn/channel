# RocketMQ 消息可靠投递与事务消息详解

> 本文系统讲清楚「**本地 DB 操作 + 发送 MQ 消息**」这个经典场景下的一致性问题，以及四种解决方案的对比选型。覆盖：
> 1. **问题本质** —— DB 和 MQ 双写一致性为什么难
> 2. **方案一**：直接 `send` 失败重试 —— 为什么不可行
> 3. **方案二**：`@Transactional` 包住 DB + send —— 为什么更糟
> 4. **方案三**：本地消息表 —— 业界标准的可靠方案
> 5. **方案四**：RocketMQ 事务消息 —— 二阶段 + 回查机制
> 6. **事务消息使用场景** —— 什么时候该用、什么时候不该用
> 7. **四种方案对比矩阵 + 选型决策树**

---

## 一、问题定义：DB + MQ 双写一致性


解决的是 本地数据库操作和发送 MQ 消息，如何保证原子性（要么都做，要么都不做）
这是分布式系统里最经典的双写一致性问题。看下面这个常见场景就明白了：

### 1.1 经典场景

```java
public class OrderServiceNaive {

    public void createOrder(Order order) {
        orderDao.insert(order);          // ① 写 DB
        mqProducer.send(orderMsg);       // ② 发 MQ
    }
}
```

这段代码几乎每个业务系统都写过，**看起来没问题**，但实际有 4 种失败组合：

| 情况 | ① 写 DB | ② 发 MQ | 结果 |
|:---:|:---:|:---:|:---|
| 1 | ✅ | ✅ | 正常 |
| 2 | ❌ | —— | DB 异常没发 MQ，**一致** |
| 3 | ✅ | ❌ | **DB 有订单但下游没收到消息（数据丢失）** |
| 4 | ✅ | ✅ 但超时未知 | **DB 有订单，MQ 可能成功也可能失败** |

如果调换顺序"先发 MQ 再写 DB"，又变成**下游处理了消息但上游 DB 没订单**。

### 1.2 核心矛盾

- **DB 和 MQ 是两个独立资源**，各有各的事务模型
- **没有任何单一技术手段能让两者的操作原子化**（除非用 XA，但 RocketMQ 不支持）
- 必须通过"状态 + 补偿"的思路，才能达到**最终一致性**

---

## 二、方案一：直接 send 失败重试

### 2.1 方案描述

```java
public class OrderServiceRetry {

    public void createOrder(Order order) {
        orderDao.insert(order);
        for (int i = 0; i < 3; i++) {
            try {
                mqProducer.send(msg);
                return;
            } catch (Exception e) {
                // 退避重试
                sleep(100 * (1 << i));
            }
        }
        // 3 次都失败怎么办？加入内存队列继续重试？
        retryQueue.add(msg);
    }
}
```

### 2.2 致命漏洞

#### 漏洞 1：DB 提交后、发 MQ 前，进程宕机

```text
T0: orderDao.insert(order)  → DB 事务提交，订单入库
T1: 进程被 kill -9 / OOM / 机器重启
T2: 还没来得及发 MQ
```

**结果**：订单在 DB 里，MQ **永远不会被发出**——因为重试逻辑只在内存里，进程没了就没了。下游系统完全不知道有这笔订单，**数据永久不一致**。

> 这是最常见的生产事故：订单入库但下游没通知，用户付了钱但没发货。

#### 漏洞 2：MQ 发送"超时"，状态未知

RocketMQ 发送超时时，调用方**不知道消息到底发没发成功**：

- Broker 压根没收到 → 需要重试
- Broker 收到了但 ACK 丢了 → 不能重试，否则重复

"失败就重试"的逻辑**根本不知道该不该重试**。

#### 漏洞 3：内存重试队列不可靠

- 进程重启 → 队列清空 → 消息丢失
- 队列满了（MQ 长时间不可用）→ 要么 OOM，要么丢弃新消息
- 重试线程处理不过来 → 堆积越来越多

### 2.3 结论

**✗ 不可行**。只在「丢几条消息也无所谓」的场景（日志采集、监控埋点）下可用。

---

## 三、方案二：`@Transactional` 包住 DB + send

### 3.1 方案描述

很多人的直觉是"Spring 事务一包就原子了"：

```java
public class OrderServiceTxWrong {

    @Transactional
    public void createOrder(Order order) {
        orderDao.insert(order);
        mqProducer.send(msg);
    }
}
```

**这个方案比方案一更糟**。

### 3.2 为什么 `@Transactional` 解决不了问题

Spring 的 `@Transactional` 底层本质就三步：

```text
① 方法进入前：从 DataSource 拿一个 Connection，setAutoCommit(false)
② 执行业务代码（这中间的 JDBC 操作都用这个 Connection）
③ 方法正常返回 → connection.commit()
   方法抛异常   → connection.rollback()
```

**事务边界 = DB Connection 的 commit/rollback**，它**只管 DB 这一个资源**：

- `mqProducer.send(msg)` 走的是**独立的 Netty 长连接**，和 DB Connection 没有任何绑定
- `send()` 一旦返回，消息就**已经在 Broker 上**，不受后续 DB commit/rollback 影响

### 3.3 三个严重问题

#### 问题 1：幽灵消息（MQ 成功 + DB 回滚）

```text
T0: @Transactional 方法进入，申请 DB Connection C1
T1: orderDao.insert(order)     → 走 C1，buffer 在事务里（未 commit）
T2: mqProducer.send(msg)       → 消息已经到 Broker，Consumer 可以立刻消费
T3: 方法要返回，Spring 调 C1.commit()
T4: commit 失败（网络抖动/死锁/连接断开）→ DB 事务回滚
T5: 订单 DB 里没有，但下游库存/积分/通知已经处理了
```

**结果**：

- 下游以为有订单，扣了库存、发了积分、发了短信
- 上游 DB 里没有订单
- **比丢消息更严重**——丢消息可以查日志补偿，幽灵消息你都不知道该不该回滚下游

#### 问题 2：连接池放大效应

`@Transactional` 期间 DB Connection 被一直占着：

```text
T0: 获取 Connection C1（HikariCP 池 -1）
T1: insert 完成，C1 继续持有（未 commit）
T2: send MQ，网络 RTT 1~5ms（正常） / 50~500ms（抖动） / 3000ms（超时）
T3: commit → 释放 C1
```

一旦 **MQ 抖动或 Broker 慢响应**：

- 所有调 `createOrder` 的线程都把 DB 连接占着等 MQ
- HikariCP 默认 10 个连接，10 个线程卡住 → **新请求全部拿不到连接，服务雪崩**
- MQ 问题**直接传染成 DB 问题**

这是典型的"慢调用放大器"——不相关的两个资源被串在一个事务里，一个慢拖死另一个。

#### 问题 3：超时时的"薛定谔消息"无解

```java
public class OrderServiceTxTimeout {

    @Transactional
    public void createOrder(Order order) {
        orderDao.insert(order);
        try {
            producer.send(msg);
        } catch (RemotingTimeoutException e) {
            // 超时回滚？但 Broker 可能已经收到了
            throw new RuntimeException(e);
        }
    }
}
```

| 真实情况 | 回滚 DB | 结果 |
|:---|:---:|:---|
| Broker 没收到 | ✓ | ✅ 一致 |
| Broker 收到但 ACK 丢 | ✓ | ❌ DB 回滚但消息已投递，**幽灵消息** |

**无论回滚不回滚都有 50% 出错概率**。

### 3.4 变种：`afterCommit` 里发送

Spring 提供 `TransactionSynchronization`，可以在事务 commit 之后再发 MQ：

```java
public class OrderServiceAfterCommit {

    @Transactional
    public void createOrder(Order order) {
        orderDao.insert(order);

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        producer.send(msg);
                    }
                });
    }
}
```

这样避免了"DB 回滚导致幽灵消息"，但：

- **DB commit 成功后，MQ 发送失败怎么办？** 事务已 commit，抛异常也回滚不了 → **丢消息**
- **`afterCommit` 执行前进程崩溃** → 消息彻底丢失
- 本质上**退化成了方案一**——只是把"send 失败"的时间窗口缩小了，没根除

### 3.5 结论

**✗✗ 严禁使用**。`@Transactional` 包 `send` 是四个方案中最糟糕的：

1. **幽灵消息**：DB 回滚时下游已处理
2. **连接池放大**：MQ 抖动拖垮 DB
3. **超时不可解**：50% 错误率

---

## 四、方案三：本地消息表

### 4.1 方案描述

**核心思想**：把消息当成业务数据的一部分，和业务操作一起写进 **同一个本地 DB 事务**，再用独立线程扫描补偿发送。

#### 4.1.1 消息表结构

```sql
CREATE TABLE local_message (
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    biz_key         VARCHAR(64)  NOT NULL COMMENT '业务唯一键（如 orderId），Consumer 幂等用',
    topic           VARCHAR(128) NOT NULL,
    tag             VARCHAR(64)  DEFAULT NULL,
    body            MEDIUMBLOB   NOT NULL COMMENT '消息体，建议 < 4MB',
    status          TINYINT      NOT NULL DEFAULT 0 COMMENT '0=PENDING 1=SENT 2=DEAD',
    retry_count     INT          NOT NULL DEFAULT 0,
    next_retry_at   DATETIME     NOT NULL COMMENT '下次重试时间',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_biz_key (biz_key),
    KEY idx_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
```

> ⚠️ 下列 Java 代码为**方案骨架示例**，`LocalMessage`、`LocalMessageDao`、`OrderDao` 等类需由业务方按项目规范自行定义（JPA / MyBatis / Lombok 等均可），这里展示的是**业务流程**，不是可直接编译运行的代码。

#### 4.1.2 业务代码

```text
@Transactional
public void createOrder(Order order) {
    // ① 写业务数据
    orderDao.insert(order);

    // ② 同一个 DB 事务里把消息持久化到 local_message 表
    LocalMessage localMsg = new LocalMessage();
    localMsg.setBizKey(order.getOrderId());
    localMsg.setTopic("OrderTopic");
    localMsg.setTag("CREATE");
    localMsg.setBody(JSON.toJSONBytes(order));
    localMsg.setStatus(LocalMessage.STATUS_PENDING);   // 0
    localMsg.setRetryCount(0);
    localMsg.setNextRetryAt(new Date());
    localMessageDao.insert(localMsg);

    // 事务提交后：业务数据和消息记录要么都有，要么都没有
    // 如果此处 commit 失败，orderDao 和 localMessageDao 的写入会同时回滚
}
```

#### 4.1.3 独立扫描任务

伪代码，展示关键流程：

```text
@Scheduled(fixedDelay = 1000)   // 每秒扫一次
public void scanAndSend() {
    // 每次拿一批待发送的消息：status=PENDING 且 next_retry_at <= now
    List<LocalMessage> pending =
        localMessageDao.findBy(status=PENDING, nextRetryAt<=now, limit=100);

    for (LocalMessage m : pending) {
        try {
            Message mq = new Message(m.getTopic(), m.getTag(), m.getBizKey(), m.getBody());
            mq.setKeys(m.getBizKey());
            SendResult result = producer.send(mq);

            if (result.getSendStatus() == SendStatus.SEND_OK) {
                // 标记为已发送：UPDATE local_message SET status=1 WHERE id=?
                localMessageDao.updateStatusSent(m.getId());
            } else {
                // 非 SEND_OK（例如 FLUSH_DISK_TIMEOUT/SLAVE_NOT_AVAILABLE）
                // 视为失败，等下次扫描重试
                updateRetry(m);
            }
        } catch (Exception e) {
            updateRetry(m);
        }
    }
}

private void updateRetry(LocalMessage m) {
    int nextRetryCount = m.getRetryCount() + 1;
    if (nextRetryCount >= 16) {
        // 达到重试上限，标记为死信，触发告警
        // UPDATE local_message SET status=2, retry_count=? WHERE id=?
        localMessageDao.updateStatusDead(m.getId(), nextRetryCount);
        alertService.notifyDeadMessage(m);
    } else {
        // 指数退避：下次重试时间 = now + min(2^retry_count, 3600) 秒
        long delaySec = Math.min(1L << nextRetryCount, 3600L);
        Date nextRetryAt = new Date(System.currentTimeMillis() + delaySec * 1000);
        // UPDATE local_message SET retry_count=?, next_retry_at=? WHERE id=?
        localMessageDao.updateRetry(m.getId(), nextRetryCount, nextRetryAt);
    }
}
```

**关键点**：

- 扫描任务**多实例部署时必须加分布式锁**（见 10.2 节），避免同一条消息被多个实例并发发送
- `biz_key` 上的 `UNIQUE KEY` 保证同一业务操作不会产生多条消息记录
- Consumer 端用 `msg.getKeys()`（即 `biz_key`）做幂等去重

### 4.2 为什么这个方案可靠

| 风险点 | 本地消息表如何解决 |
|:---|:---|
| 进程在 DB 提交后崩溃 | 消息已在 `local_message` 表里，重启后扫描任务继续处理 |
| MQ 发送超时 | 超时视为失败，等 retry；重复发送由 Consumer 幂等兜底 |
| MQ 长时间不可用 | 消息积在本地表，MQ 恢复后批量补发，**不会丢** |
| DB 和 MQ 原子性 | 不追求，用"业务表 + 消息表"的同表事务 + 异步补偿替代 |

### 4.3 代价

- **业务库多一张表**，占存储、需要定期归档
- **每次业务操作多一次 DB 写入**，吞吐略降
- **消息延迟**：取决于扫描周期（通常 1~5 秒）
- **分库分表场景**需要为每个分片都建消息表
- **治理成本**：需要监控消息积压、超时、死信

### 4.4 适用场景

- 业务库已经是强一致 DB，团队有成熟定时任务基础设施
- 对消息延迟不敏感（能接受秒级）
- 希望**完全自主掌控补偿逻辑**、不想依赖 MQ 中间件特性

---

## 五、方案四：RocketMQ 事务消息

### 5.1 核心思想

**把"本地消息表"从业务库搬到 Broker 上**，业务代码只需实现两个回调（执行本地事务、回查本地事务状态），其他所有补偿逻辑由 Broker 负责。

### 5.2 完整流程

```text
Producer                Broker                DB / 业务
   │                      │                      │
   │ 1. sendMessageInTransaction                 │
   │────prepare(half)────▶│                      │
   │                      │  消息写入半消息 Topic   │
   │                      │  （对 Consumer 不可见） │
   │◀───── SEND_OK ───────│                      │
   │                                             │
   │ 2. executeLocalTransaction                  │
   │──────本地事务（写 DB 等）─────────────────────▶│
   │                                             │
   │ 3. 根据本地事务结果                            │
   │────COMMIT / ROLLBACK─▶│                     │
   │                      │ COMMIT: 半消息变正式消息，投递给 Consumer
   │                      │ ROLLBACK: 删除半消息
   │                                             │
   │                      │ 4. UNKNOWN 或超时？Broker 主动回查
   │                      │◀──── checkLocalTransaction ──────│
   │                      │ ─────返回 COMMIT/ROLLBACK ──────▶│
```

**核心保证**：**本地事务成功 ⇔ 下游一定能收到消息**。

### 5.3 关键机制

#### 5.3.1 半消息（Half Message）

- 消息先写入 Broker 内部 Topic `RMQ_SYS_TRANS_HALF_TOPIC`
- 这个 Topic **不被任何 Consumer 订阅**，所以消息暂时对消费端不可见
- Producer 收到 `SEND_OK` 表示"半消息落盘成功"

#### 5.3.2 COMMIT / ROLLBACK 的本质

- **COMMIT**：Broker 把半消息从 `HALF_TOPIC` 搬到真正的业务 Topic，Consumer 才能看见
- **ROLLBACK**：Broker 往 `RMQ_SYS_TRANS_OP_HALF_TOPIC` 写一条 op 消息标记"这条半消息已处理"，定时任务看到标记就不再处理它
- 这两个操作都是**Broker 本地原子操作**，不依赖 Producer

#### 5.3.3 回查机制（Broker 主动找 Producer）

Broker 端 `TransactionalMessageCheckService` 后台线程按固定间隔扫描 `HALF_TOPIC`，核心参数：

| 参数 | 默认值 | 含义 |
|:---|:---:|:---|
| `transactionCheckInterval` | 60 秒 | 扫描线程的轮询间隔（多久扫一次半消息） |
| `transactionTimeOut` | 6 秒 | 半消息"免疫期"——刚写入后的 6 秒内不会被回查（给 Producer 正常提交的时间） |
| `transactionCheckMax` | 15 次 | 单条半消息最多回查多少次，超过则丢弃 |

扫描逻辑伪代码：

```text
for 每条半消息 msg:
  如果对应的 op 消息已存在（COMMIT 或 ROLLBACK 过）：跳过
  如果半消息停留时间 < transactionTimeOut（6 秒）：跳过（免疫期内）
  如果已回查次数 >= transactionCheckMax（15 次）：丢弃，写入 TRANS_CHECK_MAX_TIME_TOPIC
  否则：
    找到 msg 的 ProducerGroup → 从 Broker 维护的 Producer 列表里选一台
    发送 CHECK_TRANSACTION_STATE 请求
    Producer 的 TransactionListener.checkLocalTransaction(msg) 被调用
    Producer 根据业务 key 查 DB → 返回 COMMIT/ROLLBACK/UNKNOWN
```

**第一次回查的触发时机**：

```text
半消息写入时间 + transactionTimeOut (6s)  ≤  检测时间点  <  下次扫描时间点
```

实际场景下，Producer 宕机导致的半消息，**第一次回查一般在半消息写入后 6 秒 ~ 66 秒之间触发**（取决于扫描周期撞到哪个点）。

**注意**：

- 回查是 **Broker 向 Producer 发起**的（反向 RPC），不是 Producer 主动查
- Broker 不认识具体的 Producer 实例，靠 `ProducerGroup` 从 Broker 维护的 Producer 连接列表里**随机**选一台发 check 请求
- 所以**整个 ProducerGroup 里任意一个实例都能响应回查**——`checkLocalTransaction` 必须能独立于调用上下文工作（原始发送方可能已经宕机）

### 5.4 业务代码示例

> ⚠️ 下列代码为**方案骨架示例**，`Order`、`OrderDao` 等业务类由使用方按项目规范自行定义。`TransactionListener`、`TransactionMQProducer`、`LocalTransactionState` 均为 RocketMQ Client SDK 提供的公共 API（包：`org.apache.rocketmq.client.producer.*`）。

```text
// TransactionListener 实现：定义本地事务如何执行 + 如何回查
public class OrderTransactionListener implements TransactionListener {

    private final OrderDao orderDao;  // 由 Spring 注入

    public OrderTransactionListener(OrderDao orderDao) {
        this.orderDao = orderDao;
    }

    /**
     * 执行本地事务
     * arg 是 producer.sendMessageInTransaction(msg, arg) 传入的业务对象
     */
    @Override
    public LocalTransactionState executeLocalTransaction(Message msg, Object arg) {
        Order order = (Order) arg;
        try {
            orderDao.insert(order);
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (DuplicateKeyException e) {
            // 幂等：业务主键冲突视为已成功
            return LocalTransactionState.COMMIT_MESSAGE;
        } catch (Exception e) {
            log.error("local tx failed, orderId={}", order.getOrderId(), e);
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
    }

    /**
     * Broker 回查本地事务状态
     * 只能靠 msg.getKeys() 拿业务主键查 DB，不能依赖调用上下文
     * （原始发送方可能已经宕机，回查可能在 Group 内任意 Producer 实例上发生）
     */
    @Override
    public LocalTransactionState checkLocalTransaction(MessageExt msg) {
        String orderId = msg.getKeys();
        Order order = orderDao.findById(orderId);
        if (order == null) {
            // DB 里没有 → 本地事务肯定没成功 → 回滚
            return LocalTransactionState.ROLLBACK_MESSAGE;
        }
        // DB 里有 → 本地事务已成功 → 提交
        return LocalTransactionState.COMMIT_MESSAGE;
    }
}
```

```text
// 启动 TransactionMQProducer 并发送事务消息
public class TransactionOrderProducer {

    public static void main(String[] args) throws Exception {
        TransactionMQProducer producer =
                new TransactionMQProducer("order_tx_producer_group");
        producer.setNamesrvAddr("ns1:9876");
        producer.setTransactionListener(new OrderTransactionListener(orderDao));
        producer.start();

        Order order = new Order("ORDER_10086", 99.9);
        Message msg = new Message(
                "OrderTopic", "CREATE",
                order.getOrderId(),
                JSON.toJSONBytes(order));
        // 关键：必须设 keys（业务主键），回查时靠它查 DB
        msg.setKeys(order.getOrderId());

        // 第二个参数会原样传给 executeLocalTransaction 的 arg
        TransactionSendResult result = producer.sendMessageInTransaction(msg, order);
        System.out.println("send result: " + result.getLocalTransactionState());
    }
}
```

---

## 六、事务消息的使用场景

### 6.1 适合用事务消息的 7 个典型场景

#### 场景 1：订单创建 → 扣减库存 / 减积分 / 发优惠券

订单入库后必须可靠通知多个下游联动系统。用事务消息替代"本地消息表"，简化代码。

#### 场景 2：账户扣款 → 通知对账 / 风控 / 反洗钱

金融场景，扣款成功后必须通知对账等下游系统，**绝对不能漏**。事务消息保证"扣款成功 ⇔ 通知一定送达"。

#### 场景 3：订单状态变更 → 触发多端通知（App/短信/邮件/PUSH）

订单从"已支付"变为"已发货"，需要触发多端通知。不能"状态改了但没通知"。

#### 场景 4：分布式微服务的"最终一致性"（Saga 组合）

跨服务事务：订单服务 → 库存服务 → 支付服务。每个环节用事务消息保证"本地 DB 操作 ⇔ 通知下一环节"的原子性，整体形成最终一致性。

#### 场景 5：缓存双写一致性（DB + Redis）

```text
ProductService.updatePrice()
   ├─ 发半消息（目标：cache-refresh topic）
   ├─ 本地事务：UPDATE product SET price=?
   └─ COMMIT → CacheRefreshConsumer 刷 Redis
```

比"延时双删"、"Canal 订阅 Binlog"更简单直观。

#### 场景 6：ES / 数据仓库 / 搜索索引的异步同步

主库是真相源，ES / Hive 是派生数据。事务消息保证派生数据一定能收到更新事件。

#### 场景 7：审计日志 / 操作日志必达

金融、政务场景，审计日志必须和业务数据同生共死，用事务消息解决跨库写审计日志的一致性。

### 6.2 不适合用事务消息的场景

| 场景 | 原因 |
|:---|:---|
| 纯查询 / 只读 | 没有本地事务，用普通消息即可 |
| 下游要求严格顺序 | 事务消息不保证严格顺序（回查可能让 COMMIT 顺序乱） |
| 本地事务和消息无因果关系 | 用本地消息表或普通消息 + 幂等更简单 |
| 本地事务超长（> 1 分钟） | 会频繁触发回查，业务设计别扭——本地事务应短平快 |
| 超低延迟（< 10ms 端到端） | 事务消息多一轮 prepare+commit，延迟从 1ms 升到 2~5ms |
| Consumer 端需要事务保证 | 事务消息**只保证生产端**，Consumer 一致性靠幂等解决 |

---

## 七、为什么 `@Transactional` 解决不了跨资源原子性？

本质原因：**MQ 不是 XA 资源**。

Spring 的 `@Transactional` 只能保证**同一个 PlatformTransactionManager 管理的资源**的原子性。真要让 DB 和 MQ 原子提交，需要 **XA 分布式事务（两阶段提交）**：

```text
Transaction Manager (TM)
      │
      ├─ DB 作为 XA Resource
      └─ MQ 作为 XA Resource

prepare 阶段：问两边"能不能提交"
commit  阶段：都能则都提交，一个不能则都回滚
```

**RocketMQ 支持 XA 吗？** 4.x 不支持，5.x 有实验性支持。而且 XA 方案：

- 性能极差（两阶段 RTT + 锁持有时间翻倍）
- 运维复杂（TM 本身需要高可用）
- 业界早已弃用

所以 RocketMQ 选择了另一条路——**用事务消息（本质是 TCC 的变形）**：

- Try：发半消息 + 执行本地事务
- Confirm：COMMIT，Broker 把半消息转成正式消息
- Cancel：ROLLBACK，Broker 删除半消息
- 补偿：Broker 主动回查（解决 Producer 宕机场景）

---

## 八、四种方案对比矩阵

表头说明：

- **幽灵消息**：MQ 已下发但 DB 没数据 —— "✓ 不会"表示此方案能避免；"❌ 会"表示此方案会产生
- **丢消息**：DB 已有数据但 MQ 未下发 —— "✓ 不丢"表示此方案能避免；"❌ 丢"表示此方案可能丢

| 方案 | 幽灵消息 | 丢消息 | 连接池占用 | 实现复杂度 | 消息延迟 | 推荐度 |
|:---|:---:|:---:|:---:|:---:|:---:|:---:|
| **①** 直接 send 失败重试 | ✓ 不会 | ❌ 丢 | 低 | 最低 | 最低 | ⭐ |
| **②** `@Transactional` 包 DB + send | ❌ **会** | ❌ 丢 | ❌ 高 | 低 | 低 | 🚫 严禁 |
| **②+** `afterCommit` 里发 | ✓ 不会 | ❌ 丢 | 中 | 中 | 低 | ⭐⭐ |
| **③** 本地消息表 | ✓ 不会 | ✓ 不丢 | 低 | 中 | 秒级 | ⭐⭐⭐⭐ |
| **④** RocketMQ 事务消息 | ✓ 不会 | ✓ 不丢 | 低 | 低 | 毫秒级 | ⭐⭐⭐⭐⭐ |

---

## 九、选型决策树

```text
需要"本地 DB 操作成功 ⇔ 下游一定收到消息"吗？
  │
  ├─ 否（丢几条无所谓，比如日志埋点）
  │   └─ 方案 ①：普通 send + 重试
  │
  └─ 是
      │
      ├─ 能接受秒级延迟？业务库已有定时任务基础设施？团队倾向自主掌控？
      │   └─ 方案 ③：本地消息表
      │
      ├─ 要求毫秒级延迟，希望业务代码简洁？
      │   └─ 方案 ④：RocketMQ 事务消息
      │
      └─ 跨多服务多库的长流程事务？
          └─ Seata / Saga 框架 + 事务消息组合
```

**金融核心场景**的稳妥做法：**本地消息表 + 事务消息双保险**（少数团队采用）。

---

## 十、实战要点

### 10.1 事务消息实战

#### 本地事务必须幂等

Broker 回查会再次触发业务处理判定，`checkLocalTransaction` 必须通过业务主键查 DB 判断状态，而不是重新执行业务。

#### 消息 Key 必须是业务主键

```text
msg.setKeys(orderId);  // 回查时靠它查 DB
```

回查时拿不到 `executeLocalTransaction` 的 `arg` 参数，只能靠 `Message#getKeys()` 定位业务数据。

#### 回查次数有限

默认 `transactionCheckMax=15`，超过后半消息被丢弃。业务必须保证回查逻辑能在 15 次内给出明确答案（COMMIT 或 ROLLBACK），不要一直返回 UNKNOWN。

#### Consumer 端仍需幂等

事务消息**只保证生产端**，Consumer 端重复投递（Rebalance、ack 丢失等）依然可能发生，必须用业务主键去重。

### 10.2 本地消息表实战

#### 扫描任务加分布式锁

多实例部署时，扫描任务必须加锁（Redis / 数据库行锁 / ZooKeeper），避免重复发送。

#### 消息表分区归档

每天几百万条消息会让表膨胀，需定期把 `status=SENT` 且超过 N 天的消息归档到历史表。

#### 死信告警

`retry_count >= 16` 的消息进入死信状态，必须监控告警，人工介入。

#### 指数退避

```text
next_retry_at = now() + min(2^retry_count, 3600) seconds
```

避免 MQ 故障恢复时瞬间打爆。

### 10.3 Broker 事务消息配置

```properties
# broker.conf

# 半消息写入后的"免疫期"：在此时间内不会被回查（给 Producer 正常 commit/rollback 的时间）
# 默认 6 秒，单位毫秒
transactionTimeOut=6000

# 单条半消息最大回查次数，超过则丢弃（写入 TRANS_CHECK_MAX_TIME_TOPIC）
# 默认 15 次
transactionCheckMax=15

# 回查扫描线程的轮询间隔：多久扫一次半消息 Topic
# 默认 60 秒，单位毫秒
transactionCheckInterval=60000
```

**三个参数的协同关系**：

- `transactionTimeOut` 决定**单条半消息最早多久后可能被回查**（免疫期）
- `transactionCheckInterval` 决定**扫描任务的触发频率**
- 所以 Producer 宕机后，第一次回查大约发生在「半消息写入 + `transactionTimeOut` ~ + `transactionTimeOut` + `transactionCheckInterval`」之间（默认 6~66 秒内）

**生产调优建议**：

- 本地事务通常很快（< 1 秒）：保持默认即可
- 本地事务较长（5~30 秒）：适当调大 `transactionTimeOut`，避免本地事务还在跑就被回查
- 对实时性要求高：调小 `transactionCheckInterval`（如 10 秒），但会增加 Broker 扫描负载

---

## 十一、一句话总结

> DB + MQ 双写一致性的**唯一正确解法**是：**用"状态 + 补偿"实现最终一致性**。
>
> - **方案 ①** "直接 send + 重试"：进程崩溃会丢消息，**不可用**
> - **方案 ②** "`@Transactional` 包起来"：会造成**幽灵消息**和**连接池放大**，**严禁使用**——`@Transactional` 是本地事务，不是分布式事务
> - **方案 ③** "本地消息表"：业界标准，把消息当业务数据写进同一个 DB 事务 + 独立扫描补偿
> - **方案 ④** "RocketMQ 事务消息"：把本地消息表搬到 Broker 上，业务代码最简洁
>
> **事务消息的本质 = 把本地消息表从业务库搬到了 MQ 基础设施层**，没有银弹，只有权衡。
>
> **判断是否该用事务消息的 3 条标准**：
>
> 1. 有一段本地 DB 事务需要执行
> 2. 本地事务执行后必须**可靠地**通知下游（不能丢消息）
> 3. 下游处理**可以异步**（不要求和本地事务同步返回）
>
> 三条都满足，用事务消息；否则用普通消息 + 幂等，或者同步 RPC。不要为用而用——任何跨资源的协调都有成本。
