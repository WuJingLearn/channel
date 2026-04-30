## Flink 合流与分流

### 合流

合流是将**多条数据流合并为一条**进行统一处理。Flink 提供两种合流方式：

#### 1. union（联合）

将**多条类型相同**的流合并为一条流，合并后的流包含所有输入流的全部数据。

```java
DataStream<String> stream1 = env.fromElements("a", "b");
DataStream<String> stream2 = env.fromElements("c", "d");
DataStream<String> stream3 = env.fromElements("e", "f");

// 可以同时 union 多条流
DataStream<String> merged = stream1.union(stream2, stream3);
// 输出: a, b, c, d, e, f（顺序不保证）
```

**特点**：
- 所有流的**数据类型必须相同**
- 可以同时合并多条流
- 合并后数据的顺序不保证

#### 2. connect（连接）

将**两条流**连接在一起，两条流的**类型可以不同**，形成 `ConnectedStreams`。连接后需要使用 `CoMapFunction` 或 `CoFlatMapFunction` 分别处理两条流的数据。

```java
DataStream<Integer> intStream = env.fromElements(1, 2, 3);
DataStream<String> strStream = env.fromElements("a", "b");

ConnectedStreams<Integer, String> connected = intStream.connect(strStream);

DataStream<String> result = connected.map(new CoMapFunction<Integer, String, String>() {
    @Override
    public String map1(Integer value) {
        return "数字流: " + value;
    }

    @Override
    public String map2(String value) {
        return "字符串流: " + value;
    }
});
// 输出: 数字流: 1, 数字流: 2, 数字流: 3, 字符串流: a, 字符串流: b
```

**特点**：
- **只能连接两条流**
- 两条流的数据类型**可以不同**
- 通过 `map1` / `map2` 分别处理各自流的数据
- 常用于**规则流 + 数据流**的场景（如动态配置更新）

#### union 与 connect 对比

| 特性 | union | connect |
|------|-------|---------|
| 流的数量 | 可以多条 | 只能两条 |
| 数据类型 | 必须相同 | 可以不同 |
| 处理方式 | 统一处理 | 分别处理（map1 / map2） |
| 适用场景 | 同类型数据合并 | 不同类型流关联（如规则流 + 数据流） |

---

### 分流（Side Output）

分流是将**一条数据流拆分为多条**，根据条件将不同的数据分发到不同的流中。

Flink 使用**侧输出流（Side Output）**来实现分流，通过 `OutputTag` 标识不同的输出流。

```java
// 1. 定义侧输出标签
OutputTag<String> shortWords = new OutputTag<String>("short") {};
OutputTag<String> longWords = new OutputTag<String>("long") {};

// 2. 在 process 函数中分流
SingleOutputStreamOperator<String> mainStream = input.process(
    new ProcessFunction<String, String>() {
        @Override
        public void processElement(String value, Context ctx, Collector<String> out) {
            if (value.length() <= 3) {
                ctx.output(shortWords, value);    // 短单词 → 侧输出流
            } else if (value.length() >= 6) {
                ctx.output(longWords, value);     // 长单词 → 侧输出流
            } else {
                out.collect(value);               // 中等长度 → 主流
            }
        }
    }
);

// 3. 获取各个流
DataStream<String> main = mainStream;                                    // 主流
DataStream<String> shortStream = mainStream.getSideOutput(shortWords);   // 短单词流
DataStream<String> longStream = mainStream.getSideOutput(longWords);     // 长单词流
```

**特点**：
- 使用 `OutputTag` 定义侧输出标签
- 只能在 `ProcessFunction` 中使用 `ctx.output()` 输出到侧输出流
- `out.collect()` 输出到主流
- 通过 `getSideOutput()` 获取侧输出流
- 一条数据**可以同时输出到多个流**（既输出到主流又输出到侧输出流）

---

### Flink SQL 中的合流与分流

#### SQL 合流

**UNION ALL**（对应 DataStream 的 union）：将多个查询结果合并，保留所有记录（包括重复）。

```sql
SELECT name, amount FROM order_stream_a
UNION ALL
SELECT name, amount FROM order_stream_b
UNION ALL
SELECT name, amount FROM order_stream_c
```

> `UNION ALL` 保留重复行，`UNION` 会去重。流处理中通常使用 `UNION ALL`，因为 `UNION` 需要维护全量状态来去重，开销非常大。

**JOIN**（对应 DataStream 的 connect）：将两条流按条件关联，类型可以不同。

```sql
-- 普通 JOIN
SELECT a.user_id, a.order_amount, b.user_name
FROM orders a
JOIN users b ON a.user_id = b.user_id

-- 时间区间 JOIN（流处理中常用）
SELECT a.user_id, a.order_amount, b.user_name
FROM orders a
JOIN users b ON a.user_id = b.user_id
  AND b.rowtime BETWEEN a.rowtime - INTERVAL '10' MINUTE AND a.rowtime

-- Lookup JOIN（关联维表）
SELECT a.user_id, a.order_amount, b.user_name
FROM orders a
JOIN user_dim FOR SYSTEM_TIME AS OF a.proc_time AS b
  ON a.user_id = b.user_id
```

#### SQL 分流

Flink SQL 没有直接的分流语法，通过**多条 INSERT INTO** 实现，推荐使用 `STATEMENT SET`：

```sql
BEGIN STATEMENT SET;

INSERT INTO short_words
SELECT word FROM input_table WHERE LENGTH(word) <= 3;

INSERT INTO long_words
SELECT word FROM input_table WHERE LENGTH(word) >= 6;

INSERT INTO medium_words
SELECT word FROM input_table WHERE LENGTH(word) > 3 AND LENGTH(word) < 6;

END;
```

> Flink 会自动优化，多条 SQL 读取同一个源表时会**共享 Source 读取**，不会重复消费数据。

---

### 普通 JOIN 与 Lookup JOIN（维表 JOIN）的区别

#### 普通 JOIN（双流 JOIN）

两边都是**实时数据流**，Flink 需要将两条流的数据都缓存在状态中，等待匹配。

```sql
SELECT a.user_id, a.order_amount, b.user_name
FROM orders a
JOIN users b ON a.user_id = b.user_id
```

工作原理：

```
orders 流:  订单1(user_id=1) → 订单2(user_id=2) → 订单3(user_id=1) → ...
                  ↘                   ↘                   ↘
                   ┌─────────────────────────────────────────┐
                   │          Flink 状态（State）              │
                   │                                         │
                   │  orders 缓存: {订单1, 订单2, 订单3, ...}  │
                   │  users 缓存:  {用户A, 用户B, ...}         │
                   │                                         │
                   │  每来一条数据，去对面的缓存里找匹配          │
                   └─────────────────────────────────────────┘
                  ↗                   ↗
users 流:   用户A(user_id=1) → 用户B(user_id=2) → ...
```

特点：
- 两条流的数据都需要**缓存在 Flink 的状态中**
- 状态会**持续膨胀**，必须设置 TTL（`table.exec.state.ttl`）来清理过期数据
- 适合两边都在实时变化的场景（如订单流 JOIN 支付流）

#### Lookup JOIN（维表 JOIN）

一边是**实时数据流**，另一边是**外部存储的维表**（如 MySQL、HBase、Redis），每来一条流数据，就去外部存储**实时查询**。

```sql
SELECT a.user_id, a.order_amount, b.user_name
FROM orders a
JOIN user_dim FOR SYSTEM_TIME AS OF a.proc_time AS b
  ON a.user_id = b.user_id
```

工作原理：

```
orders 流:  订单1(user_id=1) → 订单2(user_id=2) → 订单3(user_id=1) → ...
                  ↓                   ↓                   ↓
              查 MySQL             查 MySQL             查 MySQL
              WHERE id=1          WHERE id=2           WHERE id=1
                  ↓                   ↓                   ↓
              返回用户信息           返回用户信息          返回用户信息

┌──────────────────────────────┐
│  外部维表（MySQL/HBase/Redis） │
│  user_id | user_name         │
│  1       | 张三               │
│  2       | 李四               │
└──────────────────────────────┘
```

特点：
- **不缓存维表数据到 Flink 状态中**（或仅做少量缓存加速）
- 每条流数据到达时，**实时查询外部存储**
- 不会导致状态膨胀
- 适合流关联相对静态的维度数据（如用户信息、商品信息）

> `FOR SYSTEM_TIME AS OF a.proc_time` 表示用流数据到达时的处理时间去查维表的当前快照。

#### 核心区别对比

| 特性 | 普通 JOIN（双流 JOIN） | Lookup JOIN（维表 JOIN） |
|------|---------------------|------------------------|
| **数据来源** | 两边都是实时流 | 一边是流，一边是外部存储 |
| **状态开销** | 大，两边数据都要缓存在状态中 | 小，不缓存维表数据 |
| **状态 TTL** | 必须设置，否则状态无限膨胀 | 不需要 |
| **查询方式** | 两边互相在缓存中查找匹配 | 每条流数据去外部存储实时查 |
| **性能瓶颈** | 状态大小、Checkpoint 耗时 | 外部存储的查询延迟和 QPS |
| **适用场景** | 两边都在实时变化 | 流关联相对静态的维度数据 |
| **SQL 语法** | `FROM a JOIN b ON ...` | `FROM a JOIN b FOR SYSTEM_TIME AS OF ... ON ...` |

---

### 维表变更后的数据一致性

Lookup JOIN 是**来一条查一次**的模式。数据到达时查维表拿到的值就作为最终结果输出，之后维表即使更新了，**已经输出的结果不会被修正**。

```
时间线 ─────────────────────────────────────────────→

维表状态:  user_id=1, name="张三"            变更为 name="张三丰"
           ──────────────────── ┃ ────────────────────
                                ┃
订单流:    订单A(user_id=1)     ┃     订单B(user_id=1)
              ↓                 ┃         ↓
          查维表 → "张三"       ┃     查维表 → "张三丰"
              ↓                 ┃         ↓
          输出: (订单A, 张三) ✅ ┃     输出: (订单B, 张三丰) ✅

结论：
  - 订单A 关联的是 "张三"（查询时的值），不会被追溯更新
  - 订单B 关联的是 "张三丰"（变更后的新值）
```

#### 不同 JOIN 类型的更新行为对比

| JOIN 类型 | 维表变更后，已输出结果是否更新 | 原因 |
|-----------|--------------------------|------|
| **Lookup JOIN** | ❌ 不会 | 查完就输出，不保留状态，不追溯 |
| **普通 JOIN（Append 模式）** | ❌ 不会 | 匹配后直接输出，状态只用于等待匹配 |
| **普通 JOIN（Retract/Changelog 模式）** | ✅ 会 | 两边都在状态中，一方更新触发撤回旧结果、发出新结果 |

#### 如果希望维表变更后能反映到结果中？

**方案一：设置 Lookup 缓存过期时间**（减少延迟，不能追溯）

```sql
CREATE TABLE user_dim (
    ...
) WITH (
    'lookup.cache.max-rows' = '1000',
    'lookup.cache.ttl' = '10 min'    -- 缓存 10 分钟后过期，重新查
);
```

> 这只能让**后续数据**更快拿到新值，已输出的结果仍不会更新。

**方案二：改用普通 JOIN + CDC 变更流**（可以追溯，但状态开销大）

将维表也作为一条变更流（CDC）接入，使用普通 JOIN。维表变更时会产生 Retract（撤回）+ Insert（新结果），下游可以收到更新。

> **总结**：Lookup JOIN 是"快照式"查询——查到什么就是什么，不追溯。如果业务要求维表变更后历史结果也要更新，需要用普通 JOIN + CDC 变更流的方式，但要承受更大的状态开销。
