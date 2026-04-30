# Flink 窗口（Window）

## 一、为什么需要窗口？

流数据是**无界的、源源不断的**。但很多计算场景需要对"一批"数据做聚合，比如：

- 每 5 秒统计一次网站的 PV
- 每 1 分钟计算一次某商品的销量
- 统计用户最近 10 分钟的点击次数

**窗口就是将无界流切割为有限大小的"桶"（bucket）**，让我们可以在每个桶上做聚合计算。

---

## 二、窗口的分类

Flink 提供了 4 种内置窗口：

### 1. 滚动窗口（Tumbling Window）

- 固定大小、**无重叠**、首尾相接
- 每个元素只属于一个窗口
- 适合做固定周期的统计（如：每 10 秒统计一次）

```
|----窗口1----|----窗口2----|----窗口3----|
0s          10s          20s          30s
```

```java
// 基于处理时间的 10 秒滚动窗口
stream
    .keyBy(value -> value.f0)
    .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .sum(1);

// 基于事件时间的滚动窗口
stream
    .keyBy(value -> value.f0)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .sum(1);
```

### 2. 滑动窗口（Sliding Window）

- 固定大小、**有重叠**，窗口按滑动步长向前滑动
- 一个元素可能属于多个窗口
- 适合做"最近 N 时间内"的统计

```
|--------窗口1--------|
     |--------窗口2--------|
          |--------窗口3--------|
0s   5s   10s  15s  20s  25s
     窗口大小=10s，滑动步长=5s
```

```java
// 窗口大小 10 秒，每 5 秒滑动一次
stream
    .keyBy(value -> value.f0)
    .window(SlidingProcessingTimeWindows.of(Time.seconds(10), Time.seconds(5)))
    .sum(1);
```

### 3. 会话窗口（Session Window）

- **没有固定大小**，由活动间隙（gap）定义
- 如果两个元素之间的间隔超过 gap，就会开启一个新窗口
- 适合用户行为分析（如：用户会话）

```
|--窗口1--|        |----窗口2----|    |--窗口3--|
 事件 事件  gap>5s  事件 事件 事件 gap>5s 事件 事件
```

```java
// 会话间隙为 5 秒
stream
    .keyBy(value -> value.f0)
    .window(ProcessingTimeSessionWindows.withGap(Time.seconds(5)))
    .sum(1);
```

### 4. 全局窗口（Global Window）

- 所有元素分配到**同一个窗口**
- 必须自定义 Trigger 来触发计算，否则永远不会触发
- 高度灵活，一般用于自定义场景

```java
stream
    .keyBy(value -> value.f0)
    .window(GlobalWindows.create())
    .trigger(CountTrigger.of(100))  // 每 100 个元素触发一次
    .sum(1);
```

---

## 三、窗口函数（Window Function）

窗口收集好数据后，需要用**窗口函数**来处理，主要有两类：

### 增量聚合函数 —— 来一条算一条，高效

- **`ReduceFunction`**：两两归约，输入输出类型一致
- **`AggregateFunction`**：更灵活，输入/累加器/输出类型可以不同

```java
// ReduceFunction 示例
stream
    .keyBy(value -> value.f0)
    .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .reduce((v1, v2) -> Tuple2.of(v1.f0, v1.f1 + v2.f1));

// AggregateFunction 示例：计算平均值
stream
    .keyBy(value -> value.f0)
    .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .aggregate(new AggregateFunction<Event, Tuple2<Long, Integer>, Double>() {
        @Override
        public Tuple2<Long, Integer> createAccumulator() {
            return Tuple2.of(0L, 0);
        }
        @Override
        public Tuple2<Long, Integer> add(Event value, Tuple2<Long, Integer> acc) {
            return Tuple2.of(acc.f0 + value.getAmount(), acc.f1 + 1);
        }
        @Override
        public Double getResult(Tuple2<Long, Integer> acc) {
            return (double) acc.f0 / acc.f1;
        }
        @Override
        public Tuple2<Long, Integer> merge(Tuple2<Long, Integer> a, Tuple2<Long, Integer> b) {
            return Tuple2.of(a.f0 + b.f0, a.f1 + b.f1);
        }
    });
```

### 增量聚合的执行过程

以 `reduce` 为例，假设在一个 10 秒窗口内，key="hello" 收到了 3 条数据：

```
(hello, 1)  ← 第1条到达
(hello, 3)  ← 第2条到达
(hello, 2)  ← 第3条到达
```

执行过程如下：

| 步骤 | 事件 | reduce 动作 | 中间结果（累加器） |
|---|---|---|---|
| 1 | `(hello, 1)` 到达 | 第一条数据，直接存为累加器 | `(hello, 1)` |
| 2 | `(hello, 3)` 到达 | `reduce(累加器, 新数据)` → `(hello, 1+3)` | `(hello, 4)` |
| 3 | `(hello, 2)` 到达 | `reduce(累加器, 新数据)` → `(hello, 4+2)` | `(hello, 6)` |
| 4 | 窗口结束 | **输出最终结果** | → `(hello, 6)` |

关键点：

- **每来一条数据，确实会立即触发一次 reduce 计算**，把新数据和之前的中间结果做合并——所以叫"增量聚合"
- **但不会每来一条就输出一次结果**，只在**窗口结束时才输出最终结果**
- Flink 内部只维护一个**固定大小的累加器**（中间结果），而不是把窗口内所有数据都攒起来——这就是它高效、省内存的原因

> **一句话总结**：`reduce` / `aggregate` 是**来一条算一条（增量合并到累加器），但攒到窗口结束才输出**。计算是增量的，输出是批量的。

### ReduceFunction 与 AggregateFunction 的对比

两者**工作原理完全相同**（都是增量聚合），核心区别在于**类型约束的灵活性**：

| | `ReduceFunction` | `AggregateFunction` |
|---|---|---|
| **触发时机** | 每来一条数据就合并一次 | 每来一条数据就合并一次 |
| **输出时机** | 窗口结束时输出 | 窗口结束时输出 |
| **内存占用** | 只维护一个累加器 | 只维护一个累加器 |
| **类型约束** | 输入、输出、累加器类型**必须一致** | 输入、累加器、输出类型**可以各不相同** |

#### ReduceFunction —— 输入输出类型必须一致

只有一个方法 `reduce(T value1, T value2) -> T`，简单但不灵活：

```java
// 输入是 Tuple2<String, Integer>，输出也必须是 Tuple2<String, Integer>
.reduce((v1, v2) -> Tuple2.of(v1.f0, v1.f1 + v2.f1));
```

#### AggregateFunction —— 输入、累加器、输出类型可以各不相同

有 4 个方法，各司其职：

```java
// IN = 输入类型, ACC = 累加器类型, OUT = 输出类型
AggregateFunction<IN, ACC, OUT>

ACC  createAccumulator()           // 创建累加器（初始值）
ACC  add(IN value, ACC acc)        // 新数据到达，合并到累加器
OUT  getResult(ACC acc)            // 窗口结束时，从累加器提取最终结果
ACC  merge(ACC a, ACC b)           // 合并两个累加器（用于会话窗口等场景）
```

#### 对比示例：计算每个用户 10 秒窗口内的平均消费金额

**ReduceFunction 做不到**：因为输入是 `Order(userId, amount)`，但输出要的是 `Double`（平均值），类型不一样。而且求平均值需要同时记录总金额和条数，`reduce` 的累加器就是输入类型本身，没地方存条数。

**AggregateFunction 轻松搞定**：

```java
// IN = Order, ACC = Tuple2<总金额, 条数>, OUT = Double(平均值)
new AggregateFunction<Order, Tuple2<Long, Integer>, Double>() {
    public Tuple2<Long, Integer> createAccumulator() {
        return Tuple2.of(0L, 0);              // 初始：总金额=0, 条数=0
    }
    public Tuple2<Long, Integer> add(Order order, Tuple2<Long, Integer> acc) {
        return Tuple2.of(acc.f0 + order.getAmount(), acc.f1 + 1);  // 累加
    }
    public Double getResult(Tuple2<Long, Integer> acc) {
        return (double) acc.f0 / acc.f1;      // 窗口结束时算平均值
    }
    public Tuple2<Long, Integer> merge(Tuple2<Long, Integer> a, Tuple2<Long, Integer> b) {
        return Tuple2.of(a.f0 + b.f0, a.f1 + b.f1);
    }
}
```

> **选择建议**：能用 `reduce` 就用 `reduce`（代码简洁），类型对不上时再用 `aggregate`。

### 全窗口函数 —— 攒齐所有数据再处理，能获取窗口元信息

- **`ProcessWindowFunction`**：可以拿到窗口的起止时间、状态等上下文信息

```java
stream
    .keyBy(value -> value.f0)
    .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .process(new ProcessWindowFunction<Tuple2<String, Integer>, String, String, TimeWindow>() {
        @Override
        public void process(String key, Context context,
                          Iterable<Tuple2<String, Integer>> elements,
                          Collector<String> out) {
            long count = 0;
            for (Tuple2<String, Integer> element : elements) {
                count++;
            }
            long start = context.window().getStart();
            long end = context.window().getEnd();
            out.collect("窗口[" + start + "~" + end + "] key=" + key + " count=" + count);
        }
    });
```

### 最佳实践：增量聚合 + ProcessWindowFunction 结合使用

既高效又能拿到窗口信息：

```java
stream
    .keyBy(value -> value.f0)
    .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .reduce(
        (v1, v2) -> Tuple2.of(v1.f0, v1.f1 + v2.f1),  // 增量聚合
        new ProcessWindowFunction<>() {                  // 包装结果
            @Override
            public void process(String key, Context ctx,
                              Iterable<Tuple2<String, Integer>> elements,
                              Collector<String> out) {
                Tuple2<String, Integer> result = elements.iterator().next();
                out.collect("窗口结束时间: " + ctx.window().getEnd()
                           + " | " + result.f0 + ": " + result.f1);
            }
        }
    );
```

---

## 四、Keyed Window vs Non-Keyed Window

| 对比项 | Keyed Window | Non-Keyed Window |
|---|---|---|
| **前置操作** | 先 `keyBy()` | 不需要 `keyBy()` |
| **窗口 API** | `.window()` | `.windowAll()` |
| **并行度** | 可以多并行，每个 key 独立开窗 | 并行度只能为 1 |
| **适用场景** | 绝大多数场景 | 全局统计 |

```java
// Keyed Window（推荐）
stream.keyBy(v -> v.f0).window(TumblingProcessingTimeWindows.of(Time.seconds(10))).sum(1);

// Non-Keyed Window（全局，并行度=1）
stream.windowAll(TumblingProcessingTimeWindows.of(Time.seconds(10))).sum(1);
```

---

## 五、窗口的核心组件

一个完整的窗口由以下组件构成：

- **Window Assigner**：决定元素分配到哪个窗口（即 Tumbling/Sliding/Session/Global）
- **Trigger（触发器）**：决定窗口何时触发计算（默认在窗口结束时触发）
- **Evictor（驱逐器）**：可选，在触发计算前/后移除某些元素
- **Allowed Lateness（允许迟到）**：事件时间窗口中，允许迟到数据在窗口关闭后仍被处理

```java
stream
    .keyBy(value -> value.f0)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .trigger(EventTimeTrigger.create())       // 触发器
    .allowedLateness(Time.seconds(5))         // 允许迟到 5 秒
    .sideOutputLateData(lateOutputTag)        // 迟到数据输出到侧输出流
    .sum(1);
```

---

## 六、窗口处理流程总结

```
无界数据流
    │
    ▼
 keyBy() ──按 key 分组
    │
    ▼
 window() ──分配到窗口（Tumbling / Sliding / Session / Global）
    │
    ▼
 [trigger] ──触发条件（默认：窗口结束时）
    │
    ▼
 窗口函数 ──计算（reduce / aggregate / process）
    │
    ▼
 输出结果
```

---

## 七、为什么需要 keyBy？

### 核心作用

**`keyBy` 的本质是"分组"** —— 把数据流按某个字段分成逻辑上独立的子流，让相同 key 的数据被路由到同一个并行子任务中处理。

没有 `keyBy`，所有数据混在一起，无法对"某一类"数据单独做聚合。

### 场景：电商平台实时统计每个商品的销量

假设有一个订单流，每条数据长这样：

```
{商品ID: "iPhone16", 数量: 1, 时间: 10:00:01}
{商品ID: "MacBook",  数量: 1, 时间: 10:00:02}
{商品ID: "iPhone16", 数量: 2, 时间: 10:00:03}
{商品ID: "iPad",     数量: 1, 时间: 10:00:04}
{商品ID: "iPhone16", 数量: 1, 时间: 10:00:05}
```

需求是：**每 10 秒统计一次每个商品卖了多少件。**

### 不用 keyBy 会怎样？

```java
stream
    .windowAll(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .sum("数量");
```

结果只会得到**所有商品的总销量 = 6**，根本分不清 iPhone 卖了多少、MacBook 卖了多少。而且 `windowAll` 的并行度只能是 1，性能极差。

### 用 keyBy 之后

```java
stream
    .keyBy(order -> order.getProductId())   // 按商品ID分组
    .window(TumblingProcessingTimeWindows.of(Time.seconds(10)))
    .sum("数量");
```

Flink 内部会这样处理：

```
原始流 ──keyBy(商品ID)──┬─→ 子任务1: [iPhone16的所有订单] → 窗口聚合 → iPhone16: 4
                       ├─→ 子任务2: [MacBook的所有订单]  → 窗口聚合 → MacBook:  1
                       └─→ 子任务3: [iPad的所有订单]     → 窗口聚合 → iPad:     1
```

**效果**：

1. **逻辑隔离** —— 每个商品有自己独立的窗口，互不干扰
2. **并行计算** —— 不同 key 的数据可以在不同的并行实例上同时处理，吞吐量大幅提升
3. **状态隔离** —— 每个 key 有独立的状态（如累加器），不会串数据

### 一句话总结

> **`keyBy` = 告诉 Flink "我要按什么维度分别统计"**。就像 SQL 里的 `GROUP BY`，没有它只能做全局聚合，有了它才能按维度拆分。

### 常见 keyBy 场景

| 场景 | keyBy 的字段 |
|---|---|
| 统计每个商品的销量 | `商品ID` |
| 统计每个用户的访问次数 | `用户ID` |
| 统计每个城市的实时温度均值 | `城市` |
| 统计每个 URL 的请求 QPS | `URL` |

### 经典三板斧

**先 `keyBy` 分组 → 再 `window` 切时间段 → 最后用聚合函数算结果**，这就是 Flink 流处理最经典的模式。