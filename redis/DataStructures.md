# Redis 五种常见数据类型：实现原理与应用场景

> 面向面试与实战的系统笔记。涵盖 String / List / Hash / Set / ZSet 的应用场景、底层编码、核心原理与常见八股。
> Redis 版本以 7.x 为基准。

---

## 〇、全景图：对外类型 vs 底层编码

这是理解 Redis 数据结构的**第一性原理**：
Redis 对外暴露 5 种数据类型（Type），每种类型在底层根据**数据规模和元素特征**会选择不同的**编码（encoding）**，动态切换以节省内存。

| 对外类型 | 底层编码（Redis 7.x） | 切换阈值（默认） |
|---------|----------------------|----------------|
| **String** | `int` / `embstr` / `raw` | 整数 → int；≤44 字节 → embstr；>44 字节 → raw |
| **List** | `listpack`（7.0+）/ `quicklist` | 元素少且小 → listpack；否则 quicklist |
| **Hash** | `listpack` / `hashtable` | 元素 ≤128 且每个 value ≤64 字节 → listpack |
| **Set** | `intset` / `listpack`（7.2+）/ `hashtable` | 全整数且 ≤512 → intset；元素 ≤128 且每个 ≤64 字节 → listpack；否则 hashtable |
| **ZSet** | `listpack` / `skiplist` + `hashtable` | 元素 ≤128 且每个 value ≤64 字节 → listpack |

> **关键命令**：`OBJECT ENCODING key` 可以查看某个 key 当前的底层编码。面试被追问"你怎么知道"的时候，这是标准答案。

### 编码切换的设计哲学

- **小数据量用紧凑编码**：节省内存（如 listpack、intset 都是连续数组）
- **大数据量用标准编码**：保证操作复杂度（如 hashtable 的 O(1) 查找）
- **切换通常单向**：防止在阈值附近反复抖动，代价是极端场景可能占用过多内存

### redisObject 统一封装

Redis 每个 key-value 在内部都是一个 `redisObject`：

```c
typedef struct redisObject {
    unsigned type:4;       // 对外类型：STRING / LIST / HASH / SET / ZSET
    unsigned encoding:4;   // 底层编码
    unsigned lru:LRU_BITS; // LRU/LFU 时钟
    int refcount;          // 引用计数
    void *ptr;             // 指向真正的数据
} robj;
```

`type` 决定语义，`encoding` 决定存储和操作方式，二者解耦。

---

## 一、String（字符串）

### 1.1 应用场景

| 场景 | 用法 | 关键命令 |
|------|------|---------|
| **缓存对象** | 序列化 JSON 存入 | `SET user:1001 '{"name":"xxx"}'` |
| **计数器** | 单线程保证原子性 | `INCR article:1001:views` |
| **分布式锁** | 原子性 SET | `SET lock val NX EX 30` |
| **限流（固定窗口）** | INCR + EXPIRE | `INCR rate:uid:1001` + `EXPIRE 60` |
| **Session 共享** | 微服务共享登录态 | `SET session:token userId EX 1800` |
| **位图（BitMap）** | String 的位操作，统计签到/活跃用户 | `SETBIT sign:202611 uid 1` |
| **全局 ID 生成** | INCR 原子递增 | `INCR global:order:id` |

### 1.2 底层实现：SDS（Simple Dynamic String）

Redis 没用 C 的原生 `char*`，而是自己设计了 SDS。Redis 3.2+ 按长度分 5 种 header：`sdshdr5/8/16/32/64`，这里以 sdshdr8 为例：

```c
struct sdshdr8 {
    uint8_t len;        // 已使用长度
    uint8_t alloc;      // 分配总长度（不含 header 和结尾 \0）
    unsigned char flags;// 低 3 位标识 header 类型
    char buf[];         // 实际数据，结尾仍保留 \0 以兼容 C 字符串函数
};
```

**SDS 对比 C 字符串的 4 大优势**：

| 特性 | C 字符串 | SDS |
|------|---------|-----|
| 获取长度 | O(N) 遍历到 `\0` | **O(1)** 读 `len` 字段 |
| 缓冲区溢出 | 需手动分配内存 | 自动扩容 |
| 二进制安全 | 以 `\0` 为结束符，不能存二进制 | 以 `len` 判断结束，**可存任意字节** |
| 内存重分配 | 每次修改都要 realloc | **空间预分配** + **惰性释放** |

**空间预分配**：修改后 `len < 1MB`，额外分配等长空间（`alloc = 2*len`）；`len >= 1MB`，额外分配 1MB。
**惰性释放**：缩短字符串时不立刻缩容，仅更新 `len`，把多余空间留作未来扩容。

### 1.3 三种编码

```
SET num 123        → int     （long 整数直接存在 ptr 字段，不分配 SDS）
SET name "hello"   → embstr  （≤44 字节，redisObject 和 SDS 一次分配，连续内存）
SET bio "xxxxx..." → raw     （>44 字节，redisObject 和 SDS 分两次分配）
```

**为什么是 44 字节？** `redisObject`（16B）+ `sdshdr8`（3B）+ `buf` + `\0`(1B) 要塞进一个 64 字节的 jemalloc 内存块，留给 `buf` 的就是 44 字节。**embstr 只分配一次内存**，cache 友好。

> **注意**：embstr 是**只读编码**，任何修改（如 `APPEND`）都会退化为 raw，不会回退。

#### embstr vs raw 简明对比（应用开发视角）

| 维度 | embstr | raw |
|------|--------|-----|
| 触发条件 | 长度 ≤ 44 字节 | 长度 > 44 字节 |
| 内存分配 | 1 次（robj + SDS 连续） | 2 次（robj 和 SDS 分开） |
| 可修改性 | 只读，改一下就变 raw | 可直接修改 |
| 典型场景 | 短 token、状态码、枚举值 | 长 JSON、大段文本 |

> **应用层记这一句就够了**：短字符串更省内存更快，长字符串无所谓；`APPEND` 一下 embstr 就永久变 raw，不会回退。

#### int 编码的特殊性：不使用 SDS

**当 value 能解析为 long 范围内的整数时，Redis 根本不会分配 SDS**，而是把整数值**直接塞在 `redisObject` 的 `ptr` 字段里**。

| encoding | `ptr` 字段含义 |
|---------|---------------|
| `int` | **直接存放 long 整数值本身**（不是地址！） |
| `embstr` | 指向紧挨在 redisObject 后面的 SDS |
| `raw` | 指向独立分配的 SDS |

**原理**：64 位系统上 `ptr` 是 8 字节，恰好能装下一个 long（也是 8 字节）。取值时先看 encoding，若是 `int` 就把 `ptr` 强转回 long：

```c
// 伪代码
if (obj->encoding == OBJ_ENCODING_INT) {
    long value = (long)obj->ptr;  // 强转，直接取整数值
} else {
    sds s = obj->ptr;             // 当指针解引用
}
```

**收益**：
- **省内存**：对比用 SDS 存 "123"（`robj` 16B + `sdshdr8` 3B + `"123\0"` 4B ≈ 23B），int 编码只要 16B
- **省一次 malloc**：SDS 要独立分配内存，int 编码省了
- **cache 友好**：数据就在 `redisObject` 内部，无需解引用

#### 共享整数对象池

Redis 启动时会预创建 `[0, 10000)` 的整数对象（`server.shared.integers`），所有需要这些小整数的地方**共享同一个 robj**，用 `refcount` 计数引用：

```bash
127.0.0.1:6379> SET a 100
127.0.0.1:6379> SET b 100
# a 和 b 的 value robj 其实是同一个！refcount = 2
```

这也是为什么 `INCR counter` 性能极其夸张——命令执行快 + 对象都不用新建。

> **失效场景**：当配置了 `maxmemory` 且淘汰策略是 LRU/LFU/TTL 时，共享池会失效（共享对象无法独立记录访问时间），但单个 key 的 `int` 编码本身仍然生效，只是不共享而已。

#### int 编码会退化的三种情况

```bash
# 1. 数值超出 long 范围
SET big 99999999999999999999
OBJECT ENCODING big       → "embstr"

# 2. 对整数字符串做字符串修改
SET num 123
OBJECT ENCODING num       → "int"
APPEND num "abc"
OBJECT ENCODING num       → "raw"   ← 退化后不会回退

# 3. 字符串形式的整数超过 44 字节（罕见但存在）
SET n "12345...（45 个数字字符）"
OBJECT ENCODING n         → "raw"
```

### 1.4 面试八股

**Q：String 最大能存多大？**
A：512 MB。但生产中 value > 10KB 就需要关注，> 1MB 属于大 Key。

**Q：为什么 INCR 是原子的？**
A：Redis 单线程执行命令，INCR 内部是 `读 → 解析为整数 → +1 → 写回` 一条命令内完成，不会被其他命令打断。

### 1.5 实战：固定窗口限流（支持任意窗口秒数）

**需求示例**：每 2 秒最多允许 10 次请求（即每秒 5 QPS，按 2 秒窗口聚合）。

#### 核心思路

- **每个窗口一个独立 key**，用 `INCR` 自增，超阈值即拒绝
- 时间戳按窗口秒数对齐：`windowStart = currentSec / windowSecs`，同一窗口内的请求落到同一 key
- 靠 TTL 自动清理过期 key，无需手动删除

#### Key 设计

```
rate:limit:{resource}:{windowSecs}s:{windowStart}

举例（窗口 2 秒）：
  当前秒 1714892400 → windowStart = 857446200 → key: rate:limit:api:login:2s:857446200
  当前秒 1714892401 → windowStart = 857446200 → 同一窗口 ✅
  当前秒 1714892402 → windowStart = 857446201 → 下一窗口
```

#### Lua 脚本（保证 INCR + EXPIRE 原子性）

```lua
-- KEYS[1] = 限流 key
-- ARGV[1] = 阈值
-- ARGV[2] = 窗口秒数

local current = redis.call('INCR', KEYS[1])
if current == 1 then
    -- 首次创建才设 TTL，TTL 比窗口多 1 秒做冗余，防边界过早过期
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) + 1)
end
if current > tonumber(ARGV[1]) then
    return 0  -- 拒绝
end
return 1      -- 放行
```

#### Java 实现

```java
@Component
public class FixedWindowRateLimiter {

    private static final DefaultRedisScript<Long> SCRIPT;
    static {
        SCRIPT = new DefaultRedisScript<>();
        SCRIPT.setScriptText(
            "local current = redis.call('INCR', KEYS[1]) " +
            "if current == 1 then redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) + 1) end " +
            "if current > tonumber(ARGV[1]) then return 0 end " +
            "return 1"
        );
        SCRIPT.setResultType(Long.class);
    }

    @Resource
    private StringRedisTemplate redisTemplate;

    /**
     * @param resource    资源标识，如 "api:login"
     * @param limit       窗口内最大请求数
     * @param windowSecs  窗口秒数
     * @return true=放行，false=拒绝
     */
    public boolean tryAcquire(String resource, int limit, int windowSecs) {
        long windowStart = (System.currentTimeMillis() / 1000) / windowSecs;
        String key = "rate:limit:" + resource + ":" + windowSecs + "s:" + windowStart;
        Long result = redisTemplate.execute(
            SCRIPT,
            Collections.singletonList(key),
            String.valueOf(limit),
            String.valueOf(windowSecs)
        );
        return result != null && result == 1L;
    }
}
```

**使用**：

```java
// 每 2 秒最多 10 次
if (!rateLimiter.tryAcquire("api:login", 10, 2)) {
    throw new BizException("请求过于频繁，请稍后重试");
}
```

#### 关键设计点

| 设计点 | 说明 |
|-------|------|
| **Lua 原子性** | 避免 `INCR` 成功但 `EXPIRE` 未执行导致 key 永不过期的内存泄漏 |
| **首次才设 TTL** | `if current == 1`，避免每次请求都重置 TTL 导致窗口被拉长 |
| **TTL = 窗口 + 1 秒** | 防止 key 在窗口结束前提前过期丢计数 |
| **Key 带窗口秒数后缀** | 同一资源可同时存在 1s/2s/60s 多级限流，互不干扰 |
| **时间戳对齐** | `currentSec / windowSecs` 把时间轴分段，同窗口落同 key |

#### 固定窗口的边界缺陷

```
时间轴（2 秒窗口，阈值 10）：
  ↓ 10个请求在 T:01.9           ↓ 10个请求在 T:02.1
──────────┬──────────────────┬──────────────▶
        T:00              T:02              T:04

窗口[T:00,T:02)：10 个 ✅   窗口[T:02,T:04)：10 个 ✅
但 0.2 秒内实际放行了 20 个，瞬时 QPS 被突破 2 倍
```

**对精度敏感的场景**（登录、支付）请改用 **ZSet 滑动窗口**（见 ZSet 小节）或 **令牌桶**。
当请求集中在一个窗口的末尾和一个窗口的开头访问时，系统的瞬时流量会翻倍。

固定窗口限流的核心缺陷是窗口切换点的双倍瞬时突发：因为每个窗口独立计数、到点清零，攻击者只要把请求集中在两个相邻窗口的交界处
（如第一个窗口末尾 0.1 秒和第二个窗口开头 0.1 秒），就能在远小于一个窗口的时间内累计放行 2 倍阈值的请求——每个窗口各自看都"合规"（都没超过阈值）
但实际瞬时 QPS 已被突破 2 倍，违背了限流的初衷。根因在于窗口是硬边界而非滑动的，对精度敏感的场景（登录、支付）需改用 ZSet 滑动窗口或令牌桶等平滑方案。
---

## 二、List（列表）

### 2.1 应用场景

| 场景 | 用法 | 要点 |
|------|------|------|
| **消息队列**（简易） | `LPUSH` + `BRPOP` 阻塞消费 | 无 ACK，不如 Stream/MQ 可靠 |
| **最新 N 条数据** | `LPUSH` + `LTRIM 0 N-1` | 微博时间线、最近浏览 |
| **栈 / 队列** | LPUSH+LPOP 栈；LPUSH+RPOP 队列 | O(1) |
| **分页任务池** | `LRANGE start stop` | 注意大 List 分片 |

> 阻塞消费 `BLPOP` 的实现原理见本目录 `ListBlockingConsume.md`。

### 2.2 底层实现演进

| Redis 版本 | 编码 | 说明 |
|-----------|------|------|
| < 3.2 | `ziplist` + `linkedlist` | 两者切换时需全量转换，有性能抖动 |
| 3.2 ~ 6.x | `quicklist`（双向链表 + ziplist 节点） | 统一编码 |
| **7.0+** | `quicklist`（双向链表 + **listpack** 节点） | listpack 替代 ziplist，根除连锁更新 |

### 2.3 quicklist：Redis 的自研组合结构

> **先定性**：quicklist 是 Redis 自研的私有数据结构，**不是通用数据结构**（教科书里没有这个名字）。其核心思想最接近学术上的 **unrolled linked list（展开链表）**——即"链表节点里套一个小数组"，但 Redis 用更紧凑的 listpack 取代了定长数组，是工程化改良版。

#### 2.3.1 为什么不用纯链表或纯数组？

| 方案 | 问题 |
|------|------|
| **纯双向链表**（Redis 3.2 前的 linkedlist） | 每个节点两个指针 16B，小元素时**指针开销远大于数据本身**；内存分散，cache 不友好 |
| **纯连续数组**（早期的 ziplist） | 大 List 需要整块连续内存；中间插入/删除触发 realloc，代价高 |
| **quicklist（双向链表 + listpack）** | 链表分段管理大 List；每段是 listpack 连续内存，摊薄指针开销 + cache 友好 |

**核心思想**：**节点粒度大 → 省指针；段内连续 → 省 cache**。

#### 2.3.2 整体结构

```
quicklist:
  ┌─ head ─────────────────────────────────────────────── tail ─┐
  │                                                              │
  ▼                                                              ▼
[quicklistNode]←→[quicklistNode]←→[quicklistNode]←→[quicklistNode]
  │                 │                 │                 │
  ▼                 ▼                 ▼                 ▼
 listpack         listpack          LZF             listpack
 [e1,e2,e3]       [e4,e5,e6]      (compressed)      [e9,e10]
                                     ↑
                              中间节点可被压缩
```

**两层结构**：
- **外层**：`quicklist` + `quicklistNode` 组成双向链表，负责"分段"
- **内层**：每个节点挂一个 `listpack`（7.0 前是 ziplist），负责"段内紧凑存储"

```c
// 简化结构
typedef struct quicklist {
    quicklistNode *head;
    quicklistNode *tail;
    unsigned long count;       // 总元素数
    unsigned long len;          // 节点数
    int fill;                   // 单节点大小限制（配置项）
    unsigned int compress;      // 压缩深度（配置项）
} quicklist;

typedef struct quicklistNode {
    struct quicklistNode *prev;
    struct quicklistNode *next;
    unsigned char *entry;       // 指向 listpack（或压缩后的 LZF 数据）
    size_t sz;                  // listpack 字节大小
    unsigned int count : 16;    // 本节点元素个数
    unsigned int encoding : 2;  // RAW=未压缩 / LZF=已压缩
    unsigned int container : 2; // PLAIN=单元素 / PACKED=listpack
    unsigned int recompress : 1;
    // ...
} quicklistNode;
```

#### 2.3.3 关键配置：单节点大小怎么控制？

由两个参数控制，**任一触及即认为当前节点"满了"，新元素会开新节点**：

```conf
list-max-listpack-size -2    # 每个节点的 listpack 大小上限
list-compress-depth    0      # 两端保留多少节点不压缩（0=全不压）
```

**`list-max-listpack-size` 的取值**：

| 值 | 含义 |
|----|------|
| 正数 N | 每个节点最多 N 个元素 |
| -1 | 每节点 listpack ≤ 4KB |
| **-2（默认）** | 每节点 listpack ≤ **8KB** |
| -3 | 每节点 listpack ≤ 16KB |
| -4 | 每节点 listpack ≤ 32KB |
| -5 | 每节点 listpack ≤ 64KB |

**为什么默认 8KB？** 这是 CPU L1 cache 大小量级，刚好让一个节点的数据能尽量装进 cache；同时避免 realloc 时搬太大的内存。

#### 2.3.4 中间节点压缩：list-compress-depth

`list-compress-depth` 控制两端"不压缩"的节点数，中间节点用 **LZF 算法压缩**：

```
compress-depth = 2 时：
[node1]←→[node2]←→[LZF]←→[LZF]←→[LZF]←→[node7]←→[node8]
 ^^^^^^^^^^^^^^                           ^^^^^^^^^^^^^^
 头部 2 个不压缩                          尾部 2 个不压缩
```

**设计动机**：List 的访问热点通常在**两端**（LPUSH/RPUSH/LPOP/RPOP/LRANGE 0 N），中间元素冷。冷数据压缩能**省大量内存**（LZF 对文本压缩比常 2~3 倍），代价只有访问时解压一下。

> **生产建议**：默认 `compress=0`（不压缩），性能优先；只在超大 List 内存紧张时才开启。

#### 2.3.5 LPUSH / RPUSH 流程

```
LPUSH key value:
  1. 找到 head 节点
  2. 判断 head 节点的 listpack 还能不能塞下 value
     - 能 → 直接把 value 插入 listpack 头部（O(N) 但 N 小）
     - 不能 → 新建一个 quicklistNode，挂到 head 前面，value 放入新节点
  3. 更新 count
```

**两端操作均摊 O(1)**——绝大多数情况下新元素能塞进现有首尾节点，只有节点满了才有一次开新节点的 O(1) 操作。

#### 2.3.6 LINDEX 的真实复杂度

`LINDEX key N` 的理论复杂度是 O(N)，但实际有节点级快速跳过：

```
LINDEX key 500:
  1. 遍历 quicklist 节点，累加每个节点的 count
     node1.count=128, 累计=128 (不够)    ← 节点级 O(1) 跳过
     node2.count=128, 累计=256 (不够)    ← 节点级 O(1) 跳过
     node3.count=128, 累计=384 (不够)    ← 节点级 O(1) 跳过
     node4.count=128, 累计=512 (够了)    ← 目标在此节点
  2. 在 node4 的 listpack 内顺序扫描第 (500-384)=116 个元素
```

**实际性能 ≈ O(N/M) 次节点跳转 + O(M) 次节点内扫描**（M 是单节点元素数，默认 ~128）。两端访问（`LINDEX 0` / `LINDEX -1`）是严格 O(1)。

### 2.4 listpack：quicklist 的"小数组"

#### 2.4.1 元素是**变长**的

listpack 里每个 entry 的字节数由内容决定，**不是等长**：

```
┌────────┬──────────┬─────────────┬──────────────┬──────────────┬─────┐
│ header │ entry1   │   entry2    │    entry3    │   entry4     │ END │
│ (6B)   │"hi"(3B)  │ 12345(3B)   │ "hello"(6B)  │"xxxxxx"(7B)  │ 1B  │
└────────┴──────────┴─────────────┴──────────────┴──────────────┴─────┘

每个 entry 内部：
┌──────────┬────────┬──────────┐
│ encoding │  data  │ backlen  │
│ (1~5B)   │ (变长) │ (1~5B)   │
└──────────┴────────┴──────────┘
```

- **encoding**：头部字节，标识元素类型（整数/字符串）和长度
- **data**：实际数据
- **backlen**：**自己这个 entry 的总长度**，放在尾部，用于反向遍历

#### 2.4.2 取中间元素要遍历，但很快

listpack **不支持 O(1) 下标访问**（变长嘛），取第 K 个元素必须从头扫 K 次。但因为：

1. **单个 listpack 很小**（默认 ≤128 元素或 ≤8KB）
2. **连续内存 + cache 友好**：一两次 cache line 命中就扫完了
3. **跳跃很快**：读 entry 头部的 encoding 就知道跳多少字节，不用逐字节解析

所以实际 O(N) 遍历比链表的指针跳转还快。**Redis 用"牺牲 O(1) 随机访问换内存紧凑"的权衡**，反正 N 小。

#### 2.4.3 backlen 支持反向遍历

`backlen` 放在 entry 尾部这个设计很精妙——**从尾部读最后一个字节的 backlen，就能知道最后一个 entry 多长**，从而定位它的头部：

```
反向取倒数第 2 个：
end ← 读末尾 backlen → 跳到最后 entry 头部
    ← 读它前面 entry 的 backlen → 跳到倒数第 2 个 entry ✅
```

`LINDEX key -1`、`LRANGE key -3 -1` 这类尾部访问命令因此很快。

### 2.5 ziplist 回顾与 listpack 的改进

这是 Redis 7.0 用 listpack 替换 ziplist 的核心原因。

#### 2.5.1 ziplist 的完整结构

**定位**：Redis 7.0 之前用于小集合（小 Hash / 小 ZSet / List 的 quicklist 节点）的紧凑编码。用一块**连续内存**模拟"变长元素的数组"。

**整体布局**：

```
┌─────────┬─────────┬─────────┬────────┬────────┬─────┬────────┬──────┐
│ zlbytes │ zltail  │ zllen   │ entry1 │ entry2 │ ... │ entryN │ zlend│
│  (4B)   │  (4B)   │  (2B)   │        │        │     │        │ (1B) │
└─────────┴─────────┴─────────┴────────┴────────┴─────┴────────┴──────┘
```

| 字段 | 大小 | 作用 |
|------|------|------|
| `zlbytes` | 4B | 整个 ziplist 占用字节数（含自己），realloc 要用 |
| `zltail`  | 4B | 最后一个 entry 的偏移量，让尾部访问 O(1) |
| `zllen`   | 2B | entry 个数，≥ 65535 时失效需遍历 |
| `zlend`   | 1B | 固定 `0xFF`，结束标记 |

**固定开销合计 11 字节**（header 10B + zlend 1B）。

**单个 entry 的结构**：

```
┌──────────┬──────────┬──────────┐
│ prevlen  │ encoding │   data   │
│ (1或5B)  │ (1~5B)   │  (变长)  │
└──────────┴──────────┴──────────┘
```

- **prevlen**：**前一个 entry 的长度**（注意不是自己的！），用于反向遍历
  - 前一个 entry < 254 字节 → prevlen 用 **1 字节**
  - 前一个 entry ≥ 254 字节 → prevlen 用 **5 字节**（首字节 `0xFE` + 后 4B 实际长度）
- **encoding**：标识 data 的类型（字符串/整数）和长度，本身也是**变长的**
  - **字符串**分三档（encoding 字段 1/2/5 字节）：长度 ≤ 63B 用 1B encoding；≤ 16383B 用 2B；≤ 2^32-1 用 5B
  - **整数**用 1B encoding：int16 / int32 / int64 / 24 位整数 / 8 位整数；**0~12 的小整数直接编码进 encoding，data 长度为 0**
- **data**：实际数据

**亮点**：小整数（0~12）的 entry 只有 `prevlen + encoding` 两字节，压缩到极致。

#### 2.5.2 致命问题：连锁更新

ziplist 的优点和缺点都来自 **prevlen 记录的是"前一个"的长度**。

**复现**：假设 ziplist 里有一串 253 字节的 entry（prevlen 都只占 1 字节），头部插入一个 1000 字节的大 entry：

```
步骤 1：插入 X 后
┌──────┬──────────┬──────────┬──────────┬──────────┐
│ head │ X(1000B) │  A       │  B       │  C       │
│      │          │prevlen=? │          │          │
└──────┴──────────┴──────────┴──────────┴──────────┘
                   ↑
         A 的 prevlen: 1B → 5B（X 超过 254B 了）
         A 总长度：253 → 257 字节

步骤 2：A 也超过 254B 了 → B 的 prevlen 也要 1B→5B
步骤 3：B 超过 254B → C 的 prevlen 也要改
...
最坏：一次插入引发全链重分配，O(N²)
```

**删除同样会触发**：删掉一个大 entry 后，后续 entry 的 prevlen 可能从 5B 缩回 1B，自身长度变化又引发下一个...

**触发条件苛刻**（需要一串 entry 长度都精准卡在 250~253B），**生产概率低但一旦触发就是性能抖动尖刺**，属于"不常发但会爆炸"的坑。

#### 2.5.3 listpack 的改进

listpack 的每个 entry 只记录**自己**的长度（放在尾部的 `backlen`），**不依赖前一个 entry**。插入/删除只影响自己这个 entry，**彻底根除连锁更新**。

| 对比 | ziplist | listpack |
|------|---------|----------|
| 长度字段位置 | entry 头部记录"前一个"的长度（`prevlen`） | entry 尾部记录"自己"的长度（`backlen`） |
| entry 间依赖 | 有（prevlen 链式引用） | 无（各自独立） |
| 插入/删除影响 | 可能引发连锁更新 O(N²) | 只影响自身 O(1) 修改 |
| 反向遍历 | 靠 prevlen 回退 | 靠 backlen 回退 |
| 正向遍历 | 靠 encoding 解析自身长度 | 靠 encoding 解析自身长度 |
| 固定开销 | header 10B + zlend 1B = **11B** | header 6B + end 1B = **7B** |
| Redis 版本 | < 7.0 | ≥ 7.0 |

**Redis 7.0 全面用 listpack 替换 ziplist**（List/Hash/ZSet 的小编码都换了），这是 7.0 的重要改进之一。

> **一句话记忆**：ziplist 把"长度信息"放**前面**来描述"前一个"，紧凑但有**传染性**；listpack 把"长度信息"放**后面**来描述"自己"，同样紧凑但**彼此独立**。

### 2.6 面试八股

**Q：List 能当消息队列用吗？缺点是什么？**
A：能，`LPUSH + BRPOP` 可实现阻塞消费。缺点：
- **无 ACK 机制**：消费者 pop 后宕机，消息就丢了（BLMOVE 可部分缓解）
- **不支持多消费者组**：一条消息只能被一个消费者消费
- **不能回溯**：消费过的消息找不回来
- **堆积风险**：大量堆积会导致大 Key

生产环境推荐用 **Redis Stream** 或 **RocketMQ/Kafka**。

---

## 三、Hash（哈希）

### 3.1 应用场景

| 场景 | 用法 | 对比 String |
|------|------|------------|
| **存储对象** | `HSET user:1001 name "xx" age 18` | 可单独读写某字段，省网络 |
| **购物车** | `HSET cart:uid skuId num` | field 当 skuId，value 当数量 |
| **计数聚合** | `HINCRBY stats:202611 pv 1` | 按维度聚合计数 |
| **配置中心** | `HSET config:serviceA timeout 3000` | 单字段更新无需反序列化 |

> **对象存储选型**：字段多且经常改少数字段 → Hash；整体读写 → String + JSON 更简单。

### 3.2 底层实现：listpack / hashtable

**小 Hash → listpack**
结构：`[field1, value1, field2, value2, ...]` 依次存放，查找 O(N) 但 N 小，内存紧凑。

**升级阈值（任一触及即切换为 hashtable，只升不降）**：

| 配置项 | 默认值 | 含义 |
|-------|-------|------|
| `hash-max-listpack-entries` | 128 | field 数量上限 |
| `hash-max-listpack-value`   | 64  | 单个 field 或 value 的字节上限 |

> Redis 7.0 之前对应的是 `hash-max-ziplist-entries` / `hash-max-ziplist-value`，7.0 改名但阈值不变。

**大 Hash → hashtable（`dict` 结构）**：

```c
typedef struct dict {
    dictht ht[2];        // 两个哈希表，用于渐进式 rehash
    long rehashidx;      // rehash 进度（-1 表示未 rehash）
} dict;

typedef struct dictht {
    dictEntry **table;   // 桶数组
    unsigned long size;  // 桶数量，总是 2^n
    unsigned long used;  // 已存元素数
} dictht;
```

冲突解决：**链地址法**（拉链法），头插法。

### 3.3 核心知识点：渐进式 rehash

**问题**：如果一次性 rehash 百万级元素会长时间阻塞主线程。

**解决**：
1. 扩容时分配 `ht[1]`（大小为 `ht[0].used * 2` 向上取 2^n）
2. 每次对字典的增删改查，**顺带迁移 `ht[0]` 的 `rehashidx` 索引位上的一个桶到 `ht[1]`**
3. 后台定时任务（`serverCron`）也会协助迁移
4. rehash 期间：
   - **查询**：先查 `ht[0]`，没找到再查 `ht[1]`
   - **插入**：**只写入 `ht[1]`**（保证 `ht[0]` 只减不增，最终会空）
5. 全部迁移完成后，释放 `ht[0]`，将 `ht[1]` 设为 `ht[0]`，`rehashidx = -1`

**触发条件**：
- 扩容：负载因子 ≥ 1 且没在做 BGSAVE/BGREWRITEAOF；或负载因子 ≥ 5（强制扩容）
- 缩容：负载因子 < 0.1

> **类比**：搬家不是一次性搬完，而是每次回家顺手搬一箱，最终搬完。避免大锁长停顿。

### 3.4 面试八股

**Q：为什么 BGSAVE 时不扩容？**
A：BGSAVE 是 fork 子进程用 COW 做 RDB，如果此时 rehash 大量迁移数据，会触发 COW 拷贝大量内存页，**白白增加物理内存占用**。

**Q：Hash 和 String+JSON 哪个省内存？**
A：**小 Hash（listpack 编码）最省**；字段多时 Hash 切到 hashtable 后，每个 field 一个 dictEntry（≥48B 开销），反而比 String+JSON 更费内存。经验：字段数 < 100 且有频繁单字段更新 → Hash；否则 String。

---

## 四、Set（集合）

### 4.1 应用场景

| 场景 | 用法 | 关键命令 |
|------|------|---------|
| **去重** | 文章阅读 UV | `SADD article:1001:uv uid` + `SCARD` |
| **标签** | 用户/文章标签 | `SADD user:1001:tags "java" "redis"` |
| **共同好友 / 共同关注** | 集合运算 | `SINTER user:A:follow user:B:follow` |
| **抽奖** | 随机抽取 | `SRANDMEMBER` / `SPOP` |
| **黑白名单** | 快速判存 | `SISMEMBER blacklist uid` |

### 4.2 底层实现：intset / listpack / hashtable

**编码选择优先级**：`intset` > `listpack`（7.2+）> `hashtable`。

**1) intset（整数集合）**：全部元素都是整数且数量 ≤ `set-max-intset-entries`（默认 512）时使用。

```c
typedef struct intset {
    uint32_t encoding;   // INT16 / INT32 / INT64
    uint32_t length;
    int8_t contents[];   // 有序数组，查找用二分 O(log N)
} intset;
```

**升级机制**：插入一个更大范围的整数时，整个数组**统一升级**到更大编码。例如原来都是 int16，插入一个 int32 数，整个数组全部扩展成 int32。**只升不降**。

**2) listpack（Redis 7.2+ 新增用于 Set）**：有非整数元素但规模小时使用，替代原来的小集合 hashtable 场景，进一步省内存。

**3) hashtable**：大集合使用，**value 为 NULL**，只用 key 去重，查找/插入/删除均 O(1)。

**切换阈值（任一触及即升级，只升不降）**：

| 配置项 | 默认值 | 含义 |
|-------|-------|------|
| `set-max-intset-entries`     | 512 | intset 的元素数上限（超过或出现非整数 → 退 listpack/hashtable） |
| `set-max-listpack-entries`   | 128 | listpack 的元素数上限（7.2+） |
| `set-max-listpack-value`     | 64  | listpack 单个元素的字节上限（7.2+） |

**切换路径**：

```
intset ──(出现非整数 或 元素数 > 512)──▶ listpack 或 hashtable
listpack ──(元素数 > 128 或 单元素 > 64B)──▶ hashtable
hashtable ──✗──▶ 不回退
```

### 4.3 集合运算的代价

`SINTER`、`SUNION`、`SDIFF` 非常好用，但：
- 时间复杂度 O(N*M)（N 是最小集合大小，M 是集合数量）
- 大集合运算会**长时间占用主线程**
- 建议用 `SINTERSTORE` 把结果存起来复用，或改用 `SINTERCARD`（7.0+，只要基数不要元素）

### 4.4 面试八股

**Q：Set 和 Hash 的 field 都能去重，怎么选？**
A：
- 只需要"存不存在" → Set（`SISMEMBER` O(1)）
- 需要给每个元素附加属性 → Hash（field 做 key，value 存属性）

**Q：统计超大基数用什么？**
A：Set 精确但占内存；**HyperLogLog** 用约 12KB 空间估算基数，误差 0.81%，适合 UV 统计等可容忍误差的场景。

---

## 五、ZSet（有序集合）—— Redis 最精巧的数据结构

### 5.1 应用场景

| 场景 | 用法 | 关键命令 |
|------|------|---------|
| **排行榜** | score 当分数 | `ZADD rank 100 uid` + `ZREVRANGE 0 9 WITHSCORES` |
| **延迟队列** | score 当执行时间戳 | `ZADD delay <timestamp> taskId` + 轮询 `ZRANGEBYSCORE` |
| **滑动窗口限流** | score 当请求时间戳 | `ZADD` + `ZREMRANGEBYSCORE` 删旧 + `ZCARD` 计数 |
| **带权重的标签** | score 当权重 | `ZADD tags 0.9 "java"` |
| **时间线** | score 当发布时间 | 按时间排序的 Feed 流 |

### 5.2 底层实现：listpack / (skiplist + hashtable)

**小 ZSet → listpack**：`[member1, score1, member2, score2, ...]` 按 score 升序排列，插入时二分定位 + 顺序挪移。

**大 ZSet → skiplist + hashtable**：

| 操作 | 只用 skiplist | 只用 hashtable | 组合方案 |
|------|-------------|---------------|---------|
| 按 score 范围查 | O(log N) ✅ | O(N) ❌ | O(log N) ✅ |
| 按 member 查 score | O(log N) | O(1) ✅ | O(1) ✅ |

所以 **skiplist 负责排序，hashtable 负责 O(1) 的 member→score 映射**。两者共享同一份元素（指针），**内存只多存一份指针**。

**切换阈值（任一触及即升级为 skiplist+hashtable，只升不降）**：

| 配置项 | 默认值 | 含义 |
|-------|-------|------|
| `zset-max-listpack-entries` | 128 | 元素数上限 |
| `zset-max-listpack-value`   | 64  | 单个 member 的字节上限 |

> Redis 7.0 之前对应的是 `zset-max-ziplist-entries` / `zset-max-ziplist-value`。

### 5.3 跳表（SkipList）核心原理

**思想**：在有序链表上建"多层索引"，查找时自顶向下、从左往右跳。

```
Level 3:  1 --------→ 7 --------→ 19
Level 2:  1 ---→ 4 -→ 7 ---→ 12 → 19
Level 1:  1 → 3 → 4 → 7 → 9 → 12 → 17 → 19
Level 0:  1 → 3 → 4 → 7 → 9 → 12 → 17 → 19 → 21 → 23
```

**查找 12**：从 Level 3 的 1 开始 → 下一个是 7 (≤12)，前进 → 下一个是 19 (>12)，下降 → Level 2 的 7 下一个是 12，命中。只比较了 4 次。

**关键特性**：
- 每个节点的**层数是随机的**（概率 p=0.25，抛硬币决定是否上升）
- 期望层数 `1/(1-p) ≈ 1.33`，期望查找复杂度 **O(log N)**
- 支持**范围查询**（底层是有序链表），这是哈希表做不到的

### 5.4 为什么选跳表不选红黑树？（高频面试题）

Antirez 在 Redis 文档里亲自回答过：

| 对比维度 | 跳表 | 红黑树 |
|---------|------|-------|
| **实现复杂度** | 简单（几十行核心代码） | 复杂（旋转、变色，容易写错） |
| **范围查询** | 底层有序链表，**天然支持** | 需中序遍历，常数大 |
| **内存灵活性** | 可通过调节 p 平衡内存/查询 | 固定 |
| **并发友好度** | 局部修改，**锁粒度小** | 旋转影响范围大 |
| **查找复杂度** | O(log N) 期望 | O(log N) 保证 |

> Redis 的 ZSet 场景以范围查询（`ZRANGE`、`ZRANGEBYSCORE`）为主，跳表是更优选择。

### 5.5 Redis 跳表的两点改进

1. **双向指针**：每个节点的第 0 层有 `backward` 指针，支持 `ZREVRANGE` 反向遍历。
2. **span 字段**：每个前进指针记录跨越的节点数，用于 **O(log N) 计算 rank**（`ZRANK`）。

### 5.6 面试八股

**Q：ZSet 的小集合为什么也用 listpack 能有序？**
A：listpack 是**顺序存储**，ZSet 用 listpack 时是 `[member1, score1, member2, score2, ...]` **按 score 升序排列**。插入时二分定位 + 顺序挪移，N 小可接受。元素变多再切 skiplist。

**Q：score 相同怎么排？**
A：按 member 的**字典序（字节序）**排序。

---

## 六、常见综合题

### 6.1 编码切换是单向的吗？

**基本都是单向（只膨胀不收缩）**。
- intset 升级到 int64 后不会降回 int16
- listpack 转成 hashtable 后即使删到只剩 1 个元素也不会转回

**原因**：防止在阈值附近反复抖动；**代价**：极端情况下占用过多内存。

### 6.2 大 Key 的判断和处理

| 数据类型 | 大 Key 判断标准 |
|---------|----------------|
| String | value > 10KB 需关注，> 1MB 认为是大 Key |
| Hash/Set/ZSet/List | 元素数 > 5000 或 总大小 > 10MB |

**处理方案**：
1. **拆分**：大 Hash 按 field 前缀拆成多个小 Hash；大 List 分片
2. **异步删除**：`UNLINK key`（Redis 4.0+），后台线程释放，不阻塞主线程
3. **分批清理**：`HSCAN` + `HDEL` 分批删 Hash 字段
4. **发现工具**：`redis-cli --bigkeys`、`MEMORY USAGE key`

### 6.3 SCAN 系列为什么不阻塞

`SCAN` / `HSCAN` / `SSCAN` / `ZSCAN` 基于**游标 cursor** 的渐进式遍历，每次返回一批元素和下一个 cursor，cursor=0 表示结束。**单次操作 O(1)**，适合大 Key 扫描。

**代价**：遍历期间元素变化可能导致**重复或遗漏新插入的元素**（不保证一致性，但保证遍历完所有未变化的元素）。

---

## 七、总结记忆口诀

> **String 看长度**（int / embstr / raw）
> **List 用 quicklist 套 listpack**
> **Hash 和 ZSet 都是"小用 listpack、大用哈希表/跳表"**
> **Set 全整数用 intset、非整数用 listpack / hashtable**
>
> 三大经典设计：
> - **SDS**：O(1) 取长度、二进制安全、空间预分配
> - **渐进式 rehash**：分摊式搬家，避免长停顿
> - **listpack 替代 ziplist**：根除连锁更新

---

## 八、选型速查表

| 需求 | 首选数据类型 | 关键命令 |
|------|------------|---------|
| 计数器 / 限流 | String | INCR / INCRBY |
| 分布式锁 | String | SET NX EX |
| 用户对象（频繁改字段） | Hash | HSET / HGET |
| 简单队列 | List | LPUSH / BRPOP |
| 标签 / 去重 | Set | SADD / SISMEMBER |
| 排行榜 / 延迟队列 | ZSet | ZADD / ZRANGEBYSCORE |
| 签到 / 活跃统计 | String 的 BitMap | SETBIT / BITCOUNT |
| 海量 UV 估算 | HyperLogLog | PFADD / PFCOUNT |
| 可靠消息队列 | Stream | XADD / XREADGROUP |
| 地理位置 | Geo（基于 ZSet） | GEOADD / GEOSEARCH |
