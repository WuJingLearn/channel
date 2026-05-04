# Flink ProcessFunction 家族全解

## 一、为什么要有 ProcessFunction？

`map / flatMap / filter / reduce / window` 等高阶算子**把很多底层能力藏起来了**——你看不到水位线、碰不到定时器、不能给结果分路输出。而生产里经常需要：

- 事件 A 到来后 5 分钟没看到 B 就告警（**需要 Timer**）
- 一条流要同时产出"正常结果 + 迟到数据 + 异常数据"（**需要侧输出**）
- 访问当前 key / 事件时间戳 / 水位线 做复杂判断（**需要底层 Context**）
- 双流 JOIN、广播规则、窗口内自定义逻辑

ProcessFunction 就是为此而生——**Flink 流处理的最底层 API**，把 **State / Time / Timer / Watermark / Side Output** 五大核心能力全部暴露给用户。

---

## 二、家族全景与继承关系

```
                    Function (顶层接口)
                         │
                 AbstractRichFunction  ← open/close/getRuntimeContext
                         │
     ┌───────────────────┼────────────────────────────────┐
     │                   │                                │
ProcessFunction   KeyedProcessFunction   CoProcessFunction / KeyedCoProcessFunction
  (普通流)           (Keyed 流) ★         (双流 connect)
     │                   │
     │        BroadcastProcessFunction / KeyedBroadcastProcessFunction
     │                   (广播流)
     │
ProcessWindowFunction / ProcessAllWindowFunction  (窗口内)
ProcessJoinFunction                                (Interval Join)
```

### 成员速览表

| 函数 | 挂到哪种流 | 核心能力 | 挂载方法 |
|---|---|---|---|
| **`ProcessFunction<I, O>`** | `DataStream` | 侧输出 + 处理时间 Timer | `.process(...)` |
| **`KeyedProcessFunction<K, I, O>`** ★ | `KeyedStream` | Keyed State + 事件/处理时间 Timer + 侧输出 | `.process(...)` |
| **`CoProcessFunction<I1, I2, O>`** | `ConnectedStreams`（非 keyed） | 双流分别处理 + 侧输出 + 处理时间 Timer | `.process(...)` |
| **`KeyedCoProcessFunction<K, I1, I2, O>`** | `ConnectedStreams`（keyed） | 双流 + Keyed State + 双时间 Timer + 侧输出 | `.process(...)` |
| **`BroadcastProcessFunction<I, B, O>`** | `connect(BroadcastStream)` 非 keyed | 广播状态 + 事件流处理 | `.process(...)` |
| **`KeyedBroadcastProcessFunction<K,I,B,O>`** ★ | keyed `.connect(BroadcastStream)` | Keyed State + 广播状态 + Timer | `.process(...)` |
| **`ProcessWindowFunction<I, O, K, W>`** | `WindowedStream` | 窗口全量数据迭代 + 窗口元信息 | `.process(...)` |
| **`ProcessAllWindowFunction<I, O, W>`** | `AllWindowedStream` | 非 keyed 全窗口 | `.process(...)` |
| **`ProcessJoinFunction<I1, I2, O>`** | `IntervalJoin` | 区间内两条事件一起处理 | `.process(...)` |

**一条规律**：凡是 ProcessFunction 家族的成员，都用 **`.process(...)`** 挂载到对应的流上。

---

## 三、各成员详解

### 1. ProcessFunction\<I, O\> —— 普通流的底层算子

#### 概念
作用于 `DataStream`，**不能用 Keyed State**，Timer 只支持处理时间（因为无法按 key 隔离事件时间的 Timer）。

#### 类结构

```java
public abstract class ProcessFunction<I, O> extends AbstractRichFunction {

    // 每条数据触发
    public abstract void processElement(I value, Context ctx, Collector<O> out) throws Exception;

    // 定时器回调（可选）
    public void onTimer(long timestamp, OnTimerContext ctx, Collector<O> out) throws Exception {}

    public abstract class Context {
        public abstract Long timestamp();                          // 事件时间戳
        public abstract TimerService timerService();               // 定时器
        public abstract <X> void output(OutputTag<X> tag, X v);    // 侧输出
    }
}
```

#### 挂到 DataStream

```java
DataStream<Event> source = env.fromSource(...);

SingleOutputStreamOperator<Result> main = source
    .process(new ProcessFunction<Event, Result>() {
        @Override
        public void processElement(Event e, Context ctx, Collector<Result> out) {
            out.collect(new Result(e));
            ctx.output(SIDE_TAG, e);    // 侧输出
        }
    });
```

> **注意**：普通 `ProcessFunction` 里调用 `getRuntimeContext().getState(...)` 会运行时抛异常 —— 要用 State 请先 keyBy。

---

### 2. KeyedProcessFunction\<K, I, O\> ★ 最常用

#### 概念
作用于 `KeyedStream`，能力最全：**Keyed State + 事件/处理时间 Timer + 侧输出 + 完整 Context**。

#### 类结构

```java
public abstract class KeyedProcessFunction<K, I, O> extends AbstractRichFunction {

    public abstract void processElement(I value, Context ctx, Collector<O> out) throws Exception;

    public void onTimer(long ts, OnTimerContext ctx, Collector<O> out) throws Exception {}

    public abstract class Context {
        public abstract Long timestamp();
        public abstract TimerService timerService();
        public abstract <X> void output(OutputTag<X> tag, X v);
        public abstract K getCurrentKey();       // 当前 key
    }

    public abstract class OnTimerContext extends Context {
        public abstract TimeDomain timeDomain(); // EVENT_TIME / PROCESSING_TIME
    }
}
```

#### 方法详解

| 方法 / Context 能力 | 说明 |
|---|---|
| `processElement(value, ctx, out)` | 每条数据触发 |
| `onTimer(ts, ctx, out)` | 定时器到点触发（注意 ts 是定时器设定的时间） |
| `ctx.getCurrentKey()` | 当前处理数据的 key |
| `ctx.timestamp()` | 当前数据的事件时间戳（null = 处理时间模式） |
| `ctx.timerService().currentWatermark()` | 当前水位线 |
| `ctx.timerService().currentProcessingTime()` | 当前机器时间 |
| `ctx.timerService().registerEventTimeTimer(ts)` | 注册事件时间定时器 |
| `ctx.timerService().registerProcessingTimeTimer(ts)` | 注册处理时间定时器 |
| `ctx.timerService().deleteEventTimeTimer(ts)` | 删除定时器 |
| `ctx.output(tag, v)` | 侧输出 |

> **定时器按 (key, timestamp) 去重**：同一个 key 注册相同的 ts 只会触发一次。

#### 挂到 KeyedStream

```java
DataStream<Event> source = ...;

SingleOutputStreamOperator<Alert> main = source
    .keyBy(Event::getUserId)                              // 必须先 keyBy
    .process(new KeyedProcessFunction<String, Event, Alert>() {

        private transient ValueState<Long> lastTs;

        @Override
        public void open(Configuration p) {
            lastTs = getRuntimeContext().getState(
                new ValueStateDescriptor<>("last", Long.class));
        }

        @Override
        public void processElement(Event e, Context ctx, Collector<Alert> out) throws Exception {
            lastTs.update(e.ts);
            ctx.timerService().registerEventTimeTimer(e.ts + 60_000);
        }

        @Override
        public void onTimer(long ts, OnTimerContext ctx, Collector<Alert> out) throws Exception {
            if (ts - lastTs.value() >= 60_000) {
                out.collect(new Alert(ctx.getCurrentKey()));
            }
        }
    });
```

#### 使用场景
会话超时、异常检测、规则引擎、自定义窗口、去重限流、复杂状态机。

---

### 3. CoProcessFunction\<I1, I2, O\> —— 非 keyed 双流

#### 概念
两条流 `connect` 后得到 `ConnectedStreams`，可在**同一算子**内分别处理两条流数据，**共享算子状态**。

#### 类结构

```java
public abstract class CoProcessFunction<I1, I2, O> extends AbstractRichFunction {
    public abstract void processElement1(I1 value, Context ctx, Collector<O> out) throws Exception;
    public abstract void processElement2(I2 value, Context ctx, Collector<O> out) throws Exception;
    public void onTimer(long ts, OnTimerContext ctx, Collector<O> out) throws Exception {}
}
```

#### 挂载

```java
DataStream<Order>   orders   = ...;
DataStream<Payment> payments = ...;

orders
    .connect(payments)                       // 得到 ConnectedStreams
    .process(new CoProcessFunction<Order, Payment, Match>() {
        @Override
        public void processElement1(Order o, Context ctx, Collector<Match> out) { ... }
        @Override
        public void processElement2(Payment p, Context ctx, Collector<Match> out) { ... }
    });
```

> 非 keyed 情况下**没有 Keyed State**，且 Timer 只支持处理时间。

---

### 4. KeyedCoProcessFunction\<K, I1, I2, O\> —— keyed 双流（生产常用）

#### 概念
两条流先各自 `keyBy` 相同的 key，然后 `connect`——**双流 + Keyed State + 双时间 Timer + 侧输出**全部支持。

#### 挂载

```java
orders
    .keyBy(Order::getOrderId)
    .connect(payments.keyBy(Payment::getOrderId))
    .process(new KeyedCoProcessFunction<String, Order, Payment, Match>() {

        private transient ValueState<Order>   orderState;
        private transient ValueState<Payment> payState;

        @Override
        public void open(Configuration p) {
            orderState = getRuntimeContext().getState(new ValueStateDescriptor<>("o", Order.class));
            payState   = getRuntimeContext().getState(new ValueStateDescriptor<>("p", Payment.class));
        }

        @Override
        public void processElement1(Order o, Context ctx, Collector<Match> out) throws Exception {
            Payment p = payState.value();
            if (p != null) { out.collect(new Match(o, p)); clear(); }
            else { orderState.update(o); ctx.timerService().registerEventTimeTimer(o.ts + 60_000); }
        }

        @Override
        public void processElement2(Payment p, Context ctx, Collector<Match> out) throws Exception {
            Order o = orderState.value();
            if (o != null) { out.collect(new Match(o, p)); clear(); }
            else { payState.update(p); ctx.timerService().registerEventTimeTimer(p.ts + 60_000); }
        }

        @Override
        public void onTimer(long ts, OnTimerContext ctx, Collector<Match> out) {
            // 超时未匹配：侧输出 + 清理
            clear();
        }

        private void clear() { orderState.clear(); payState.clear(); }
    });
```

#### 使用场景
订单-支付匹配、双流延迟 JOIN、事件因果关联。

---

### 5. BroadcastProcessFunction\<I, B, O\> —— 非 keyed 广播流

#### 概念
一条"主流"和一条"规则/配置"广播流 connect。广播流的数据会被**复制到每个并行子任务**，每个 subtask 都有完整规则副本。

#### 类结构

```java
public abstract class BroadcastProcessFunction<I, B, O> extends AbstractRichFunction {

    // 主流每条数据（只读访问广播状态）
    public abstract void processElement(I v, ReadOnlyContext ctx, Collector<O> out) throws Exception;

    // 广播流每条数据（可写广播状态）
    public abstract void processBroadcastElement(B v, Context ctx, Collector<O> out) throws Exception;
}
```

#### 挂载

```java
MapStateDescriptor<String, Rule> descriptor =
    new MapStateDescriptor<>("rules", String.class, Rule.class);

BroadcastStream<Rule> ruleBroadcast = ruleStream.broadcast(descriptor);

events
    .connect(ruleBroadcast)
    .process(new BroadcastProcessFunction<Event, Rule, Alert>() {
        @Override
        public void processElement(Event e, ReadOnlyContext ctx, Collector<Alert> out) throws Exception {
            ReadOnlyBroadcastState<String, Rule> rules = ctx.getBroadcastState(descriptor);
            // 按 rules 评估 event
        }
        @Override
        public void processBroadcastElement(Rule r, Context ctx, Collector<Alert> out) throws Exception {
            ctx.getBroadcastState(descriptor).put(r.id, r);
        }
    });
```

---

### 6. KeyedBroadcastProcessFunction\<K, I, B, O\> ★ 动态规则引擎首选

#### 概念
主流是 **keyed**，配合广播流；既能用 **Keyed State** 维护每个 key 的状态，又能读到**全局广播规则**。

#### 核心差异

- `processElement` 里的 `ReadOnlyContext` 可访问：Keyed State、只读广播状态、Timer
- `processBroadcastElement` 里的 `Context` 可遍历**所有 key 的 state**（`applyToKeyedState`）

#### 挂载

```java
events
    .keyBy(Event::getUserId)
    .connect(ruleStream.broadcast(descriptor))
    .process(new KeyedBroadcastProcessFunction<String, Event, Rule, Alert>() {

        private transient ValueState<Long> userCntState;

        @Override
        public void open(Configuration p) {
            userCntState = getRuntimeContext().getState(
                new ValueStateDescriptor<>("cnt", Long.class));
        }

        @Override
        public void processElement(Event e, ReadOnlyContext ctx, Collector<Alert> out) throws Exception {
            long cnt = Optional.ofNullable(userCntState.value()).orElse(0L) + 1;
            userCntState.update(cnt);

            ReadOnlyBroadcastState<String, Rule> rules = ctx.getBroadcastState(descriptor);
            for (Map.Entry<String, Rule> r : rules.immutableEntries()) {
                if (cnt > r.getValue().threshold) out.collect(new Alert(e.userId, r.getKey()));
            }
        }

        @Override
        public void processBroadcastElement(Rule r, Context ctx, Collector<Alert> out) throws Exception {
            ctx.getBroadcastState(descriptor).put(r.id, r);
            // 可选：遍历所有 key 的 state
            // ctx.applyToKeyedState(stateDescriptor, (key, state) -> { ... });
        }
    });
```

#### 使用场景
**风控规则动态下发**、**A/B 测试配置**、**实时开关控制**。

---

### 7. ProcessWindowFunction\<I, O, K, W\> —— 窗口内全量处理

#### 概念
窗口**触发时**一次性拿到窗口内**全部数据迭代器**，同时可访问窗口元信息（start / end / maxTimestamp）。

#### 类结构

```java
public abstract class ProcessWindowFunction<I, O, K, W extends Window> extends AbstractRichFunction {
    public abstract void process(K key, Context ctx, Iterable<I> elements, Collector<O> out)
        throws Exception;

    public void clear(Context ctx) throws Exception {}

    public abstract class Context {
        public abstract W window();                           // 窗口元数据
        public abstract long currentProcessingTime();
        public abstract long currentWatermark();
        public abstract KeyedStateStore windowState();        // 窗口级 state
        public abstract KeyedStateStore globalState();        // 跨窗口 state
        public abstract <X> void output(OutputTag<X> tag, X v);
    }
}
```

#### 挂载

```java
stream
    .keyBy(Event::getUserId)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .process(new ProcessWindowFunction<Event, Result, String, TimeWindow>() {
        @Override
        public void process(String key, Context ctx, Iterable<Event> elements,
                            Collector<Result> out) {
            long count = 0;
            for (Event e : elements) count++;
            out.collect(new Result(key, ctx.window().getStart(), ctx.window().getEnd(), count));
        }
    });
```

#### 与 `aggregate + ProcessWindowFunction` 组合

纯 ProcessWindowFunction 要缓存全部数据，状态大。生产常用**增量聚合 + 全量处理**：

```java
.window(TumblingEventTimeWindows.of(Time.seconds(10)))
.aggregate(new CountAgg(), new MyProcessWindowFunction());    // 先聚合再拿窗口元信息
```

---

### 8. ProcessAllWindowFunction\<I, O, W\> —— 非 keyed 全窗口

跟 `ProcessWindowFunction` 一样，只是作用在 `AllWindowedStream`（`.windowAll(...)` 得来，所有数据进一个窗口）。

```java
stream
    .windowAll(TumblingProcessingTimeWindows.of(Time.seconds(5)))
    .process(new ProcessAllWindowFunction<Event, Result, TimeWindow>() { ... });
```

> 并行度只能是 1，**大流量别用**。

---

### 9. ProcessJoinFunction\<I1, I2, O\> —— 区间 JOIN

用于 **Interval Join**：流 A 的元素与流 B 的元素时间差在 `[lower, upper]` 之内就配对。

```java
orders.keyBy(Order::getUserId)
    .intervalJoin(payments.keyBy(Payment::getUserId))
    .between(Time.seconds(-5), Time.seconds(5))   // a.ts - 5 ≤ b.ts ≤ a.ts + 5
    .process(new ProcessJoinFunction<Order, Payment, Match>() {
        @Override
        public void processElement(Order o, Payment p, Context ctx, Collector<Match> out) {
            out.collect(new Match(o, p));
        }
    });
```

`Context` 能访问两条流的时间戳 + 侧输出，**不支持注册 Timer**（Interval Join 内部自己管理状态和清理）。

---

## 四、DataStream 挂载方式总表

| 起点流类型 | 转换操作 | 目标流 | 能挂的 ProcessFunction |
|---|---|---|---|
| `DataStream` | `.process(...)` | `SingleOutputStreamOperator` | `ProcessFunction` |
| `DataStream` | `.keyBy(k)` | `KeyedStream` | — |
| `KeyedStream` | `.process(...)` | `SingleOutputStreamOperator` | **`KeyedProcessFunction`** |
| `KeyedStream` | `.window(w).process(...)` | `SingleOutputStreamOperator` | `ProcessWindowFunction` |
| `DataStream` | `.windowAll(w).process(...)` | `SingleOutputStreamOperator` | `ProcessAllWindowFunction` |
| `DataStream` | `.connect(other).process(...)` | `SingleOutputStreamOperator` | `CoProcessFunction` |
| `KeyedStream` | `.connect(KeyedStream).process(...)` | `SingleOutputStreamOperator` | **`KeyedCoProcessFunction`** |
| `DataStream` | `.connect(BroadcastStream).process(...)` | `SingleOutputStreamOperator` | `BroadcastProcessFunction` |
| `KeyedStream` | `.connect(BroadcastStream).process(...)` | `SingleOutputStreamOperator` | **`KeyedBroadcastProcessFunction`** |
| `KeyedStream` | `.intervalJoin(KeyedStream).between(...).process(...)` | `SingleOutputStreamOperator` | `ProcessJoinFunction` |

**一句话**：`process(...)` 是 ProcessFunction 家族**唯一的挂载入口**，用哪种实现取决于前面的流类型。

---

## 五、能力对比速查表

| 能力 | Process | KeyedProcess | CoProcess | KeyedCoProcess | Broadcast | KeyedBroadcast | ProcessWindow | ProcessJoin |
|---|---|---|---|---|---|---|---|---|
| Keyed State | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ | ✅ | ❌ |
| Operator State | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 广播 State | ❌ | ❌ | ❌ | ❌ | ✅ | ✅ | ❌ | ❌ |
| 窗口 State | ❌ | ❌ | ❌ | ❌ | ❌ | ❌ | ✅ | ❌ |
| 事件时间 Timer | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ | ❌ | ❌ |
| 处理时间 Timer | ✅ | ✅ | ✅ | ✅ | ❌ | ✅ | ❌ | ❌ |
| 侧输出 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 访问水位线 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |
| 访问当前 key | ❌ | ✅ | ❌ | ✅ | ❌ | ✅ | ✅ | ✅ |

---

## 六、选型决策树

```
流是单流还是双流 / 带广播？
│
├── 单流
│   ├── 需要按 key 做状态/定时器？
│   │   ├── 是 → KeyedProcessFunction  ★最常用
│   │   └── 否 → ProcessFunction
│   │
│   └── 已开窗？
│       ├── keyed 窗口 → ProcessWindowFunction（建议配合 aggregate/reduce）
│       └── 全窗口     → ProcessAllWindowFunction（慎用）
│
├── 双流（connect）
│   ├── 都 keyed → KeyedCoProcessFunction
│   └── 非 keyed → CoProcessFunction
│
├── 广播配置/规则
│   ├── 主流 keyed → KeyedBroadcastProcessFunction ★动态规则首选
│   └── 主流非 keyed → BroadcastProcessFunction
│
└── 区间 JOIN（intervalJoin） → ProcessJoinFunction
```

---

## 七、端到端示例：订单超时 + 双流匹配 + 动态规则

```java
// 1. 普通 Process：清洗 + 异常分流
OutputTag<Event> BAD = new OutputTag<Event>("bad") {};
SingleOutputStreamOperator<Event> cleaned = raw
    .process(new ProcessFunction<Event, Event>() {
        @Override
        public void processElement(Event e, Context ctx, Collector<Event> out) {
            if (e.isValid()) out.collect(e);
            else ctx.output(BAD, e);
        }
    });

// 2. KeyedProcess：会话聚合 + 定时超时
SingleOutputStreamOperator<Session> sessions = cleaned
    .keyBy(Event::getUserId)
    .process(new SessionAggregator());   // 用 State + EventTimeTimer

// 3. KeyedCoProcess：订单与支付匹配
DataStream<Match> matches = orders.keyBy(Order::getId)
    .connect(payments.keyBy(Payment::getOrderId))
    .process(new OrderPayMatcher());     // 用 State + Timer 超时

// 4. KeyedBroadcastProcess：动态风控规则
DataStream<Alert> alerts = sessions.keyBy(Session::getUserId)
    .connect(rules.broadcast(RULE_DESC))
    .process(new RiskEvaluator());

// 5. ProcessWindow：10 秒滚动窗口统计
stream.keyBy(Event::getType)
    .window(TumblingEventTimeWindows.of(Time.seconds(10)))
    .aggregate(new CountAgg(), new WindowResultEmitter());
```

---

## 八、最佳实践

1. **有状态算子必显式 `.uid("xxx")`**，否则改拓扑后无法从 Savepoint 恢复
2. **ProcessWindowFunction 永远配 aggregate/reduce**，避免缓存全量数据
3. **Timer 注册要幂等**——重置前先 `delete` 旧的，避免重复触发
4. **OutputTag 用匿名内部类** `new OutputTag<Event>("x") {}` 保留泛型
5. **`KeyedBroadcastProcessFunction` 里的 `applyToKeyedState`** 能遍历所有 key，但会阻塞 subtask，慎用
6. **跨并行度 rescale** 前先 Savepoint，`maxParallelism` 提前设大（如 1024/4096）
7. **`CoProcessFunction` 双流建议先 keyBy 再 connect**，Keyed 版本功能强得多

---

## 九、一句话总结

> **ProcessFunction 家族是 Flink 的"低层通用算子"**，全部通过 `.process(...)` 挂到对应的 DataStream/KeyedStream/ConnectedStreams/WindowedStream 上。
> 记住一条线索：**流的类型决定能挂哪种 ProcessFunction，ProcessFunction 决定你能用哪些底层能力（State / Time / Timer / Watermark / SideOutput）**。
