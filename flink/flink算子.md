## Flink 算子

### 算子概念

Flink 中的算子（Operator）是数据流处理管道的基本构建块，用于对 DataStream 进行各种操作。一个完整的 Flink 程序通常由以下三类算子组成：

- **Source 算子**：数据源，负责从外部系统读取数据，生成初始的 DataStream
- **Transformation 算子**：转换算子，对数据流进行处理和变换，输出新的 DataStream
- **Sink 算子**：数据输出，负责将处理结果写入外部系统

其中转换算子是最核心的部分，又可以细分为 **基本转换算子** 和 **聚合算子** 两大类。

---

### 基本转换算子

基本转换算子可以直接作用于 `DataStream`，对流中的每个元素进行变换。

#### 1. map（映射）

对流中的每个元素进行一对一转换，输入一个元素，输出一个元素。

**适用场景**：类型转换、数据清洗、字段提取等。

```java
DataStream<String> input = env.fromElements("hello", "flink");
DataStream<Integer> lengths = input.map(s -> s.length());
// 输出: 5, 5
```

#### 2. flatMap（扁平映射）

对流中的每个元素进行一对多转换，输入一个元素，可以输出零个、一个或多个元素。

**适用场景**：分词、拆分记录等。

```java
DataStream<String> input = env.fromElements("hello world", "flink stream");
DataStream<String> words = input.flatMap((String line, Collector<String> out) -> {
    for (String word : line.split(" ")) {
        out.collect(word);
    }
}).returns(Types.STRING);
// 输出: hello, world, flink, stream
```

> 注意：使用 Lambda 表达式时，Java 的类型擦除可能导致 Flink 无法推断返回类型，需要通过 `.returns()` 显式指定。

#### 3. filter（过滤）

对流中的每个元素进行条件判断，保留满足条件的元素，丢弃不满足的元素。

**适用场景**：数据过滤、异常值剔除等。

```java
DataStream<Integer> input = env.fromElements(1, 5, 12, 8, 20);
DataStream<Integer> filtered = input.filter(value -> value > 10);
// 输出: 12, 20
```

#### 4. keyBy（按键分区）

将数据流按照指定的 key 进行逻辑分区，相同 key 的数据会被分到同一个分区，返回 `KeyedStream`。

**适用场景**：分组聚合的前置操作，是使用聚合算子的前提。

```java
DataStream<Tuple2<String, Integer>> input = env.fromElements(
    Tuple2.of("a", 1), Tuple2.of("b", 2), Tuple2.of("a", 3)
);
KeyedStream<Tuple2<String, Integer>, String> keyed = input.keyBy(value -> value.f0);
```

> 注意：`keyBy` 不是转换算子，而是对数据流进行重新分区的操作。它是聚合算子的基础，后续的 `sum`、`reduce` 等操作都必须在 `KeyedStream` 上调用。

#### 5. process（处理函数）

最底层、最灵活的转换算子，提供对时间戳、定时器、状态、侧输出流的完全控制。

**适用场景**：复杂业务逻辑、需要访问事件时间或定时器等高级功能。

```java
DataStream<String> input = env.fromElements("hello", "flink");
SingleOutputStreamOperator<String> result = input.process(
    new ProcessFunction<String, String>() {
        @Override
        public void processElement(String value, Context ctx, Collector<String> out) {
            out.collect(value.toUpperCase());
        }
    }
);
// 输出: HELLO, FLINK
```

---

### 聚合算子

聚合算子是在 `KeyedStream` 上执行的滚动聚合操作，**必须先通过 `keyBy()` 分组后才能使用**。

#### 1. sum（求和）

对指定字段进行滚动求和。

```java
DataStream<Tuple2<String, Integer>> input = env.fromElements(
    Tuple2.of("a", 1), Tuple2.of("a", 2), Tuple2.of("b", 3)
);
DataStream<Tuple2<String, Integer>> result = input.keyBy(value -> value.f0).sum(1);
// 输出: (a,1), (a,3), (b,3)
```

#### 2. min / max（最小值 / 最大值）

取指定字段的最小值或最大值，但**其他字段不保证是同一条记录的值**，只会更新指定字段。

```java
DataStream<Tuple3<String, Integer, Integer>> input = env.fromElements(
    Tuple3.of("a", 1, 100), Tuple3.of("a", 3, 200), Tuple3.of("a", 2, 300)
);
DataStream<Tuple3<String, Integer, Integer>> result = input.keyBy(value -> value.f0).min(1);
// 注意：第三个字段不一定是最小值对应的那条记录的值
```

#### 3. minBy / maxBy（最小值 / 最大值所在记录）

取指定字段最小值或最大值**对应的整条记录**，所有字段都来自同一条原始数据。

```java
DataStream<Tuple3<String, Integer, Integer>> input = env.fromElements(
    Tuple3.of("a", 1, 100), Tuple3.of("a", 3, 200), Tuple3.of("a", 2, 300)
);
DataStream<Tuple3<String, Integer, Integer>> result = input.keyBy(value -> value.f0).minBy(1);
// 所有字段都来自 f1 最小值对应的那条完整记录
```

> **`min` 与 `minBy` 的区别**：`min` 只保证目标字段是最小值，其他字段可能不一致；`minBy` 返回的是目标字段最小值所在的那条完整记录。`max` 与 `maxBy` 同理。

#### 4. reduce（归约）

对相同 key 的元素进行自定义的滚动聚合，灵活度最高。

```java
DataStream<Tuple2<String, Integer>> input = env.fromElements(
    Tuple2.of("a", 1), Tuple2.of("a", 2), Tuple2.of("b", 3), Tuple2.of("a", 4)
);
DataStream<Tuple2<String, Integer>> result = input.keyBy(value -> value.f0)
    .reduce((v1, v2) -> Tuple2.of(v1.f0, v1.f1 + v2.f1));
// 输出: (a,1), (a,3), (b,3), (a,7)
```

---

### 算子总结

| 算子 | 类型 | 输入 → 输出 | 说明 |
|------|------|-------------|------|
| `map` | 基本转换 | 1 → 1 | 一对一转换 |
| `flatMap` | 基本转换 | 1 → 0~N | 一对多转换 |
| `filter` | 基本转换 | 1 → 0或1 | 条件过滤 |
| `keyBy` | 分区操作 | DataStream → KeyedStream | 按 key 分组 |
| `process` | 基本转换 | 1 → 0~N | 底层处理函数，最灵活 |
| `sum` | 聚合（需 keyBy） | KeyedStream → DataStream | 滚动求和 |
| `min`/`max` | 聚合（需 keyBy） | KeyedStream → DataStream | 取最值，其他字段不保证 |
| `minBy`/`maxBy` | 聚合（需 keyBy） | KeyedStream → DataStream | 取最值对应的完整记录 |
| `reduce` | 聚合（需 keyBy） | KeyedStream → DataStream | 自定义滚动聚合 |

### 富函数（Rich Functions）

每个基本算子都有对应的 Rich 版本（如 `RichMapFunction`、`RichFilterFunction`），提供额外能力：

- **`open()` / `close()`**：生命周期方法，分别在算子初始化和销毁时调用，适合做资源初始化和释放
- **`getRuntimeContext()`**：获取运行时上下文，可以访问状态（State）、累加器（Accumulator）、广播变量等

```java
DataStream<String> result = input.map(new RichMapFunction<String, String>() {
    @Override
    public void open(Configuration parameters) throws Exception {
        // 初始化资源，如数据库连接
    }

    @Override
    public String map(String value) throws Exception {
        return value.toUpperCase();
    }

    @Override
    public void close() throws Exception {
        // 释放资源
    }
});
```

---

## 并行度、Slot 与 keyBy 分区

### 用一个 WordCount 示例理解三者

假设我们有如下 Flink 程序，并行度设为 2：

```java
env.setParallelism(2);

DataStream<String> input = env.fromElements(
    "hello world", "hello flink", "flink world"
);

input.flatMap(new WordSplitter())   // 并行度 2
     .keyBy(word -> word.f0)        // 按单词分区
     .sum(1)                        // 并行度 2
     .print();                      // 并行度 2
```

#### 并行度（Parallelism）

并行度是指一个算子同时运行多少个并行实例（SubTask）。

- **并行度 = 2** 意味着 `flatMap` 算子会启动 2 个 SubTask，各自独立处理一部分数据
- 并行度可以在多个层级设置，优先级从高到低：**算子级 > env 级 > 配置文件级**

```
并行度 = 2 时的 flatMap 算子：

输入数据: ["hello world", "hello flink", "flink world"]
              ↓                    ↓
     ┌────────────────┐   ┌────────────────┐
     │  flatMap[0]    │   │  flatMap[1]    │
     │ "hello world"  │   │ "hello flink"  │
     │ "flink world"  │   │                │
     └────────────────┘   └────────────────┘
```

#### Slot（任务槽）

Slot 是 TaskManager 中用于执行 SubTask 的资源单元（CPU + 内存的隔离单位）。

```
假设集群有 2 个 TaskManager，每个 TaskManager 有 2 个 Slot：

┌──── TaskManager 1 ────┐  ┌──── TaskManager 2 ────┐
│  ┌───────┐ ┌───────┐  │  │  ┌───────┐ ┌───────┐  │
│  │ Slot 0│ │ Slot 1│  │  │  │ Slot 2│ │ Slot 3│  │
│  └───────┘ └───────┘  │  │  └───────┘ └───────┘  │
└───────────────────────┘  └───────────────────────┘
           总共 4 个 Slot
```

关键概念：

- **一个 Slot 可以运行一个完整的算子链（Pipeline）**，Flink 会将可以链接的算子合并后放到同一个 Slot 中执行
- **程序所需 Slot 数 = 最大并行度**。上面的例子中最大并行度为 2，所以最少需要 2 个 Slot
- Slot 之间**内存隔离**，但**共享 CPU**（同一个 TaskManager 内）

#### keyBy 分区如何工作

`keyBy` 按 `hash(key) % 下游并行度` 决定数据发往哪个下游 SubTask。

```
flatMap 输出的数据（并行度 = 2）：

flatMap[0] 输出:  (hello,1)  (world,1)  (flink,1)  (world,1)
flatMap[1] 输出:  (hello,1)  (flink,1)

                    ↓ keyBy(word -> word.f0) ↓

按 key 的 hash 值重新分区（下游并行度 = 2）：
  hash("hello") % 2 = 0  →  发往 sum[0]
  hash("world") % 2 = 1  →  发往 sum[1]
  hash("flink") % 2 = 0  →  发往 sum[0]

                    ↓ 结果 ↓

┌─── sum[0] ───────────────┐  ┌─── sum[1] ───────────────┐
│ 收到:                     │  │ 收到:                     │
│   (hello,1) ← flatMap[0] │  │   (world,1) ← flatMap[0] │
│   (flink,1) ← flatMap[0] │  │   (world,1) ← flatMap[0] │
│   (hello,1) ← flatMap[1] │  │                           │
│   (flink,1) ← flatMap[1] │  │                           │
│                           │  │                           │
│ 聚合结果:                  │  │ 聚合结果:                  │
│   (hello, 2)              │  │   (world, 2)              │
│   (flink, 2)              │  │                           │
└───────────────────────────┘  └───────────────────────────┘
```

keyBy 的核心保证：

- **相同 key 的数据一定会被分到同一个 SubTask**，这是聚合正确性的前提
- **不同 key 可能在同一个 SubTask**（hash 冲突时）
- keyBy 会触发**网络 Shuffle**（数据在不同 SubTask 间通过网络传输），这是性能开销较大的操作

#### 三者的关系

| 概念 | 作用 | 关系 |
|------|------|------|
| **并行度** | 决定算子有多少个并行实例（SubTask） | 决定了需要多少个 Slot |
| **Slot** | TaskManager 中的资源隔离单元 | 每个 Slot 运行一条算子链的 SubTask |
| **keyBy** | 按 key 的 hash 值重新分区 | 保证相同 key 到同一个 SubTask，触发网络 Shuffle |

> 形象比喻：把 Flink 想象成一个工厂。**并行度**是流水线的数量，**Slot** 是工位（每条流水线占一个工位），**keyBy** 是分拣员——它把贴有相同标签的包裹分到同一条流水线上处理。

---

### 上下游并行度不同时 keyBy 的行为

假设 flatMap 并行度是 2，sum 并行度是 3，keyBy 的分区计算按**下游并行度**来：

```
hash("hello") % 3 = 0  →  发往 sum[0]
hash("world") % 3 = 1  →  发往 sum[1]
hash("flink") % 3 = 2  →  发往 sum[2]
```

此时每个 flatMap SubTask 都要和每个 sum SubTask 建立网络连接，形成**全连接（All-to-All）**拓扑：

```
  flatMap（并行度 2）                     sum（并行度 3）
                          keyBy
┌─── flatMap[0] ───┐    Shuffle     ┌─── sum[0] ──────────────┐
│ (hello,1) ───────────────────────→│ (hello,1) ← flatMap[0] │
│ (world,1) ──────────────────┐     │ (hello,1) ← flatMap[1] │
│ (flink,1) ─────────────┐   │     │ 聚合: (hello, 2)        │
│ (world,1) ─────────┐   │   │     └─────────────────────────┘
└──────────────────┘  │   │   │
                      │   │   │     ┌─── sum[1] ──────────────┐
┌─── flatMap[1] ───┐  │   │   └───→│ (world,1) ← flatMap[0] │
│ (hello,1) ──────────────────────→│ (world,1) ← flatMap[0] │
│ (flink,1) ──────────┐  │         │ 聚合: (world, 2)        │
└──────────────────┘   │  │         └─────────────────────────┘
                       │  │
                       │  │         ┌─── sum[2] ──────────────┐
                       │  └────────→│ (flink,1) ← flatMap[0] │
                       └───────────→│ (flink,1) ← flatMap[1] │
                                    │ 聚合: (flink, 2)        │
                                    └─────────────────────────┘

总连接数 = 上游并行度 × 下游并行度 = 2 × 3 = 6 条
```

> keyBy 的分区只看下游并行度。不管上游有几个并行实例，只要 key 相同，数据一定会通过网络 Shuffle 汇聚到下游的同一个 SubTask。

---

### Slot 数量必须 ≥ 最大并行度

如果 sum 算子的并行度设为 3，**至少需要 3 个 Slot**，只有 2 个 Slot 是不行的。

原因：**Slot 数量必须 ≥ 程序中所有算子的最大并行度**。如果不够，作业直接无法启动，会抛出异常：

```
org.apache.flink.runtime.jobmanager.scheduler.NoResourceAvailableException: 
Could not allocate all requires slots within timeout. 
Slots required: 3, slots available: 2
```

Flink 默认开启了 **Slot Sharing（Slot 共享）**：同一个 Slot 内可以运行**不同算子**的 SubTask（如 flatMap[0] 和 sum[0]），但**同一个算子的不同 SubTask 不能共享同一个 Slot**。

```
✅ 合法：同一个 Slot 中放 flatMap[0] + sum[0]（不同算子的 SubTask）
❌ 非法：同一个 Slot 中放 sum[0] + sum[1]（同一个算子的两个 SubTask）
```

Slot 分配示意（flatMap 并行度 2，sum 并行度 3）：

```
┌──── Slot 0 ─────┐  ┌──── Slot 1 ─────┐  ┌──── Slot 2 ─────┐
│  flatMap[0]     │  │  flatMap[1]     │  │  (无 flatMap)    │
│  sum[0]         │  │  sum[1]         │  │  sum[2]          │
└─────────────────┘  └─────────────────┘  └─────────────────┘
```

| 场景 | 最大并行度 | 所需 Slot 数 | 2 个 Slot 够吗 |
|------|-----------|-------------|--------------|
| flatMap=2, sum=2 | 2 | 2 | ✅ 够 |
| flatMap=2, sum=3 | 3 | 3 | ❌ 不够 |
| flatMap=4, sum=3 | 4 | 4 | ❌ 不够 |

> 公式：**所需 Slot 数 = max(所有算子的并行度)**。Slot 不够时作业直接报错，不会降级运行。

---

### 同一个 Slot 中的算子如何运行

同一个 Slot 中放 flatMap[0] + sum[0] 时，有两种情况：

#### 情况一：可以链接（无 keyBy），同一线程同步执行

如果上下游算子之间没有 Shuffle 操作（如 `source → map → filter`），Flink 会将它们合并为**算子链（Operator Chaining）**，运行在同一个线程中，通过方法调用直接传递数据：

```
┌──────────── Slot 0（一个线程）────────────┐
│                                          │
│   数据到达                                │
│      ↓                                   │
│   map[0].processElement(data)            │
│      ↓  （方法调用，直接传递，无需网络）      │
│   filter[0].processElement(result)       │
│      ↓                                   │
│   输出结果                                │
│                                          │
└──────────────────────────────────────────┘
```

#### 情况二：被 keyBy 打断，不同线程异步执行

如果中间有 keyBy 等 Shuffle 操作（如 `flatMap → keyBy → sum`），即使在同一个 Slot 中，flatMap 和 sum 也运行在**不同线程**中，通过序列化 + 网络缓冲区异步交换数据：

```
┌────────────── Slot 0 ──────────────┐
│                                    │
│  线程 A:  flatMap[0]               │
│     │                              │
│     ↓ （序列化 → 网络缓冲区）        │
│                                    │
│  线程 B:  sum[0]                   │
│                                    │
└────────────────────────────────────┘
```

#### 对比总结

| 场景 | 是否同一线程 | 数据传递方式 | 性能 |
|------|------------|------------|------|
| 可以链接（如 `map → filter`，无 Shuffle） | ✅ 同一线程 | 方法直接调用 | 🚀 高 |
| 被 keyBy 打断（如 `flatMap → keyBy → sum`） | ❌ 不同线程 | 序列化 + 网络缓冲区 | 较低 |

> 同一个 Slot 中的算子，如果能合成算子链就共享一个线程同步执行；如果被 keyBy 等 Shuffle 操作打断，就运行在不同线程中异步并行执行。

---

## Slot 共享、算子链与线程模型

### 并行度都是 1 时，算子一定在同一个 Slot 吗？

以 `flatMap(并行度1) → keyBy → sum(并行度1)` 为例：

**大概率在同一个 Slot，但不是绝对的。**

Flink 默认开启了 **Slot Sharing**，只要两个算子在同一个 Slot Sharing Group（默认都是 `default`），它们就可以共享同一个 Slot。由于最大并行度 = 1，整个作业只需要 1 个 Slot：

```
┌──────────── Slot 0 ────────────┐
│  Source[0]                     │
│  flatMap[0]                    │
│  sum[0]                        │
│  print[0]                      │
└────────────────────────────────┘
```

但如果人为修改了 Slot Sharing Group，就不一定了：

```java
// 不同 Group → 即使并行度都是 1，也会占用 2 个 Slot
input.flatMap(new WordSplitter()).slotSharingGroup("group1")
     .keyBy(word -> word.f0)
     .sum(1).slotSharingGroup("group2");
```

**注意**：即使在同一个 Slot 中，由于中间有 keyBy，flatMap 和 sum 仍然运行在**不同的线程**中：

```
┌──────────── Slot 0 ────────────┐
│                                │
│  线程 A: Source[0] → flatMap[0]│  ← 可以链接
│     │                          │
│     ↓ keyBy（打断算子链）        │
│     ↓ 本地网络缓冲区传递         │
│                                │
│  线程 B: sum[0] → print[0]    │  ← 可以链接
│                                │
└────────────────────────────────┘
```

> **"在同一个 Slot" ≠ "在同一个线程"。** Slot 是资源分配单位，线程是执行单位。keyBy 不影响 Slot 共享，但一定会打断算子链。

---

### 什么条件下算子一定在同一个 Slot 且形成算子链？

必须**同时满足**以下所有条件：

| 条件 | 说明 |
|------|------|
| 上下游并行度相同 | 如 `map` 并行度 2，`filter` 并行度也是 2 |
| 没有数据重分区 | 中间没有 `keyBy`、`rebalance`、`shuffle`、`broadcast` 等操作 |
| 在同一个 Slot Sharing Group | 默认都是 `default`，除非手动修改 |
| 没有手动禁用算子链 | 没有调用 `.disableChaining()` 或 `env.disableOperatorChaining()` |
| 下游没有强制开始新链 | 没有调用 `.startNewChain()` |

典型的可以形成算子链的场景：

```java
// ✅ 这三个算子会合并为一个算子链，运行在同一个线程中
input.map(value -> value.toUpperCase())      // 并行度 2
     .filter(value -> value.length() > 3)     // 并行度 2
     .map(value -> value + "!")               // 并行度 2
```

```
┌──────── Slot 0（线程 A）────────┐  ┌──────── Slot 1（线程 B）────────┐
│                                │  │                                │
│  map[0] → filter[0] → map[0]  │  │  map[1] → filter[1] → map[1]  │
│  （一个算子链，方法直接调用）     │  │  （一个算子链，方法直接调用）     │
│                                │  │                                │
└────────────────────────────────┘  └────────────────────────────────┘
```

会打断算子链的操作：

```java
input.map(...).keyBy(...).sum(1);                // ❌ keyBy 打断
input.map(...).rebalance().filter(...);           // ❌ rebalance 打断
input.map(...).setParallelism(2)
     .filter(...).setParallelism(4);              // ❌ 并行度不同，打断
input.map(...).disableChaining().filter(...);     // ❌ 手动禁用
input.map(...).filter(...).startNewChain();       // ❌ 强制开始新链
```

---

### 算子链内部的执行方式

算子链中的所有函数共享**同一个线程**，处理一条数据时，按顺序**同步调用**所有函数：

```
时间线 ──────────────────────────────────────────────→

线程 A（Slot 0 中的算子链 map → filter → map）:

  ┌─ 数据1 ──────────────┐ ┌─ 数据2 ──────────────┐ ┌─ 数据3 ─...
  │ map → filter → map   │ │ map → filter → map   │ │ map → ...
  └───────────────────────┘ └───────────────────────┘ └─────────

同一时刻只在处理一条数据，处理完一条才处理下一条。
```

**不是**三个函数各自独立运行并行处理，而是像一条流水线上的一个工人：拿起零件 → 打磨（map）→ 检查（filter）→ 贴标（map）→ 放下，再拿下一个。

#### 为什么这样设计？

因为算子链的核心优势是**避免多线程开销**：

| 方式 | 数据传递 | 开销 |
|------|---------|------|
| 算子链（同一线程） | 方法直接调用，对象引用传递 | 几乎为零 |
| 非算子链（不同线程） | 序列化 → 缓冲区 → 反序列化 | 较高 |

对于 map、filter 这类简单操作，每条数据的处理时间通常在微秒级，线程切换和序列化的开销反而远大于计算本身。

#### 吞吐量靠并行度提升

```
并行度 = 1：一个线程，逐条处理
  线程A: 数据1 → 数据2 → 数据3 → 数据4 → ...
  吞吐量: 1x

并行度 = 4：四个线程，各自逐条处理
  线程A: 数据1 → 数据5 → 数据9  → ...
  线程B: 数据2 → 数据6 → 数据10 → ...
  线程C: 数据3 → 数据7 → 数据11 → ...
  线程D: 数据4 → 数据8 → 数据12 → ...
  吞吐量: 4x
```

> **总结**：一个算子链 = 一个线程，同一时刻只处理一条数据。想提高吞吐量，增加并行度（多开几条算子链），而不是在链内部做并发。

---

## 分区算子（Partition Operators）

### 分区算子概念

分区算子用于**控制数据在并行实例（SubTask）之间如何分发**。它们不改变数据内容，只改变数据的**流向**，决定上游的每条数据应该发往下游的哪个并行实例。

默认情况下，Flink 的数据分发策略取决于算子链的情况：
- 算子链内部：数据直接传递，不涉及分区
- 算子链之间：默认使用 rebalance（轮询）

在实际场景中，可能需要自定义数据分发策略，如解决数据倾斜、广播配置、全局排序等，这时就需要用到分区算子。

> 所有分区算子都会**打断算子链**，触发网络 Shuffle，上下游算子会运行在不同的线程中。

---

### 常用分区算子

#### 1. keyBy（按键分区）

按 key 的 hash 值分区，**相同 key 一定到同一个下游 SubTask**。是最常用的分区方式，也是聚合操作的前提。

```java
dataStream.keyBy(value -> value.f0);
```

> 严格来说 `keyBy` 返回的是 `KeyedStream`，不仅仅是分区，还绑定了状态语义。

#### 2. rebalance（轮询分区）

将数据**轮询（Round-Robin）** 分发到下游所有 SubTask，保证每个 SubTask 收到的数据量大致相同。

**适用场景**：解决数据倾斜问题。

```java
dataStream.rebalance();
```

```
上游 SubTask 0 的数据轮询分发：
  数据1 → 下游[0]
  数据2 → 下游[1]
  数据3 → 下游[2]
  数据4 → 下游[0]   ← 循环
  ...
```

**补充：上下游并行度相同时无分区**

当两个连续算子并行度相同且没有 Shuffle 操作时，它们会合并为算子链，数据在各自的 SubTask 内**直接传递，不涉及任何分区**：

```java
input.map(func1).setParallelism(2)
     .map(func2).setParallelism(2);
// 并行度相同，无 Shuffle → 合并为算子链
```

```
┌──── Slot 0（线程 A）────┐  ┌──── Slot 1（线程 B）────┐
│                        │  │                        │
│  map1[0] → map2[0]    │  │  map1[1] → map2[1]    │
│  （方法直接调用）        │  │  （方法直接调用）        │
│                        │  │                        │
└────────────────────────┘  └────────────────────────┘

map1[0] 处理的数据直接传给 map2[0]
map1[1] 处理的数据直接传给 map2[1]
不存在数据跨 SubTask 的情况，无网络开销
```

**补充：上下游并行度不同时自动触发 rebalance**

当两个连续算子并行度不同且没有指定任何分区算子时，Flink 会**自动使用 rebalance** 分发数据。例如 map1 并行度 2，map2 并行度 3：

```java
input.map(func1).setParallelism(2)
     .map(func2).setParallelism(3);
// 没有显式指定分区，Flink 自动插入 rebalance
```

```
  map1（并行度 2）                     map2（并行度 3）
                   自动 rebalance
┌─── map1[0] ───┐   轮询分发    ┌─── map2[0] ───┐
│ 数据1 ──────────────────────→ │               │
│ 数据2 ─────────────────┐      └───────────────┘
│ 数据3 ────────────┐    │
│ ...               │    │      ┌─── map2[1] ───┐
└───────────────┘   │    └────→ │               │
                    │           └───────────────┘
┌─── map1[1] ───┐   │
│ 数据A ─────────────┼────────→ ┌─── map2[2] ───┐
│ 数据B ─────────────┘          │               │
│ ...               │           └───────────────┘
└───────────────┘
```

此时会产生两个影响：
- **算子链被打断**：map1 和 map2 运行在不同线程中，数据需要序列化和网络传输
- **所需 Slot 数 = 3**：取最大并行度

> 这也是为什么尽量让连续算子保持相同并行度——可以形成算子链，避免不必要的网络开销。

#### 3. shuffle（随机分区）

将数据**随机**分发到下游的某个 SubTask。

**适用场景**：需要随机打散数据时使用。

```java
dataStream.shuffle();
```

#### 4. rescale（局部轮询分区）

与 `rebalance` 类似是轮询，但只在**上下游的部分并行实例之间**进行，不是全连接。

**适用场景**：上下游并行度成倍数关系时，减少网络连接数。

```java
dataStream.rescale();
```

```
rebalance（全连接）          rescale（局部连接）
上游[0] → 下游[0,1,2,3]     上游[0] → 下游[0,1]
上游[1] → 下游[0,1,2,3]     上游[1] → 下游[2,3]
连接数: 2×4 = 8              连接数: 2×2 = 4
```

#### 5. broadcast（广播分区）

将数据**发送到下游所有 SubTask**，每个 SubTask 都会收到完整的数据副本。

**适用场景**：广播配置信息、规则数据等小数据集到所有并行实例。

```java
dataStream.broadcast();
```

```
上游数据: "config_update"
  → 下游[0] 收到 "config_update"
  → 下游[1] 收到 "config_update"
  → 下游[2] 收到 "config_update"
```

#### 6. global（全局分区）

将所有数据**发送到下游第一个 SubTask（index=0）**，相当于把并行度强制降为 1。

**适用场景**：全局排序、全局聚合等需要单点处理的场景。

```java
dataStream.global();
```

> ⚠️ 慎用！会导致所有数据集中到一个 SubTask，容易成为性能瓶颈。

#### 7. partitionCustom（自定义分区）

使用用户自定义的分区逻辑来分发数据。

```java
dataStream.partitionCustom(
    new Partitioner<String>() {
        @Override
        public int partition(String key, int numPartitions) {
            if (key.startsWith("VIP")) {
                return 0;  // VIP 用户全部分到第一个分区
            }
            return key.hashCode() % numPartitions;
        }
    },
    value -> value.f0  // key 提取器
);
```

---

### 分区算子总结

| 分区算子 | 分发策略 | 网络拓扑 | 适用场景 |
|---------|---------|---------|---------|
| `keyBy` | 按 key hash | 全连接 | 分组聚合 |
| `rebalance` | 轮询 | 全连接 | 解决数据倾斜 |
| `shuffle` | 随机 | 全连接 | 随机打散 |
| `rescale` | 局部轮询 | 局部连接 | 上下游并行度成倍数 |
| `broadcast` | 全量广播 | 全连接 | 广播配置/规则 |
| `global` | 全部发往第一个 | 多对一 | 全局排序/聚合 |
| `partitionCustom` | 自定义逻辑 | 全连接 | 自定义分区规则 |

---

## 输出算子（Sink）与精准一次

### Sink 算子概念

Sink 算子是 Flink 数据处理管道的最后一环，负责将处理结果**写入外部系统**（如 Kafka、MySQL、HDFS、Redis 等）。

```java
// DataStream API 常见 Sink
result.addSink(new FlinkKafkaProducer<>(...));   // 写入 Kafka
result.print();                                   // 打印到控制台
result.writeAsText("/path/to/file");              // 写入文件
```

---

### 精准一次（Exactly-Once）

#### 什么是精准一次？

精准一次是 Flink 中最严格的数据一致性保证：**每条数据对最终结果的影响恰好一次**——不丢、不重。

> ⚠️ 精`准一次**不是**说每条数`据只被处理一次，而是**最终效果等价于只处理了一次**。实际上数据可能被重复处理（如故障恢复后重放），但 Flink 通过机制保证最终结果正确。

#### 三种语义级别

| 语义 | 含义 | 数据表现 |
|------|------|---------|
| **At-Most-Once（最多一次）** | 数据可能丢失，但不会重复 | 故障后不恢复，丢了就丢了 |
| **At-Least-Once（至少一次）** | 数据不会丢失，但可能重复 | 故障恢复后重放，可能重复处理 |
| **Exactly-Once（精准一次）** | 数据不丢不重，结果完全正确 | 故障恢复后结果和没有故障时完全一致 |

---

### Flink 如何实现精准一次？

精准一次分为**两个层面**：

#### 1. Flink 内部的 Exactly-Once

通过 **Checkpoint（检查点）** 机制实现。Flink 定期对所有算子的状态做快照，故障恢复时从最近的 Checkpoint 恢复状态，并从 Source 端重放数据。

```
正常运行: 数据1 → 数据2 → 数据3 → 数据4 → 数据5 → ...
                              ↑
                         Checkpoint（保存状态快照）
                              
发生故障后恢复:
  1. 从 Checkpoint 恢复状态（回到数据3处理完的状态）
  2. 从 Source 重新消费 数据4、数据5 ...
  3. 最终结果和没有故障时完全一致 ✅
```

> 这要求 Source 支持**重放**（如 Kafka 可以按 offset 重新消费）。

#### 2. 端到端的 Exactly-Once（包含 Sink）

Flink 内部能保证 Exactly-Once，但数据输出到外部系统（Sink）时，如果 Sink 不配合就可能出现重复。

**两阶段提交（2PC）**是实现端到端 Exactly-Once 的核心机制：

```
正常流程:
  Checkpoint 开始 → Sink 预提交数据（写入但未提交）
  Checkpoint 完成 → Sink 正式提交数据 ✅

故障恢复流程:
  Checkpoint 开始 → Sink 预提交数据
  故障发生！→ 预提交的数据被回滚 ❌
  从 Checkpoint 恢复 → 重新处理 → 重新预提交 → 正式提交 ✅
  
结果：数据不会重复写入外部系统
```

---

### 不同 Sink 的 Exactly-Once 支持

| Sink 类型 | 是否支持 Exactly-Once | 实现方式 |
|-----------|---------------------|---------|
| **Kafka Sink** | ✅ 支持 | 两阶段提交（Kafka 事务） |
| **JDBC Sink**（MySQL 等） | ✅ 支持 | 两阶段提交 / 幂等写入（UPSERT） |
| **文件 Sink**（HDFS/S3） | ✅ 支持 | 先写临时目录，Checkpoint 成功后移动到正式目录 |
| **Redis Sink** | ⚠️ 部分支持 | 通过幂等写入（SET 操作天然幂等） |
| **Print Sink** | ❌ 不支持 | 直接打印，无法撤回 |
| **自定义 Sink** | 取决于实现 | 需要实现 `TwoPhaseCommitSinkFunction` |

---

### 幂等写入 vs 两阶段提交

实现端到端 Exactly-Once 有两种方式：

| 方式 | 原理 | 示例 |
|------|------|------|
| **两阶段提交（2PC）** | 先预提交、Checkpoint 成功后正式提交，失败则回滚 | Kafka 事务、JDBC 事务 |
| **幂等写入** | 重复写入同一条数据结果不变（天然去重） | MySQL UPSERT、Redis SET、HBase PUT |

```sql
-- 幂等写入示例：MySQL UPSERT
-- 即使重复写入 user_id=1 的数据，结果也只有一条
INSERT INTO user_table (user_id, user_name) VALUES (1, '张三')
ON DUPLICATE KEY UPDATE user_name = '张三';
```

---

### 端到端 Exactly-Once 总结

```
                    Source          Flink 内部         Sink
                   (可重放)      (Checkpoint)      (2PC/幂等)
                      │               │               │
At-Most-Once:         ×               ×               ×
At-Least-Once:        ✅              ✅               ×
Exactly-Once:         ✅              ✅              ✅
                      │               │               │
                      └── 三个环节都满足才是端到端 Exactly-Once ──┘
```

> **总结**：Flink 的精准一次 = **Checkpoint（内部状态一致）** + **Source 可重放** + **Sink 支持事务或幂等**。三个环节缺一不可，才能实现端到端的 Exactly-Once。
