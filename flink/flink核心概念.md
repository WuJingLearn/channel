# Flink 核心概念

## 一、并行度（Parallelism）

### 1. 什么是并行度

并行度是 Flink 中一个**核心的执行概念**，它决定了一个算子（Operator）被拆分成多少个**并行实例（SubTask）**来同时处理数据。

> **一句话理解：并行度 = 一个算子同时运行的副本数量**

### 2. 从算子到 SubTask

TaskManager 上运行的是 SubTask（子任务），这些 SubTask 就是并行度的直接体现：

```
假设一个 map 算子的并行度设为 3：

          ┌─────────────┐
          │  map (算子)   │
          └─────────────┘
                │
        并行度 = 3，拆分为 3 个 SubTask
                │
    ┌───────────┼───────────┐
    ▼           ▼           ▼
┌────────┐ ┌────────┐ ┌────────┐
│ map[0] │ │ map[1] │ │ map[2] │   ← 3 个 SubTask
│ (TM-1) │ │ (TM-1) │ │ (TM-2) │   ← 分布在不同 Slot 上
└────────┘ └────────┘ └────────┘
```

每个 SubTask 是一个**独立的线程**，运行在 TaskManager 的某个 Slot 中，处理输入数据的一个子集。

### 3. 并行度与 Slot 的关系

- 一个 **Slot** 可以运行一个或多个 SubTask（通过算子链 Operator Chaining 合并）
- 一个作业所需的 **Slot 总数 = 作业中最大的算子并行度**（而不是所有算子并行度之和，因为 Flink 支持 Slot 共享）
- 如果集群可用 Slot 数量不足以满足作业的最大并行度，**作业将无法启动**

```
示例：一个作业有 3 个算子
  Source（并行度=2）→ Map（并行度=4）→ Sink（并行度=2）

  所需 Slot 数 = max(2, 4, 2) = 4 个 Slot

  Slot 共享机制下，每个 Slot 内可以运行来自不同算子的 SubTask：
  ┌──────────────┐ ┌──────────────┐ ┌──────────────┐ ┌──────────────┐
  │   Slot 0     │ │   Slot 1     │ │   Slot 2     │ │   Slot 3     │
  │ Source[0]    │ │ Source[1]    │ │              │ │              │
  │ Map[0]       │ │ Map[1]       │ │ Map[2]       │ │ Map[3]       │
  │ Sink[0]      │ │ Sink[1]      │ │              │ │              │
  └──────────────┘ └──────────────┘ └──────────────┘ └──────────────┘
```

### 4. 并行度的四个设置层级

Flink 提供了 4 个层级来设置并行度，**优先级从高到低**：

#### （1）算子级别（最高优先级）

为单个算子单独设置并行度：

```java
dataStream.map(new MyMapFunction()).setParallelism(4);
```

#### （2）执行环境级别

为整个作业设置默认并行度，未单独设置的算子会继承此值：

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
env.setParallelism(3);
```

#### （3）客户端提交级别

通过命令行参数 `-p` 指定：

```bash
flink run -p 6 MyFlinkJob.jar
```

#### （4）集群配置级别（最低优先级）

在 `flink-conf.yaml` 中配置全局默认值：

```yaml
parallelism.default: 1
```

> **优先级总结**：算子级别 > 执行环境级别 > 客户端提交级别 > 集群配置级别

### 5. 数据传输模式：One-to-One 与 Redistributing

上下游算子之间的数据传输分为两大类：**One-to-One（一对一）** 和 **Redistributing（重分区）**，决定了数据如何从上游 SubTask 流向下游 SubTask。

#### （1）One-to-One（Forward 模式）

上游 SubTask 的数据**只发给下游对应编号的那一个 SubTask**，数据的分区和顺序完全保持不变。

```
上游并行度 = 3，下游并行度 = 3

  Source[0] ──→ Map[0]     （数据不跨 SubTask，直接传递）
  Source[1] ──→ Map[1]
  Source[2] ──→ Map[2]
```

**特点**：
- 不涉及网络传输（如果在同一个 TaskManager 内）
- 数据顺序完全保持
- 满足算子链合并条件时，会被合并成一个 Task，在同一个线程中执行
- 类似 Spark 中的**窄依赖（Narrow Dependency）**

**触发条件**：上下游算子**并行度相同**，且没有调用 `keyBy()`、`rebalance()`、`shuffle()` 等重分区操作。

**典型场景**：`source → map`、`map → filter`、`sum → print`

#### （2）Redistributing（重分区）

上游一个 SubTask 的数据会被**分发到下游的多个 SubTask**，数据需要跨 SubTask 甚至跨 TaskManager 传输。

```
上游并行度 = 2，下游并行度 = 3（REBALANCE 轮询分发）

  Source[0] ──┬──→ Map[0]
              ├──→ Map[1]
              └──→ Map[2]
  Source[1] ──┬──→ Map[0]
              ├──→ Map[1]
              └──→ Map[2]
```

**特点**：
- 涉及网络 Shuffle，有序列化/反序列化和网络传输开销
- 数据顺序不保证（除非使用特定策略）
- 不能形成算子链，上下游必须拆成独立的 Task
- 类似 Spark 中的**宽依赖（Wide Dependency）**

#### （3）常见的重分区策略

| 策略 | API | 说明 |
|:---|:---|:---|
| **REBALANCE** | `rebalance()` 或上下游并行度不同时自动触发 | 轮询（Round-Robin）均匀分发，解决数据倾斜 |
| **HASH** | `keyBy()` | 按 key 的 hash 值分区，相同 key 的数据一定到同一个 SubTask |
| **BROADCAST** | `broadcast()` | 将数据广播到下游所有 SubTask（每个都收到全量数据） |
| **SHUFFLE** | `shuffle()` | 随机分发到下游某个 SubTask |
| **RESCALE** | `rescale()` | 局部轮询，只在本地 TaskManager 内的 SubTask 之间分发，减少网络开销 |
| **GLOBAL** | `global()` | 所有数据发到下游第一个 SubTask（并行度变为 1） |

#### （4）结合 WordCount 实例理解

```
Source(1) ──REBALANCE──→ FlatMap(3) ──HASH──→ [sum → print](3)
            重分区                    重分区       one-to-one（算子链）
```

- **Source → FlatMap**：并行度 1→3，自动触发 REBALANCE（重分区）
- **FlatMap → sum**：调用了 `keyBy()`，触发 HASH（重分区）
- **sum → print**：并行度都是 3，Forward 传输（one-to-one），合并为算子链

> **总结：One-to-One 是数据"直通"，不跨 SubTask；重分区是数据"打散重发"，需要网络 Shuffle。能 one-to-one 的地方 Flink 会尽量合并成算子链来优化性能。**

### 6. 算子链（Operator Chaining）

#### （1）什么是算子链

Flink 会自动将满足条件的相邻算子**合并成一个 Task（算子链）**，在同一个线程中执行，避免不必要的序列化、反序列化和网络传输，从而提升性能。

> **Flink Web UI 中一个方框代表一个 Task（算子链），而不是单个算子。**

#### （2）合并条件

相邻算子必须**同时满足以下三个条件**才能被合并：

- **并行度相同**
- **数据传输模式为 Forward（one-to-one）**——即上游 SubTask 的数据只发给下游对应的一个 SubTask
- **属于同一个 Slot 共享组**（默认都在 `default` 组）

```
合并前（3 个独立 SubTask，需要线程切换）：
  Source[0] → Map[0] → Filter[0]

合并后（1 个 Task，1 个线程，无线程切换）：
  [Source → Map → Filter][0]
```

#### （3）WordCount 实例分析

以 WordCount 任务为例，代码逻辑为：

```java
socketTextStream  →  flatMap  →  keyBy  →  sum  →  print
```

在 Flink Web UI 中显示为 **3 个方框（Task）**：

```
┌─────────────────────┐         ┌─────────────┐         ┌──────────────────────────────┐
│ Source: Socket Stream│ REBALANCE│  Flat Map   │  HASH   │ Keyed Aggregation → Sink:    │
│   Parallelism: 1    │────────→│ Parallelism:3│────────→│   Print to Std. Out          │
│                     │         │             │         │   Parallelism: 3             │
└─────────────────────┘         └─────────────┘         └──────────────────────────────┘
```

**为什么是 3 个方框而不是 5 个？逐一分析：**

| 算子边界 | 能否合并 | 原因 |
|:---|:---|:---|
| Source → FlatMap | ❌ 不能合并 | 并行度不同（1 vs 3），传输模式为 **REBALANCE**（轮询分发） |
| FlatMap → keyBy | - | `keyBy()` **不是算子**，它只是一个数据分区操作，体现为连线上的 **HASH** 标注 |
| sum → print | ✅ 合并 | 并行度相同（都是 3），传输模式为 **Forward**，满足所有合并条件 |

所以最终：
- **Source** 单独一个 Task（并行度 1）
- **Flat Map** 单独一个 Task（并行度 3，因为与 Source 并行度不同）
- **Keyed Aggregation（sum）→ Sink: Print** 合并为一个 Task（并行度 3）

#### （4）关于 keyBy 的特殊说明

`keyBy()` 本身**不是一个算子（Operator）**，而是一个**数据分区操作**：

- 它按照指定 key 的 hash 值，将数据重新分发到下游不同的 SubTask
- 在 Web UI 中不会显示为独立的方框，而是体现在连线上的 **HASH** 标注
- `keyBy()` 之后的 `sum()` / `reduce()` 等才是真正的算子（Keyed Aggregation）

#### （5）数据传输模式总结

从 WordCount 实例中可以看到三种典型的数据传输模式：

| 传输模式 | 触发场景 | 示例 |
|:---|:---|:---|
| **Forward** | 上下游并行度相同且一对一 | sum → print（合并为算子链） |
| **REBALANCE** | 上下游并行度不同 | Source(1) → FlatMap(3)，轮询分发 |
| **HASH** | 调用了 `keyBy()` | FlatMap → sum，按 key 的 hash 值分区 |

#### （6）手动控制算子链

可以通过 API 手动禁用或断开算子链：

```java
// 全局禁用算子链
env.disableOperatorChaining();

// 从当前算子开始断开，不与前面的算子合并
dataStream.map(...).disableChaining();

// 从当前算子开始，开启一个新的算子链
dataStream.map(...).startNewChain();
```

### 7. 并行度设置的最佳实践

- **Source 并行度**：通常与数据源的分区数对齐（如 Kafka Topic 的 Partition 数）
- **计算算子并行度**：根据数据量和计算复杂度调整，一般与可用 Slot 数匹配
- **Sink 并行度**：根据下游系统的写入能力设置，避免写入瓶颈或产生过多小文件
- **避免过高并行度**：并行度过高会导致大量 SubTask 间的网络通信开销，反而降低性能
- **避免过低并行度**：会导致数据处理瓶颈，无法充分利用集群资源

### 8. 总结

> **并行度决定了"一个算子被复制成多少份同时干活"**，它直接影响作业的吞吐量、资源占用和数据分发方式，是 Flink 性能调优中最基础也最重要的参数之一。