# Day 19-20：高并发场景设计

> 复习目标：掌握秒杀、短链、延迟队列、分库分表、读写分离等高频系统设计题。

---

## 一、秒杀系统设计

**Q1：秒杀系统的核心设计思路？**

A：核心原则 —— **将请求尽量拦截在上游，减少到达数据库的请求量**。

```
用户 → CDN/前端（按钮防抖、验证码）
     → 网关（限流、黑名单、用户频率控制）
     → Redis（库存预扣减、原子操作）
     → MQ（异步下单、削峰）
     → 数据库（实际扣库存、创建订单）
```

**关键设计点**：
1. **前端限流**：按钮点击后置灰、答题/验证码、请求加密签名防刷
2. **网关限流**：Sentinel 限流、IP 频率限制、用户级别限流
3. **库存预热**：活动开始前将库存加载到 Redis
4. **Redis 预扣库存**：`DECR stock_key`，原子操作。结果 < 0 则售罄直接返回
5. **异步下单**：Redis 扣减成功后发 MQ 消息，消费者异步创建订单
6. **数据库兜底**：消费者用乐观锁/FOR UPDATE 扣减数据库库存，防止超卖

---

**Q2：如何防止超卖？**

A：三层保障：

**1. Redis 原子扣减（Lua 脚本）**：
```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock > 0 then
    redis.call('DECR', KEYS[1])
    return 1  -- 扣减成功
end
return 0  -- 售罄
```

**2. 数据库乐观锁**：
```sql
UPDATE product SET stock = stock - 1 WHERE id = #{id} AND stock > 0;
-- 返回影响行数 0 则说明库存不足
```

**3. 唯一约束**：订单表 `(user_id, product_id)` 唯一索引，防止同一用户重复下单。

---

## 二、短链系统设计

**Q3：短链系统的核心设计？**

A：
**核心流程**：
1. 用户提交长 URL → 生成短码（如 `abc123`）→ 存入映射表 → 返回短 URL `https://s.cn/abc123`
2. 用户访问短 URL → 查映射表 → **302 重定向**到原始长 URL

**短码生成方案**：
| 方案 | 原理 | 优缺点 |
|------|------|--------|
| 自增 ID + Base62 | 数据库自增 ID → Base62 编码（0-9, a-z, A-Z） | 简单、有序，但可被枚举 |
| Hash 截取 | MurmurHash(URL) → 取前 6-8 位 | 可能碰撞，需布隆过滤器去重 |
| 发号器 | Snowflake/Leaf → Base62 | 分布式友好，无碰撞 |

**302 vs 301**：
- **302 临时重定向**（推荐）：浏览器每次都请求短链服务器 → 可统计点击量
- **301 永久重定向**：浏览器缓存结果 → 减少服务器压力，但无法统计

**性能优化**：
- Redis 缓存热门短链映射
- 布隆过滤器快速判断短码是否存在
- 读多写少，适合多级缓存

---

## 三、延迟任务

**Q4：订单超时取消的方案对比？**

A：
| 方案 | 原理 | 精度 | 适用场景 |
|------|------|------|---------|
| **定时扫描** | 定时任务扫描数据库中未支付订单 | 低（取决于扫描间隔） | 数据量小、精度要求不高 |
| **延迟队列（RocketMQ）** | 发送延迟消息，到时间后消费者处理 | 中（18 个级别，最细 1 秒） | 已用 RocketMQ 的项目 |
| **Redis 过期键 + 通知** | 设 key 过期 → keyspace notification | 不可靠（通知可能丢失） | 不推荐生产使用 |
| **Redis ZSet** | score = 过期时间戳，定时 ZRANGEBYSCORE 取到期的 | 中（取决于扫描间隔） | 轻量级方案 |
| **时间轮（HashedWheelTimer）** | Netty 时间轮，O(1) 添加/删除定时任务 | 高 | 单机、大量定时任务 |
| **Redisson 延迟队列** | 基于 Redis SortedSet 实现的延迟队列 | 高 | 分布式延迟任务 |

**推荐方案**：RocketMQ 延迟消息（已有 MQ）或 Redisson 延迟队列（需要分布式 + 精度）。

---

## 四、分库分表

**Q5：什么时候需要分库分表？分库和分表有什么区别？**

A：
**分表时机**：单表数据量超过 **500 万-1000 万行** 或单表大小超过 **2GB**，查询和写入性能明显下降。

| 维度 | 分表 | 分库 |
|------|------|------|
| 解决问题 | 单表数据量大，查询慢 | 单库并发高 / 容量不够 |
| 方式 | 水平分表（同结构多张表） | 水平分库（同结构多个库） |
| 跨表问题 | 跨分片查询、排序、JOIN | 跨库事务（分布式事务） |

---

**Q6：常用分片策略有哪些？**

A：
| 策略 | 原理 | 优点 | 缺点 |
|------|------|------|------|
| **Hash 取模** | `hash(sharding_key) % N` | 数据分布均匀 | 扩容需要 rehash（数据迁移） |
| **Range 范围** | 按时间/ID 范围划分（如 1-100万、100万-200万） | 扩容方便（新增分片即可） | 容易数据倾斜（热点在最新分片） |
| **一致性 Hash** | 哈希环，增减节点只影响相邻分片 | 扩容影响小 | 实现复杂，可能不均匀（需虚拟节点） |

**选择建议**：
- 业务 ID 分片 → Hash 取模（如订单按 user_id 取模）
- 时间序列数据 → Range（如日志按月分表）

---

**Q7：ShardingSphere 的原理？**

A：ShardingSphere 提供两种模式：

**1. ShardingSphere-JDBC**（推荐）：
- 嵌入应用的轻量级 Java 框架
- 在 JDBC 层拦截 SQL → 解析 → 路由到目标库表 → 改写 SQL → 执行 → 结果归并
- 无代理层，性能损耗小

**2. ShardingSphere-Proxy**：
- 独立部署的数据库代理
- 对应用透明（应用像连单库一样连 Proxy）
- 适合异构语言

**核心流程**：SQL 解析 → 路由（根据分片策略）→ SQL 改写（替换表名）→ 执行（多数据源并发）→ 结果归并（ORDER BY、LIMIT、聚合）

---

**Q8：分库分表后有哪些问题？如何解决？**

A：
| 问题 | 解决方案 |
|------|---------|
| **跨分片查询** | 冗余字段、宽表、ES 异构索引 |
| **跨分片 JOIN** | 全局表（字典表每个库都放一份）、应用层组装 |
| **跨分片排序/分页** | 各分片取数据 → 应用层归并排序（深分页性能差） |
| **分布式事务** | Seata AT / 柔性事务（本地消息表） |
| **全局 ID** | Snowflake / Leaf / 号段模式 |
| **扩容迁移** | 预分片（提前分够 1024 个逻辑表）、双写迁移 |

---

## 五、读写分离

**Q9：读写分离的方案和一致性问题？**

A：
**方案**：
- 写操作 → 主库（Master）
- 读操作 → 从库（Slave）
- 中间件路由：ShardingSphere、MyCat、ProxySQL

**一致性问题**：主从延迟导致从库读到旧数据。

**解决方案**：
| 方案 | 说明 |
|------|------|
| **强制走主库** | 写操作后一段时间内的读强制走主库（如加标记到 ThreadLocal 或 Cookie） |
| **半同步复制** | 至少一个从库确认收到 Binlog 才返回（减少延迟窗口） |
| **延迟检测** | 监控主从延迟（`SHOW SLAVE STATUS`），延迟超阈值则读走主库 |
| **GTID 等待** | 写操作后获取 GTID，读操作等从库 GTID 追上后再读 |

---

## 六、大数据量导出

**Q10：大数据量导出方案？**

A：
| 方案 | 说明 | 适用场景 |
|------|------|---------|
| **流式查询** | `ResultSet.TYPE_FORWARD_ONLY` + `fetchSize=Integer.MIN_VALUE`（MySQL），逐行读取不加载全量到内存 | 数据量大但结构简单 |
| **分批查询** | `WHERE id > lastId ORDER BY id LIMIT 1000`，逐批查询导出 | 通用方案 |
| **异步导出** | 前端提交导出任务 → 后端异步生成文件 → 完成后通知下载 | 导出耗时长 |
| **分片并行** | 多线程按 ID 范围分片并行查询 + 合并结果 | 追求速度 |

**生成 Excel**：
- 小数据量：Apache POI
- 大数据量：**EasyExcel**（阿里，基于 SAX 解析，内存占用低）

**关键注意**：
- 避免 OOM：不要一次性 `SELECT *`，用分批或流式
- 导出文件不要写到应用内存，直接写磁盘或 OSS
- 超大文件考虑 CSV 替代 Excel（Excel 单 sheet 上限约 104 万行）

---

## 七、其他高频场景

**Q11：如何设计一个限流组件？**

A：
```java
// 基于令牌桶的简易限流器
public class TokenBucketRateLimiter {
    private final long maxTokens;        // 桶容量
    private final long refillRate;       // 每秒放入的令牌数
    private long currentTokens;          // 当前令牌数
    private long lastRefillTimestamp;     // 上次填充时间

    public synchronized boolean tryAcquire() {
        refill();
        if (currentTokens > 0) {
            currentTokens--;
            return true;  // 获取令牌成功
        }
        return false;  // 限流
    }

    private void refill() {
        long now = System.nanoTime();
        long elapsed = now - lastRefillTimestamp;
        long tokensToAdd = elapsed * refillRate / 1_000_000_000;
        currentTokens = Math.min(maxTokens, currentTokens + tokensToAdd);
        lastRefillTimestamp = now;
    }
}
```

分布式限流：用 Redis + Lua 脚本实现令牌桶（或直接用 Sentinel）。

---

**Q12：如何设计一个延迟队列？**

A：基于 Redis ZSet 的延迟队列：
```java
// 添加延迟任务
jedis.zadd("delay_queue", System.currentTimeMillis() + delayMs, taskId);

// 消费者轮询
while (true) {
    // 取出到期的任务（score ≤ 当前时间）
    Set<String> tasks = jedis.zrangeByScore("delay_queue", 0, System.currentTimeMillis(), 0, 10);
    for (String taskId : tasks) {
        // ZREM 原子移除，返回 1 表示成功抢到（防并发重复消费）
        if (jedis.zrem("delay_queue", taskId) == 1) {
            processTask(taskId);
        }
    }
    Thread.sleep(100);  // 轮询间隔
}
```

---

**Q13：如何设计一个幂等框架？**

A：
**通用幂等方案**：
1. 客户端生成唯一请求 ID（如 UUID）
2. 服务端在处理前检查该 ID 是否已处理
3. 未处理 → 执行业务 + 记录 ID → 返回结果
4. 已处理 → 直接返回之前的结果

**存储选择**：
- Redis：`SETNX request_id result EX 3600`，高性能，适合大部分场景
- 数据库：唯一索引表，强一致，适合金融场景

**注意**：业务操作和幂等标记的写入需要在**同一事务/原子操作**中，否则可能出现标记写入但业务失败的情况。
