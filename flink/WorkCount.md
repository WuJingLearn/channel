# Flink Word Count 算子笔记

## 整体处理流程

```
输入文本（String）
    ↓ flatMap
(word, 1) 二元组
    ↓ keyBy
按单词分区的 KeyedStream
    ↓ sum
(word, 累计次数) 聚合结果
    ↓ print
输出到控制台
```

---

## 一、flatMap 算子

### 作用

`flatMap` 是一个 **一对多的转换算子**，将一个输入元素转换为 **零个、一个或多个** 输出元素。

### 与 Java Stream flatMap 的区别

| 维度 | Java Stream flatMap | Flink flatMap |
|---|---|---|
| **输出方式** | 返回一个 `Stream<R>`，框架自动合并 | 通过 `Collector` 手动 `collect` 输出 |
| **输出控制** | 必须返回一个 Stream | 可以输出 0 个、1 个或多个元素 |
| **执行模型** | 单机、惰性求值 | **分布式**、流式处理 |
| **典型用途** | 集合扁平化、Optional 解包 | 分词、过滤+转换、一行拆多行 |

### 本质联系

两者的数学本质相同，都是 **monad 的 bind 操作**（即 `flatMap = map + flatten`）：
- **Java Stream**：`map` 产生 `Stream<Stream<T>>`，再 `flatten` 为 `Stream<T>`
- **Flink**：`map` 产生多个输出元素（概念上的嵌套），再"铺平"到输出流中

Flink 用 **Collector 命令式输出** 替代了 Java Stream 的 **返回 Stream 声明式输出**，这样在分布式流处理场景下更灵活——可以在 `flatMap` 中做过滤（不调用 `collect` 就等于过滤掉），相当于 **map + filter + flatten 三合一**。

### 在 Word Count 中的使用

```java
inputStream.flatMap(new FlatMapFunction<String, Tuple2<String, Integer>>() {
    @Override
    public void flatMap(String line, Collector<Tuple2<String, Integer>> collector) {
        // 将文本转为小写后按空白字符分割，遍历每个单词
        for (String word : line.toLowerCase().split("\\s+")) {
            // 过滤空字符串
            if (!word.isEmpty()) {
                // 输出 (单词, 1) 二元组
                collector.collect(Tuple2.of(word, 1));
            }
        }
    }
});
```

**输入**：`"hello world hello flink"`（1 个 String 元素）

**输出**：`(hello,1), (world,1), (hello,1), (flink,1)`（4 个 Tuple2 元素）

### 简单记忆

> Java 的 flatMap 是"拆开容器铺平"，Flink 的 flatMap 是"一个进来、多个出去"。

---

## 二、keyBy 算子

### 作用

`keyBy` 是 Flink 中的 **分区算子**，核心作用是 **按指定的 key 对数据流进行逻辑分组**，将相同 key 的数据路由到同一个算子实例（Task）上处理。

### 为什么需要 keyBy？

Flink 是 **分布式** 的，数据会被分散到多个并行任务上处理。如果不做 `keyBy`：

```
Task-1 收到: (flink, 1), (hello, 1)
Task-2 收到: (flink, 1), (world, 1)
Task-3 收到: (flink, 1)
```

每个 Task 只能看到自己的数据，**无法得到全局正确的计数**。

做了 `keyBy` 之后：

```
Task-1 收到所有 "flink": (flink, 1), (flink, 1), (flink, 1) → sum → (flink, 3) ✅
Task-2 收到所有 "hello": (hello, 1), (hello, 1)             → sum → (hello, 2) ✅
Task-3 收到所有 "world": (world, 1)                          → sum → (world, 1) ✅
```

### 与 Java groupingBy 的区别

| 维度 | Java Stream groupingBy | Flink keyBy |
|---|---|---|
| **本质** | 将数据收集到 `Map<K, List<V>>` | 对数据流做 **物理分区**（类似 Kafka 的 partition） |
| **执行方式** | 单机，全量数据在内存中分组 | **分布式**，通过网络 shuffle 将数据路由到对应节点 |
| **数据模型** | 有界集合，一次性处理完 | **无界流**，数据持续到达，持续分组 |
| **后续操作** | 对分组后的 Map 做聚合 | 接 `sum`/`reduce`/`window` 等有状态算子 |
| **底层机制** | HashMap | **Hash 分区 + 网络传输**（类似 MapReduce 的 Shuffle） |

### 在 Word Count 中的使用

```java
.keyBy(tuple -> tuple.f0)   // 按 Tuple2 的第一个字段（单词）进行分组
```

**输入**（flatMap 的输出）：`DataStream<Tuple2<String, Integer>>`

**输出**：`KeyedStream<Tuple2<String, Integer>, String>`

> 注意：`keyBy` 本身 **不改变数据内容**，只是改变了数据的 **分区方式**，从 `DataStream` 变成 `KeyedStream`，让相同 key 的数据聚到一起。

### 简单记忆

> `keyBy` = 分布式环境下的"按 key 分区"，类似 SQL 的 `GROUP BY` 或 MapReduce 的 Shuffle 阶段。

---

## 三、sum 算子

### 作用

`sum` 是 Flink 中的 **聚合算子**，作用是 **对 KeyedStream 中指定字段进行滚动求和**。

### 执行过程

假设输入数据流经过 `flatMap` 后产生了以下二元组：

```
(hello, 1)  ← 第 1 条
(flink, 1)  ← 第 2 条
(hello, 1)  ← 第 3 条
(hello, 1)  ← 第 4 条
```

经过 `keyBy(f0).sum(1)` 后，**每来一条数据就会立即更新并输出当前结果**：

```
(hello, 1)  ← 第 1 条到达，hello 首次出现，计数 = 1
(flink, 1)  ← 第 2 条到达，flink 首次出现，计数 = 1
(hello, 2)  ← 第 3 条到达，hello 累加 1+1 = 2
(hello, 3)  ← 第 4 条到达，hello 累加 2+1 = 3
```

### 关键特性

- **滚动聚合**：`sum` 是有状态的，Flink 会在内部为每个 key 维护一个累加值，每来一条新数据就更新并输出
- **增量计算**：不需要等所有数据到齐，来一条算一条，天然适合 **无界流**
- **必须在 keyBy 之后使用**：因为 `sum` 需要知道"对哪个分组求和"

### sum 与 reduce 的关系

`sum(1)` 其实是 `reduce` 的一个快捷方式，等价于：

```java
.reduce((tuple1, tuple2) -> Tuple2.of(tuple1.f0, tuple1.f1 + tuple2.f1))
```

`sum` 更简洁，但只能做简单的字段求和；`reduce` 更灵活，可以自定义任意聚合逻辑。

### 在 Word Count 中的使用

```java
.keyBy(tuple -> tuple.f0)   // 按单词分组
.sum(1)                      // 对第 2 个字段（索引为 1 的计数值）累加求和
```

**输入**（keyBy 的输出）：`KeyedStream<Tuple2<String, Integer>, String>`

**输出**：`DataStream<Tuple2<String, Integer>>`

### 简单记忆

> `sum` = 按分组滚动累加，来一条算一条，实时输出最新结果。

---

## 四、三个算子的协作关系

```
DataStream<String>                          ← 原始文本流
    │
    ▼ flatMap(WordSplitter)
DataStream<Tuple2<String, Integer>>         ← (word, 1) 二元组流
    │
    ▼ keyBy(tuple -> tuple.f0)
KeyedStream<Tuple2<String, Integer>, String> ← 按单词分区后的流
    │
    ▼ sum(1)
DataStream<Tuple2<String, Integer>>         ← (word, 累计次数) 结果流
```

**核心规律**：每个算子的输入都是上一个算子的输出，形成算子链（Operator Chain）。

---

## 五、Word Count 完整示例代码

### 5.1 基于文件 / 内置数据的 Word Count

> 文件路径：`src/main/java/org/javaboy/flinkdemo/WordCount.java`

**数据源**：本地文件（通过 `FileSource` + `TextLineInputFormat`）或内置示例数据（通过 `fromData`）

**运行方式**：
- 无参数：使用内置示例数据直接运行
- 传入文件路径：从 `src/main/resources/words.txt` 读取

```java
package org.javaboy.flinkdemo;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.connector.file.src.FileSource;
import org.apache.flink.connector.file.src.reader.TextLineInputFormat;
import org.apache.flink.core.fs.Path;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;

public class WordCount {

    private static final String[] SAMPLE_DATA = {
            "hello world hello flink",
            "flink is a distributed stream processing framework",
            "hello flink world",
            "stream processing with flink is powerful",
            "flink supports batch and stream processing"
    };

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<String> inputStream;
        if (args.length > 0) {
            // 文件模式：使用 FileSource 从指定路径读取文本文件
            FileSource<String> fileSource = FileSource
                    .forRecordStreamFormat(new TextLineInputFormat(), new Path(args[0]))
                    .build();
            inputStream = environment.fromSource(fileSource, WatermarkStrategy.noWatermarks(), "file-source");
        } else {
            // 内置模式：直接从内存中的示例数据创建有界数据流
            inputStream = environment.fromData(SAMPLE_DATA);
        }

        DataStream<Tuple2<String, Integer>> wordCounts = inputStream
                .flatMap(new WordSplitter())
                .keyBy(tuple -> tuple.f0)
                .sum(1);

        wordCounts.print();
        environment.execute("Flink Word Count Demo");
    }

    public static class WordSplitter implements FlatMapFunction<String, Tuple2<String, Integer>> {
        @Override
        public void flatMap(String line, Collector<Tuple2<String, Integer>> collector) {
            for (String word : line.toLowerCase().split("\\s+")) {
                if (!word.isEmpty()) {
                    collector.collect(Tuple2.of(word, 1));
                }
            }
        }
    }
}
```

**Maven 依赖**：

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-streaming-java</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-clients</artifactId>
    <version>2.0.0</version>
</dependency>
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-connector-files</artifactId>
    <version>2.0.0</version>
</dependency>
```

---

### 5.2 基于 Kafka 的 Word Count

> 文件路径：`src/main/java/org/javaboy/flinkdemo/KafkaWordCount.java`

**数据源**：Kafka Topic（通过 Flink 官方 `flink-connector-kafka`）

**运行前准备**：
```bash
# 1. 启动 Kafka Broker（默认 localhost:9092）
# 2. 创建 Topic
kafka-topics.sh --create --topic word-count-topic --bootstrap-server localhost:9092
# 3. 运行 KafkaWordCount.main()
# 4. 向 Topic 发送消息
kafka-console-producer.sh --topic word-count-topic --bootstrap-server localhost:9092
> hello world hello flink
```

```java
package org.javaboy.flinkdemo;

import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;

public class KafkaWordCount {

    private static final String BOOTSTRAP_SERVERS = "localhost:9092";
    private static final String TOPIC = "word-count-topic";
    private static final String GROUP_ID = "flink-word-count-group";

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();

        // 使用 Flink 官方 KafkaSource Builder 构建 Kafka 数据源
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
                .setBootstrapServers(BOOTSTRAP_SERVERS)
                .setTopics(TOPIC)
                .setGroupId(GROUP_ID)
                .setStartingOffsets(OffsetsInitializer.earliest())
                .setValueOnlyDeserializer(new SimpleStringSchema())
                .build();

        DataStream<String> messageStream = environment
                .fromSource(kafkaSource, WatermarkStrategy.noWatermarks(), "kafka-source");

        DataStream<Tuple2<String, Integer>> wordCounts = messageStream
                .flatMap(new WordCount.WordSplitter())
                .keyBy(tuple -> tuple.f0)
                .sum(1);

        wordCounts.print();
        environment.execute("Flink Kafka Word Count");
    }
}
```

**Maven 依赖**：

```xml
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-connector-kafka</artifactId>
    <version>4.0.0-2.0</version>
</dependency>
```

**关键点**：
- `KafkaSource.builder()` 是 Flink 官方推荐的 Kafka 数据源构建方式
- `OffsetsInitializer.earliest()` 表示从最早的消息开始消费
- `SimpleStringSchema` 将 Kafka 消息的 value 反序列化为 String
- 官方 Connector 内置支持 **exactly-once** 语义和 **checkpoint** 机制

---

### 5.3 基于 RocketMQ 的 Word Count

> 文件路径：`src/main/java/org/javaboy/flinkdemo/RocketMQWordCount.java`

**数据源**：RocketMQ Topic（通过自定义 `RichSourceFunction` 封装 RocketMQ Push Consumer）

> 由于 Flink 2.0.0 没有官方 RocketMQ Connector，需要自定义 Source 实现。

**运行前准备**：
```bash
# 1. 启动 RocketMQ NameServer + Broker（默认 localhost:9876）
# 2. 创建 Topic
mqadmin updateTopic -n localhost:9876 -t word-count-topic -c DefaultCluster
# 3. 运行 RocketMQWordCount.main()
# 4. 向 Topic 发送消息
mqadmin sendMessage -n localhost:9876 -t word-count-topic -p "hello world hello flink"
```

```java
package org.javaboy.flinkdemo;

import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.common.functions.OpenContext;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.source.legacy.RichSourceFunction;
import org.apache.flink.util.Collector;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.LinkedBlockingQueue;

public class RocketMQWordCount {

    private static final String NAME_SERVER_ADDRESS = "localhost:9876";
    private static final String TOPIC = "word-count-topic";
    private static final String CONSUMER_GROUP = "flink-word-count-group";

    public static void main(String[] args) throws Exception {
        StreamExecutionEnvironment environment = StreamExecutionEnvironment.getExecutionEnvironment();

        DataStream<String> messageStream = environment
                .addSource(new RocketMQSource(NAME_SERVER_ADDRESS, TOPIC, CONSUMER_GROUP))
                .name("rocketmq-source");

        DataStream<Tuple2<String, Integer>> wordCounts = messageStream
                .flatMap(new WordCount.WordSplitter())
                .keyBy(tuple -> tuple.f0)
                .sum(1);

        wordCounts.print();
        environment.execute("Flink RocketMQ Word Count");
    }

    /**
     * 自定义 RocketMQ Source，封装 RocketMQ Push Consumer。
     * 使用 LinkedBlockingQueue 作为消息缓冲区，
     * RocketMQ 的 MessageListener 将消息放入队列，
     * Flink 的 run 方法从队列中取出消息并发送到下游。
     */
    public static class RocketMQSource extends RichSourceFunction<String> {

        private final String nameServerAddress;
        private final String topic;
        private final String consumerGroup;

        private transient DefaultMQPushConsumer consumer;
        private transient LinkedBlockingQueue<String> messageQueue;
        private volatile boolean isRunning = true;

        public RocketMQSource(String nameServerAddress, String topic, String consumerGroup) {
            this.nameServerAddress = nameServerAddress;
            this.topic = topic;
            this.consumerGroup = consumerGroup;
        }

        @Override
        public void open(OpenContext openContext) throws Exception {
            super.open(openContext);
            messageQueue = new LinkedBlockingQueue<>(1024);

            consumer = new DefaultMQPushConsumer(consumerGroup);
            consumer.setNamesrvAddr(nameServerAddress);
            consumer.subscribe(topic, "*");

            // 注册并发消息监听器，将消息体放入阻塞队列
            consumer.registerMessageListener((MessageListenerConcurrently) (messages, context) -> {
                for (MessageExt message : messages) {
                    String body = new String(message.getBody(), StandardCharsets.UTF_8);
                    messageQueue.offer(body);
                }
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            });

            consumer.start();
        }

        @Override
        public void run(SourceContext<String> sourceContext) throws Exception {
            while (isRunning) {
                String message = messageQueue.poll(100, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (message != null) {
                    sourceContext.collect(message);
                }
            }
        }

        @Override
        public void cancel() {
            isRunning = false;
            if (consumer != null) {
                consumer.shutdown();
            }
        }
    }
}
```

**Maven 依赖**：

```xml
<dependency>
    <groupId>org.apache.rocketmq</groupId>
    <artifactId>rocketmq-client</artifactId>
    <version>5.4.0</version>
</dependency>
```

**关键点**：
- 由于 Flink 2.0.0 没有官方 RocketMQ Connector，需要自定义 `RichSourceFunction`
- 使用 `LinkedBlockingQueue` 作为 RocketMQ 消息监听器和 Flink Source 之间的缓冲区
- Flink 2.0.0 中 `open` 方法参数从 `Configuration` 改为了 `OpenContext`（Breaking Change）
- 此方案适合学习自定义 Source 原理，生产环境建议等待官方 Connector 适配 Flink 2.0

---

### 5.4 三种方案对比

| 维度 | 基于文件 | 基于 Kafka | 基于 RocketMQ |
|---|---|---|---|
| **数据源类型** | 有界（文件/内置数据） | 无界（Kafka Topic） | 无界（RocketMQ Topic） |
| **Connector** | 官方 flink-connector-files | 官方 flink-connector-kafka | 自定义 SourceFunction |
| **代码量** | 最少 | 较少（Builder 模式） | 较多（需手写 Source） |
| **可靠性** | 文件读取，天然可靠 | 内置 exactly-once | 需自行处理 checkpoint |
| **适用场景** | 批处理、快速验证 | 生产环境推荐 | 学习自定义 Source 原理 |
| **核心 API** | `FileSource` + `fromSource` | `KafkaSource` + `fromSource` | `addSource` + 自定义 `RichSourceFunction` |