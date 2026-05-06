# Redis List 阻塞消费实现原理

> 主题：`BLPOP` / `BRPOP` / `BRPOPLPUSH` / `BLMOVE` 等阻塞命令在 Redis 单线程模型下是如何实现"阻塞且不阻塞其他客户端"的。

---

## 一、为什么需要阻塞消费

把 Redis List 当简易消息队列时，最朴素的消费方式是**轮询**：

```bash
while true:
    msg = LPOP queue
    if msg: handle(msg)
    else: sleep(100ms)   # 空队列时防止 CPU 100%
```

### 轮询方案的三大问题

| 问题 | 说明 |
|------|------|
| **实时性差** | sleep 间隔越大延迟越高；越小 CPU 越吃紧 |
| **无效 QPS 高** | 队列长时间空时，绝大多数 LPOP 都返回 nil，浪费 Redis CPU 和网络带宽 |
| **惊群难以控制** | 多消费者同时轮询时，大量空响应 |

所以 Redis 提供了阻塞版本：`BLPOP key [key ...] timeout` —— **队列为空时让客户端"挂起"，一有新消息立刻唤醒；timeout=0 表示无限等待**。

---

## 二、核心疑问：单线程怎么做到"阻塞"？

这是面试官必追问的点。

**直觉陷阱**：如果 Redis 主线程真的在 `BLPOP` 上 `sleep`，那它就不能处理其他客户端了，整个 Redis 就卡死了。显然不是这样。

**真正的实现**：**阻塞的是客户端连接（Client），不是 Redis 主线程**。

Redis 主线程用 **IO 多路复用（epoll）+ 事件驱动**，永远在循环中处理所有就绪的客户端。所谓"阻塞 BLPOP"，本质是：

> 把当前执行 BLPOP 的客户端挂到一个"等待列表"上，**不给它回响应**，主线程立刻回到事件循环去服务其他客户端；等到有人对该 key 执行 `LPUSH/RPUSH` 时，再把等待的客户端取出来、给它回一个响应。

可以理解为：**Redis 把"阻塞"的锅甩给了 TCP 连接**——客户端的 `read()` 系统调用自然会阻塞等待服务端响应，Redis 只需要"压着响应不发"就行了。

---

## 三、BLPOP 的完整执行流程

### 3.1 命令入口的分支逻辑

以 `BLPOP key timeout` 为例，源码在 `t_list.c` 的 `blpopCommand` → `blockingPopGenericCommand`：

```
blpopCommand(client c):
    // 1. 先当普通 LPOP 处理：看 key 是否存在且非空
    for key in keys:
        if key 存在且 list 非空:
            value = listPop(key)
            回复 client 该 value
            return   ← 直接走完，不阻塞

    // 2. 所有 key 都空 → 真正进入阻塞流程
    blockClient(c, BLOCKED_LIST, keys, timeout)
```

**关键点**：BLPOP **不总是阻塞**，只有当**所有指定的 key 都为空**时才会挂起。队列里有数据就是普通 LPOP 语义，O(1) 返回。

### 3.2 阻塞挂起：blockForKeys

真正挂起一个 client 做的事：

1. **标记 client 状态**：`c->flags |= CLIENT_BLOCKED`，`c->btype = BLOCKED_LIST`
2. **登记等待关系**：在 `db->blocking_keys` 这个字典里，为每个 key 挂上这个 client
   ```
   db->blocking_keys: {
       "queue1": [client_A, client_C],   // 有两个客户端在等 queue1
       "queue2": [client_B]
   }
   ```
3. **设置超时**：如果 `timeout > 0`，把 client 加到 `server.clients_timeout_table`（一个按到期时间排序的 rax 基数树），供事件循环做超时检测
4. **主线程继续干活**：函数返回，主线程回到事件循环，**不给这个 client 发任何响应**

此时这个客户端的 TCP 连接上 **没有响应到来**，客户端的 `recv()` 在内核里自然阻塞。从客户端看就是"hang 住了"，从服务端看它只是"在等待名单上"。

### 3.3 唤醒：谁来把 client 捞出来

有两条路径会唤醒阻塞中的 client：

| 触发路径 | 说明 |
|---------|------|
| **有新元素到达 key**（LPUSH/RPUSH/LINSERT 等） | 最常见，下面重点讲 |
| **超时到期** | `beforeSleep` 里的 `handleBlockedClientsTimeout()` 扫到期 |
| **客户端断连 / key 被删** | `unblockClient()` 清理 |

---

## 四、LPUSH 如何唤醒 BLPOP —— ready list 机制

### 4.1 关键数据结构

```c
// 每个 DB 上的两个字段
dict *blocking_keys;     // key → list of blocked clients（谁在等）
list *ready_keys;        // 本轮事件循环中有新数据的 key 列表（待处理）
```

### 4.2 LPUSH 的流程

```
lpushCommand:
    listPush(key, value)               // 真正把元素塞进 list
    signalKeyAsReady(db, key)          // 如果 blocking_keys 里有人在等这个 key
                                        // 把 key 加到 server.ready_keys 尾部
    notifyKeyspaceEvent(...)
```

**注意**：LPUSH 本身**不直接唤醒**等待的 client！它只是在 `ready_keys` 里打了个标记：**"这个 key 有货了，稍后处理"**，然后立刻返回。这是为了保持 LPUSH 本身的 O(1) 极速响应。

### 4.3 真正的唤醒时机：beforeSleep

Redis 每一轮事件循环结构如下：

```
while (!stop):
    beforeSleep()          ← ★ 关键！
    epoll_wait(...)        ← 阻塞等 IO 事件
    处理就绪的读/写事件
    afterSleep()
```

在 `beforeSleep()` 里有一个 `handleClientsBlockedOnKeys()`：

```
handleClientsBlockedOnKeys:
    while server.ready_keys 非空:
        取出一个 ready key
        找到 blocking_keys[key] 上等待的所有 client
        for client in waiting_clients:
            if list 还有元素:
                pop 一个元素
                给 client 回复 [key, value]
                unblockClient(client)   // 标记为非阻塞，恢复正常
            else:
                break   // 元素被前面的 client 抢光了
```

**先到先得**：等待队列按 FIFO 顺序，**先阻塞的 client 先拿到新元素**，避免饥饿。

### 4.4 时序图

```
T1  ClientA: BLPOP queue 0    → 挂到 blocking_keys["queue"]，不回包
T2  ClientB: BLPOP queue 0    → 挂到 blocking_keys["queue"]，不回包
T3  ClientC: LPUSH queue hi   → 元素入队 + ready_keys 标记 queue
                                  → LPUSH 本身立刻返回 (integer) 1
T4  主线程回到事件循环
T5  beforeSleep → handleClientsBlockedOnKeys
    → 看到 queue 在 ready_keys
    → 取 blocking_keys["queue"] 队头 = ClientA
    → pop 出 "hi"，回复 ClientA ["queue","hi"]，unblock A
    → queue 空了，ClientB 继续等
T6  ClientA 的 recv() 收到响应，应用层拿到消息
```

> 因此：**BLPOP 的唤醒不是毫秒级实时，而是"下一轮事件循环"级别**，极限情况下有微秒~亚毫秒级的延迟。对 99% 的业务场景没影响。

---

## 五、超时是怎么实现的

`BLPOP key 5` 表示最多等 5 秒。

### 5.1 超时结构

Redis 7.x 用 **Radix Tree（基数树）** `server.clients_timeout_table` 按到期时间戳排序所有阻塞 client：

```
clients_timeout_table (按 timeout 升序):
  [1699999000_ms] → clientA
  [1699999003_ms] → clientB
  [1699999010_ms] → clientC
```

### 5.2 超时扫描

`beforeSleep()` 里还有一个 `handleBlockedClientsTimeout()`：

```
handleBlockedClientsTimeout:
    now = 当前时间
    while table 首个元素.timeout <= now:
        client = pop(table)
        回复 client nil
        unblockClient(client)
```

因为 rax 首元素就是最早到期的，检查超时是 **O(log N) 起步 + O(K)（K 为本轮到期数）**，非常高效。

### 5.3 epoll_wait 本身也要设超时

如果集群空闲，主线程在 `epoll_wait` 上会睡着。Redis 会计算"距离下一个 timeout 还有多久"作为 `epoll_wait` 的超时参数，保证超时不会被无限推迟。

---

## 六、BRPOPLPUSH / BLMOVE：阻塞 + 原子转移

```bash
BRPOPLPUSH src dst timeout    # 6.2 起废弃
BLMOVE src dst LEFT|RIGHT LEFT|RIGHT timeout   # 6.2+ 推荐
```

语义：从 `src` 阻塞弹出，原子地 push 到 `dst`。常用于**可靠队列**（处理中队列）。

### 可靠消费模式

```
消费者:
  1. msg = BLMOVE task_queue processing_queue RIGHT LEFT 0
  2. handle(msg)
  3. 成功 → LREM processing_queue 1 msg
     失败 → 留在 processing_queue，由死信扫描器重新投递
```

**对比 BLPOP**：BLPOP 拿到消息后如果消费者宕机，消息就丢了。BLMOVE 把消息先放到"处理中队列"，即便消费者挂掉也有机会重试，类似 RocketMQ 的 invisibleTime / ACK 机制。

---

## 七、常见陷阱与注意事项

### 7.1 连接池不友好

BLPOP 会**长时间占用一个连接**（最长到 timeout 或有消息）。

| 问题 | 建议 |
|------|------|
| 连接池被耗尽 | 阻塞消费用**独立连接**，不走连接池 |
| 代理层超时 | Twemproxy、Codis 等代理可能在 N 秒无数据时强行断开，要把 BLPOP 的 timeout 设得小于代理超时 |
| 云厂商 ELB 空闲断连 | 同上，或用 TCP keepalive |

### 7.2 timeout=0 不是"永不超时"的好选择

生产环境建议 `timeout = 30` 秒而非 0：
- 避免连接异常挂死无法恢复
- 应用层循环调用 BLPOP，每 30 秒给一次健康检查/重连的机会

### 7.3 多消费者惊群 / 公平性

多个消费者 `BLPOP same_key 0` 时，**按阻塞先后顺序 FIFO 唤醒**，不会"惊群"唤醒所有人。Redis 源码里 `serveClientsBlockedOnListKey` 是遍历等待链表逐个处理的。

### 7.4 Cluster 模式的限制

`BLPOP key1 key2 key3` 多 key 等待时，**多个 key 必须在同一个 slot**（可用 `{tag}` hash tag），否则报 `CROSSSLOT` 错误。

### 7.5 持久化和主从切换

- BLPOP 自己**不写 AOF**（它不改数据）
- 真正产生数据变化的是 LPUSH 侧
- 主从切换瞬间，阻塞在旧主上的 client 会被断开，应用层要有重连重试

### 7.6 与 Redis 事务 / Lua 不兼容

- `MULTI/EXEC` 里执行 BLPOP，**不会阻塞**，等价于 LPOP（空就返回 nil）
- Lua 脚本里同理。阻塞命令本质上需要"让出执行权"，而事务和 Lua 要求原子不可中断，二者天然冲突

---

## 八、什么时候该用 List 阻塞消费，什么时候不该用

### ✅ 适合的场景

- 轻量任务队列，允许极小概率丢消息
- 单次消费、无重复消费需求
- 消费者数量固定、拓扑简单

### ❌ 不适合的场景，改用 Stream / RocketMQ / Kafka

| 需求 | List 缺陷 | 更好的选择 |
|------|----------|-----------|
| **消息 ACK / 重试** | 无 ACK 机制（BLMOVE 能补但繁琐） | Redis Stream 的 PEL / MQ |
| **消费者组广播** | List 是竞争消费，一条消息只能一个消费者拿 | Redis Stream 的 Consumer Group |
| **回溯消费 / 持久订阅** | pop 后消息就没了 | Redis Stream / Kafka |
| **百万级堆积** | 单 Redis 内存有限，易 OOM | Kafka / RocketMQ 磁盘存储 |
| **严格顺序 + 多分区** | 单 List 不能并发消费（顺序就是竞争） | RocketMQ 顺序消息 |

---

## 九、一句话面试答案

> **BLPOP 的阻塞是"客户端被挂起"而非"Redis 线程被阻塞"**。
> 其核心实现是：
> 1. 队列空时，把 client 挂到 `db->blocking_keys[key]` 等待列表，**不回响应**；
> 2. LPUSH 时只在 `server.ready_keys` 标记该 key；
> 3. 事件循环的 `beforeSleep` 阶段扫描 `ready_keys`，按 FIFO 唤醒最先到达的等待 client；
> 4. 超时由 `clients_timeout_table`（rax 基数树）统一管理，同样在 `beforeSleep` 处理。
>
> 这样 Redis 单线程既能服务大量阻塞客户端，又不影响其他命令的执行吞吐。

---

## 十、参考源码位置（Redis 7.x）

| 功能 | 文件 | 关键函数 |
|------|------|---------|
| BLPOP/BRPOP 命令入口 | `t_list.c` | `blpopCommand` / `blockingPopGenericCommand` |
| 挂起 client | `blocked.c` | `blockClient` / `blockForKeys` |
| LPUSH 通知 | `t_list.c` + `blocked.c` | `signalKeyAsReady` |
| 唤醒 | `blocked.c` | `handleClientsBlockedOnKeys` / `serveClientsBlockedOnListKey` |
| 超时管理 | `blocked.c` | `handleBlockedClientsTimeout` / `addClientToTimeoutTable` |
| 事件循环 | `server.c` | `beforeSleep` |
