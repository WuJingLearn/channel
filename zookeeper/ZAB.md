# ZAB 协议详解：选举、同步、一致性保证

> 面向面试和工程实战的系统笔记。ZAB（ZooKeeper Atomic Broadcast）是 ZooKeeper 专用的共识协议，是理解 ZooKeeper 高可用和强一致的核心。
> 本文按 "概念 → 选举 → 同步 → 广播 → 一致性证明 → 故障场景" 的顺序展开。
> 共识算法的整体定位和与 Raft / Paxos 的对比，请配套阅读 `ConsensusAlgorithm.md`。

---

## 一、ZAB 是什么

### 1.1 一句话定义

**ZAB（ZooKeeper Atomic Broadcast）** 是 ZooKeeper 专用的**支持崩溃恢复的原子广播协议**——它保证：

1. **顺序一致性**：所有事务按照同一个全局顺序在所有节点上被执行
2. **崩溃恢复**：Leader 宕机后能自动选出新 Leader 并恢复数据，已提交的事务永不丢失
3. **原子性**：每个事务要么在所有节点上提交，要么在所有节点上回滚

### 1.2 ZAB 不是 Paxos

虽然 ZAB 借鉴了 Paxos 的"多数派"和"提案编号"思想，但它**不是 Paxos 的实现**：

| 维度 | Paxos | ZAB |
|------|-------|-----|
| 目标 | 解决"对一个值达成共识"的通用问题 | 解决"主备复制 + 崩溃恢复"的具体问题 |
| Leader | 多 Proposer 并存 | 单 Leader |
| 顺序 | 不强制 | 严格按 zxid 顺序 |
| 应用层语义 | 通用 | 专为 ZooKeeper 数据树服务 |

ZAB 更接近 Raft——**单 Leader + 多数派 + 任期号**，但 ZAB 比 Raft 早提出 6 年。

### 1.3 四种节点角色（ServerState）

ZooKeeper 节点在运行时处于以下四种状态之一：

| 状态 | 含义 | 行为 |
|------|------|------|
| **LOOKING** | 寻找 Leader | 处于选举过程中，不对外服务 |
| **LEADING** | 当前任期的 Leader | 处理写请求，广播事务 |
| **FOLLOWING** | 跟随 Leader 的 Follower | 参与投票、同步数据、处理读请求 |
| **OBSERVING** | 观察者 | 同步数据、处理读请求，**不参与投票** |

**Observer 的作用**：扩展读吞吐而不影响写性能。投票节点越多，写延迟越高（要等更多 ACK），但 Observer 不参与投票，所以加 Observer 只增读不增写延。

### 1.4 zxid：ZAB 的核心数据结构

zxid 是 64 位整数，结构如下：

```
┌────────────────────────┬────────────────────────┐
│   epoch（高 32 位）     │   counter（低 32 位）  │
└────────────────────────┴────────────────────────┘
   选举轮次（任期号）        本任期内的事务序号
```

- **epoch**：每次选出新 Leader 时 +1，对应 Raft 的 term
- **counter**：本任期内事务编号，每次提交事务 +1，新任期重置为 0

**性质**：

- **全局严格单调递增**：epoch 总是新选出的 Leader 比旧的大，counter 在同 epoch 内递增
- **唯一**：(epoch, counter) 二元组在整个集群历史里唯一
- **可比较**：两个 zxid 大小直接对应"事件先后顺序"

```
示例：
  zxid = 0x100000005
       = epoch=1, counter=5
       → 表示第 1 个 Leader 任期内的第 5 个事务

  zxid = 0x200000001
       = epoch=2, counter=1
       → 表示第 2 个 Leader 任期内的第 1 个事务

  比较：0x200000001 > 0x100000005
       → epoch=2 的事务一定排在 epoch=1 的所有事务之后
```

**关键设计原因**：epoch 放高位让"换届"成为最高优先级——新 Leader 任期的第一条事务，永远比旧 Leader 任期的最后一条事务"更新"。

---

## 二、三大阶段总览

ZAB 把节点的整个生命周期分成三个循环切换的阶段：

```
                        崩溃 / 网络分区
              ┌─────────────────────────────────┐
              ▼                                 │
     ┌──────────────┐   ┌──────────────┐   ┌──────────────┐
     │  选举阶段     │──▶│  恢复阶段    │──▶│  广播阶段    │
     │  Election     │   │  Recovery    │   │  Broadcast   │
     │ (LOOKING)     │   │ (同步数据)    │   │ (正常服务)   │
     └──────────────┘   └──────────────┘   └──────────────┘
```

| 阶段 | 触发条件 | 核心任务 | 涉及协议 |
|------|---------|---------|---------|
| **Election（选举）** | 集群启动 / Leader 失联 | 选出新 Leader | FastLeaderElection |
| **Recovery（恢复）** | 选举结束后 | 协商新 epoch + 同步数据 | Discovery + Synchronization |
| **Broadcast（广播）** | 同步完成后 | 处理客户端写请求 | 两阶段提交（PROPOSAL → ACK → COMMIT）|

每个阶段的进入和退出都有严格条件——**任何阶段不达标，都不能进入下一阶段**，这是 ZAB 安全性的基础。

---

## 三、阶段一：Leader 选举（FastLeaderElection）

### 3.1 触发时机

集群进入 LOOKING 状态、需要发起选举的两个时机：

1. **集群启动**：所有节点初始都是 LOOKING
2. **Leader 失联**：Follower 在 `tickTime * syncLimit` 时间内（默认 2s × 5 = 10s）没收到 Leader 心跳

### 3.2 投票结构

每个节点广播自己的"选票"，结构是 `(myid, zxid, electionEpoch, peerEpoch)`：

| 字段 | 含义 |
|------|------|
| **myid** | 节点 ID（在 `myid` 配置文件里写死，集群内唯一）|
| **zxid** | 节点见过的最大 zxid |
| **electionEpoch** | 选举轮次（每次发起选举 +1，防止旧选票干扰）|
| **peerEpoch** | 候选 Leader 的 epoch（实际就是 zxid 的高 32 位）|

### 3.3 投票规则（PK 逻辑）

收到别人的选票后，每个节点要判断"应该投票给谁"。比较自己的票和对方的票，按以下顺序 PK：

```
PK(我的票, 对方的票)：
  1. 比 electionEpoch（选举轮次）
     - 对方更大 → 切换到对方的轮次，重新投票
     - 对方更小 → 忽略对方的票（旧选票）
     - 相等 → 进入下一步比较

  2. 比 peerEpoch（候选 Leader 的任期）
     - 对方更大 → 改投对方
     - 对方更小 → 坚持自己
     - 相等 → 进入下一步

  3. 比 zxid（事务历史）
     - 对方更大 → 改投对方
     - 对方更小 → 坚持自己
     - 相等 → 进入下一步

  4. 比 myid（节点 ID）
     - 对方更大 → 改投对方
     - 对方更小 → 坚持自己
```

**为什么这个顺序**：

- **electionEpoch 优先**：保证旧选票不会污染新一轮选举
- **peerEpoch 次之**：保证新任期的数据优先于旧任期（zxid 高 32 位）
- **zxid 再次**：保证拥有最新已提交日志的节点优先当选（这是数据安全的核心）
- **myid 兜底**：当一切相同时，用配置 ID 打破对称（避免活锁）

> **关键认知**：第 3 步"比 zxid"是 ZAB 数据安全的核心——**保证选出的 Leader 一定包含所有已提交的事务**，和 Raft 的"选举限制"等价。

### 3.4 收敛条件：多数派认同同一个候选人

每个节点维护一张"投票箱"（HashMap），记录每个节点投了谁。每收到一张新选票就更新投票箱，然后统计：

```
收敛条件：投票箱里"投给同一个候选人"的票数 ≥ ⌊N/2⌋ + 1
```

一旦满足收敛条件，被选中的节点状态变 LEADING，其他节点变 FOLLOWING（或 OBSERVING）。

### 3.5 完整选举时序示例

假设 5 节点集群（N1~N5），myid 分别为 1、2、3、4、5，所有节点 zxid 相同（都是 0x100000010）。Leader 突然宕机，所有节点同时进入 LOOKING：

```
T0  所有节点 (N1~N5) 进入 LOOKING，electionEpoch+1
    各自先投自己：
    N1 → vote(myid=1, zxid=0x10010)
    N2 → vote(myid=2, zxid=0x10010)
    N3 → vote(myid=3, zxid=0x10010)
    N4 → vote(myid=4, zxid=0x10010)
    N5 → vote(myid=5, zxid=0x10010)

T1  所有节点广播自己的票，并接收其他节点的票

T2  PK 阶段：每个节点都做同样的判断
    - electionEpoch、peerEpoch、zxid 都相同
    - 比 myid → N5 最大
    → 所有节点都改投 N5

T3  N5 收到自己 + 4 张投自己的票 = 5 票（≥3 多数派）
    其他节点也收到 5 张都投 N5 的票

T4  N5 → LEADING
    N1~N4 → FOLLOWING

整个过程通常在 200ms~1s 内完成。
```

### 3.6 选举的两个反直觉细节

#### 细节 1：myid 大不代表能当 Leader

很多文章说"myid 越大越容易当选"——这只在**所有节点 zxid 相同**的极端场景成立。生产环境中：

```
N1: zxid=0x10020 (最新)，myid=1
N5: zxid=0x10010 (落后)，myid=5

PK 结果：N1 胜出（zxid 是第 3 步，myid 是第 4 步，先满足谁谁赢）
```

**所以最终是"数据最新者优先，相同时 myid 兜底"**。这正是数据安全的保证——绝不能让数据落后的节点当 Leader。

#### 细节 2：选举不一定一轮收敛

如果集群处于网络抖动状态，某些节点收到的票分散，可能要打多轮。每轮 electionEpoch +1，旧轮次的选票会被自动丢弃。这就是 `electionEpoch` 字段的意义——**让旧选票自然失效**，避免活锁。

### 3.7 LOOKING 状态下不对外服务

**重要**：选举期间整个集群对外不可用——所有节点处于 LOOKING，既不能写也不能读。这是 ZooKeeper 选 CP 的典型表现：**为了保证一致性，宁可短暂不可用**。

生产环境中选举耗时通常 < 1s（小集群）到几秒（大集群跨机房）。这就是 ZooKeeper 故障转移的真实窗口。

---

## 四、阶段二：恢复阶段（Discovery + Synchronization）

选举只是选出了一个"准 Leader"——它的数据可能不完整、Follower 之间数据可能不一致。**恢复阶段的目标是把整个集群的数据状态对齐到一个可服务的基线**，然后才允许进入广播阶段。

恢复阶段实际上是两个子阶段串行执行：**Discovery（发现）** 和 **Synchronization（同步）**。

### 4.1 Discovery 阶段：协商新 epoch

#### 目标

让准 Leader 收集所有 Follower 的 `lastZxid`，确定自己的数据视图，并协商出一个**全集群唯一、严格大于所有历史 epoch 的新 epoch**。

#### 流程

```
1. Follower → Leader：发送 FOLLOWERINFO，携带自己的 lastZxid（最大事务 ID）

2. Leader 收集多数派 Follower 的 lastZxid 后：
   newEpoch = max(所有 Follower 的 lastZxid 的 epoch 部分) + 1

3. Leader → Follower：发送 LEADERINFO(newEpoch)

4. Follower 校验 newEpoch > 自己当前的 acceptedEpoch
   - 通过 → 持久化 acceptedEpoch = newEpoch，回 ACKEPOCH(自己的 lastZxid + 历史日志范围)
   - 不通过 → 拒绝并重新进入选举

5. Leader 收集多数派 ACKEPOCH 后，进入 Synchronization 阶段
```

#### 为什么要重新协商 epoch

防止"幽灵 Leader"——假设旧 Leader 因网络分区被隔离，现在网络恢复了它带着旧 epoch 回来，新 epoch 一定大于它，**它的所有写请求都会被拒绝**，从而避免脑裂。

```
T0  N1 是旧 Leader，epoch=5，被网络分区隔离
T1  剩下 N2~N5 重新选举，N2 当选，新 epoch=6
T2  N1 网络恢复，仍以为自己是 Leader（epoch=5）
T3  N1 给客户端处理写请求，广播 PROPOSAL(zxid=0x500000099)
T4  N2~N5 看到这条 PROPOSAL 的 epoch=5 < 自己的 acceptedEpoch=6
    → 直接拒绝
T5  N1 收不到多数派 ACK，自己也意识到失联，转 LOOKING 状态
```

这就是 epoch 单调递增 + acceptedEpoch 校验的双重防护。

### 4.2 Synchronization 阶段：同步数据到 Follower

#### 目标

把 Leader 的事务日志同步到每个 Follower，**让所有 Follower 的数据状态追平 Leader**。

#### 关键参数

Leader 维护两个边界值：

- **minCommittedLog**：自己事务日志中最早一条已提交事务的 zxid
- **maxCommittedLog**：自己事务日志中最晚一条已提交事务的 zxid

这两个值定义了 Leader 的"可同步差量区间"。

#### 四种同步策略

Leader 收到 Follower 的 `lastZxid` 后，根据它落在哪个区间决定同步策略：

```
       minCommittedLog              maxCommittedLog
            │                            │
   ─────────┼────────────────────────────┼─────────▶  zxid 时间线
        ❶   │             ❷              │      ❸
       区间 A           区间 B            区间 C  (Follower 比 Leader 还新)

  ❶ Follower lastZxid < minCommittedLog → SNAP（全量快照）
  ❷ minCommittedLog ≤ Follower lastZxid ≤ maxCommittedLog → DIFF（增量同步）
  ❸ Follower lastZxid > maxCommittedLog → TRUNC（让 Follower 截断）
  ❹ 区间 ❷ 内但 Follower 有 Leader 没有的脏日志 → TRUNC + DIFF（先截断再增量）
```

#### 策略 1：DIFF（增量同步，最常见）

**触发条件**：Follower 的 lastZxid 在 `[minCommittedLog, maxCommittedLog]` 区间内，且没有脏日志。

**做法**：Leader 把 `(lastZxid, maxCommittedLog]` 之间的事务一条条发给 Follower 重放。

```
Leader → Follower : DIFF
Leader → Follower : PROPOSAL(zxid=0x500000005)
Leader → Follower : COMMIT(zxid=0x500000005)
Leader → Follower : PROPOSAL(zxid=0x500000006)
Leader → Follower : COMMIT(zxid=0x500000006)
Leader → Follower : NEWLEADER(newEpoch)
Follower → Leader : ACK
Leader → Follower : UPTODATE   ← 同步完成，可以对外服务
```

**适用场景**：Follower 短暂掉线（几秒~几分钟）后重连，Leader 上还保留着这段差量日志。

#### 策略 2：TRUNC（截断，处理脏日志）

**触发条件**：Follower 的 lastZxid > Leader 的 maxCommittedLog——意味着 **Follower 上有 Leader 没提交过的事务**（俗称"幽灵日志"）。

**做法**：让 Follower 把超出 Leader 提交点的日志**全部丢弃**。

```
场景重现：
T0  旧 Leader L 在 zxid=0x500000010 写了一个 PROPOSAL，Follower F 持久化了
T1  L 还没收到多数派 ACK 就宕机
T2  集群重新选主，N3 当选（N3 没收到 0x500000010）
T3  F 上线，发现自己的 lastZxid=0x500000010 > N3 的 maxCommittedLog=0x50000000F
T4  N3 → F : TRUNC(targetZxid=0x50000000F)
T5  F 把 0x500000010 这条事务从日志中删除
```

**为什么可以丢**：因为这条日志没有被多数派 ACK，根据 Quorum 性质，它从来没被"提交"过——丢弃它不会让任何客户端看到的"已成功"事务消失。

#### 策略 3：SNAP（全量快照）

**触发条件**：Follower 的 lastZxid < Leader 的 minCommittedLog——Follower 落后太多，Leader 上的差量日志都不够覆盖。

**做法**：Leader 把自己的**整个内存数据树（DataTree）序列化成快照**，发给 Follower。Follower 用快照覆盖自己的数据，然后接收后续的增量日志。

**适用场景**：

- Follower 长时间下线（几小时~几天）后重连
- 新加入集群的节点（从空状态开始）

**代价**：传输大、耗时长（GB 级数据可能要几分钟），同步期间 Follower 不可用。

#### 策略 4：TRUNC + DIFF（截断 + 增量）

**触发条件**：Follower 的 lastZxid 在区间内，但**有脏日志**（自己有 Leader 没有的事务）。

**做法**：先 TRUNC 把脏日志切掉，再 DIFF 同步缺失部分。

### 4.3 同步的提交时机：NEWLEADER 消息

Leader 把所有差量同步完后，发一条 **NEWLEADER** 消息，包含 newEpoch。Follower 收到后：

1. 持久化 currentEpoch = newEpoch
2. 把刚同步过来的所有事务 apply 到状态机（DataTree）
3. 回 ACK

Leader 收到**多数派 ACK 后**，认为同步完成，进入广播阶段，发 **UPTODATE** 通知 Follower 可以对外服务。

### 4.4 完整恢复阶段时序

```
准 Leader N3，Follower N1, N2, N4, N5（多数派 = 3）

T0  N3 当选，进入 Recovery
T1  各 Follower → N3 : FOLLOWERINFO(lastZxid)
    N1.lastZxid = 0x40000000A
    N2.lastZxid = 0x40000000A
    N4.lastZxid = 0x40000000F   (有脏日志)
    N5.lastZxid = 0x300000005   (落后很多)

T2  N3 收集多数派后，newEpoch = max(epoch=4) + 1 = 5
T3  N3 → 各 Follower : LEADERINFO(newEpoch=5)
T4  各 Follower : 持久化 acceptedEpoch=5，回 ACKEPOCH

T5  N3 决定同步策略：
    N1: DIFF (lastZxid 在区间内，无脏日志)
    N2: DIFF
    N4: TRUNC + DIFF (要切掉 0x40000000B~0x40000000F)
    N5: SNAP (落后到上个 epoch)

T6  并行执行同步...

T7  N3 → 各 Follower : NEWLEADER(epoch=5)
T8  各 Follower 应用到状态机，回 ACK
T9  N3 收到多数派 ACK
T10 N3 → 各 Follower : UPTODATE
T11 进入广播阶段，对外服务
```

### 4.5 恢复阶段的核心保证

经过 Discovery + Synchronization：

1. ✅ **新 epoch 严格大于所有历史 epoch**：杜绝幽灵 Leader
2. ✅ **多数派 Follower 的数据追平 Leader**：保证后续广播的多数派一定包含全部已提交日志
3. ✅ **脏日志被清理**：被 TRUNC 切掉的日志一定不是已提交的（因为没拿到多数派 ACK）

只有这三点都达成，集群才进入广播阶段——这是 ZAB 一致性的根基。

---

## 五、阶段三：消息广播（Broadcast）

恢复阶段结束后，集群进入正常服务状态。客户端的写请求由 Leader 接收并通过 ZAB 广播到所有 Follower。

### 5.1 整体流程：两阶段提交

ZAB 广播阶段是一个**简化版的两阶段提交（2PC）**：

```
阶段 1：PROPOSE（提议）
   Client → Leader     : 写请求 (e.g., create /foo "bar")
   Leader              : 生成 zxid（counter+1），写本地事务日志
   Leader → Follower 们 : PROPOSAL(zxid, 事务内容)
   Follower 们          : 写本地事务日志，回 ACK
   Leader              : 收到多数派 ACK 后认为可以提交

阶段 2：COMMIT（提交）
   Leader → Follower 们 : COMMIT(zxid)
   Leader 和 Follower 们 : 把事务 apply 到内存数据树
   Leader → Client     : 返回 OK
```

### 5.2 详细时序

5 节点集群，N1 是 Leader：

```
T0  Client → N1   : create /foo "bar"
T1  N1            : 分配 zxid=0x500000020，写本地事务日志（fsync 落盘）
T2  N1 → N2~N5    : PROPOSAL(zxid=0x500000020, op=create, ...)
T3  N2、N3        : 持久化日志成功，回 ACK
T4  N1            : 收到 N1+N2+N3 = 3 个 ACK（≥多数派）→ 认为可提交
T5  N1            : 自己 apply 到 DataTree（创建 /foo 节点）
T6  N1 → N2~N5    : COMMIT(zxid=0x500000020)
T7  N1 → Client   : OK
T8  N2~N5         : 收到 COMMIT 后 apply 到 DataTree
```

**关键观察**：

- T4~T5 之间，**Leader 已认定提交，但 Follower 还没收到 COMMIT**
- 这个窗口里如果有客户端从 Follower 上读 `/foo`，**读不到**（DataTree 里还没有）
- 这就是为什么 ZooKeeper 的读是**顺序一致性而非线性一致性**——客户端可能读到稍旧的数据，需要 `sync()` 命令强制 Follower 追平

### 5.3 和 Raft 的两阶段对比

ZAB 和 Raft 的广播本质都是"多数派写入即提交"，差别在第二阶段的实现方式：

| 维度 | ZAB | Raft |
|------|-----|------|
| 第一阶段 | PROPOSAL 消息 | AppendEntries RPC |
| 第二阶段 | **独立的 COMMIT 消息** | **捎带在下次 AppendEntries 的 leaderCommit 字段** |
| 消息数（每事务）| 3 次（PROPOSAL + ACK + COMMIT）| 2 次（AppendEntries + ACK，commit 顺带）|
| 提交延迟 | 短（专门一条 COMMIT）| 略高（要等下次 RPC）|
| 网络开销 | 略高（多一次广播）| 略低 |

**实际效果**：ZAB 提交延迟更稳定（COMMIT 立刻发），Raft 提交延迟取决于心跳间隔。生产环境中两者性能差异不大。

### 5.4 FIFO 通道：保证消息顺序

ZAB 强制要求 **Leader 和每个 Follower 之间的网络通道是 FIFO 的**——TCP 天然满足这个性质。这是 ZAB 顺序一致性的工程基础：

- Leader 按 zxid 顺序发 PROPOSAL → Follower 按相同顺序收到
- Follower 按相同顺序写日志、apply 到状态机
- **所有节点的状态机演化路径完全相同**

如果用 UDP 这种无序协议，消息乱序到达，Follower 就要做大量额外排序——ZAB 选 TCP 是工程上的必然。

### 5.5 写性能影响因素

ZAB 单次写的耗时大约是：

```
T = T_disk_fsync + T_network_RTT
  ≈ 1~10ms (fsync) + 0.1~1ms (RTT)
  ≈ 1~10ms
```

**优化点**：

- **批量提交**：Leader 把多个 PROPOSAL 合并成一批发，摊薄 fsync 开销
- **关闭 forceSync**：把 `forceSync=no` 让事务日志不强制 fsync（牺牲少量数据安全换性能，**生产不推荐**）
- **专用磁盘**：把事务日志放到独立 SSD，避免和数据快照、应用日志竞争 IO

ZooKeeper 的 TPS 通常在 **10K~50K** 量级，远低于 Redis 但远高于 MySQL 主从。

---

## 六、一致性保证的核心机制

ZAB 提供的一致性级别是**顺序一致性（Sequential Consistency）**——比线性一致性弱，但比最终一致性强。具体保证如下五条。

### 6.1 全局顺序：所有节点按 zxid 顺序应用事务

**保证**：任意两个节点的状态机演化路径完全相同——同一个 zxid 在所有节点上对应的事务相同，且应用顺序相同。

**实现机制**：

1. **唯一 Leader 分配 zxid**：广播阶段只有 Leader 能生成新 zxid，counter 严格递增 → 全局序号唯一
2. **TCP FIFO 通道**：Leader 按 zxid 顺序发出的消息，Follower 按相同顺序收到
3. **顺序应用**：Follower 按 zxid 顺序写日志、按 zxid 顺序 apply 到状态机

```
所有节点的 DataTree 演化路径：
  T0 → T1 → T2 → T3 → ...
  其中每个 Ti 是相同的事务，按相同顺序应用
```

### 6.2 已提交事务持久化：永不丢失

**保证**：一旦客户端收到"写成功"的响应，这条事务在后续所有时刻都能被读到（即便 Leader 宕机、网络分区）。

**实现机制（Quorum + 选举限制）**：

```
事务提交的定义：被 ⌊N/2⌋+1 个节点持久化（写入事务日志）

选举 Leader 时，新 Leader 必须 zxid ≥ 多数派节点的 lastZxid
  → 由于已提交事务在多数派上
  → 多数派交集保证：任意"多数派 Follower 集合"里至少有一个节点拥有该事务
  → 新 Leader 必然包含该事务
```

这就是 **ZAB 数据安全的核心证明**——和 Raft 的"选举限制"逻辑等价。

### 6.3 单调读：客户端不会看到时间倒流

**保证**：同一个客户端连续两次读，**第二次的视图至少和第一次一样新**。

**实现机制**：每个客户端会话维护一个 `lastSeenZxid`，连接到 Follower 时如果 Follower 的 zxid < 自己的 lastSeenZxid，连接会被拒绝（`ZOOKEEPER-1666` 之类的处理），客户端会重连其他更新的节点。

这避免了"客户端先连 N1 看到 zxid=10，再连 N2 却看到 zxid=8"的退化现象。

### 6.4 不会读到未提交的"幽灵数据"

**保证**：客户端永远不会看到一条事务在某节点存在、在另一节点不存在的中间状态——要么所有节点都看到，要么都看不到。

**实现机制**：

- Follower 收到 PROPOSAL 后**只写日志，不 apply**
- 必须等收到 COMMIT 才 apply 到内存数据树
- 客户端读的是内存数据树（apply 后的视图），不是事务日志
- 已提交的事务一定被多数派 apply，未提交的事务即便在某个 Follower 上有日志，DataTree 里也没有

恢复阶段的 **TRUNC 策略**进一步处理了"幽灵日志"——把 Follower 上未提交的脏日志直接切掉，确保不会有任何残留。

### 6.5 Leader 唯一性：永远只有一个 Leader 在工作

**保证**：同一时刻整个集群对外有效的 Leader 最多只有一个。

**实现机制（双重防护）**：

1. **多数派投票**：选 Leader 必须拿到 ⌊N/2⌋+1 票，由多数派交集性质决定**最多一个候选人能拿到多数派**
2. **epoch 单调递增 + Follower acceptedEpoch 校验**：旧 Leader 即便不知道自己被废，它带着旧 epoch 发的 PROPOSAL 也会被 Follower 拒绝（见 4.1 幽灵 Leader 处理）

```
脑裂场景：
  网络分区后，分区 A 有 N1（旧 Leader），分区 B 有 N2、N3、N4（凑齐多数派选 N2 当新 Leader）
  
  - N2 当选后 epoch 升级到 6
  - N1 仍以为自己是 Leader（epoch=5），继续接收客户端写
  - 但 N1 在分区 A 内只有自己 1 个节点 < 多数派 → 任何 PROPOSAL 都拿不到多数派 ACK
  - N1 的写请求永远不会"提交"，客户端永远收不到 OK
  
  → 不会出现"两个 Leader 都在提交事务"的情况
```

### 6.6 一致性级别：为什么是顺序一致而非线性一致

**线性一致性（Linearizability）**：每个操作看起来在某个全局时间点瞬间完成，所有客户端看到的顺序和实时时钟一致。

**顺序一致性（Sequential Consistency）**：所有操作有一个全局顺序，但这个顺序**不一定和实时时钟一致**——客户端 A 在时间 T1 写完，客户端 B 在时间 T2(>T1) 读，可能读不到 A 的写。

ZooKeeper 默认是顺序一致——因为 **Follower 可以直接服务读**，Follower 的状态机可能滞后于 Leader 几毫秒。

**强制线性一致的方法**：调 `sync()` 命令，强制 Follower 追平 Leader 的 commitIndex 后再读：

```java
zk.sync("/foo", null, null);    // 等 Follower 追平
zk.getData("/foo", ...);          // 此时读的一定是最新数据
```

### 6.7 一致性保证的总览图

```
              客户端写
                 │
                 ▼
            ┌─────────┐
            │ Leader  │── 6.5 唯一性 ──┐
            └─────────┘                │
                 │                     │
        FIFO TCP 通道                  │
        (6.1 全局顺序)                 │
                 │                     │
       ┌─────────┼─────────┐           │
       ▼         ▼         ▼           │
   ┌──────┐ ┌──────┐ ┌──────┐         │
   │Foll 1│ │Foll 2│ │Foll 3│         │
   └──────┘ └──────┘ └──────┘         │
       │         │         │           │
       └────多数派 ACK ────┘           │
       (6.2 持久化保证)                │
                 │                     │
         apply 到 DataTree             │
       (6.4 不读未提交)                │
                 │                     │
        客户端读（任意节点）           │
       (6.3 单调读保证)                │
                                       │
       脑裂时 epoch 校验 ──────────────┘
       (6.5 Leader 唯一)
```

---

## 七、典型故障场景剖析

理论讲完了，下面用具体场景验证 ZAB 的健壮性。每个场景描述"发生了什么"和"ZAB 怎么应对"。

### 7.1 场景一：Leader 宕机（最常见）

**故障描述**：Leader 进程崩溃 / 机器掉电 / 网络断开。

**ZAB 应对**：

```
T0   Leader N1 正常服务，最大 zxid=0x500000020
T1   N1 突然宕机
T2   Follower 们在 syncLimit (默认 10s) 内收不到心跳
T3   所有 Follower 转为 LOOKING，发起新选举
T4   FastLeaderElection：拥有最大 zxid 的节点胜出（假设是 N3）
T5   N3 → LEADING，进入 Recovery 阶段
T6   N3 协商新 epoch=6，向 Follower 同步差量
T7   多数派同步完成 → 进入 Broadcast 阶段
T8   集群对外恢复服务
```

**对外影响**：T1~T8 整个故障窗口集群不可用，通常 5~30s（取决于 syncLimit 和数据量）。客户端会话进入"已断开"状态，连接的 SDK 会自动重连。

**已提交数据**：通过 6.2 的选举限制保证，N1 在宕机前已提交的事务一定在 N3 上 → 永不丢失。

### 7.2 场景二：Follower 宕机

**故障描述**：单个 Follower 崩溃。

**ZAB 应对**：

```
T0  N4 (Follower) 宕机
T1  Leader 给 N4 发 PROPOSAL → 收不到 ACK，无所谓（还有其他 Follower 凑多数派）
T2  集群仍正常服务（5 节点中 4 节点存活，仍超过多数派）
T3  N4 重启
T4  N4 → Leader : FOLLOWERINFO(lastZxid)
T5  Leader 决定同步策略（DIFF / TRUNC / SNAP）
T6  同步完成，N4 重新加入集群
```

**对外影响**：完全无感知——只要存活节点数 ≥ 多数派，集群正常服务。

### 7.3 场景三：网络分区（脑裂场景）

**故障描述**：5 节点集群被网络分成 {N1, N2}（少数派）和 {N3, N4, N5}（多数派）两个分区。假设 N1 是原 Leader。

**ZAB 应对**：

```
分区 A：{N1, N2}
  N1 仍以为自己是 Leader，继续接收写请求
  但 N1 只能联系到 N2（自己 1 票 + N2 1 票 = 2 < 3 多数派）
  → 所有 PROPOSAL 拿不到多数派 ACK
  → 客户端写请求永远收不到 OK（最终超时失败）
  → 分区 A 实际不可写，只可读（顺序一致读还能服务）

分区 B：{N3, N4, N5}
  N3、N4、N5 在 syncLimit 后收不到 N1 心跳，转 LOOKING
  发起选举，N3 当选（假设 zxid 最大），新 epoch=6
  → 分区 B 正常服务

网络恢复：
  N1、N2 看到 N3 的 epoch=6 > 自己的 epoch=5
  → N1 主动让位，转为 Follower
  → N2 同上
  → N1 和 N2 经过 Recovery 阶段同步数据，集群恢复
```

**关键保证**：

- **不会出现两个 Leader 同时提交事务**：少数派分区拿不到多数派，写请求全部失败
- **数据不丢**：分区期间分区 A 的写请求都没成功提交（没有 OK 响应给客户端），所以网络恢复后被覆盖也不算"丢数据"

### 7.4 场景四：Leader 提交瞬间宕机（数据丢失边界）

**故障描述**：这是最微妙的场景——Leader 把事务广播给多数派，自己 apply 完准备发 COMMIT，但**就在发 COMMIT 给 Follower 之前宕机了**。

**两种子情况分析**：

#### 子情况 A：多数派 Follower 已收到 PROPOSAL 但未收到 COMMIT

```
T0  Leader N1 广播 PROPOSAL(zxid=0x500000020)
T1  N2、N3 持久化日志，回 ACK（多数派达成）
T2  N1 自己 apply，给客户端返回 OK
T3  N1 准备发 COMMIT，宕机
T4  集群重新选主，N3 当选（N3 的 lastZxid = 0x500000020）
T5  Recovery 阶段，N3 把 0x500000020 同步给所有 Follower
T6  N3 进入 Broadcast，广播 COMMIT(zxid=0x500000020)（即便没 client 在等）
```

**结果**：✅ 事务被保留——客户端看到的"OK"是真实的。

#### 子情况 B：只有 Leader 自己 apply，多数派 Follower 没收到 PROPOSAL

```
T0  Leader N1 广播 PROPOSAL(zxid=0x500000020)
T1  PROPOSAL 包丢了（N1 网卡异常）
T2  N1 没收到任何 ACK，事务还没"提交"
T3  N1 宕机
T4  集群重新选主，Follower 们的 lastZxid 都是 0x50000001F（不含 0x20）
T5  N4 当选，新 epoch=6，counter 从 0 重置
T6  集群从 0x50000001F 之后的状态继续服务
```

**结果**：✅ 事务被丢弃——但因为客户端**从未收到 OK**（N1 没拿到多数派 ACK 就不会回 OK），所以丢弃符合契约。

#### 子情况 C：Leader 已 apply 给客户端回 OK，但只发了少数 Follower

```
T0  N1 广播，只有 N2 收到（N1+N2 = 2 < 多数派 3）
T1  N1 没拿到多数派 ACK，本不该 apply
```

**这种情况不可能发生**——Leader 严格遵守"多数派 ACK 才 apply 才回 OK"的规则。如果 N1 真的违反规则提前回 OK 了，那是实现 bug，不是协议问题。

### 7.5 场景五：少数派持久化的"幽灵日志"

**故障描述**：Leader 的 PROPOSAL 只到达了少数 Follower 就宕机，留下"幽灵日志"。

```
T0  Leader N1 广播 PROPOSAL(zxid=0x500000020) 给 N2~N5
T1  只有 N2 收到并持久化（其他 3 个网络抖动没收到）
T2  N1 没拿到多数派 ACK，没 apply、没回 OK
T3  N1 宕机
T4  集群重新选主：
    N2.lastZxid = 0x500000020 (有幽灵日志)
    N3、N4、N5.lastZxid = 0x50000001F (没收到)
    
    PK：N2 的 zxid 最大 → N2 当选？
    
    实际不会：为什么？
```

**关键点**：N2 当选后进入 Recovery 阶段，会发现自己的 0x500000020 是个**未提交的幽灵日志**——因为没有任何其他节点确认过它。N2 会把这个事务保留并继续广播给其他节点（让多数派持久化）。

**两种结局**：

- **结局 A**：N2 成功把 0x500000020 同步到多数派 → 事务被"事后提交"
- **结局 B**：N2 同步过程中再次宕机 → 后续选举时 N3、N4、N5 中某一个当选 → 通过 TRUNC 让 N2（如果回来）丢掉 0x500000020

无论哪种结局，**客户端的语义都没违背**——因为 N1 当初就没回 OK 给客户端，所以这条事务"提交不提交都行"，由协议自由选择。

### 7.6 故障容忍能力总结

| 故障类型 | 容忍数（5 节点）| ZAB 行为 |
|---------|--------------|---------|
| Leader 宕机 | 1 次至少 | 重新选举（5~30s 不可用），数据不丢 |
| Follower 宕机 | 最多 2 个 | 完全无感知，集群继续服务 |
| 网络分区 | 任意分布 | 多数派分区继续服务，少数派拒绝写入 |
| Leader 提交瞬间宕机 | 1 次 | 已 OK 的事务保留，未 OK 的可能丢但符合契约 |
| 少数派幽灵日志 | 任意 | 通过 TRUNC 清理或事后补提交 |

**核心结论**：ZAB 在 Crash-Stop 故障模型下，可容忍 **⌊(N-1)/2⌋ 个节点同时故障**，已提交数据永不丢失。

---

## 八、ZAB vs Raft 小结

ZAB 和 Raft 是**同一族算法的两种实现**——核心思想完全一致，差别在工程细节。

### 8.1 核心思想对比（完全一致）

| 元素 | ZAB | Raft |
|------|-----|------|
| 单 Leader | ✅ | ✅ |
| 多数派写入 | ✅ | ✅ |
| 任期号 | epoch | term |
| 选举时数据安全 | 比 zxid（先 epoch 后 counter）| 比日志（先 term 后 index）|
| 已提交数据不丢 | Quorum 交集 + 选举限制 | Quorum 交集 + Election Restriction |
| 故障模型 | Crash-Stop | Crash-Stop |
| 容错能力 | ⌊(N-1)/2⌋ | ⌊(N-1)/2⌋ |

### 8.2 工程细节差异

| 维度 | ZAB | Raft |
|------|-----|------|
| 提出年份 | 2008 | 2014 |
| 任期编码 | zxid 高 32 位（紧凑）| 独立的 term + index 字段（解耦）|
| 第二阶段提交 | 独立 COMMIT 消息 | 捎带在下次 AppendEntries |
| 日志冲突处理 | 会话开始时一次性 DIFF/TRUNC/SNAP | 每次 RPC 隐式校验 prevLogIndex/Term |
| 节点角色 | LOOKING/LEADING/FOLLOWING/OBSERVING | Follower/Candidate/Leader（+Learner）|
| 读请求 | Follower 直接服务（顺序一致）| 默认走 Leader（线性一致）|
| 设计目标 | 服务 ZooKeeper | 通用、可理解 |
| 实现复杂度 | 中（角色多、阶段多）| 低（论文目标就是简单）|

### 8.3 一句话区分记忆

> **ZAB 是"为 ZooKeeper 量身定制的 Raft 早期版"** ——同样的核心思想，但 ZAB 的协议术语和实现细节更复杂，Raft 学完了再看 ZAB 几乎不用重新理解，反之亦然。

### 8.4 选型建议

- **业务系统选 etcd / TiKV（Raft）**：生态丰富、API 友好、性能优异，适合做服务注册、配置中心、分布式锁
- **遗留系统继续用 ZooKeeper（ZAB）**：Hadoop、HBase、Kafka 早期版本依赖 ZK，迁移成本高
- **学习推荐先 Raft 后 ZAB**：Raft 论文就是为了"可理解性"写的，先建立直觉再去看 ZAB 细节会轻松很多

---

## 九、面试速答模板

### Q1：ZAB 协议是什么？

**答**：ZAB 是 ZooKeeper 专用的支持崩溃恢复的原子广播协议。核心目标是保证**顺序一致性、崩溃恢复、原子性**。它不是 Paxos 也不是 Raft，但和 Raft 极其相似——都是单 Leader + 多数派 + 任期号的设计。ZAB 把节点生命周期分为三个阶段：选举（Election）、恢复（Recovery）、广播（Broadcast）。

### Q2：zxid 是什么，怎么设计的？

**答**：zxid 是 64 位事务 ID，高 32 位是 epoch（任期号），低 32 位是 counter（任期内事务序号）。每次选出新 Leader epoch +1，counter 重置为 0；每次提交事务 counter +1。这种设计保证 zxid 全局严格单调递增，且比较 zxid 大小直接对应"事件先后"——是 ZAB 数据安全的核心数据结构。

### Q3：FastLeaderElection 怎么工作？

**答**：每个节点广播自己的选票 `(myid, zxid, electionEpoch, peerEpoch)`。比较选票时按以下顺序 PK：先比 electionEpoch（防旧票）→ 再比 peerEpoch（任期）→ 再比 zxid（数据新旧）→ 最后比 myid（破对称）。多数派认同同一个候选人就当选。**关键设计是先比数据新旧，保证选出的 Leader 一定包含所有已提交日志**。

### Q4：恢复阶段 Discovery 的作用？

**答**：协商一个新 epoch，等于所有 Follower 的 lastZxid 中最大 epoch + 1。这保证了：①新 Leader 的 epoch 严格大于所有历史 epoch；②即便旧 Leader 因网络分区"复活"，它带着旧 epoch 发的写请求会被 Follower 直接拒绝——杜绝脑裂。

### Q5：Synchronization 阶段的四种策略？

**答**：

- **DIFF**：Follower 数据在 [minCommittedLog, maxCommittedLog] 区间且无脏日志 → 增量同步差量事务
- **TRUNC**：Follower 有 Leader 没有的脏日志 → 让 Follower 截断
- **SNAP**：Follower 落后太多（< minCommittedLog）→ 全量快照覆盖
- **TRUNC + DIFF**：既要截断又要补增量

### Q6：ZAB 怎么保证已提交数据不丢？

**答**：通过 **Quorum 多数派交集** + **选举限制**。事务"提交"的定义是被多数派持久化；选举新 Leader 时必须满足 zxid ≥ 多数派的 lastZxid。由于已提交事务在多数派上，多数派交集保证新 Leader 必然持有该事务——即便旧 Leader 宕机，已提交数据也永不丢失。

### Q7：广播阶段是几阶段提交？

**答**：是简化版的两阶段提交：

1. **PROPOSE**：Leader 写本地日志后广播 PROPOSAL，Follower 持久化日志后回 ACK
2. **COMMIT**：Leader 收到多数派 ACK 后广播 COMMIT，所有节点 apply 到状态机

和 Raft 的差别：Raft 的"提交通知"捎带在下次 AppendEntries 的 leaderCommit 字段里，ZAB 用独立的 COMMIT 消息。

### Q8：ZAB 的一致性级别是什么？

**答**：默认是**顺序一致性**——所有节点按相同顺序应用事务，但 Follower 状态机可能滞后于 Leader 几毫秒。客户端可能在 T1 写完，T2(>T1) 在另一个 Follower 上读不到。要强制线性一致性，可以先调 `sync()` 命令强制 Follower 追平再读。

### Q9：ZAB 容忍多少节点故障？

**答**：N 节点集群可容忍 ⌊(N-1)/2⌋ 个节点同时故障。3 节点容忍 1 个，5 节点容忍 2 个。生产推荐 5 节点（性价比最高）。**永远部署奇数节点**——偶数节点既不增加容错能力又增加写入延迟和脑裂风险。

### Q10：ZAB 和 Raft 的关系？

**答**：本质是**同一族算法**——单 Leader + 多数派 + 任期号 + 选举安全限制。ZAB 比 Raft 早 6 年提出，但 Raft 论文以可理解性为目标做了大量简化，所以 Raft 文档比 ZAB 易读。差别在工程细节：ZAB 用 zxid 紧凑编码 epoch+counter，Raft 用独立的 term+index；ZAB 有独立 COMMIT 消息，Raft 用心跳捎带 leaderCommit；ZAB 允许 Follower 直接服务读，Raft 默认强制走 Leader。

---

## 十、和其他笔记的关联

- **共识算法整体定位**：详见本目录 `ConsensusAlgorithm.md`，含 Paxos / Raft 详解和 CAP 视角
- **ZAB 在 Redis 分布式锁讨论中的角色**：详见 `redis/DistributedLock.md` 第五章 V5（Redlock 与共识算法的争议）
- **CAP 定理的更多应用**：详见 `java-interview-notes/week3-system-design/day15-16-distributed-theory.md`
- **ZooKeeper 在分布式系统中的实战定位**：在 RocketMQ NameServer / Kafka Controller 中的角色对比，详见 `rocketmq/NameServer.md`

> **后续可补充的笔记**：ZooKeeper 的 Watch 机制、临时顺序节点、ZK 实现的分布式锁、Kafka 从 ZK 迁移到 KRaft 的历程等。
