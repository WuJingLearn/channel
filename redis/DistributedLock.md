# Redis 分布式锁：从错误示范到生产可用

> 面向面试与实战的系统笔记。Redis 分布式锁是面试出现率最高的题目之一，本文从最朴素的版本一路演化到生产可用，每一步都对应一个真实的坑。
> Redis 版本以 7.x 为基准。

---

## 〇、什么是分布式锁，为什么用 Redis

**分布式锁**用于在多个进程 / 多台机器之间协调对共享资源的互斥访问——比如同一个订单号防止被两个服务实例同时处理、定时任务在集群里只能由一个实例执行、库存扣减不能并发等。

实现分布式锁的常见方案有三类：

| 方案 | 一致性 | 性能 | 复杂度 | 适用场景 |
|------|------|------|------|------|
| **数据库**（唯一索引 / `SELECT ... FOR UPDATE`） | 强 | 低（行锁、连接池压力） | 低 | 低并发，已有 DB 依赖 |
| **Redis** | 弱（主从异步） | **高**（内存操作 + 单线程） | 中 | 高并发、可容忍极小概率失效 |
| **ZooKeeper / etcd**（基于共识算法） | 强 | 中 | 高 | 强一致、要求绝对正确 |

**Redis 的优势在于"快"**——内存操作 + 单线程模型，加锁解锁都是亚毫秒级，TPS 轻松到几万；缺点是主从异步复制带来的"理论上不安全"。本文围绕"Redis 怎么把这把锁尽量做对"展开。

它本质是利用 **`SET key value NX EX seconds` 的原子性**，在多节点之间抢一把"全局唯一的标识"。下面从最朴素的版本一路演化到生产可用。

---

## 一、V1：最简版本——`SETNX + EXPIRE`（错误示范）

最容易想到的写法：

```bash
SETNX lock:order:1001 "1"     # 抢锁：不存在才设置成功
EXPIRE lock:order:1001 30     # 设过期，防止持锁者宕机后死锁
# ... 业务 ...
DEL lock:order:1001           # 释放锁
```

**致命问题**：`SETNX` 和 `EXPIRE` 是两条命令、非原子。如果 `SETNX` 成功后客户端在 `EXPIRE` 之前**崩溃 / 网络中断 / 进程被 kill -9**，这把锁就**永久存在**——没有 TTL，谁都拿不走，整个系统挂死。

> 这是最经典的反面教材，面试问"用 Redis 做分布式锁"的时候，第一句就要先把这个版本否定掉。

---

## 二、V2：原子加锁——`SET ... NX EX`

Redis 2.6.12 起，`SET` 命令本身支持原子的 NX 和 EX 选项，把"设值 + 设过期"合并成一条命令：

```bash
SET lock:order:1001 <unique-value> NX EX 30
# OK     → 加锁成功
# (nil)  → 加锁失败，已被别人占
```

这条命令要么"设值 + 设 TTL"全部成功，要么全失败，从根本上消灭了 V1 的死锁问题。**所有用 Redis 实现分布式锁的代码，加锁这一步都必须用这个原子命令**。

---

## 三、V3：解锁要校验持锁者——避免误删别人的锁

光有 V2 还不够。考虑这个场景：

```
T0  线程 A 抢到锁，TTL 30s，开始执行业务
T35 业务还没跑完，但 TTL 已过，锁被 Redis 自动删除
T36 线程 B 抢到锁
T37 线程 A 业务终于跑完，执行 DEL lock:order:1001
    → 把线程 B 的锁删了！B 还在临界区里裸奔
T40 线程 C 也抢到锁……数据被两个线程同时改写
```

根因是 V2 的解锁是 `DEL key`，**根本没检查"这把锁是不是我的"**。

**修复**：加锁时把 value 设为本线程独一无二的标识（UUID + 线程 ID），解锁时先比对 value、相等才删。但**比对和删除必须原子**——否则又退化成 V1 的两步问题：刚比对完确认是自己的，下一行执行 DEL 之前 TTL 到期、别人抢到锁，DEL 还是删了别人。

唯一正确的做法是用 **Lua 脚本**让 Redis 在单线程模型里把"GET + 比对 + DEL"作为一条命令执行：

```lua
-- KEYS[1] = 锁 key, ARGV[1] = 当前线程的唯一标识
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('DEL', KEYS[1])
else
    return 0
end
```

Java 端配合 `RedisTemplate` 的写法：

```java
// 加锁
String token = UUID.randomUUID().toString() + ":" + Thread.currentThread().getId();
Boolean ok = redisTemplate.opsForValue().setIfAbsent(
    "lock:order:1001", token, Duration.ofSeconds(30));

// 解锁
DefaultRedisScript<Long> script = new DefaultRedisScript<>(
    "if redis.call('GET', KEYS[1]) == ARGV[1] then return redis.call('DEL', KEYS[1]) else return 0 end",
    Long.class);
redisTemplate.execute(script, Collections.singletonList("lock:order:1001"), token);
```

> **value 为什么要带"线程 ID"而不只是 UUID？** 防止同一个进程内的可重入锁场景下两个线程拿到同一个 UUID 出错。生产中常用 `UUID + ":" + threadId` 这种组合。

---

## 四、V4：业务比 TTL 久——锁过期续命（看门狗）

V3 解决了"误删"，但没解决"业务还没跑完锁就过期"。如果业务平均耗时 10s 偶尔会卡到 60s，TTL 再怎么调都两难：调短容易过期失锁；调长一旦持锁线程崩了，其他人要等很久才能抢锁。

正确的做法是**自动续期**：持锁线程启动一个后台任务，每隔 `TTL/3` 执行一次"续命脚本"，如果锁还在自己手里就把 TTL 重置回原值。这就是 **Redisson 看门狗（WatchDog）** 的核心思想。

续命也要用 Lua 保证原子（"先确认是我的锁、再 EXPIRE"）：

```lua
-- KEYS[1] = 锁 key, ARGV[1] = 持锁标识, ARGV[2] = 新的 TTL（毫秒）
if redis.call('GET', KEYS[1]) == ARGV[1] then
    return redis.call('PEXPIRE', KEYS[1], ARGV[2])
else
    return 0
end
```

### Redisson 的默认行为

- `redissonClient.getLock(key).lock()`——**不传 TTL**就启用看门狗，初始 TTL 30s，每 10s 续期一次。
- `lock(timeout, TimeUnit)`——**显式传 TTL** 就**关闭**看门狗，到期就过期（避免续命永不释放）。
- 客户端断开 / 进程崩溃后看门狗也停了，锁会在最多一个 TTL 后自然释放，不会死锁。

> **如何记忆 Redisson 行为**：传了 TTL 就"信任你的设置"不续期，没传就"我替你管"启动看门狗。

---

## 五、V5：主从切换丢锁——Redlock 的争议

到 V4 为止单机 Redis 上的锁已经够用了。但生产上常用的**主从架构 + 哨兵**有个根本问题：**主从复制是异步的**。

```
T0  客户端 A 在主节点抢到锁
T1  主节点还没把这条 SET 同步到从节点就宕机
T2  哨兵把从节点提升为新主——新主上根本没这把锁
T3  客户端 B 来抢锁，成功！现在 A 和 B 同时持有"同一把锁"
```

这是 Redis 单点分布式锁的**理论缺陷，无法靠看门狗或解锁脚本规避**。

### Redlock 算法

Antirez 为此提出了 **Redlock 算法**：部署 N（通常 5）个**完全独立的主节点**（不是主从，是各自独立），客户端依次向它们 `SET NX EX`，**只有在 N/2+1 个节点上都成功**且总耗时小于 TTL，才算加锁成功；解锁时向所有 N 个节点都发 DEL。

加锁流程的关键细节：

1. 取当前毫秒时间戳 `T1`。
2. 依次向 5 个节点发 `SET NX EX`，**每个请求都设较短的超时**（比 TTL 小一两个数量级），避免某个节点挂掉拖死整体。
3. 取当前毫秒时间戳 `T2`，计算总耗时 `T2 - T1`。
4. 加锁成功的条件是：**成功节点数 ≥ N/2+1** **且** `T2 - T1 < TTL`。
5. 锁的真实有效期 = TTL - (T2 - T1) - 时钟漂移补偿。
6. 任一条件不满足 → 向所有 5 个节点发 DEL 释放，宣告失败。

### Redlock 的代价和争议

- **代价**：要 5 套独立 Redis；每次加锁要 5 次网络 RTT；客户端时钟需要大致同步（依赖系统时钟在 TTL 内不能跳跃）。
- **争议**：分布式领域大牛 **Martin Kleppmann 公开质疑** Redlock 在 GC 停顿、时钟漂移这类故障模型下并不安全，强一致场景应使用**基于共识算法的方案（ZooKeeper、etcd）**。Antirez 写了长文回应，社区至今没有定论。

### 实践结论

- 99% 的业务（限流、防重复提交、定时任务防并发）→ **单节点 Redis + Redisson 看门狗**就够了，主从切换偶发"双持锁"的风险可以靠业务幂等兜底。
- 真要强一致（金额扣减、库存绝对不超卖）→ **不要用 Redis 锁**，用 **ZooKeeper / etcd** 的有序临时节点，或者用数据库唯一索引 / 乐观锁兜底。

---

## 六、可重入锁

### 6.1 为什么需要可重入

可重入锁解决的核心问题是：**同一个持有者在已经拿到锁的情况下，调用链下游再次请求同一把锁，不会自己把自己锁死**。如果用不可重入锁，第二次 `SET NX` 会直接失败 → 自己等自己 → 死锁。

判断标准很简单：**只要"持锁后的调用链中可能再次出现 `lock(同一个 key)`"——不管是显式调用、AOP 切面还是递归——就必须用可重入锁**。

### 6.2 典型使用场景

#### 场景 1：递归调用（最教科书的本意）

```java
RLock lock = redisson.getLock("tree:" + nodeId);

void traverse(Node node) {
    lock.lock();
    try {
        process(node);
        for (Node child : node.children) {
            traverse(child);   // 递归 → 再次 lock 同一把锁
        }
    } finally {
        lock.unlock();
    }
}
```

#### 场景 2：同一事务内的多个方法都加锁（最常见的实战）

业务代码里底层方法为了独立可用通常都自带加锁，上层方法不知情也加同一把锁，**调用一深就重入**：

```java
@Service
class OrderService {
    @Transactional
    public void placeOrder(Long userId) {
        RLock lock = redisson.getLock("user:" + userId);
        lock.lock();
        try {
            checkInventory(userId);   // 内部又要拿同一把锁
            deductBalance(userId);    // 内部又要拿同一把锁
            createOrder(userId);
        } finally {
            lock.unlock();
        }
    }

    private void checkInventory(Long userId) {
        RLock lock = redisson.getLock("user:" + userId);
        lock.lock();    // ← 重入
        try { /* ... */ } finally { lock.unlock(); }
    }
}
```

#### 场景 3：Spring AOP / 自定义注解嵌套

```java
@DistributedLock(key = "#userId")
public void outer(Long userId) {
    inner(userId);   // inner 也带 @DistributedLock(key="#userId")
}

@DistributedLock(key = "#userId")
public void inner(Long userId) { /* ... */ }
```

切面是无脑加锁的，不知道外层是不是已经加过——**必须用可重入锁兜底**，否则注解一加就死锁。

#### 场景 4：状态机 / 工作流的多步操作

```java
RLock lock = redisson.getLock("order:state:" + orderId);
lock.lock();
try {
    Order order = load(orderId);
    if (order.canPay()) {
        markPaying(orderId);   // 内部加锁
        callPayment();          // 远程调用
        markPaid(orderId);     // 内部加锁
    }
} finally {
    lock.unlock();
}
```

### 6.3 什么时候**不需要**可重入

| 场景 | 推荐方案 |
|------|---------|
| 防重复提交按钮 | `SET NX EX` + Lua 解锁，业务短无嵌套 |
| 缓存击穿互斥重建 | 普通 `SET NX EX`，单次操作 |
| 限流 | 不是锁的语义，用 ZSet 滑动窗口 |
| 强一致金融场景 | 可重入与否不解决主从切换丢锁，要用 ZooKeeper |

### 6.4 实现思路：状态必须放在 Redis，不能放在客户端

可重入需要记录"持有者是谁"和"重入了几次"两个状态。一个常见的错误想法是把它们放在 **JVM 内存**里：

```java
// ❌ 错误方案
volatile Thread exclusiveThread;
int reentrantCount;
```

这套方案有三个致命问题：

1. **跨进程失效**：状态只在本进程内存里，远程调用进入另一个 JVM 时，新进程的 `exclusiveThread = null`，根本不知道"自己已经持有锁"，会去 Redis 重新抢锁失败。
2. **本地状态和 Redis 操作不原子**：本地变量的读写与 Redis 加解锁是两步，存在 race condition。
3. **解锁计数判断没法写进 Lua**：客户端做计数判断的瞬间可能切换上下文，状态错乱。

> **关键认知**：分布式锁的"持有者身份"必须是**全局唯一真相源**，只能放在 Redis 上，所有客户端进程都从 Redis 看到同一份状态。

### 6.5 Redisson 的实现：Hash 结构 + Lua 原子

Redisson 把"持有者"和"重入次数"全部存在 Redis 的 **Hash 结构**里：

```
key:    lock:order:1001
type:   Hash
value:  { "uuid:threadId" : 重入次数 }
```

> **持有者标识为什么是 `uuid:threadId`？** `uuid` 标识进程（每个 JVM 启动时生成一个），`threadId` 标识进程内的线程。组合起来才能在跨进程场景下唯一区分一个"逻辑持有者"。

#### 加锁 Lua

```lua
-- KEYS[1] = 锁 key, ARGV[1] = TTL 毫秒, ARGV[2] = 持有者标识 (uuid:threadId)

-- 情况 1：key 不存在，第一次加锁
if redis.call('exists', KEYS[1]) == 0 then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)   -- 把自己加进去，计数 = 1
    redis.call('pexpire', KEYS[1], ARGV[1])
    return nil  -- 加锁成功
end

-- 情况 2：key 存在且持有者是我，重入
if redis.call('hexists', KEYS[1], ARGV[2]) == 1 then
    redis.call('hincrby', KEYS[1], ARGV[2], 1)   -- 重入计数 +1
    redis.call('pexpire', KEYS[1], ARGV[1])      -- 顺便续 TTL
    return nil  -- 重入成功
end

-- 情况 3：key 存在但持有者不是我，加锁失败
return redis.call('pttl', KEYS[1])  -- 返回剩余 TTL，调用方决定怎么等
```

#### 解锁 Lua（计数归零才真删）

```lua
-- KEYS[1] = 锁 key, ARGV[1] = TTL 毫秒, ARGV[2] = 持有者标识

-- 不是我的锁，直接报错
if redis.call('hexists', KEYS[1], ARGV[2]) == 0 then
    return nil
end

-- 计数 -1
local counter = redis.call('hincrby', KEYS[1], ARGV[2], -1)
if counter > 0 then
    redis.call('pexpire', KEYS[1], ARGV[1])  -- 还有重入层级，续命
    return 0
else
    redis.call('del', KEYS[1])               -- 计数归零，真正释放
    return 1
end
```

整套逻辑全在 Lua 里执行，借 Redis 单线程模型天然原子，全局可见。

### 6.6 自研方案 vs Redisson 方案对比

| 维度 | 本地状态方案 | Redisson Hash 方案 |
|------|-------------|------------------|
| 状态存放位置 | JVM 内存 | Redis（全局唯一真相源） |
| 跨进程重入 | ❌ 失效 | ✅ 正常工作 |
| 计数判断与 Redis 操作 | 分离，存在 race | Lua 内一气呵成，原子 |
| 客户端崩溃后状态 | 丢失 | TTL 过期自然清理 |
| 是否需要 `synchronized` 兜底 | 需要 | 完全无锁 |

**结论**：可重入分布式锁请直接用 Redisson 的 `RLock`，不要自己造轮子。

---

## 七、完整生产实践清单

实现一把生产可用的 Redis 分布式锁，必须同时满足：

1. **加锁原子**：`SET key uniqueValue NX EX seconds`，绝不分两步写。
2. **value 带身份标识**：`UUID + 线程 ID`，避免误删别人的锁。
3. **解锁用 Lua**：`GET + 比对 + DEL` 一气呵成。
4. **TTL 必须有**：兜底防客户端崩溃后死锁，时长按业务 P99 估算。
5. **业务可能超 TTL → 续命**：用 Redisson 看门狗，或自己写定时任务执行续命 Lua。
6. **加锁失败要可重试 / 可放弃**：`tryLock(waitTime, leaseTime)` 模式，避免无限阻塞。
7. **明确强一致需求**：能容忍极小概率失效 → 单节点 + 看门狗；不能容忍 → ZooKeeper/etcd。
8. **业务层做幂等**：分布式锁是"防并发"的，不是"防重复执行"的——网络重试、超时重发都可能让同一操作走两遍，业务逻辑必须自己幂等。

---

## 八、高频面试追问

**为什么解锁不能直接 DEL？**

如果你的锁因 TTL 到期被自动释放，紧接着别人抢到了同一个 key 的锁，此时你执行 DEL 会把别人的锁删掉。所以解锁必须"先校验是不是我的"，且校验和删除必须原子（Lua）。

**TTL 设置多长合适？**

按**业务 P99 耗时的 1.5~2 倍**起步，配合看门狗续命兜底。太短容易业务没跑完锁就过期；太长一旦客户端真崩了，其他人要等更久。

**主从架构下 Redis 分布式锁绝对安全吗？**

不绝对。主从是异步复制，主节点抢锁成功后还没同步到从节点就宕机，从节点升主后这把锁就消失了，可能出现"两个客户端同时持锁"。强一致场景请用 ZooKeeper/etcd 或 Redlock（Redlock 自身也有争议）。

**可重入怎么实现？**

value 不再是单纯的 token，而是 **Hash 结构** `{token: 重入次数}`。加锁时如果 key 已存在但 token 是自己，就 `HINCRBY` 计数 +1；解锁时 -1，归零才真正 DEL。Redisson 的可重入锁就是这么做的，整套逻辑也是 Lua 实现。

**为什么不直接用 ZooKeeper？**

性能差距巨大。Redis 加锁是亚毫秒级、TPS 几万；ZooKeeper 因为要走 ZAB 协议落盘 + 多数派确认，单次加锁通常 10ms+ 量级、TPS 几千。99% 的业务不需要 ZK 那种强一致，Redis 已经够用；只有金额、库存这类绝对不能错的场景才值得付出 ZK 的性能代价。

**看门狗为什么要每 TTL/3 续一次？**

设 TTL=30s，续期间隔 10s，相当于**给网络抖动 / GC 停顿留 2 次重试机会**。如果首次续期因为网络抖动失败，10s 后还能再试一次，再失败还有第三次，三次都失败说明网络真的瘫了，这时候锁过期释放也是合理的。如果间隔等于 TTL，一次失败就直接丢锁，过于敏感。

---

## 九、和其他笔记的关联

- 本文聚焦"分布式锁"这一具体应用；Redis String 类型本身的实现（int / embstr / raw 三种编码、SDS）请见 `DataStructures.md` 第一章。
- 涉及主从切换、哨兵故障转移的细节（为什么主从是异步、新主选举怎么进行）请见 `ClusterArchitecture.md`。
- 限流和分布式锁是 String 的两大实战，固定窗口限流的实现和缺陷请见 `DataStructures.md` 1.5 节，滑动窗口（基于 ZSet）请见 5.5 节。
