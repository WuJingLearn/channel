# Flink SQL 学习笔记

## 一、概述

### Flink SQL 是什么？

Flink SQL 是 Apache Flink 提供的**基于标准 SQL 的流批一体查询语言**。它让开发者可以用写 SQL 的方式来处理实时流数据和批数据，而不需要编写 Java/Scala 代码。

### 核心优势

- **开发效率高**：一条 SQL 顶几十行 DataStream API 代码
- **学习成本低**：会写 SQL 就能写 Flink 任务
- **流批统一**：同一套 SQL 既能做实时计算，也能做离线批处理
- **生态丰富**：内置大量 Connector（Kafka、MySQL、ES、Hive 等），开箱即用

### Flink SQL 的架构层次

```
用户 SQL
   │
   ▼
Table API / SQL ──── 声明式，关系型抽象
   │
   ▼
DataStream API ──── 命令式，流处理抽象
   │
   ▼
Flink Runtime ──── 底层执行引擎
```

Flink SQL 最终会被翻译成 DataStream API 的执行计划来运行。

---

### 执行环境

#### 方式一：SQL Client（命令行交互式）

Flink 自带的 SQL 交互式客户端，适合调试和学习：

```bash
# 启动 SQL Client
./bin/sql-client.sh

# 在 SQL Client 中直接写 SQL
Flink SQL> CREATE TABLE ...;
Flink SQL> SELECT * FROM ...;
```

#### 方式二：Java 代码中嵌入 SQL

```java
import org.apache.flink.table.api.EnvironmentSettings;
import org.apache.flink.table.api.TableEnvironment;

public class FlinkSqlDemo {
    public static void main(String[] args) {
        // 创建 TableEnvironment
        EnvironmentSettings settings = EnvironmentSettings.newInstance()
                .inStreamingMode()   // 流模式，也可以用 .inBatchMode() 批模式
                .build();
        TableEnvironment tableEnv = TableEnvironment.create(settings);

        // 执行 DDL
        tableEnv.executeSql("CREATE TABLE ...");

        // 执行查询
        tableEnv.executeSql("SELECT * FROM ...").print();

        // 执行 INSERT（会提交一个 Flink Job）
        tableEnv.executeSql("INSERT INTO sink_table SELECT * FROM source_table");
    }
}
```

#### 方式三：与 DataStream 混合使用

```java
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

// DataStream → Table
DataStream<Order> orderStream = env.addSource(...);
Table orderTable = tableEnv.fromDataStream(orderStream);

// Table → DataStream
DataStream<Row> resultStream = tableEnv.toDataStream(resultTable);
```

---

## 二、数据类型

Flink SQL 支持的常用数据类型：

| 类型 | 说明 | 示例 |
|---|---|---|
| **BOOLEAN** | 布尔值 | `TRUE`, `FALSE` |
| **INT** | 32 位整数 | `42` |
| **BIGINT** | 64 位整数 | `9999999999` |
| **FLOAT** | 32 位浮点数 | `3.14` |
| **DOUBLE** | 64 位浮点数 | `3.141592653` |
| **DECIMAL(p, s)** | 精确小数，p 总位数，s 小数位 | `DECIMAL(10, 2)` |
| **STRING** | 字符串（等价于 VARCHAR） | `'hello'` |
| **VARCHAR(n)** | 可变长字符串 | `VARCHAR(255)` |
| **TIMESTAMP(p)** | 时间戳，p 为秒的小数精度（0-9） | `TIMESTAMP(3)` |
| **DATE** | 日期 | `2026-04-30` |
| **TIME** | 时间 | `14:30:00` |
| **BYTES** | 二进制数据 | - |
| **ARRAY<T>** | 数组 | `ARRAY<STRING>` |
| **MAP<K, V>** | 键值对 | `MAP<STRING, INT>` |
| **ROW<...>** | 行类型（结构体） | `ROW<name STRING, age INT>` |

---

## 三、DDL —— 建表语法

DDL 是 Flink SQL 最核心的部分，通过 `CREATE TABLE` 声明数据的来源和去向。

### 基本语法

```sql
CREATE TABLE 表名 (
    列名1  数据类型  [约束],
    列名2  数据类型  [约束],
    ...
    [WATERMARK FOR 时间列 AS 水位线表达式],
    [PRIMARY KEY (列名) NOT ENFORCED]
) WITH (
    'connector' = '连接器类型',
    '参数key'   = '参数value',
    ...
);
```

### 示例：创建 Kafka 源表

```sql
CREATE TABLE kafka_orders (
    order_id    INT,
    user_id     INT,
    product_id  STRING,
    amount      DECIMAL(10, 2),
    order_time  TIMESTAMP(3),
    -- 声明水位线：基于 order_time，允许 5 秒乱序
    WATERMARK FOR order_time AS order_time - INTERVAL '5' SECOND
) WITH (
    'connector' = 'kafka',
    'topic'     = 'orders',
    'properties.bootstrap.servers' = 'localhost:9092',
    'properties.group.id'          = 'flink-consumer',
    'scan.startup.mode'            = 'latest-offset',
    'format'    = 'json'
);
```

### 示例：创建 MySQL CDC 源表

```sql
CREATE TABLE mysql_users (
    id       INT,
    name     STRING,
    email    STRING,
    age      INT,
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector'     = 'mysql-cdc',
    'hostname'      = 'localhost',
    'port'          = '3306',
    'username'      = 'root',
    'password'      = '123456',
    'database-name' = 'mydb',
    'table-name'    = 'users'
);
```

### 示例：创建 Elasticsearch 目标表

```sql
CREATE TABLE es_order_stats (
    product_id  STRING,
    hour_start  TIMESTAMP(3),
    order_count BIGINT,
    PRIMARY KEY (product_id, hour_start) NOT ENFORCED
) WITH (
    'connector' = 'elasticsearch-7',
    'hosts'     = 'http://localhost:9200',
    'index'     = 'order_stats'
);
```

### 示例：创建 Print 表（调试用）

```sql
CREATE TABLE print_sink (
    product_id  STRING,
    order_count BIGINT
) WITH (
    'connector' = 'print'
);
```

### 其他 DDL 操作

```sql
-- 查看所有表
SHOW TABLES;

-- 查看表结构
DESCRIBE kafka_orders;
-- 或
SHOW CREATE TABLE kafka_orders;

-- 删除表
DROP TABLE IF EXISTS kafka_orders;

-- 修改表（有限支持）
ALTER TABLE kafka_orders RENAME TO kafka_orders_v2;

-- 创建临时表（会话级别，退出后消失）
CREATE TEMPORARY TABLE temp_orders (...) WITH (...);

-- 创建视图
CREATE VIEW order_summary AS
SELECT product_id, COUNT(*) AS cnt
FROM kafka_orders
GROUP BY product_id;
```

---

## 四、DML —— 数据查询与写入

### 4.1 基础查询

```sql
-- 查询所有列
SELECT * FROM kafka_orders;

-- 查询指定列
SELECT order_id, product_id, amount FROM kafka_orders;

-- 列别名
SELECT product_id AS 商品ID, amount AS 金额 FROM kafka_orders;

-- 表达式计算
SELECT product_id, amount * 0.8 AS discounted_price FROM kafka_orders;
```

### 4.2 WHERE 过滤

```sql
-- 基本过滤
SELECT * FROM kafka_orders WHERE amount > 100;

-- 多条件
SELECT * FROM kafka_orders WHERE amount > 100 AND user_id = 1001;

-- IN
SELECT * FROM kafka_orders WHERE product_id IN ('iPhone16', 'MacBook', 'iPad');
 
-- LIKE 模糊匹配
SELECT * FROM kafka_orders WHERE product_id LIKE 'iPhone%';

-- BETWEEN
SELECT * FROM kafka_orders WHERE amount BETWEEN 50 AND 200;

-- IS NULL / IS NOT NULL
SELECT * FROM kafka_orders WHERE user_id IS NOT NULL;
```

### 4.3 聚合查询

```sql
-- COUNT
SELECT product_id, COUNT(*) AS order_count FROM kafka_orders GROUP BY product_id;

-- SUM
SELECT product_id, SUM(amount) AS total_amount FROM kafka_orders GROUP BY product_id;

-- AVG / MIN / MAX
SELECT
    product_id,
    AVG(amount)  AS avg_amount,
    MIN(amount)  AS min_amount,
    MAX(amount)  AS max_amount
FROM kafka_orders
GROUP BY product_id;

-- HAVING（对聚合结果过滤）
SELECT product_id, COUNT(*) AS cnt
FROM kafka_orders
GROUP BY product_id
HAVING COUNT(*) > 100;
```

> ⚠️ **注意**：在流模式下，不带窗口的 `GROUP BY` 会产生**持续更新的结果**（Retract 流），每来一条数据就更新一次聚合结果。如果只想要窗口结束时输出一次，需要配合窗口使用。

### 4.4 DISTINCT 去重

```sql
-- 去重查询
SELECT DISTINCT product_id FROM kafka_orders;

-- COUNT DISTINCT
SELECT product_id, COUNT(DISTINCT user_id) AS unique_users
FROM kafka_orders
GROUP BY product_id;
```

### 4.5 UNION / UNION ALL

```sql
-- 合并两个流（去重）
SELECT * FROM kafka_orders_cn
UNION
SELECT * FROM kafka_orders_us;

-- 合并两个流（不去重，常用）
SELECT * FROM kafka_orders_cn
UNION ALL
SELECT * FROM kafka_orders_us;
```

### 4.6 INSERT INTO —— 写入目标表

```sql
-- 将查询结果写入目标表（提交一个 Flink Job）
INSERT INTO es_order_stats
SELECT
    product_id,
    window_start AS hour_start,
    COUNT(*) AS order_count
FROM TABLE(
    TUMBLE(TABLE kafka_orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR)
)
GROUP BY product_id, window_start, window_end;

-- 多路输出：一条 SQL 任务写入多个目标表
BEGIN STATEMENT SET;

INSERT INTO es_order_stats
SELECT product_id, COUNT(*) FROM kafka_orders GROUP BY product_id;

INSERT INTO print_sink
SELECT product_id, SUM(amount) FROM kafka_orders GROUP BY product_id;

END;
```

> 💡 `STATEMENT SET` 可以将多个 INSERT 合并为一个 Flink Job 执行，共享 Source 读取，避免重复消费。

---

## 五、Connector 与 WITH 参数

`WITH` 子句定义了表与外部系统的连接方式，不同的 Connector 有不同的参数。

### 5.1 Kafka Connector

```sql
CREATE TABLE kafka_table (
    ...
) WITH (
    'connector'                         = 'kafka',
    'topic'                             = 'my-topic',
    'properties.bootstrap.servers'      = 'broker1:9092,broker2:9092',
    'properties.group.id'               = 'my-group',
    'scan.startup.mode'                 = 'latest-offset',
    -- 可选值：earliest-offset | latest-offset | group-offsets | timestamp | specific-offsets
    'format'                            = 'json',
    -- 常用 format: json | csv | avro | debezium-json | canal-json
    'json.fail-on-missing-field'        = 'false',
    'json.ignore-parse-errors'          = 'true'
);
```

### 5.2 MySQL CDC Connector

```sql
CREATE TABLE mysql_cdc_table (
    ...
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector'      = 'mysql-cdc',
    'hostname'       = 'localhost',
    'port'           = '3306',
    'username'       = 'root',
    'password'       = '123456',
    'database-name'  = 'mydb',        -- 支持正则：'mydb.*'
    'table-name'     = 'my_table',    -- 支持正则：'orders_.*'
    'server-time-zone' = 'Asia/Shanghai'
);
```

### 5.3 Elasticsearch Connector

```sql
CREATE TABLE es_table (
    ...
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector'                    = 'elasticsearch-7',
    'hosts'                        = 'http://localhost:9200',
    'index'                        = 'my-index',
    'sink.bulk-flush.max-actions'  = '1000',
    'sink.bulk-flush.interval'     = '1s'
);
```

### 5.4 JDBC Connector（MySQL/PostgreSQL 等关系型数据库）

```sql
CREATE TABLE jdbc_table (
    id      INT,
    name    STRING,
    age     INT,
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector'  = 'jdbc',
    'url'        = 'jdbc:mysql://localhost:3306/mydb',
    'table-name' = 'users',
    'username'   = 'root',
    'password'   = '123456',
    -- 用于 Lookup Join 的缓存配置
    'lookup.cache.max-rows'   = '5000',
    'lookup.cache.ttl'        = '10min'
);
```

### 5.5 FileSystem Connector

```sql
CREATE TABLE file_table (
    user_id  INT,
    name     STRING,
    dt       STRING    -- 分区字段
) PARTITIONED BY (dt) WITH (
    'connector'      = 'filesystem',
    'path'           = 'hdfs:///data/users',
    'format'         = 'parquet',
    'sink.partition-commit.policy.kind' = 'success-file'
);
```

### 5.6 Print Connector（调试用）

```sql
CREATE TABLE print_table (...) WITH ('connector' = 'print');
```

### 5.7 DataGen Connector（造测试数据）

```sql
CREATE TABLE datagen_table (
    id       INT,
    name     STRING,
    amount   DOUBLE,
    ts       TIMESTAMP(3)
) WITH (
    'connector'                  = 'datagen',
    'rows-per-second'            = '100',
    'fields.id.kind'             = 'sequence',
    'fields.id.start'            = '1',
    'fields.id.end'              = '10000',
    'fields.name.length'         = '10',
    'fields.amount.min'          = '1.0',
    'fields.amount.max'          = '1000.0'
);
```

> 💡 `datagen` 非常适合在没有外部数据源时用来学习和测试 Flink SQL。

---

## 六、时间语义与水位线

在流处理中，时间是核心概念。Flink SQL 支持两种时间：

### 6.1 处理时间（Processing Time）

数据被 Flink 算子处理时的系统时间，不需要从数据中提取：

```sql
CREATE TABLE orders (
    order_id    INT,
    product_id  STRING,
    amount      DECIMAL(10, 2),
    proc_time   AS PROCTIME()    -- 声明处理时间列（虚拟列，不占存储）
) WITH (...);
```

### 6.2 事件时间（Event Time）

数据自身携带的业务时间，需要配合 **WATERMARK** 声明水位线：

```sql
CREATE TABLE orders (
    order_id    INT,
    product_id  STRING,
    amount      DECIMAL(10, 2),
    order_time  TIMESTAMP(3),
    -- 水位线 = 事件时间 - 容忍的乱序程度
    WATERMARK FOR order_time AS order_time - INTERVAL '5' SECOND
) WITH (...);
```

### 水位线（Watermark）的含义

```
数据到达顺序（可能乱序）:
  时间→  1s  3s  2s  5s  4s  7s  6s  8s  ...

水位线（假设容忍5秒乱序）:
  当收到 8s 的数据时，水位线 = 8 - 5 = 3s
  → 意味着 Flink 认为 ≤3s 的数据已经全部到齐
  → 如果窗口结束时间 ≤ 3s，就可以触发计算了
```

### 常用水位线策略

```sql
-- 允许 5 秒乱序（最常用）
WATERMARK FOR ts AS ts - INTERVAL '5' SECOND

-- 严格单调递增（数据完全有序，0 延迟）
WATERMARK FOR ts AS ts

-- 允许 1 分钟乱序
WATERMARK FOR ts AS ts - INTERVAL '1' MINUTE
```

### 6.3 处理时间 vs 事件时间

| | 处理时间 | 事件时间 |
|---|---|---|
| **来源** | 机器系统时钟 | 数据自身的时间字段 |
| **声明方式** | `AS PROCTIME()` | `WATERMARK FOR ts AS ...` |
| **准确性** | 不保证（受处理延迟影响） | 精确（基于业务时间） |
| **乱序处理** | 不支持 | 通过水位线处理 |
| **适用场景** | 对精度要求不高的实时监控 | 需要精确时间语义的业务计算 |

---

## 七、窗口语法

### 7.1 TVF 窗口（推荐，Flink 1.13+）

TVF（Table-Valued Function）是 Flink SQL 推荐的新窗口写法，语法更清晰。

#### 滚动窗口 TUMBLE

```sql
SELECT
    product_id,
    window_start,
    window_end,
    COUNT(*) AS order_count,
    SUM(amount) AS total_amount
FROM TABLE(
    TUMBLE(TABLE kafka_orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR)
)
GROUP BY product_id, window_start, window_end;
```

#### 滑动窗口 HOP

```sql
-- 窗口大小 1 小时，每 10 分钟滑动一次
SELECT
    product_id,
    window_start,
    window_end,
    COUNT(*) AS order_count
FROM TABLE(
    HOP(TABLE kafka_orders, DESCRIPTOR(order_time), INTERVAL '10' MINUTE, INTERVAL '1' HOUR)
)
GROUP BY product_id, window_start, window_end;
```

#### 累积窗口 CUMULATE

累积窗口是 Flink 特有的窗口类型，在一个最大窗口周期内，按固定步长逐步累积：

```sql
-- 最大窗口 1 天，每 1 小时累积输出一次
-- 效果：每小时输出"今天截至目前"的累计值
SELECT
    product_id,
    window_start,
    window_end,
    COUNT(*) AS cumulative_count
FROM TABLE(
    CUMULATE(TABLE kafka_orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR, INTERVAL '1' DAY)
)
GROUP BY product_id, window_start, window_end;
```

```
累积窗口示意（最大窗口=1天，步长=1小时）：
|--1h--|
|----2h----|
|------3h------|
|--------4h--------|
...
|--------------------24h--------------------|
```

#### GROUPING SETS —— 多维度聚合

```sql
-- 同时输出"每个商品"和"所有商品"的统计结果
SELECT
    COALESCE(product_id, '全部') AS product_id,
    window_start,
    window_end,
    COUNT(*) AS order_count
FROM TABLE(
    TUMBLE(TABLE kafka_orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR)
)
GROUP BY GROUPING SETS (
    (product_id, window_start, window_end),
    (window_start, window_end)
);
```

### 7.2 OVER 窗口（开窗函数）

OVER 窗口不像 TUMBLE/HOP 那样对数据分桶，而是**为每一行**计算一个基于相邻行的聚合值，类似传统 SQL 的窗口函数。

#### 按时间范围的 OVER 窗口

```sql
-- 每一行计算"过去 1 小时内"该商品的订单数和平均金额
SELECT
    order_id,
    product_id,
    amount,
    order_time,
    COUNT(*) OVER w AS recent_order_count,
    AVG(amount) OVER w AS recent_avg_amount
FROM kafka_orders
WINDOW w AS (
    PARTITION BY product_id
    ORDER BY order_time
    RANGE BETWEEN INTERVAL '1' HOUR PRECEDING AND CURRENT ROW
);
```

#### 按行数的 OVER 窗口

```sql
-- 每一行计算"最近 10 条订单"的平均金额
SELECT
    order_id,
    product_id,
    amount,
    AVG(amount) OVER (
        PARTITION BY product_id
        ORDER BY order_time
        ROWS BETWEEN 10 PRECEDING AND CURRENT ROW
    ) AS moving_avg
FROM kafka_orders;
```

#### OVER 窗口支持的函数

| 函数 | 说明 |
|---|---|
| `COUNT(*)` | 窗口内行数 |
| `SUM(col)` | 窗口内求和 |
| `AVG(col)` | 窗口内平均 |
| `MIN(col)` / `MAX(col)` | 窗口内最小/最大值 |
| `ROW_NUMBER()` | 行号（用于 TopN） |
| `FIRST_VALUE(col)` / `LAST_VALUE(col)` | 窗口内第一个/最后一个值 |
| `LAG(col, n)` / `LEAD(col, n)` | 前 n 行 / 后 n 行的值 |

---

## 八、JOIN 语法

Flink SQL 支持多种 JOIN 方式，适用于不同的流处理场景。

### 8.1 Regular Join（常规双流 JOIN）

两条流互相等待，任意一侧有新数据到达都会尝试匹配：

```sql
SELECT
    o.order_id,
    o.product_id,
    o.amount,
    u.name AS user_name,
    u.email
FROM kafka_orders AS o
INNER JOIN kafka_users AS u
    ON o.user_id = u.id;
```

支持的 JOIN 类型：`INNER JOIN`、`LEFT JOIN`、`RIGHT JOIN`、`FULL OUTER JOIN`

> ⚠️ **注意**：Regular Join 会保留两侧流的**全部历史状态**，长期运行会导致状态无限增长。需要配置状态 TTL：
>
> ```sql
> -- 设置状态保留时间为 1 小时
> SET 'table.exec.state.ttl' = '3600000';
> ```

### 8.2 Interval Join（时间区间 JOIN）

只匹配时间范围内的数据，状态会自动清理，适合有时间关联关系的场景：

```sql
-- 订单和支付在 30 分钟内的匹配
SELECT
    o.order_id,
    o.amount,
    p.pay_time,
    p.pay_method
FROM kafka_orders AS o
JOIN kafka_payments AS p
    ON o.order_id = p.order_id
    AND p.pay_time BETWEEN o.order_time AND o.order_time + INTERVAL '30' MINUTE;
```

### 8.3 Lookup Join（维表 JOIN）

流数据实时关联外部数据库（如 MySQL），用于补充维度信息：

```sql
-- 先创建 JDBC 维表（数据库中已有数据）
CREATE TABLE dim_products (
    id      STRING,
    name    STRING,
    category STRING,
    PRIMARY KEY (id) NOT ENFORCED
) WITH (
    'connector'  = 'jdbc',
    'url'        = 'jdbc:mysql://localhost:3306/mydb',
    'table-name' = 'products',
    'username'   = 'root',
    'password'   = '123456',
    'lookup.cache.max-rows' = '5000',
    'lookup.cache.ttl'      = '10min'
);

-- Lookup Join：关键字 FOR SYSTEM_TIME AS OF
SELECT
    o.order_id,
    o.product_id,
    p.name AS product_name,
    p.category,
    o.amount
FROM kafka_orders AS o
JOIN dim_products FOR SYSTEM_TIME AS OF o.proc_time AS p
    ON o.product_id = p.id;
```

> 💡 `FOR SYSTEM_TIME AS OF o.proc_time` 表示用订单到达的时刻去查维表的快照。

### 8.4 Temporal Join（时态 JOIN）

关联一个随时间变化的版本表（如汇率表），获取对应时刻的版本数据：

```sql
-- 汇率表（CDC 方式读取，数据会更新）
CREATE TABLE currency_rates (
    currency    STRING,
    rate        DECIMAL(10, 6),
    update_time TIMESTAMP(3),
    WATERMARK FOR update_time AS update_time - INTERVAL '5' SECOND,
    PRIMARY KEY (currency) NOT ENFORCED
) WITH ('connector' = 'mysql-cdc', ...);

-- Temporal Join：根据订单时间关联当时的汇率
SELECT
    o.order_id,
    o.amount,
    o.currency,
    r.rate,
    o.amount * r.rate AS amount_in_cny
FROM kafka_orders AS o
JOIN currency_rates FOR SYSTEM_TIME AS OF o.order_time AS r
    ON o.currency = r.currency;
```

### JOIN 类型对比

| JOIN 类型 | 适用场景 | 状态管理 | 性能 |
|---|---|---|---|
| **Regular Join** | 两条流对等匹配 | 状态无限增长，需设 TTL | ⚠️ |
| **Interval Join** | 有时间范围约束的双流匹配 | 自动清理过期状态 | ✅ |
| **Lookup Join** | 流关联外部数据库维表 | 无状态（查数据库+缓存） | ✅ |
| **Temporal Join** | 流关联随时间变化的版本表 | 维护版本状态 | ✅ |

---

## 九、高级特性

### 9.1 TopN

实时计算排行榜，Flink SQL 通过 `ROW_NUMBER()` 实现：

```sql
-- 实时热销商品 Top 10
SELECT product_id, order_count
FROM (
    SELECT
        product_id,
        order_count,
        ROW_NUMBER() OVER (ORDER BY order_count DESC) AS row_num
    FROM (
        SELECT product_id, COUNT(*) AS order_count
        FROM kafka_orders
        GROUP BY product_id
    )
)
WHERE row_num <= 10;
```

#### 窗口 TopN（每小时的 Top 10）

```sql
SELECT product_id, window_start, window_end, order_count
FROM (
    SELECT
        product_id,
        window_start,
        window_end,
        order_count,
        ROW_NUMBER() OVER (PARTITION BY window_start, window_end ORDER BY order_count DESC) AS row_num
    FROM (
        SELECT
            product_id,
            window_start,
            window_end,
            COUNT(*) AS order_count
        FROM TABLE(
            TUMBLE(TABLE kafka_orders, DESCRIPTOR(order_time), INTERVAL '1' HOUR)
        )
        GROUP BY product_id, window_start, window_end
    )
)
WHERE row_num <= 10;
```

### 9.2 去重（Deduplication）

对流数据按主键去重，只保留第一条或最后一条：

```sql
-- 保留每个 order_id 的第一条数据（去重）
SELECT order_id, user_id, product_id, amount, order_time
FROM (
    SELECT
        *,
        ROW_NUMBER() OVER (PARTITION BY order_id ORDER BY order_time ASC) AS row_num
    FROM kafka_orders
)
WHERE row_num = 1;

-- 保留最后一条（取最新）
-- 将 ORDER BY 改为 DESC 即可
```

### 9.3 CEP 模式匹配（MATCH_RECOGNIZE）

用 SQL 实现复杂事件处理（CEP），在流中匹配一系列事件模式：

```sql
-- 场景：检测用户连续 3 次登录失败
SELECT *
FROM user_login_events
MATCH_RECOGNIZE (
    PARTITION BY user_id
    ORDER BY event_time
    MEASURES
        A.event_time AS first_fail_time,
        C.event_time AS last_fail_time
    ONE ROW PER MATCH
    AFTER MATCH SKIP PAST LAST ROW
    PATTERN (A B C)
    DEFINE
        A AS A.login_status = 'FAIL',
        B AS B.login_status = 'FAIL',
        C AS C.login_status = 'FAIL'
);

-- 场景：检测股价连续上涨
SELECT *
FROM stock_prices
MATCH_RECOGNIZE (
    PARTITION BY symbol
    ORDER BY ts
    MEASURES
        FIRST(A.price)  AS start_price,
        LAST(A.price)   AS end_price,
        COUNT(A.price)  AS rise_count
    ONE ROW PER MATCH
    AFTER MATCH SKIP PAST LAST ROW
    PATTERN (A{3,})                           -- 连续 3 次及以上
    DEFINE
        A AS A.price > LAST(A.price, 1)       -- 当前价格 > 上一条价格
);
```

### 9.4 常用内置函数

#### 字符串函数

```sql
SELECT
    UPPER('hello'),                         -- 'HELLO'
    LOWER('HELLO'),                         -- 'hello'
    LENGTH('flink'),                        -- 5
    CONCAT('hello', ' ', 'flink'),          -- 'hello flink'
    SUBSTRING('hello flink', 7),            -- 'flink'
    REPLACE('hello world', 'world', 'flink'), -- 'hello flink'
    TRIM('  hello  '),                      -- 'hello'
    REGEXP_EXTRACT('order-12345', '(\d+)', 1) -- '12345'
FROM (VALUES (1));
```

#### 时间函数

```sql
SELECT
    CURRENT_DATE,                           -- 当前日期
    CURRENT_TIME,                           -- 当前时间
    CURRENT_TIMESTAMP,                      -- 当前时间戳
    NOW(),                                  -- 同 CURRENT_TIMESTAMP
    DATE_FORMAT(TIMESTAMP '2026-04-30 14:30:00', 'yyyy-MM-dd'), -- '2026-04-30'
    TIMESTAMPDIFF(MINUTE, ts1, ts2),        -- 两个时间差（分钟）
    TIMESTAMPADD(HOUR, 1, ts)               -- 加 1 小时
FROM ...;
```

#### 条件函数

```sql
SELECT
    -- CASE WHEN
    CASE
        WHEN amount > 1000 THEN '高'
        WHEN amount > 100  THEN '中'
        ELSE '低'
    END AS level,

    -- IF
    IF(amount > 100, '大额', '小额') AS order_type,

    -- COALESCE（取第一个非 NULL 值）
    COALESCE(nickname, name, '匿名用户') AS display_name,

    -- NULLIF（两值相等返回 NULL）
    NULLIF(status, 'UNKNOWN') AS valid_status
FROM ...;
```

#### 类型转换

```sql
SELECT
    CAST('123' AS INT),                     -- 字符串转整数
    CAST(123 AS STRING),                    -- 整数转字符串
    CAST('2026-04-30' AS DATE),             -- 字符串转日期
    CAST(amount AS DECIMAL(10, 2))          -- 转精确小数
FROM ...;
```

### 9.5 UDF —— 自定义函数

当内置函数不够用时，可以用 Java 编写自定义函数：

#### 标量函数（Scalar Function）—— 一进一出

```java
public class MaskPhoneFunction extends ScalarFunction {
    public String eval(String phone) {
        if (phone == null || phone.length() != 11) return phone;
        return phone.substring(0, 3) + "****" + phone.substring(7);
    }
}
```

```sql
-- 注册并使用
CREATE FUNCTION mask_phone AS 'com.example.MaskPhoneFunction';
SELECT mask_phone(phone) FROM users;
```

#### 表函数（Table Function）—— 一进多出

```java
public class SplitFunction extends TableFunction<Row> {
    public void eval(String str) {
        for (String s : str.split(",")) {
            collect(Row.of(s, s.length()));
        }
    }
}
```

```sql
CREATE FUNCTION split_func AS 'com.example.SplitFunction';

SELECT word, len
FROM orders, LATERAL TABLE(split_func(tags)) AS t(word, len);
```

#### 聚合函数（Aggregate Function）—— 多进一出

```java
public class WeightedAvg extends AggregateFunction<Double, WeightedAvgAccum> {
    @Override
    public WeightedAvgAccum createAccumulator() { return new WeightedAvgAccum(); }

    public void accumulate(WeightedAvgAccum acc, long value, int weight) {
        acc.sum += value * weight;
        acc.count += weight;
    }

    @Override
    public Double getValue(WeightedAvgAccum acc) {
        return (double) acc.sum / acc.count;
    }
}
```

---

## 十、SET 配置参数

在 SQL Client 或代码中可以通过 `SET` 调整运行时参数：

```sql
-- 状态后端 TTL（状态保留时间）
SET 'table.exec.state.ttl' = '3600000';         -- 1 小时

-- 并行度
SET 'parallelism.default' = '4';

-- Checkpoint 间隔
SET 'execution.checkpointing.interval' = '60s';

-- Mini-Batch 优化（减少状态访问频率，提升吞吐）
SET 'table.exec.mini-batch.enabled' = 'true';
SET 'table.exec.mini-batch.allow-latency' = '5s';
SET 'table.exec.mini-batch.size' = '5000';

-- 查看当前配置
SET;
```

---

## 总结：Flink SQL 核心语法速查

```
DDL ──── CREATE TABLE ... WITH (connector配置)
         CREATE VIEW / DROP TABLE / SHOW TABLES

DML ──── SELECT / WHERE / GROUP BY / HAVING
         INSERT INTO ... SELECT ...
         STATEMENT SET（多路输出）

时间 ──── PROCTIME()           处理时间
         WATERMARK FOR ts     事件时间 + 水位线

窗口 ──── TUMBLE / HOP / CUMULATE / SESSION（TVF 窗口）
         OVER (PARTITION BY ... ORDER BY ... ROWS/RANGE ...)

JOIN ──── Regular Join          双流等值 JOIN
         Interval Join         时间区间 JOIN
         Lookup Join           维表 JOIN（FOR SYSTEM_TIME AS OF）
         Temporal Join         时态 JOIN

高级 ──── ROW_NUMBER()          TopN / 去重
         MATCH_RECOGNIZE       CEP 模式匹配
         UDF                   自定义函数（Scalar/Table/Aggregate）
```