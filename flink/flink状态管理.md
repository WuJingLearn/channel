# Flink 状态管理（State Management）

## 一、为什么流处理需要"状态"？

批处理的一个任务只跑一次、读的是"有界数据"，天然可以一次性算完；
而流处理是**7×24 跑在无界数据上**，很多计算必须"记住过去发生了 什么"才能产出正确结果。

典型场景：

- 窗口聚合：统计过去 10 秒订单总额 → 必须累计中间结果
- 去重：用户是否首次访问 → 必须记录历史
- CEP 规则：A 事件后 5 分钟内没看到 B 事件就告警 → 必须记住 A
- 双流 JOIN：等待另一条流对应的 key 到来 → 必须缓存已到达的数据

> **状态 = 算子在处理数据过程中需要记住的、跨条数据的中间结果。**

Flink 把状态当作**一等公民**：提供统一的 API 来读写、统一的机制来容错和恢复。

---

## 二、状态的两大分类

Flink 里所有状态，按照"作用域"划分就两类：

| 分类 | 作用域 | 典型用途 |
|---|---|---|
| **Keyed State** ★ 最常用 | 跟着 `key` 走，每个 key 独立一份 | 分组聚合、用户画像、会话跟踪 |
| **Operator State** | 跟着**算子子任务（subtask）**走 | Source 的偏移量、Sink 的缓冲区 |

```
                      ┌────── Keyed State ──────┐
                      │ key=A → state_A         │
DataStream.keyBy() ──▶│ key=B → state_B         │  按 key 分片
                      │ key=C → state_C         │
                      └─────────────────────────┘

                      ┌──── Operator State ─────┐
普通算子（未 keyBy）──▶│ subtask-0 → state_0     │  按 subtask 分片
                      │ subtask-1 → state_1     │
                      └─────────────────────────┘
```

**关键区别**：
- Keyed State **必须** 在 `keyBy` 之后的算子里使用
- Operator State 在任意算子里都能用，常用于 Source / Sink

---

## 三、Keyed State 的五种类型

在 `KeyedStream` 的算子里（`RichFunction`、`KeyedProcessFunction` 等），通过 `getRuntimeContext().getXxxState(descriptor)` 获取。

| 类型 | 存什么 | 主要 API |
|---|---|---|
| **ValueState\<T\>** | 单值 | `value()` / `update(T)` / `clear()` |
| **ListState\<T\>** | 列表 | `add(T)` / `get()` / `update(List)` |
| **MapState\<K,V\>** | KV 映射 | `put` / `get` / `contains` / `remove` / `entries` |
| **ReducingState\<T\>** | 自动聚合的单值（规约） | `add(T)` 内部自动 reduce |
| **AggregatingState\<IN,OUT\>** | 自动聚合，输入输出类型可不同 | `add(IN)` → `get():OUT` |

### ValueState 详解

#### 1. 什么是 ValueState？

**ValueState\<T\>** 是 Flink Keyed State 里**最基础、最常用**的一种状态类型，用于为**每一个 key** 存储**一个可读写的值**。

> 在 `KeyedStream` 上，ValueState 给每个 key 维护一份**独立的单值存储**，这个值可以是任何可序列化的类型（基本类型、POJO、集合等）。

简而言之：
- **"Value"** = 存一个值（而不是列表、映射）
- **"State"** = 这个值会被 Flink 管理（容错、持久化、恢复）
- **隐含前提** = 必须在 `keyBy` 之后使用，**按 key 自动隔离**

#### 2. 核心 API

```java
public interface ValueState<T> extends State {
    T value() throws IOException;             // 读取当前 key 的值，若无则返回 null
    void update(T value) throws IOException;  // 更新当前 key 的值，传 null 等价于 clear()
    void clear();                             // 清除当前 key 的值（继承自 State）
}
```

只有三个操作：**读、写、清**。简单但非常强大。

#### 3. ValueState 的作用

**作用 1：记住"上一次是什么"** —— 拿当前事件和历史做比较

示例：检测温度异常跳变（相邻两条数据温差 > 10°C 就告警）

```java
public class TempAlert extends RichFlatMapFunction<Reading, Alert> {
    private transient ValueState<Double> lastTemp;

    @Override
    public void open(Configuration p) {
        lastTemp = getRuntimeContext().getState(
            new ValueStateDescriptor<>("lastTemp", Double.class));
    }

    @Override
    public void flatMap(Reading r, Collector<Alert> out) throws Exception {
        Double prev = lastTemp.value();                     // 读上一次
        if (prev != null && Math.abs(r.temp - prev) > 10) {
            out.collect(new Alert(r.sensorId, prev, r.temp));
        }
        lastTemp.update(r.temp);                            // 写入当前值
    }
}
```

**作用 2：累积中间结果** —— 例如每个用户的累计点击次数

```java
public class ClickCounter extends RichFlatMapFunction<Click, Tuple2<String, Long>> {
    private transient ValueState<Long> countState;

    @Override
    public void open(Configuration parameters) {
        ValueStateDescriptor<Long> desc = new ValueStateDescriptor<>("count", Long.class);
        countState = getRuntimeContext().getState(desc);
    }

    @Override
    public void flatMap(Click click, Collector<Tuple2<String, Long>> out) throws Exception {
        Long cur = countState.value();
        long next = (cur == null ? 0L : cur) + 1;
        countState.update(next);
        out.collect(Tuple2.of(click.userId, next));
    }
}
```

**作用 3：保存"当前会话/状态机"** —— 如订单状态机（待支付 → 已支付 → 已发货）

```java
ValueState<OrderStatus> statusState;
// 收到 "支付成功" 事件 → update(PAID)
// 收到 "发货" 事件     → update(SHIPPED)
```

**作用 4：配合 Timer 实现超时检测** —— 如用户登录 30 分钟无操作就踢下线

```java
public void processElement(Event e, Context ctx, Collector<Event> out) throws Exception {
    lastActive.update(e.ts);
    ctx.timerService().registerEventTimeTimer(e.ts + 30 * 60 * 1000);
}

public void onTimer(long ts, OnTimerContext ctx, Collector<Event> out) throws Exception {
    if (ts - lastActive.value() >= 30 * 60 * 1000) {
        out.collect(new KickOffEvent(ctx.getCurrentKey()));
        lastActive.clear();
    }
}
```

#### 4. ValueState 的关键特性

**① 自动按 key 隔离**（最重要）

代码里看到的 `valueState.value()`，Flink 内部实际做的是：

```
value()   → backend.get(currentKey, stateName)
update(v) → backend.put(currentKey, stateName, v)
```

当前 key 是**运行时自动注入**的（即当前处理的这条数据的 key），你完全不用关心 key 是什么。

```
数据流:  (userA,1) (userB,1) (userA,1) (userB,1) (userA,1)
              │        │         │         │         │
              ▼        ▼         ▼         ▼         ▼
        countA=1   countB=1  countA=2  countB=2  countA=3
        （Flink 自动根据当前 key 切换读写的"存储槽"）
```

**② 必须声明在 KeyedStream 上**

```java
stream.keyBy(Event::getUserId)     // 必须先 keyBy
      .flatMap(new MyFunction());  // 这里才能用 ValueState
```

没 keyBy 直接 `getState(...)` → 运行时报错。

**③ 由 Flink 管理生命周期**

- **注册**：通过 `ValueStateDescriptor` 声明名字、类型、默认值、TTL
- **存储**：根据 StateBackend 落到堆内存或 RocksDB
- **容错**：随 Checkpoint 自动快照
- **恢复**：任务重启时自动还原
- **清理**：可配 TTL 自动过期

**④ 状态是"本地"的**

当前 key 的 state **只存在它所在的 subtask 里**。不同 key 可能被路由到不同 subtask，**彼此看不到对方的状态**——这也是 Flink 能水平扩展的核心原因。

#### 5. 使用三步曲

```java
public class MyFunction extends RichFlatMapFunction<In, Out> {

    // 1. 声明字段（必须 transient，不被 Java 序列化）
    private transient ValueState<Long> myState;

    @Override
    public void open(Configuration p) {
        // 2. 在 open 里通过 Descriptor 初始化
        ValueStateDescriptor<Long> desc = new ValueStateDescriptor<>(
            "my-state",            // 状态名（算子内唯一）
            Long.class             // 类型（或 TypeInformation）
        );
        // 可选：配 TTL
        desc.enableTimeToLive(StateTtlConfig.newBuilder(Time.hours(1)).build());
        myState = getRuntimeContext().getState(desc);
    }

    @Override
    public void flatMap(In in, Collector<Out> out) throws Exception {
        // 3. 在业务方法里读写
        Long v = myState.value();
        myState.update(v == null ? 1L : v + 1);
    }
}
```

#### 6. ValueState vs 其他状态类型的选择

| 场景 | 推荐 |
|---|---|
| 存一个数、一个对象 | **ValueState** |
| 存一个不断追加的列表 | ListState |
| 存按子 key 分组的数据 | MapState（优于 ValueState\<HashMap\>） |
| 需要自动 reduce | ReducingState |
| 输入输出类型不同的聚合 | AggregatingState |

> **反模式**：用 `ValueState<HashMap<K,V>>` 存大量 KV → 每次读写要**整体序列化**，RocksDB 后端性能极差。应改用 `MapState<K,V>`，它支持按 key 单独读写。

#### 7. 常见坑

1. **在构造器里初始化 ValueState** → NPE，必须在 `open()` 里（RuntimeContext 还未准备好）
2. **忘了 `transient`** → 作业序列化分发时报错
3. **对可变对象（如 List、Map）修改了内部字段但没 `update()`** → RocksDB 后端不会感知修改，状态没真正更新
4. **相同 name 声明成不同类型** → 恢复时序列化失败
5. **未处理 `value()` 返回 null** → NPE（ValueState 初始值就是 null）

#### 8. 一句话总结

> **ValueState 就是"每个 key 一个值"的存储槽**——用它在流处理中"记住上一次"、"累加至今"、"标记状态"，Flink 负责按 key 隔离、容错、持久化、恢复。所有 Keyed State 里**最基础、最常用、入门必学**的就是它。

### ListState / MapState

```java
ListStateDescriptor<Event> listDesc = new ListStateDescriptor<>("events", Event.class);
ListState<Event> eventList = getRuntimeContext().getListState(listDesc);
eventList.add(event);

MapStateDescriptor<String, Integer> mapDesc = new MapStateDescriptor<>("attrs", String.class, Integer.class);
MapState<String, Integer> attrs = getRuntimeContext().getMapState(mapDesc);
attrs.put("score", 100);
```

> 大量数据用 **MapState** 比 ValueState 存 `HashMap` 更优：底层在 RocksDB 时可以**按 key 单独 get/put**，不用整体序列化。

---

## 四、Operator State 的三种类型

用于算子级别的状态（比如 Kafka Source 的 offset）。通过实现 `CheckpointedFunction` 接口或 `ListCheckpointed`（已废弃）使用。

| 类型 | 含义 | 重分配策略 |
|---|---|---|
| **ListState** | 普通列表状态 | 恢复时在新 subtask 间**均匀再分配** |
| **UnionListState** | 联合列表状态 | 恢复时每个 subtask **拿到全量**，再自行决定用哪些 |
| **BroadcastState** | 广播状态，只读 | 每个 subtask 都有完整副本（配合广播流） |

### CheckpointedFunction 示例

```java
public class BufferingSink implements SinkFunction<Event>, CheckpointedFunction {
    private final int threshold = 100;
    private final List<Event> buffer = new ArrayList<>();
    private transient ListState<Event> checkpointedBuffer;

    @Override
    public void invoke(Event value, Context ctx) {
        buffer.add(value);
        if (buffer.size() >= threshold) { flush(); buffer.clear(); }
    }

    @Override
    public void snapshotState(FunctionSnapshotContext ctx) throws Exception {
        checkpointedBuffer.update(buffer);      // checkpoint 前把内存中的 buffer 写入状态
    }

    @Override
    public void initializeState(FunctionInitializationContext ctx) throws Exception {
        ListStateDescriptor<Event> desc = new ListStateDescriptor<>("buf", Event.class);
        checkpointedBuffer = ctx.getOperatorStateStore().getListState(desc);
        if (ctx.isRestored()) {
            for (Event e : checkpointedBuffer.get()) buffer.add(e);   // 恢复
        }
    }
}
```

### Broadcast State：动态规则下发

```java
MapStateDescriptor<String, Rule> ruleDesc = new MapStateDescriptor<>("rules", String.class, Rule.class);
BroadcastStream<Rule> ruleBroadcast = ruleStream.broadcast(ruleDesc);

eventStream
    .keyBy(Event::getUserId)
    .connect(ruleBroadcast)
    .process(new KeyedBroadcastProcessFunction<>() {
        @Override
        public void processElement(Event e, ReadOnlyContext ctx, Collector<Alert> out) {
            ReadOnlyBroadcastState<String, Rule> rules = ctx.getBroadcastState(ruleDesc);
            // 用广播过来的规则评估事件
        }
        @Override
        public void processBroadcastElement(Rule r, Context ctx, Collector<Alert> out) {
            ctx.getBroadcastState(ruleDesc).put(r.getId(), r);     // 更新规则
        }
    });
```

---

## 五、状态 TTL（Time-To-Live）

长时间运行的任务，状态会越积越大 —— 必须让冷 key 的状态**自动过期清理**。

```java
StateTtlConfig ttl = StateTtlConfig
    .newBuilder(Time.days(1))                                  // 1 天后过期
    .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite) // 写入时刷新过期时间
    .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired) // 已过期的数据读到也当不存在
    .cleanupInRocksdbCompactFilter(1000)                       // RocksDB 后端：compaction 时清理
    .build();

ValueStateDescriptor<Long> desc = new ValueStateDescriptor<>("count", Long.class);
desc.enableTimeToLive(ttl);
```

关键参数：

| 参数 | 说明 |
|---|---|
| `UpdateType.OnCreateAndWrite` | 只有创建和写入时刷新（默认） |
| `UpdateType.OnReadAndWrite` | 读写都刷新，冷数据也续命 |
| `StateVisibility.NeverReturnExpired` | 读到已过期一律当作没有（安全） |
| `StateVisibility.ReturnExpiredIfNotCleanedUp` | 只要没物理清除就返回（性能好但有脏数据） |
| `cleanupFullSnapshot()` | Checkpoint 时顺手清理 |
| `cleanupIncrementally(n, runOnUpdate)` | 每次访问顺手扫描 n 个 entry |
| `cleanupInRocksdbCompactFilter(n)` | RocksDB 专属，compaction 时清理，最推荐 |

> 一句话：**生产环境必配 TTL**，不然状态爆炸只是时间问题。

---

## 六、状态后端（State Backend）

状态**存在哪里、如何访问**由 State Backend 决定。Flink 1.13 之后只剩两种：

| State Backend | 状态存哪 | 适用场景 |
|---|---|---|
| **HashMapStateBackend** | TaskManager 堆内存（Java 对象） | 状态小（几 GB 内）、延迟极低 |
| **EmbeddedRocksDBStateBackend** ★ 生产主流 | TaskManager 本地磁盘（RocksDB） | 状态大（TB 级）、允许稍高延迟 |

### 对比

| 维度 | HashMap | RocksDB |
|---|---|---|
| 存储位置 | JVM 堆 | 本地磁盘 + off-heap |
| 状态大小限制 | 受 TM 内存 | 受本地磁盘 |
| 访问速度 | 快（纯内存对象） | 慢（需序列化 + 磁盘 IO） |
| 支持增量 Checkpoint | ❌ | ✅ |
| 适合 | 小状态、低延迟 | 大状态、长窗口、长 TTL |

### 配置方式

**代码里：**

```java
env.setStateBackend(new EmbeddedRocksDBStateBackend());
env.getCheckpointConfig().setCheckpointStorage("hdfs:///flink/checkpoints");
```

**配置文件 `flink-conf.yaml`：**

```yaml
state.backend: rocksdb
state.checkpoints.dir: hdfs:///flink/checkpoints
state.backend.incremental: true
```

> **Checkpoint 存储位置**（HDFS / S3 / OSS）和 **State Backend**（堆 / RocksDB）是**两个独立的选择**，别搞混。

---

## 七、Checkpoint：状态的快照机制

状态再多、算得再准，机器一挂全没了也白搭。Flink 用 **Checkpoint** 周期性给所有状态打快照，故障时从最近的 Checkpoint 恢复，保证**精确一次（exactly-once）**。

### 核心思想：Chandy-Lamport 改进版（Asynchronous Barrier Snapshotting）

JobManager 周期性往 Source 注入一条特殊消息 **Checkpoint Barrier**，它像水位线一样在数据流中**顺着传播**。

```
数据:  ... d3 d2 d1 [Barrier n] d0 ...
                        │
          算子收到 Barrier n 时：
          1. 异步把当前状态写到持久化存储
          2. 把 Barrier 继续转发给下游
```

### Barrier 对齐 vs 非对齐

**对齐 Checkpoint（Aligned，默认）**：

- 多输入算子收到**某一路** Barrier 后，会**暂停处理该路数据**（缓存起来）
- 直到**所有输入路**的 Barrier 都到齐，才开始做快照
- **优点**：快照一致、状态小
- **缺点**：反压严重时 Barrier 一直到不齐，Checkpoint 会超时

**非对齐 Checkpoint（Unaligned，1.11+）**：

- Barrier 一到就做快照，**把 in-flight 数据一起存进 Checkpoint**
- **优点**：反压场景下也能正常 Checkpoint
- **缺点**：Checkpoint 变大，恢复变慢

```java
env.getCheckpointConfig().enableUnalignedCheckpoints();
```

### Checkpoint 配置

```java
env.enableCheckpointing(60_000);   // 每 60s 一次
CheckpointConfig cfg = env.getCheckpointConfig();
cfg.setCheckpointingMode(CheckpointingMode.EXACTLY_ONCE);
cfg.setMinPauseBetweenCheckpoints(30_000);      // 两次 CP 间至少 30s
cfg.setCheckpointTimeout(10 * 60_000);          // 10 分钟超时
cfg.setMaxConcurrentCheckpoints(1);             // 同一时刻只允许 1 个
cfg.setExternalizedCheckpointCleanup(
    ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);   // 任务取消后保留 CP
cfg.setTolerableCheckpointFailureNumber(3);     // 容忍 3 次 CP 失败
```

---

## 八、Savepoint：手动触发的"状态存档"

Savepoint 和 Checkpoint 本质都是状态快照，但定位不同：

| 维度 | Checkpoint | Savepoint |
|---|---|---|
| 触发方 | Flink 自动 | 人工触发（`flink savepoint`） |
| 目的 | 故障恢复 | **版本升级、算子改动、scale-in/out** |
| 格式 | 面向引擎，未来不保证兼容 | 稳定格式，版本/拓扑可兼容 |
| 生命周期 | 引擎自动清理 | 用户手动管理 |

常用命令：

```bash
# 手动触发
flink savepoint <jobId> hdfs:///savepoints

# 从 Savepoint 启动
flink run -s hdfs:///savepoints/savepoint-xxx my-job.jar

# 停止并做 Savepoint
flink stop --savepointPath hdfs:///savepoints <jobId>
```

> 升级 Flink 版本、修改算子并行度、改 SQL 逻辑，**都要用 Savepoint**。

---

## 九、状态一致性：三种语义

| 语义 | 含义 | 实现 |
|---|---|---|
| **At-most-once** | 最多一次，可能丢 | 不做 checkpoint |
| **At-least-once** | 至少一次，可能重复 | checkpoint + 普通 sink |
| **Exactly-once** ★ | 精确一次，不丢不重 | checkpoint + 两阶段提交 sink |

### 端到端 Exactly-Once（End-to-End）

Flink 内部的 exactly-once 靠 **Checkpoint + Barrier 对齐**；
但要做到**从 Source 到 Sink** 的端到端 exactly-once，还需要：

1. **Source 可重放**（Kafka、Pulsar 都支持按 offset 回溯）
2. **Sink 支持事务或幂等**：
   - **幂等写入**：相同数据写多次结果一样（如按主键覆盖的 MySQL upsert）
   - **两阶段提交（2PC）**：配合 Checkpoint 的事务性 Sink
     - `preCommit`：Checkpoint 前预提交（写完但不可见）
     - `commit`：Checkpoint 完成后真正提交
     - `abort`：Checkpoint 失败则回滚
   - `TwoPhaseCommitSinkFunction` 是 Flink 提供的模板，`FlinkKafkaProducer` 的 exactly-once 模式就基于它

```
Source(Kafka) ──▶ Flink 算子(状态+CP) ──▶ Sink(Kafka 事务)
   ↑                    ↑                       ↑
 可重放             Chandy-Lamport            2PC
```

---

## 十、状态的恢复与重分配

### 恢复流程

```
Job 失败 → JobManager 取最近一次 Checkpoint 元数据
        → 重启所有 subtask
        → 每个 subtask 从 Checkpoint 读回自己负责的那部分状态
        → Source 从 Checkpoint 里记录的 offset 恢复读取
        → 正常运行
```

### Rescale（改并行度）时状态如何重新分配？

| 状态类型 | 重分配方式 |
|---|---|
| **Keyed State** | 按 `KeyGroup` 划分（默认 128 个 KeyGroup），新并行度下每个 subtask 领取一部分 KeyGroup |
| **Operator ListState** | 打散后轮询分给新 subtask |
| **Operator UnionListState** | 每个新 subtask 拿到**全量**，自己挑选 |
| **BroadcastState** | 每个 subtask 都是全量，直接复制 |

> **最大并行度（maxParallelism）** 决定了 KeyGroup 的数量，**一旦设定就不能改**，否则无法从旧状态恢复。生产上建议显式设置一个较大的值（如 1024、4096）。

---

## 十一、完整示例：带 TTL 的用户实时画像

```java
public class UserProfile extends KeyedProcessFunction<String, Event, Profile> {

    private transient ValueState<Long> pvState;
    private transient MapState<String, Long> categoryCntState;
    private transient ValueState<Long> lastActiveTs;

    @Override
    public void open(Configuration parameters) {
        StateTtlConfig ttl = StateTtlConfig.newBuilder(Time.days(7))
            .setUpdateType(StateTtlConfig.UpdateType.OnCreateAndWrite)
            .setStateVisibility(StateTtlConfig.StateVisibility.NeverReturnExpired)
            .cleanupInRocksdbCompactFilter(1000)
            .build();

        ValueStateDescriptor<Long> pvDesc = new ValueStateDescriptor<>("pv", Long.class);
        pvDesc.enableTimeToLive(ttl);
        pvState = getRuntimeContext().getState(pvDesc);

        MapStateDescriptor<String, Long> catDesc =
            new MapStateDescriptor<>("catCnt", String.class, Long.class);
        catDesc.enableTimeToLive(ttl);
        categoryCntState = getRuntimeContext().getMapState(catDesc);

        ValueStateDescriptor<Long> tsDesc = new ValueStateDescriptor<>("lastTs", Long.class);
        lastActiveTs = getRuntimeContext().getState(tsDesc);
    }

    @Override
    public void processElement(Event e, Context ctx, Collector<Profile> out) throws Exception {
        long pv = Optional.ofNullable(pvState.value()).orElse(0L) + 1;
        pvState.update(pv);

        long cnt = Optional.ofNullable(categoryCntState.get(e.category)).orElse(0L) + 1;
        categoryCntState.put(e.category, cnt);

        lastActiveTs.update(e.ts);

        out.collect(new Profile(e.userId, pv, categoryCntState.entries(), e.ts));
    }
}
```

配套配置：

```java
env.enableCheckpointing(60_000, CheckpointingMode.EXACTLY_ONCE);
env.setStateBackend(new EmbeddedRocksDBStateBackend());
env.getCheckpointConfig().setCheckpointStorage("hdfs:///flink/cp");
env.getCheckpointConfig().setExternalizedCheckpointCleanup(
    ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION);
env.setMaxParallelism(1024);   // 为未来 rescale 预留
```

---

## 十二、最佳实践与踩坑

### 推荐做法

1. **生产默认用 RocksDB 后端 + 增量 Checkpoint**，大状态时才撑得住
2. **所有 Keyed State 都配 TTL**，别让冷 key 永远留着
3. **MaxParallelism 提前设大**（1024/4096），为 rescale 留余地
4. **Checkpoint 间隔 = 1~5 分钟，超时 = 10 分钟**，视业务吞吐调整
5. **Savepoint 用于版本/拓扑升级**，Checkpoint 用于故障恢复
6. **Sink 尽量做成幂等**（按主键 upsert），比 2PC 运维成本低
7. **用 MapState 代替 ValueState\<HashMap\>**，访问粒度更细

### 常见坑

| 坑 | 说明 |
|---|---|
| 在 `open()` 外访问状态 | RuntimeContext 还没准备好，NPE |
| Keyed State 在 `KeyedStream` 之外使用 | 编译报错 / 运行异常 |
| 状态对象可变但忘了 `update()` | RocksDB 后端不会感知对象修改，必须显式 `update` 写回 |
| 不配 TTL 跑半年 | 状态无限膨胀，Checkpoint 超时、任务挂 |
| 修改状态类型后直接恢复 | 序列化不兼容，启动失败；要么加 Schema Evolution，要么换算子 UID |
| 随意改算子 `uid` | Savepoint 里的状态找不到算子，恢复失败——**所有有状态算子都应显式 `.uid("xxx")`** |
| 反压 + 对齐 Checkpoint | Barrier 对不齐，CP 超时；开 unaligned checkpoint 或排查瓶颈 |

---

## 十三、一图看懂整体架构

```
┌─────────────────────────────────────────────────────────────────┐
│                         Flink Job                                │
│                                                                   │
│   Source ─▶ keyBy ─▶ 有状态算子 ─▶ keyBy ─▶ 有状态算子 ─▶ Sink  │
│     │          │         │             │          │         │    │
│     │          │    ValueState     MapState   AggState      │    │
│     │          │         │             │          │         │    │
│     ▼          ▼         ▼             ▼          ▼         ▼    │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │             State Backend (HashMap / RocksDB)              │ │
│  └────────────────────────────────────────────────────────────┘ │
│                             │                                    │
│                   周期性 Checkpoint / 手动 Savepoint             │
│                             ▼                                    │
│                 ┌────────────────────────┐                       │
│                 │   HDFS / S3 / OSS      │                       │
│                 │   (状态快照持久化)      │                       │
│                 └────────────────────────┘                       │
└─────────────────────────────────────────────────────────────────┘
```

---

## 十四、核心三句话总结

1. **状态 = 跨数据的中间结果**：Keyed State 按 key 分片，Operator State 按 subtask 分片
2. **容错 = Checkpoint**：Barrier 在数据流中流动，做一致性快照；失败时从最近 CP 恢复
3. **exactly-once = 可重放 Source + CP + 幂等/事务 Sink**：三者缺一不可

> 一句话精髓：**Flink 的强大之处不在算得多快，而在"算得对、挂了能接着算"——这都靠状态管理**。
