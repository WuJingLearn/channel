# Flink 时间语义与水位线（Watermark）

## 一、为什么要讨论"时间"？

在流处理中，"时间"决定了**一条数据属于哪个窗口、窗口什么时候关闭、结果什么时候输出**。

举个典型例子：统计"10:00:00 ~ 10:00:10"这 10 秒内的订单量。

- 如果一条订单**在 10:00:09 生成**，但因为网络延迟**在 10:00:12 才到达 Flink**——它到底算哪个窗口？
- 如果数据乱序，后到的数据时间戳反而更早，窗口要不要等它？

这就是时间语义要解决的问题。

---

## 二、Flink 的三种时间语义

| 时间类型 | 含义 | 特点 |
|---|---|---|
| **事件时间 Event Time** | 事件**真实发生**的时间（数据自带的时间戳） | 结果准确、可复现，但需要处理乱序 |
| **摄入时间 Ingestion Time** | 事件**进入 Flink Source** 的时间 | 介于两者之间，已较少使用 |
| **处理时间 Processing Time** | 算子**处理该数据**时的系统时间 | 最简单、性能最好，但结果不确定 |

```
事件发生 ──网络传输──▶ Source ──算子处理──▶ 输出
    │                     │                │
  Event Time          Ingestion Time   Processing Time
```

### 1. 处理时间（Processing Time）

- 以算子所在机器的**本地系统时钟**为准
- **优点**：实现简单，延迟最低，无需关心乱序
- **缺点**：同一份数据，不同时间/不同机器跑，结果可能不一样；重跑历史数据会"全部挤在一瞬间"
- **适用**：对准确性要求不高的监控告警、实时大盘

### 2. 事件时间（Event Time）★ 生产主流

- 以**数据本身携带的时间戳**为准（比如日志里的 `logTime`、订单的 `orderTime`）
- **优点**：结果准确、可复现；即使乱序、延迟、重跑都能得到一致结果
- **缺点**：必须处理**乱序数据**，需要引入**水位线**机制

Flink 1.12 之后**默认就是事件时间**，无需再手动设置 `setStreamTimeCharacteristic`。

### 3. 摄入时间（Ingestion Time）

- 数据进入 Source 算子的瞬间打上时间戳
- 相当于"简化版事件时间"，中间节点不会再变化
- 现已很少单独使用

---

## 三、事件时间带来的问题：乱序

理想情况下，数据应该按事件时间顺序到达：

```
事件时间: 1s → 2s → 3s → 4s → 5s   （单调递增，爽）
```

但现实中由于**网络抖动、分布式延迟、分区并行**，实际到达顺序可能是：

```
到达顺序: 1s → 3s → 2s → 5s → 4s   （乱序）
```

问题来了：假设窗口是 `[0s, 5s)`，当 5s 的数据到达时，**窗口能不能关？**

- 关了 → 后面的 4s 数据进不来，结果错了
- 不关 → 要等多久？万一没有更晚的数据，窗口就永远不触发了

**水位线（Watermark）就是为了回答"到底等多久"这个问题。**

---

## 四、水位线（Watermark）是什么？

### 核心定义

> **水位线是一种特殊的时间戳标记，它声明"事件时间 ≤ 水位线的数据已经全部到达"。**

水位线本质是插入在数据流中的一条**特殊消息**（不是数据），携带一个时间戳 `t`，含义是：

> "截止目前，我**认为**事件时间 ≤ t 的数据都已经到了，以后不应该再有 ≤ t 的数据了。"

注意是"认为"—— 水位线是一个**主观估计**，不是绝对保证。

### 水位线如何驱动窗口？

- 窗口 `[start, end)` 的关闭条件是：**水位线 ≥ end**
- 当水位线推进到 `end`，Flink 认为窗口内的数据都到齐了，就触发计算、关闭窗口

```
数据流:  (1s) (3s) (2s) [W=3s] (5s) (4s) [W=5s] (7s) [W=7s]
                          │                 │              │
                    水位线=3s         水位线=5s       水位线=7s
                                          │
                                    窗口[0s,5s) 关闭，触发计算
```

### 为什么叫"水位线"？

想象一个蓄水池，水面就是水位线。水位**只会上涨不会下降**——即使后续来了一条更早的数据，水位线也不会回退（否则关闭过的窗口没法重开）。

---

## 五、水位线的生成策略

Flink 在 `DataStream` 上通过 `assignTimestampsAndWatermarks()` 注入水位线。

### 1. 单调递增（无乱序场景）

数据天然有序，水位线 = 当前最大事件时间：

```java
stream.assignTimestampsAndWatermarks(
    WatermarkStrategy
        .<Event>forMonotonousTimestamps()
        .withTimestampAssigner((event, ts) -> event.getTimestamp())
);
```

### 2. 固定延迟（生产常用）★

允许 **N 秒乱序**，水位线 = 当前最大事件时间 - N：

```java
stream.assignTimestampsAndWatermarks(
    WatermarkStrategy
        .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(3))  // 容忍 3 秒乱序
        .withTimestampAssigner((event, ts) -> event.getTimestamp())
);
```

**推导**：如果已见过最大事件时间是 10s，那么水位线 = 10 - 3 = 7s，意味着 Flink 认为 ≤ 7s 的数据都到齐了。

### 3. 自定义水位线生成器

实现 `WatermarkGenerator` 接口，可做更复杂的策略（如基于数据特征动态调整）：

```java
public class MyWatermarkGenerator implements WatermarkGenerator<Event> {
    private long maxTimestamp = Long.MIN_VALUE;
    private final long maxOutOfOrderness = 3000;  // 3秒

    @Override
    public void onEvent(Event event, long eventTimestamp, WatermarkOutput output) {
        maxTimestamp = Math.max(maxTimestamp, eventTimestamp);
    }

    @Override
    public void onPeriodicEmit(WatermarkOutput output) {
        output.emitWatermark(new Watermark(maxTimestamp - maxOutOfOrderness - 1));
    }
}
```

### 水位线的发射频率

- **周期性发射（默认）**：每 200ms 发射一次（由 `pipeline.auto-watermark-interval` 控制）
- **标记性发射**：每条数据都可能触发（适用于数据稀疏或对延迟敏感的场景）

---

## 六、水位线在并行任务中的传播

关键规则：

> **下游算子的水位线 = 所有上游分区水位线的最小值（min）**

### 为什么取最小值？

假设一个算子有 3 个上游分区，水位线分别是 `[10s, 15s, 8s]`：

- 如果取最大值 15s，那么来自分区 3 的 9s 数据就会被误判为"迟到"
- 取最小值 8s，才能保证**所有上游的数据**都到齐了

```
上游分区1 ──水位线=10s──┐
上游分区2 ──水位线=15s──┼──▶ 下游算子水位线 = min(10,15,8) = 8s
上游分区3 ──水位线=8s ──┘
```

### 带来的副作用：数据倾斜 / 空闲分区

如果某个分区长时间没数据（如 Kafka 的某个 partition 没流量），它的水位线就不前进，整个下游的水位线都被卡住，窗口永远不触发。

**解决办法**：`withIdleness()` 标记空闲分区，让下游跳过它：

```java
WatermarkStrategy
    .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(3))
    .withTimestampAssigner((e, ts) -> e.getTimestamp())
    .withIdleness(Duration.ofMinutes(1));   // 1分钟没数据就标记为空闲
```

---

## 七、迟到数据的三层防线

即使设置了水位线，仍可能有"漏网之鱼"——到达时事件时间已经 < 水位线。Flink 提供三层机制：

### 第一层：水位线延迟

`forBoundedOutOfOrderness(Duration.ofSeconds(3))` —— 让水位线慢 3 秒，给乱序数据留缓冲。

### 第二层：允许窗口迟到（allowedLateness）

窗口关闭后，再保留一段时间，期间有迟到数据到达就**重新触发计算**：

```java
stream
    .keyBy(...)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .allowedLateness(Time.seconds(5))     // 窗口关闭后再等5秒
    .sum("amount");
```

- 水位线到 `windowEnd` 时：第一次触发计算
- 在 `windowEnd` ~ `windowEnd + 5s` 之间到达的迟到数据：每来一条**重算一次**
- 水位线超过 `windowEnd + 5s`：窗口真正销毁

### 第三层：侧输出流（Side Output）

连第二层都错过的数据，丢到侧输出流兜底，绝不丢：

```java
OutputTag<Event> lateTag = new Output Tag<Event>("late-data"){};

SingleOutputStreamOperator<Result> result = stream
    .keyBy(...)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .allowedLateness(Time.seconds(5))
    .sideOutputLateData(lateTag)
    .sum("amount");

DataStream<Event> lateStream = result.getSideOutput(lateTag);  // 单独处理
```

### 三层防线的权衡

| 机制 | 优点 | 代价 |
|---|---|---|
| 水位线延迟 | 简单，窗口只触发一次 | 整体延迟变高 |
| allowedLateness | 延迟低，能兼容迟到数据 | 窗口会多次触发，状态保留时间长 |
| 侧输出流 | 绝不丢数据 | 需要额外的下游逻辑处理 |

**生产推荐组合**：水位线延迟 3~5 秒 + allowedLateness 1~10 分钟 + 侧输出兜底。

---

## 八、完整示例：基于事件时间的订单统计

```java
DataStream<Order> orderStream = env.fromSource(kafkaSource, ...);

OutputTag<Order> lateTag = new OutputTag<Order>("late-orders"){};

SingleOutputStreamOperator<Tuple2<String, Long>> result = orderStream
    // 1. 指定事件时间 + 水位线策略（容忍3秒乱序）
    .assignTimestampsAndWatermarks(
        WatermarkStrategy
            .<Order>forBoundedOutOfOrderness(Duration.ofSeconds(3))
            .withTimestampAssigner((order, ts) -> order.getOrderTime())
            .withIdleness(Duration.ofMinutes(1))
    )
    // 2. 按商品分组
    .keyBy(Order::getProductId)
    // 3. 事件时间 10 秒滚动窗口
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    // 4. 允许迟到 1 分钟
    .allowedLateness(Time.minutes(1))
    // 5. 超出允许迟到的数据走侧输出
    .sideOutputLateData(lateTag)
    // 6. 聚合
    .aggregate(new CountAggregator());

// 正常结果
result.print("正常");
// 超迟数据兜底
result.getSideOutput(lateTag).print("超迟");
```

---

## 九、核心理解总结

### 时间语义选择

```
┌─────────────────────────────────────────────────────┐
│  对准确性要求低 / 想要极致低延迟 → Processing Time   │
│  对准确性要求高 / 需要可复现结果 → Event Time ★      │
└─────────────────────────────────────────────────────┘
```

### 水位线的三句话

1. **水位线是一个时间戳，声明"小于等于它的数据已到齐"**
2. **水位线只会上涨，不会回退**
3. **多输入取最小：下游水位线 = min(所有上游水位线)**

### 事件时间窗口的触发条件

```
水位线 ≥ 窗口结束时间  ──▶  触发计算
水位线 ≥ 窗口结束时间 + allowedLateness  ──▶  销毁窗口
```

### 一张图看懂整体

```
数据流 ──▶ [时间戳 + 水位线生成] ──▶ keyBy ──▶ 窗口 ──▶ 聚合 ──▶ 输出
              │                                  │
         事件时间&水位线                  水位线驱动窗口关闭
                                                 │
                            ┌────────────────────┼──────────────────┐
                            ▼                    ▼                  ▼
                       正常输出            迟到重触发           超迟侧输出
```

**一句话精髓**：事件时间保证**结果正确**，水位线在**正确与延迟**之间取平衡，迟到机制给**极端情况**兜底。

---

## 十、水位线是每条数据都生成吗？

**答案：默认不是，而是每 200ms 周期性发射一次。**

### 水位线的两种发射模式

Flink 内部水位线由 `WatermarkGenerator` 生成，它有两个回调方法：

```java
public interface WatermarkGenerator<T> {
    void onEvent(T event, long eventTimestamp, WatermarkOutput output);   // 每来一条数据触发
    void onPeriodicEmit(WatermarkOutput output);                           // 周期性触发
}
```

对应两种模式：

| 模式 | 触发时机 | 典型实现 |
|---|---|---|
| **周期性（Periodic）** ★ 默认 | 每隔固定时间（默认 200ms）发一次 | `forBoundedOutOfOrderness`、`forMonotonousTimestamps` |
| **标记性（Punctuated）** | 根据每条数据特征决定要不要发 | 自定义 `WatermarkGenerator`，在 `onEvent` 里发 |

### `forBoundedOutOfOrderness` 的真实行为

这是生产上最常用的策略，内部是 `BoundedOutOfOrdernessWatermarks`：

```java
// 伪代码
onEvent(event):
    maxTimestamp = max(maxTimestamp, event.timestamp)   // 每条数据都更新最大时间戳
    // 但不发射水位线！

onPeriodicEmit():
    output.emitWatermark(maxTimestamp - outOfOrderness - 1)   // 每 200ms 发一次
```

关键点：

- **每条数据只做一件事**：更新已见到的最大事件时间戳
- **水位线不是每条都发**：Flink 后台定时器**每 200ms** 把 `maxTs - 乱序容忍度` 当成水位线发出去

### 为什么不每条都发？

- 水位线是特殊消息，会**广播**给所有下游算子，每条都发网络开销翻倍
- 水位线只涨不退，高频发射很多是**重复值**，没意义
- 200ms 的延迟在绝大多数业务里可以忽略

### 发射频率可以调

```java
env.getConfig().setAutoWatermarkInterval(100);   // 改为 100ms
```

或配置文件：

```yaml
pipeline.auto-watermark-interval: 100ms
```

- 设为 **0** 会关闭周期性发射（此时必须用标记性生成器）
- 不建议改得太小，性价比不高

### 标记性水位线示例

只有在数据非常稀疏或有明确结束标记时才需要：

```java
public class PunctuatedGenerator implements WatermarkGenerator<Event> {
    @Override
    public void onEvent(Event event, long ts, WatermarkOutput out) {
        if (event.isEndMarker()) {                  // 只有特定事件才发
            out.emitWatermark(new Watermark(ts));
        }
    }
    @Override
    public void onPeriodicEmit(WatermarkOutput out) {
        // 不做任何事
    }
}
```

---

## 十一、乱序数据流完整演示

### 场景设定

- **窗口**：事件时间滚动窗口，大小 10 秒，即 `[0, 10)`、`[10, 20)` ……
- **水位线策略**：`forBoundedOutOfOrderness(Duration.ofSeconds(2))`，容忍 2 秒乱序
  - 公式：`水位线 = 已见最大事件时间 - 2s - 1ms`（下文简化按 `-2s` 表示）
- **水位线发射**：周期性，每 200ms 发一次
- **数据流**（单位：秒）：

```
到达顺序:  (1s) → (3s) → (2s) → (9s) → (12s) → (8s) → (11s) → (15s) → (21s)
```

> 注意：到达顺序 ≠ 事件时间顺序。比如 9s 之后来了一条 8s（迟到 1 秒）。

### 逐条推演

约定：
- **maxTs** = 已见到的最大事件时间戳
- **WM** = 当前最新水位线 = maxTs - 2s

| 步骤 | 到达事件 | maxTs | 最新 WM | 发生了什么 |
|---|---|---|---|---|
| 1 | `(1s)` | 1 | -1（无效） | 第一条数据，maxTs=1，WM=1-2=-1，没用 |
| 2 | `(3s)` | 3 | **1s** | WM 推到 1s，表示"≤1s 的数据都到齐了"。窗口 `[0,10)` end=10 未到，不触发 |
| 3 | `(2s)` | 3 | 1s | 2s < maxTs=3，**maxTs 不变，WM 不变**。这条是正常乱序，属于窗口 `[0,10)` |
| 4 | `(9s)` | 9 | **7s** | WM 推到 7s，窗口 `[0,10)` end=10 仍未到，不触发 |
| 5 | `(12s)` | 12 | **10s** | ✅ **WM ≥ 10，窗口 `[0,10)` 触发计算！** 含 1、3、2、9 四条数据 |
| 6 | `(8s)` | 12 | 10s | 8s < WM(10)，**且所在窗口 `[0,10)` 已关闭** → **迟到数据**（无 `allowedLateness` 则丢弃） |
| 7 | `(11s)` | 12 | 10s | 属于窗口 `[10,20)`，maxTs 不变，WM 不变 |
| 8 | `(15s)` | 15 | **13s** | WM 推到 13s，窗口 `[10,20)` end=20 未到，不触发 |
| 9 | `(21s)` | 21 | **19s** | WM 推到 19s，仍 < 20，**不触发** |
| ... | 再来一条 `(22s)` | 22 | **20s** | ✅ **窗口 `[10,20)` 触发**，含 12、11、15 三条数据 |

### 可视化时间轴

```
事件时间轴(s):   0    2    4    6    8    10   12   14   16   18   20   22
                │    │    │    │    │    │    │    │    │    │    │    │
数据到达:       ①    ③②              ④                  ⑤    ⑥    ⑦
            (1)   (3)(2)            (9)               (12)  (8)  (11)...
                                                      ↑
                                         WM=10，触发 [0,10) 窗口

水位线推进:     -1 → 1s → 1s → 7s → 10s ─────────── 13s ─── 19s → 20s
                                  │                              │
                          窗口[0,10)关闭触发                窗口[10,20)关闭触发
                          计算 {1, 3, 2, 9}                 计算 {12, 11, 15}
```

### 关键结论

#### 1. 水位线什么时候"生成"？

- **每条数据到达**：只更新 `maxTs`，不一定立刻发水位线
- **每 200ms 一次定时器**：按 `maxTs - 乱序容忍度` 发射
- 如果 `maxTs` 没变（步骤 3、6、7），新发出的水位线值和上次一样，不会向下游推进

#### 2. 窗口什么时候"触发"？

一句话：**水位线 ≥ 窗口结束时间（end）时触发。**

- 步骤 5：WM 从 7 跳到 10，越过 `[0,10)` 的 end=10 → 触发
- 步骤 9 的后续：WM 从 19 跳到 20，越过 `[10,20)` 的 end=20 → 触发

#### 3. 乱序数据怎么被"接住"？

- 步骤 3 的 `(2s)`：到达时 WM=1 < 2s，**所属窗口 [0,10) 还未关** → 正常进窗口
- 步骤 6 的 `(8s)`：到达时 WM=10 ≥ 8s，**所属窗口 [0,10) 已关** → 迟到数据
  - 没设置 `allowedLateness` → 直接丢
  - 设置了 `allowedLateness(5s)` 且 WM < 15 → 重新触发一次计算
  - 设置了 `sideOutputLateData` → 进侧输出流兜底

#### 4. 乱序容忍度如何影响结果？

若把容忍度从 `2s` 改成 `5s`：

- 步骤 5 时 WM = 12 - 5 = 7 < 10 → 窗口**不会在这时触发**
- 要等到 `maxTs ≥ 15`，WM 才能到 10 → 窗口触发**延后**
- 好处：后来的 `(8s)` 可能就不是迟到了，能被正常纳入计算

> **容忍度 = 准确性 vs 延迟的权衡**：设大结果更准但延迟高，设小延迟低但迟到更多。

### 代码对照

```java
env.setParallelism(1);
env
    .fromElements(
        event(1), event(3), event(2), event(9),
        event(12), event(8), event(11), event(15), event(21)
    )
    .assignTimestampsAndWatermarks(
        WatermarkStrategy
            .<Event>forBoundedOutOfOrderness(Duration.ofSeconds(2))
            .withTimestampAssigner((e, ts) -> e.getTimestamp())
    )
    .keyBy(Event::getKey)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .process(new ProcessWindowFunction<>() {
        @Override
        public void process(String key, Context ctx,
                          Iterable<Event> elements, Collector<String> out) {
            long count = 0;
            StringBuilder ts = new StringBuilder();
            for (Event e : elements) { count++; ts.append(e.getTimestamp()).append(","); }
            out.collect(String.format("窗口[%d,%d) 数量=%d 事件时间=%s",
                ctx.window().getStart(), ctx.window().getEnd(), count, ts));
        }
    })
    .print();
```

预期输出：

```
窗口[0,10)   数量=4  事件时间=1,3,2,9
窗口[10,20)  数量=3  事件时间=12,11,15
窗口[20,30)  数量=1  事件时间=21      ← 需要更晚的数据推进水位线到30才会触发
```

### 一句话记住

> **窗口触发只看一件事：水位线是否已经越过了窗口的 end。**
> 而水位线由 `已见最大事件时间 - 乱序容忍度` 决定，每 200ms 定时向下游发射一次。

---

## 十二、Flink SQL 中处理水位线、允许迟到、侧输出

Flink SQL 与 DataStream API 差别很大——**SQL 更声明式，水位线在 DDL 里定义，迟到配置通过参数，侧输出只能走 DataStream 兜底**。

### 1. 水位线：在建表 DDL 里声明

Flink SQL 的水位线是**表的一部分**，在 `CREATE TABLE` 时通过 `WATERMARK FOR` 子句定义。

#### 基本语法

```sql
CREATE TABLE orders (
    order_id    STRING,
    product_id  STRING,
    amount      DECIMAL(10, 2),
    order_time  TIMESTAMP(3),                             -- 事件时间字段
    WATERMARK FOR order_time AS order_time - INTERVAL '3' SECOND   -- 容忍3秒乱序
) WITH (
    'connector' = 'kafka',
    'topic' = 'orders',
    'properties.bootstrap.servers' = 'localhost:9092',
    'format' = 'json'
);
```

等价于 DataStream API 中的 `forBoundedOutOfOrderness(Duration.ofSeconds(3))`。

#### 时间字段的两种来源

| 来源 | 写法 |
|---|---|
| 字段本身就是 `TIMESTAMP` | `WATERMARK FOR ts AS ts - INTERVAL '3' SECOND` |
| 字段是 BIGINT（毫秒） | 先用计算列转换：`ts AS TO_TIMESTAMP_LTZ(event_ms, 3)` |
| Kafka 元数据时间 | `ts TIMESTAMP_LTZ(3) METADATA FROM 'timestamp'` |

Kafka 毫秒时间戳示例：

```sql
CREATE TABLE orders (
    order_id   STRING,
    event_ms   BIGINT,
    ts         AS TO_TIMESTAMP_LTZ(event_ms, 3),          -- 计算列
    WATERMARK FOR ts AS ts - INTERVAL '3' SECOND
) WITH (...);
```

#### 处理时间（不需要水位线）

```sql
proc_time AS PROCTIME()   -- 直接声明处理时间列，无需 WATERMARK
```

#### 空闲源与水位线对齐

```sql
-- 空闲分区超时
'scan.watermark.idle-timeout' = '1 min'

-- 水位线对齐（1.17+）
'scan.watermark.alignment.group' = 'alignment-group-1'
'scan.watermark.alignment.max-drift' = '1min'
'scan.watermark.alignment.update-interval' = '1s'
```

---

### 2. 允许窗口迟到：通过配置参数

SQL 中没有 `allowedLateness()` 这个 API，改用两个**表配置**控制。

#### 晚触发（Late Fire）—— 等价于 allowedLateness

```sql
SET 'table.exec.emit.late-fire.enabled' = 'true';
SET 'table.exec.emit.late-fire.delay' = '1 min';    -- 迟到数据每累积1分钟触发一次重算
```

- 窗口关闭后，在**延迟期内**到达的迟到数据会**重新触发计算**
- 状态会保留更久，**注意内存/checkpoint 大小**

#### 早触发（Early Fire）—— 窗口未结束先出部分结果

```sql
SET 'table.exec.emit.early-fire.enabled' = 'true';
SET 'table.exec.emit.early-fire.delay' = '10 s';    -- 窗口未关就每10秒先发一次中间结果
```

适合做**实时大盘**这种"要及时看到进展，结果最终会修正"的场景。

#### 状态保留时间（兜底）

```sql
SET 'table.exec.state.ttl' = '1 d';                  -- 状态 1 天后清理
```

防止 state 无限膨胀。

#### 推荐使用 TVF 窗口（Flink 1.13+）

```sql
-- 每 10 秒滚动窗口，统计每个商品销量
SELECT
    product_id,
    window_start,
    window_end,
    SUM(amount) AS total_amount,
    COUNT(*)    AS order_cnt
FROM TABLE(
    TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '10' SECOND)
)
GROUP BY product_id, window_start, window_end;
```

TVF 窗口类型：

| 函数 | 窗口类型 |
|---|---|
| `TUMBLE` | 滚动窗口 |
| `HOP`    | 滑动窗口 |
| `CUMULATE` | 累积窗口（每隔 N 分钟输出一次从 0 点至今的累计） |
| `SESSION` | 会话窗口（1.19+） |

---

### 3. 侧输出流（迟到数据兜底）

**坏消息**：纯 SQL **不直接支持** side output。Flink SQL 本身没有 `sideOutputLateData` 的语法，超过晚触发延迟的迟到数据会被**直接丢弃**。

#### 方案 1：SQL + DataStream 混合（推荐）

先用 DataStream 做水位线 + 窗口 + 侧输出，再转 Table 给 SQL 用：

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
StreamTableEnvironment tEnv = StreamTableEnvironment.create(env);

OutputTag<Order> lateTag = new OutputTag<Order>("late-data"){};

// 1. DataStream 处理水位线 + 窗口 + 侧输出
SingleOutputStreamOperator<Result> mainStream = kafkaStream
    .assignTimestampsAndWatermarks(
        WatermarkStrategy.<Order>forBoundedOutOfOrderness(Duration.ofSeconds(3))
            .withTimestampAssigner((o, ts) -> o.getOrderTime())
    )
    .keyBy(Order::getProductId)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .allowedLateness(Time.minutes(1))
    .sideOutputLateData(lateTag)
    .aggregate(...);

DataStream<Order> lateStream = mainStream.getSideOutput(lateTag);

// 2. 注册成 Table，继续用 SQL 处理
tEnv.createTemporaryView("main_result", mainStream);
tEnv.createTemporaryView("late_orders", lateStream);

tEnv.executeSql("INSERT INTO result_sink SELECT * FROM main_result");
tEnv.executeSql("INSERT INTO late_sink   SELECT * FROM late_orders");
```

#### 方案 2：用 CURRENT_WATERMARK() 过滤迟到数据（1.15+）

```sql
-- 迟到数据分流到另一张表
INSERT INTO late_orders_sink
SELECT *
FROM orders
WHERE order_time < CURRENT_WATERMARK(order_time);

-- 正常数据走窗口聚合
INSERT INTO order_stat_sink
SELECT product_id, window_start, window_end, SUM(amount)
FROM TABLE(TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '10' SECOND))
WHERE order_time >= CURRENT_WATERMARK(order_time)
GROUP BY product_id, window_start, window_end;
```

> 注意：粒度比 DataStream 的 side output 粗，只能区分"到达时已过水位线"的数据，不能像 `allowedLateness` 那样区分"过了窗口但在允许迟到期内"。

---

### 4. 完整实战：三者一起用

```sql
-- 1. 建表（含水位线）
CREATE TABLE orders (
    order_id    STRING,
    product_id  STRING,
    amount      DECIMAL(10, 2),
    order_time  TIMESTAMP(3),
    WATERMARK FOR order_time AS order_time - INTERVAL '3' SECOND
) WITH ('connector' = 'kafka', ...);

CREATE TABLE order_stat_sink (
    product_id    STRING,
    window_start  TIMESTAMP(3),
    window_end    TIMESTAMP(3),
    total_amount  DECIMAL(20, 2),
    order_cnt     BIGINT
) WITH ('connector' = 'kafka', ...);

CREATE TABLE late_orders_sink (
    order_id    STRING,
    product_id  STRING,
    amount      DECIMAL(10, 2),
    order_time  TIMESTAMP(3)
) WITH ('connector' = 'kafka', ...);

-- 2. 配置参数
SET 'table.exec.emit.late-fire.enabled' = 'true';
SET 'table.exec.emit.late-fire.delay'   = '1 min';
SET 'table.exec.state.ttl'              = '1 d';

-- 3. 主聚合
INSERT INTO order_stat_sink
SELECT
    product_id,
    window_start,
    window_end,
    SUM(amount)      AS total_amount,
    COUNT(*)         AS order_cnt
FROM TABLE(
    TUMBLE(TABLE orders, DESCRIPTOR(order_time), INTERVAL '10' SECOND)
)
GROUP BY product_id, window_start, window_end;

-- 4. 超迟数据兜底
INSERT INTO late_orders_sink
SELECT order_id, product_id, amount, order_time
FROM orders
WHERE order_time < CURRENT_WATERMARK(order_time);
```

---

### 5. SQL vs DataStream 对照速查表

| 能力 | DataStream API | Flink SQL |
|---|---|---|
| 指定事件时间 | `assignTimestampsAndWatermarks` | DDL 中 `WATERMARK FOR ts AS ts - INTERVAL '3' SECOND` |
| 乱序容忍 | `forBoundedOutOfOrderness(3s)` | `WATERMARK ... AS ts - INTERVAL '3' SECOND` |
| 空闲分区 | `.withIdleness(Duration.ofMinutes(1))` | `'scan.watermark.idle-timeout' = '1 min'` |
| 允许迟到 | `.allowedLateness(Time.minutes(1))` | `SET table.exec.emit.late-fire.enabled = true` + `.delay = '1 min'` |
| 提前输出 | 自定义 Trigger | `SET table.exec.emit.early-fire.enabled = true` |
| 侧输出流 | `sideOutputLateData(lateTag)` | **不直接支持**，需混合 DataStream 或用 `CURRENT_WATERMARK()` 过滤 |
| 窗口定义 | `TumblingEventTimeWindows.of(...)` | `TUMBLE` / `HOP` / `CUMULATE` TVF |

---

### 6. 一句话总结

> **SQL 把水位线放到了建表阶段**、**把迟到处理变成了参数开关**，代价是**失去了 side output 这种细粒度能力**——遇到"迟到数据必须兜底"的场景，就要退回 DataStream API 或混合使用。
