# Redis 五种数据类型：实现原理与应用场景

面向面试与实战的系统笔记，涵盖 String / List / Hash / Set / ZSet 的应用场景、底层编码、核心原理。版本以 Redis 7.x 为基准。

---

## 〇、理解 Redis 数据结构的第一性原理

Redis 对外只有 **5 种数据类型**（String / List / Hash / Set / ZSet），但每种类型在内部并不只对应一种实现。**Redis 会根据数据规模和元素特征，在多种"底层编码"之间动态切换**，目的只有一个：**小数据用紧凑编码省内存，大数据用标准编码保性能**。

这套设计的根基是 `redisObject` —— 每个 key 对应的 value 在内存里都是这个统一封装：

```c
typedef struct redisObject {
    unsigned type:4;       // 对外类型：STRING / LIST / HASH / SET / ZSET
    unsigned encoding:4;   // 底层编码
    unsigned lru:LRU_BITS; // LRU/LFU 时钟
    int refcount;          // 引用计数
    void *ptr;             // 指向真正的数据（也可能直接放整数值）
} robj;
```

`type` 决定语义（暴露给用户的命令集合），`encoding` 决定存储方式（实际怎么放在内存里），**二者解耦**——这就是 Redis 能用一套命令、多套实现的关键。

下面这张表是整篇文档的"导航图"，先记住它，再深入每一节：

| 对外类型 | 底层编码（Redis 7.x） | 切换条件（默认配置） |
|---------|----------------------|----------------|
| **String** | `int` / `embstr` / `raw` | 整数 → int；≤ 44 字节 → embstr；> 44 字节 → raw |
| **List** | `quicklist`（节点为 `listpack`） | 7.0 起所有 List 都是 quicklist，节点编码统一为 listpack |
| **Hash** | `listpack` / `hashtable` | 元素 ≤ 128 且每个 field/value ≤ 64 字节 → listpack |
| **Set** | `intset` / `listpack`（7.2+）/ `hashtable` | 全整数且 ≤ 512 → intset；非整数但小 → listpack；否则 hashtable |
| **ZSet** | `listpack` / `skiplist + hashtable` | 元素 ≤ 128 且每个 member ≤ 64 字节 → listpack |

三个特别值得记住的设计哲学：

- **紧凑编码靠"连续内存"**——listpack、intset、ziplist 本质都是变长数组，省指针、对 cache 友好。
- **标准编码靠"指针结构"**——hashtable、skiplist 用指针换 O(1)/O(log N) 复杂度。
- **切换通常单向**——只升不降，防止在阈值附近反复抖动；代价是极端场景可能比预期占内存。

> 想现场看任何一个 key 的真实编码，用 `OBJECT ENCODING key`。面试被追问"你怎么验证的"，这就是答案。

---

## 一、String（字符串）

String 是 Redis 最基础也最被低估的类型。它能存的不只是文本——任意二进制数据（图片、序列化对象）、整数计数器、位图（BitMap）都用它。理解 String 的关键不是命令，而是**它在内存里到底长什么样**。

### 1.1 典型应用场景

- **缓存对象**：`SET user:1001 '{...JSON...}'`，最常见的用法。
- **计数器**：`INCR article:1001:views`，单线程保证原子，性能极致。
- **分布式锁**：`SET lock val NX EX 30`，原子的"设置 + TTL"。
- **限流**：固定窗口靠 `INCR + EXPIRE`，简单粗暴（缺陷见 1.5）。
- **Session 共享**：`SET session:token userId EX 1800`，多服务共享登录态。
- **BitMap**：`SETBIT sign:202611 uid 1`，签到、活跃用户统计，每个用户只占 1 bit。
- **全局 ID 生成**：`INCR global:order:id`，发号器。

### 1.2 底层基础：SDS（Simple Dynamic String）

Redis 没有直接用 C 的 `char*`——C 字符串以 `\0` 结尾、长度要遍历、无法存二进制，作为数据库的核心结构远远不够。Redis 自己设计了 SDS，Redis 3.2 起按长度分了 5 种 header（`sdshdr5/8/16/32/64`）以进一步省内存。下面以 `sdshdr8` 为例：

```c
struct sdshdr8 {
    uint8_t len;         // 已使用长度
    uint8_t alloc;       // 分配总长度（不含 header 和结尾 \0）
    unsigned char flags; // 低 3 位标识 header 类型
    char buf[];          // 实际数据，末尾仍保留 \0 以兼容 C 字符串函数
};
```

SDS 相比 C 字符串有四个核心改进：

- **O(1) 取长度**：直接读 `len`，不必遍历到 `\0`。这让 `STRLEN` 永远是常数时间。
- **二进制安全**：以 `len` 判断结束而非 `\0`，可以放任意字节，包括图片、protobuf 等。
- **杜绝缓冲区溢出**：每次修改前会检查 `alloc - len`，不够就自动扩容，不需要使用方手动管内存。
- **空间预分配 + 惰性释放**：修改后 `len < 1MB` 就额外分配等长空间（`alloc = 2*len`），`len >= 1MB` 就额外分配 1MB；缩短时不真缩容，只更新 `len` 把多余空间留着以后用。这两条结合起来，**把多次修改的 realloc 摊薄成几乎不发生**。

### 1.3 三种编码：int / embstr / raw

String 的 value 在内存里的存放形式由长度和内容决定，分三档：

```
SET num 123        → int     （long 整数直接放进 ptr 字段，根本不分配 SDS）
SET name "hello"   → embstr  （≤ 44 字节，robj 和 SDS 一次分配，紧挨在一起）
SET bio "xxx..."   → raw     （> 44 字节，robj 和 SDS 分两次分配）
```

**44 字节这个魔法数怎么来的？** jemalloc 一次分配 64 字节的内存块。`redisObject`（16B）+ `sdshdr8`（3B）+ buf 末尾的 `\0`（1B）一共占 20B，剩下 44B 留给 `buf`。embstr 用一次 malloc 把 robj 和 SDS 塞进同一块 64B 内存里，**只分配一次**，CPU cache 友好。一旦超过 44B 就装不下，只能分两块——这就是 raw。

> embstr 是**只读编码**：哪怕 `APPEND " "` 一个空格都会被升级成 raw，且**不会回退**。这是为了避免在已经"挤满"的 64B 块里原地修改造成内存碎片。

#### int 编码：连 SDS 都不要

当 value 能解析成 long 范围内的整数时，**Redis 干脆不分配 SDS，直接把整数值塞进 `redisObject.ptr` 字段里**。

| encoding | `ptr` 字段含义 |
|---------|---------------|
| `int` | **直接存放 long 整数值本身**（不是地址！） |
| `embstr` | 指向紧挨在 robj 后面的 SDS |
| `raw` | 指向独立分配的 SDS |

64 位系统上 `ptr` 是 8 字节，正好装得下一个 long。取值时先看 encoding：

```c
// 伪代码
if (obj->encoding == OBJ_ENCODING_INT) {
    long value = (long)obj->ptr;  // 强转，直接取整数值
} else {
    sds s = obj->ptr;             // 当指针解引用
}
```

这一招省了一次 malloc、省下 SDS 的额外开销，整个对象只占 16 字节。这也是 `INCR counter` 为什么能跑出几十万 QPS——执行路径短到极致。

#### 共享整数对象池

更进一步，Redis 启动时直接预创建了 `[0, 10000)` 共一万个整数对象（`server.shared.integers`），所有要存这些小整数的 key **共享同一个 robj**，靠 `refcount` 计数：

```bash
127.0.0.1:6379> SET a 100
127.0.0.1:6379> SET b 100
# a 和 b 的 value robj 其实是同一个，refcount = 2
```

这意味着上万个计数器 key 如果初值都是 0，几乎不占额外内存。

> **共享池的失效场景**：开启了 `maxmemory` 且淘汰策略是 LRU / LFU / TTL 时，共享对象会失效。原因是这类策略需要给每个对象单独记录访问时间，共享对象做不到。这种情况下 `int` 编码本身仍然生效，只是不再共享。

#### int 编码退化的三种情况

```bash
# 1) 整数超出 long 范围
SET big 99999999999999999999
OBJECT ENCODING big   → "embstr"

# 2) 对整数字符串做字符串操作
SET num 123
OBJECT ENCODING num   → "int"
APPEND num "abc"
OBJECT ENCODING num   → "raw"   # 升级后不会回退

# 3) 整数字符串过长（≥ 45 字节）
SET n "12345...（45 个数字字符）"
OBJECT ENCODING n     → "raw"
```

### 1.4 两个高频面试点

**String 最大能存多大？**
**512 MB**。但生产中 value 超过 10KB 就该警惕，超过 1MB 就属于大 Key，会拖慢主线程、放大网络抖动、迁移困难。

**INCR 为什么是原子的？**
Redis 主线程是单线程，一条命令从开始执行到返回结果中间不会被任何其他命令打断。INCR 内部"读 → 解析为整数 → +1 → 写回"是一个完整命令，自然原子。这也是为什么 Redis 适合做计数器、分布式锁。

### 1.5 String 限流：固定窗口（含缺陷分析）

最经典的 String 实战场景。**思路**：每个时间窗口一个独立 key，`INCR` 自增，超阈值拒绝；靠 TTL 让 key 自动消失，不需要手动清理。

为了支持任意窗口秒数（如 2 秒一窗口），Key 按窗口对齐：

```
windowStart = currentSec / windowSecs
key = rate:limit:{resource}:{windowSecs}s:{windowStart}
```

为了保证 `INCR` 和 `EXPIRE` 这两步不会因为客户端崩溃或网络异常而被拆开（拆开会导致 key 永不过期、内存泄漏），用 Lua 脚本把它们包成一个原子操作：

```lua
-- KEYS[1] = 限流 key
-- ARGV[1] = 阈值，ARGV[2] = 窗口秒数
local current = redis.call('INCR', KEYS[1])
if current == 1 then
    -- 首次创建才设 TTL，避免每次重置导致窗口被拉长
    -- TTL 比窗口多 1 秒，防止边界提前过期丢计数
    redis.call('EXPIRE', KEYS[1], tonumber(ARGV[2]) + 1)
end
if current > tonumber(ARGV[1]) then
    return 0   -- 超限，拒绝
end
return 1       -- 放行
```

几个易错的设计点：

- **首次才设 TTL**：必须用 `if current == 1` 判断，否则每次请求都重置 TTL，窗口会被无限拉长，限流彻底失效。
- **TTL = 窗口秒数 + 1**：留 1 秒冗余，防止 key 在窗口结束前抢跑过期。
- **Key 带窗口秒数后缀**：同一资源可以并行挂 1s/2s/60s 多级限流，彼此不互相干扰。

#### 固定窗口的致命缺陷：窗口边界双倍突刺

固定窗口的根本问题是**窗口是硬边界，到点清零**。攻击者只要把请求集中在两个相邻窗口的交界处，就能在远小于一个窗口的时间内放行 2 倍阈值的请求：

```
时间轴（2 秒窗口，阈值 10）：
   ↓10个请求在 T:01.9      ↓10个请求在 T:02.1
───────┬──────────────────┬──────────────▶
     T:00               T:02              T:04

窗口 [T:00,T:02) 内：10 个 ✅
窗口 [T:02,T:04) 内：10 个 ✅
但实际 0.2 秒内放行了 20 个，瞬时 QPS 突破 2 倍。
```

每个窗口独立看都"合规"，但实际瞬时流量翻倍，违背了限流的初衷。**对精度敏感的登录、支付场景**，应改用 **ZSet 滑动窗口**（见 5.1）或 **令牌桶**等平滑方案。

### 1.6 String 的另一大实战：分布式锁

分布式锁是 String 最高频的进阶用法，核心思路是利用 **`SET key value NX EX seconds` 的原子性**，在多节点之间抢一把"全局唯一的标识"。完整内容（错误示范、原子加锁、Lua 解锁、Redisson 看门狗、Redlock 与争议、可重入实现、生产清单、高频面试题）已独立成专门的笔记：

> 详见本目录下的 **`DistributedLock.md`**。

---

## 二、List（列表）

List 在用户看来是一个有序、可重复、支持两端操作的双端队列。但它的底层实现经过了三代演进，**Redis 7.0 起最终定型为 quicklist + listpack 的组合**——这套组合是理解 ziplist、listpack、quicklist 这三个紧凑编码的最好切入口。

### 2.1 典型应用场景

- **简易消息队列**：`LPUSH` 生产 + `BRPOP` 阻塞消费，缺点是无 ACK、无消费组、无回溯。
- **最新 N 条数据**：`LPUSH + LTRIM 0 N-1`，常见于微博时间线、最近浏览。
- **栈 / 队列**：LPUSH+LPOP 是栈，LPUSH+RPOP 是队列，两端均 O(1)。
- **分页任务池**：`LRANGE start stop` 切片消费。

> `BLPOP` 的内部实现（阻塞链表 + key 通知）见本目录的 `ListBlockingConsume.md`。

### 2.2 三代编码演进

| Redis 版本 | 编码 | 痛点 / 改进 |
|-----------|------|------|
| < 3.2 | `ziplist`（小）+ `linkedlist`（大） | 两种编码切换时需全量转换，有性能抖动；linkedlist 指针开销大 |
| 3.2 ~ 6.x | `quicklist`（双向链表 + ziplist 节点） | 用一种编码同时覆盖大小集合，但仍受 ziplist 连锁更新困扰 |
| **7.0+** | `quicklist`（双向链表 + **listpack** 节点） | listpack 替换 ziplist，根除连锁更新 |

下面按"quicklist 整体结构 → 节点内的 listpack → listpack 为何能取代 ziplist"的顺序展开。

### 2.3 quicklist：双向链表 + 紧凑数组

quicklist 是 Redis 自研的私有结构，**教科书里没有这个名字**。它的设计思路最接近学术上的 **unrolled linked list（展开链表）**——"链表节点里套一个小数组"，Redis 用更紧凑的 listpack 取代了定长数组，是工程化改良版。

**为什么要这样组合？** 这是在两个极端之间做权衡：

- 如果用**纯双向链表**，每个节点两个指针就 16 字节，元素小的时候指针开销远大于数据本身，而且节点散落在内存里，对 CPU cache 极不友好。这是 Redis 3.2 之前的痛。
- 如果用**纯连续数组（ziplist）**，大 List 需要一整块连续内存，中间插入/删除还要 realloc 整块，代价太高。

**quicklist 的折中**：链表节点本身依然用指针串起来负责"分段"，但每段不是单个元素，而是一个塞了几十上百个元素的 listpack。**节点粒度大 → 摊薄指针开销；段内连续 → 享受 cache 局部性**。

#### 2.3.1 整体结构

```
quicklist:
  ┌─ head ─────────────────────────────────────────────── tail ─┐
  ▼                                                              ▼
[quicklistNode]←→[quicklistNode]←→[quicklistNode]←→[quicklistNode]
  │                 │                 │                 │
  ▼                 ▼                 ▼                 ▼
 listpack         listpack          LZF             listpack
 [e1,e2,e3]       [e4,e5,e6]      (compressed)      [e9,e10]
                                     ↑
                              中间节点可被压缩
```

```c
// 简化结构
typedef struct quicklist {
    quicklistNode *head;
    quicklistNode *tail;
    unsigned long count;       // 总元素数
    unsigned long len;         // 节点数
    int fill;                  // 单节点大小上限（来自配置）
    unsigned int compress;     // 两端不压缩节点数（来自配置）
} quicklist;

typedef struct quicklistNode {
    struct quicklistNode *prev;
    struct quicklistNode *next;
    unsigned char *entry;       // 指向 listpack（或 LZF 压缩后的数据）
    size_t sz;                  // listpack 字节大小
    unsigned int count : 16;    // 本节点元素个数
    unsigned int encoding : 2;  // RAW = 未压缩 / LZF = 已压缩
    unsigned int container : 2; // PLAIN = 单元素 / PACKED = listpack
    unsigned int recompress : 1;
    // ...
} quicklistNode;
```

#### 2.3.2 单节点多大？由 list-max-listpack-size 控制

```conf
list-max-listpack-size  -2     # 单节点 listpack 大小上限
list-compress-depth      0     # 两端保留多少节点不压缩
```

`list-max-listpack-size` 取值很有意思：

| 值 | 含义 |
|----|------|
| 正数 N | 每节点最多 N 个元素 |
| -1 | listpack ≤ 4KB |
| **-2（默认）** | listpack ≤ **8KB** |
| -3 | listpack ≤ 16KB |
| -4 | listpack ≤ 32KB |
| -5 | listpack ≤ 64KB |

**为什么默认 8KB？** 这是 CPU L1 cache 的量级，让一个节点的数据尽量装进 cache，同时避免 realloc 时搬太大的内存。一旦当前节点装不下新元素，就直接开一个新的 quicklistNode 挂上去。

#### 2.3.3 中间节点压缩：list-compress-depth

`list-compress-depth` 控制两端"不压缩"的节点数，中间节点用 **LZF 算法**压缩存储：

```
compress-depth = 2 时：
[node1]←→[node2]←→[LZF]←→[LZF]←→[LZF]←→[node7]←→[node8]
 ^^^^^^^^^^^^^^^^^                       ^^^^^^^^^^^^^^^^^
 头部 2 个不压缩                          尾部 2 个不压缩
```

**为什么这样设计？** List 的访问热点几乎全在两端——`LPUSH/RPUSH/LPOP/RPOP/LRANGE 0 N`，中间元素几乎不动。冷数据压缩能省大量内存（LZF 对文本通常 2~3 倍压缩比），代价只是偶尔解压一下。

> **生产建议**：默认 `compress=0`（不压缩）性能优先；只有超大 List 内存紧张时才开启。

#### 2.3.4 操作的真实复杂度

**LPUSH / RPUSH 均摊 O(1)**：绝大多数情况下新元素能塞进现有头/尾节点的 listpack；只有节点满了才需要新建一个 quicklistNode 挂上去（仍然是 O(1) 链表插入）。

**LINDEX 看起来 O(N)，实际有"节点级跳过"加速**：

```
LINDEX key 500:
  1. 沿 quicklistNode 累加 count，逐节点 O(1) 跳过
     node1: count=128, 累计 128（不够）
     node2: count=128, 累计 256（不够）
     node3: count=128, 累计 384（不够）
     node4: count=128, 累计 512（命中）
  2. 在 node4 的 listpack 内顺序扫描第 (500-384)=116 个元素
```

实际性能 ≈ O(N/M) 次节点跳转 + O(M) 次节点内扫描（M 是单节点元素数，默认约 128）。两端访问（`LINDEX 0` / `LINDEX -1`）是严格 O(1)。

### 2.4 listpack：节点内的紧凑数组

quicklist 每个节点挂的是一个 listpack。它本质是用一块连续内存存放变长元素的"小数组"。

#### 2.4.1 整体布局：变长元素紧挨着排

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

- **encoding**：头部字节，标识元素类型（整数/字符串）和长度。
- **data**：实际数据。
- **backlen**：**自己这个 entry 的总长度**，放在 entry **尾部**，用于反向遍历——这是 listpack 区别于 ziplist 最关键的设计。

#### 2.4.2 不支持 O(1) 随机访问，但实际很快

listpack 的元素是变长的，**取第 K 个元素必须从头扫 K 次**。但因为：

1. 单个 listpack 默认就 ≤ 8KB 或 ≤ 128 元素，规模本来就小；
2. 连续内存 + cache 友好，一两次 cache line 命中就扫完了；
3. 通过 `encoding` 头部直接知道跳多少字节，不必逐字节解析。

所以实际遍历比链表的指针跳转还快。**这是用"牺牲 O(1) 随机访问换内存紧凑"的工程权衡，反正 N 小。**

#### 2.4.3 backlen 让反向遍历 O(1)

`backlen` 放在 entry 尾部这个设计很精妙——**只要从 listpack 末尾向前读 backlen，就能知道最后一个 entry 多长，进而定位它的起点**：

```
反向取倒数第 2 个：
末尾 ← 读 backlen → 跳到最后一个 entry 起点
     ← 读它前面 entry 的 backlen → 跳到倒数第 2 个起点
```

`LINDEX key -1`、`LRANGE key -3 -1` 这类尾部访问命令因此很快。

### 2.5 listpack 为什么能取代 ziplist：根除连锁更新

要理解 listpack 的价值，必须先看 ziplist 留下的坑。

#### 2.5.1 ziplist 的结构

ziplist 是 Redis 7.0 之前用于小集合（小 Hash、小 ZSet、List 的 quicklist 节点）的紧凑编码。整体布局：

```
┌─────────┬─────────┬─────────┬────────┬────────┬─────┬────────┬──────┐
│ zlbytes │ zltail  │ zllen   │ entry1 │ entry2 │ ... │ entryN │ zlend│
│  (4B)   │  (4B)   │  (2B)   │        │        │     │        │ (1B) │
└─────────┴─────────┴─────────┴────────┴────────┴─────┴────────┴──────┘
```

| 字段 | 大小 | 作用 |
|------|------|------|
| `zlbytes` | 4B | 整个 ziplist 字节数，realloc 用 |
| `zltail`  | 4B | 最后一个 entry 的偏移，让尾部访问 O(1) |
| `zllen`   | 2B | entry 个数，超过 65535 失效需遍历 |
| `zlend`   | 1B | 固定 `0xFF`，结束标记 |

固定开销 11 字节（header 10B + zlend 1B）。

每个 entry 的结构：

```
┌──────────┬──────────┬──────────┐
│ prevlen  │ encoding │   data   │
│ (1或5B)  │ (1~5B)   │  (变长)  │
└──────────┴──────────┴──────────┘
```

- **prevlen**：注意，**记录的是"前一个 entry 的长度"，不是自己的**。前一个 < 254 字节用 1B；≥ 254 字节用 5B（首字节 `0xFE` + 后 4B 长度）。
- **encoding**：标识 data 的类型和长度，本身也是变长的。字符串分 1/2/5 字节三档；整数用 1B，0~12 的小整数甚至直接编码进 encoding 里，`data` 长度为 0。
- **data**：实际数据。

亮点是小整数（0~12）的 entry 只有 `prevlen + encoding` 两字节，紧凑到极致。但代价就藏在 `prevlen` 里。

#### 2.5.2 致命问题：连锁更新

ziplist 的优点和缺点都来自 **prevlen 记录的是"前一个"的长度**——这意味着 entry 之间产生了链式依赖。

**复现**：假设 ziplist 里有一串长度都精准卡在 253 字节的 entry（prevlen 都只用 1 字节），现在头部插入一个 1000 字节的大 entry：

```
插入后：
┌──────┬──────────┬──────────┬──────────┬──────────┐
│ head │ X(1000B) │    A     │    B     │    C     │
└──────┴──────────┴──────────┴──────────┴──────────┘
                    ↑
       A 原本 prevlen 1B 不够记录 X 了，要扩成 5B
       于是 A 自身长度从 253 变成 257（也超过 254 了）
       → B 的 prevlen 也要 1B → 5B
       → C 也要改…连锁反应
       最坏 O(N²) 的全链 realloc
```

删除同样会触发：删掉一个大 entry 后，后续 entry 的 prevlen 可能从 5B 缩回 1B，自身长度变化又传染下一个。

**触发条件苛刻**（需要一串 entry 长度都精准卡在 250~253B 的"危险区间"），**生产概率低但一旦触发就是性能尖刺**，属于"不常发但一发就爆"的坑。

#### 2.5.3 listpack 的解法：打断链式依赖

listpack 的 entry 只在**尾部**记录**自己**的长度（`backlen`），不再依赖前一个。插入/删除只影响自己这一个 entry，**彻底根除连锁更新**。

| 对比维度 | ziplist | listpack |
|---------|---------|----------|
| 长度字段位置 | entry 头部，描述"前一个"（`prevlen`） | entry 尾部，描述"自己"（`backlen`） |
| entry 间依赖 | 有（链式引用） | 无（彼此独立） |
| 插入/删除影响 | 可能连锁更新 O(N²) | 仅影响自身 O(1) |
| 反向遍历 | 靠 prevlen 回退 | 靠 backlen 回退 |
| 固定开销 | 11B（10B header + 1B end） | 7B（6B header + 1B end） |
| Redis 版本 | < 7.0 | ≥ 7.0 |

> **一句话记住**：ziplist 把"长度信息"放**前面**描述**前一个**，紧凑但**有传染性**；listpack 把"长度信息"放**后面**描述**自己**，同样紧凑但**彼此独立**。这就是 7.0 全面切换 listpack 的根本原因。

### 2.6 高频面试点

**List 能当消息队列用吗？缺什么？**

能，`LPUSH + BRPOP` 就是最简单的阻塞消费模型，但缺四样东西：

- **无 ACK**：消费者 pop 之后还没处理完就宕机，消息就丢了（`BLMOVE` 把消息挪到 backup 队列可以部分缓解，但还是要业务层自己做幂等）。
- **无消费组**：一条消息只能被一个消费者拿走，做不到广播。
- **无回溯**：消费过的消息找不回来。
- **堆积风险**：消息堆积会形成大 Key，主线程操作变慢。

需要可靠消息能力，应该上 **Redis Stream** 或更专业的 **RocketMQ / Kafka**。

---

## 三、Hash（哈希）

Hash 在用户视角下是 "field → value" 的字典，对应 Java 里的 `Map<String, String>`。它的价值在于**对一个对象只读写其中一个字段**，省去了 String + JSON 方案"取出整个对象 → 反序列化 → 改一个字段 → 序列化 → 写回"这套又慢又费网络的流程。

### 3.1 典型应用场景

- **存储对象**：`HSET user:1001 name "xx" age 18`，可以单独 `HGET user:1001 age`，省带宽。
- **购物车**：`HSET cart:uid skuId qty`，field 是商品 ID，value 是数量，加减库存用 `HINCRBY` 原子完成。
- **多维度计数**：`HINCRBY stats:202611 pv 1`，一个 key 聚合一个对象上的多个计数。
- **配置中心**：`HSET config:serviceA timeout 3000`，热更新某一项不需要动其他配置。

> **选型经验**：字段较多、且经常只改个别字段 → Hash；整体读写、字段稳定 → String + JSON 更省心。**字段数 100 以内** Hash 更省内存（小 Hash 走 listpack，几乎没有元数据开销）；字段数大且字段较长 Hash 升级成 hashtable 后，每个 field 一个 `dictEntry`（约 24~48B 开销），反而比 String + JSON 更费内存。

### 3.2 底层编码：listpack / hashtable

Hash 同样是"小用紧凑编码、大用标准编码"的两段式：

| 编码 | 触发条件（默认） | 形态 |
|------|----------------|------|
| `listpack` | 元素数 ≤ 128 **且** 每个 field/value ≤ 64 字节 | 一块连续内存 `[f1, v1, f2, v2, ...]`，查找 O(N) 但 N 小 |
| `hashtable` | 任一阈值被突破后切换，**只升不降** | 拉链法实现的标准哈希表 |

控制阈值的两个配置（7.0 起改名）：

```conf
hash-max-listpack-entries  128    # field 数量上限
hash-max-listpack-value    64     # 单个 field 或 value 的字节上限
```

> 7.0 之前对应 `hash-max-ziplist-entries` / `hash-max-ziplist-value`，阈值默认值未变，只是底层把 ziplist 换成了 listpack。

#### 小 Hash 的 listpack 形态

小 Hash 直接复用了第二章讲过的 listpack——field 和 value 紧挨着排成一串。`HGET key field` 的实现就是从头扫描，每读两个 entry 比对一次 field，命中就返回它后面那个 entry 作为 value。N 小 + 连续内存 cache 友好，所以 O(N) 实测比 hashtable 还快，外加省一大堆指针开销。

#### 大 Hash 的 hashtable：双表 + 拉链

```c
typedef struct dict {
    dictType *type;
    void *privdata;
    dictht ht[2];          // 两个哈希表，平时只用 ht[0]，rehash 时双表并存
    long rehashidx;        // -1 表示没在 rehash；否则是 ht[0] 已迁移到的桶下标
    unsigned long iterators;
} dict;

typedef struct dictht {
    dictEntry **table;     // 桶数组（指针数组）
    unsigned long size;    // 桶数量，总是 2^n（哈希值取模可优化为按位与）
    unsigned long sizemask;// size - 1，用于 hash & sizemask 快速定位桶
    unsigned long used;    // 已存元素数
} dictht;

typedef struct dictEntry {
    void *key;
    union { void *val; uint64_t u64; int64_t s64; double d; } v;
    struct dictEntry *next; // 拉链：同桶冲突时串成单链表
} dictEntry;
```

冲突解决用**链地址法 + 头插法**——新元素直接挂到链表头，O(1) 完成，且通常新插入的 key 短期访问概率更高，对 cache 友好。

### 3.3 核心机制：渐进式 rehash

如果哈希表里有几百万 key，扩容时一次性把所有 entry 从 `ht[0]` 搬到 `ht[1]`，主线程会停顿好几秒——这是 Redis 绝对不能接受的。**渐进式 rehash 把这次"大搬家"摊到后续的每一次命令操作里**，每次只搬一个桶，对单个命令几乎无感。

#### 触发条件

```
扩容：负载因子（used / size）
      ≥ 1 且当前没有 BGSAVE/BGREWRITEAOF 子进程   → 触发扩容
      ≥ 5                                            → 强制扩容（无视子进程）
缩容：负载因子 < 0.1                                  → 触发缩容
```

为什么子进程在跑时尽量不扩容？因为 RDB / AOF 重写靠 `fork` + COW（写时复制）。一旦 rehash 大量迁移数据，会让大量内存页被"写"，从而触发 COW，**白白多复制一份物理内存**，可能直接把机器干 OOM。所以 Redis 在 `dict.c` 里有一个 `dict_can_resize` 开关，子进程在跑时把它关掉，只有负载因子高到 5 这种"再不扩就要崩"的情况才强行打开。

#### 迁移过程

```
扩容时分配 ht[1]：size = 第一个 ≥ ht[0].used * 2 的 2^n
rehashidx = 0   （从 0 号桶开始迁移）

之后每次对字典做命令时（GET/SET/HSET 等）：
  1) 把 ht[0].table[rehashidx] 这一个桶上的所有 entry 全部搬到 ht[1]
  2) rehashidx++
  3) 真正执行用户的命令
serverCron 后台定时任务也会顺手帮忙搬几个桶（~1ms 级）

迁移完所有桶后：
  释放 ht[0] 的桶数组 → 把 ht[1] 整个赋值给 ht[0] → ht[1] 清空 → rehashidx = -1
```

#### rehash 期间的读写规则（关键细节）

- **查询**（`HGET` 等）：先查 `ht[0]` 对应的桶；没命中再查 `ht[1]` 的桶。一次查询最多查两个桶。
- **新增**（`HSET` 等）：**直接写入 `ht[1]`**，不再写 `ht[0]`。这样保证 `ht[0]` 的元素数只减不增，最终一定会空。
- **更新 / 删除**：先在 `ht[0]` 找；找不到再去 `ht[1]`，在哪边找到就改哪边。

> **类比**：搬家不是一次扛完所有箱子，而是"每次回家顺手带一箱"，最终搬完。**期间新进的快递（新增）直接送到新家**（ht[1]），旧家的东西也只会越来越少。

### 3.4 高频面试点

**为什么 BGSAVE 期间尽量不触发 rehash？**

BGSAVE 是 `fork` 子进程，依赖 Linux 的 COW（写时复制）实现"快照式"持久化——父子进程共享物理内存页，只有父进程对某页发生写时才拷贝该页。如果 rehash 大量搬动 entry，会把哈希表所占的内存页全都"写脏"，触发 COW 复制，**物理内存瞬间多占一倍，可能直接 OOM**。所以 Redis 用 `dict_can_resize` 把扩容暂时压住，除非负载因子已经 ≥ 5 这种紧急情况才强行扩。

**Hash 和 String + JSON 怎么选？**

- 小 Hash（listpack 编码）**最省内存**，因为没有 dictEntry 这层指针开销。
- 一旦 Hash 升级到 hashtable，每个 field 至少一个 dictEntry（24~48B），加上桶数组本身的开销，反而**比一段紧凑的 JSON 字符串更费内存**。
- 经验：**字段数 < 100、需要频繁改个别字段 → Hash**；字段稳定、整体读写 → String + JSON 更简单。

**Hash 的内层为什么不用红黑树（像 Java 8 的 HashMap 那样）？**

Redis 是单线程模型，扩容靠"渐进式 rehash"分摊 + 负载因子上限保证链长可控（极端 5），平均链长几乎都很短，不需要树化；引入红黑树反而徒增代码复杂度和内存开销。

---

## 四、Set（集合）

Set 是无序、不重复的字符串集合，对应 Java 里的 `Set<String>`。它的两大杀手锏是 **O(1) 判存**（`SISMEMBER`）和 **集合运算**（`SINTER` / `SUNION` / `SDIFF`），让"共同好友"、"标签匹配"这类逻辑用一行命令就能算完。

### 4.1 典型应用场景

- **去重 / UV 统计**：`SADD article:1001:uv uid` + `SCARD` 拿 UV 数；精确但耗内存。
- **用户标签**：`SADD user:1001:tags "java" "redis"`，配合 `SINTER` 找出"两个用户共有的标签"。
- **共同好友 / 共同关注**：`SINTER user:A:follow user:B:follow` 一行命令搞定。
- **抽奖**：`SPOP` 弹出并删除一个随机元素（不重复抽奖）；`SRANDMEMBER` 不删除（可重复抽）。
- **黑白名单**：`SISMEMBER blacklist uid`，O(1) 判存。

### 4.2 三种底层编码：intset / listpack / hashtable

Redis 7.2 起 Set 的编码选择有三档，**优先级是 intset → listpack → hashtable**：

| 编码 | 触发条件（默认） | 形态与复杂度 |
|------|----------------|------|
| `intset` | 元素**全是整数**且数量 ≤ 512 | 有序整数数组，二分查找 O(log N) |
| `listpack`（7.2+） | 出现非整数、但元素 ≤ 128 且每个 ≤ 64 字节 | listpack 顺序扫描 O(N) |
| `hashtable` | 任一阈值被突破，**只升不降** | 标准哈希表，O(1) |

控制阈值的三个配置：

```conf
set-max-intset-entries     512    # intset 上限
set-max-listpack-entries   128    # listpack 元素上限（7.2+）
set-max-listpack-value     64     # listpack 单元素字节上限（7.2+）
```

切换路径很清晰：

```
intset    ──(元素 > 512 或 出现非整数)──▶ listpack 或 hashtable
listpack  ──(元素 > 128 或 单元素 > 64B)──▶ hashtable
hashtable ──✗──▶ 永不回退
```

> **从 intset 转出去时怎么选目标？** 取决于"破坏阈值"的那个元素：插入的是非整数 → 看新规模能否塞进 listpack，能则进 listpack，否则直接 hashtable；插入的还是整数但元素数突破 512 → 直接进 hashtable（既然全是整数，没必要走 listpack 这一道）。

#### intset：紧凑的有序整数数组

```c
typedef struct intset {
    uint32_t encoding;   // INTSET_ENC_INT16 / INT32 / INT64
    uint32_t length;     // 元素个数
    int8_t contents[];   // 柔性数组：实际按 encoding 解释为 int16/int32/int64
} intset;
```

**注意 `contents` 声明是 `int8_t[]`，但这只是用作"原始字节缓冲区"**——真正的元素宽度由 `encoding` 决定，读写时按 `int16_t *` / `int32_t *` / `int64_t *` 强转访问。元素**按值升序排列**，所以 `SISMEMBER` 走二分查找 O(log N)，比通用 hashtable 的 O(1) 慢一个量级常数，但因为 N ≤ 512 且数据全在一块连续内存里，cache 命中率极高，**实测比 hashtable 还省时间，且省一大堆指针开销**。

**升级机制（intsetUpgradeAndAdd）的关键细节**：当插入一个超出当前编码范围的整数（比如原本全 int16，现在塞一个 int64），整个数组要**统一拓宽到新编码**。源码注释里写得很直白：

> *Upgrade back-to-front so we don't overwrite values.*

也就是**从后往前**逐个搬：先把最后一个 int16 元素读出来，写到新编码下的最后一个位置（更靠后的偏移）；再倒数第二个……这样后面的写不会覆盖到前面还没搬走的旧数据。同时 Redis 还利用了一个巧妙的事实——**触发升级的新元素，要么比所有旧元素都大、要么都小**（因为它本身就是超出旧编码范围的极端值）——所以新元素必然落在数组**头**或**尾**，不需要插到中间，省下一次 memmove。

升级是**只升不降**的：哪怕后续把那个大整数删掉，编码也不会缩回 int16，避免抖动。

#### listpack 与 hashtable 形态

`listpack` 形态复用第二章讲过的紧凑数组：元素一个挨一个紧凑排列，`SISMEMBER` 顺序扫描，N 小所以无所谓。

`hashtable` 形态完全复用 Hash 那一套 dict 结构（包括渐进式 rehash），区别仅在 `dictEntry.v` 字段**值为 NULL**——**Set 只用 key 去重**，不存附加值。

### 4.3 集合运算的真实代价

`SINTER` / `SUNION` / `SDIFF` 用起来很爽，但复杂度差别其实很大，生产环境必须分清：

| 命令 | 复杂度 | 实现思路 |
|------|------|------|
| `SUNION key1...keyN` | **O(N)**，N 是所有集合元素总和 | 全部塞进结果集，自然去重 |
| `SDIFF key1 key2...keyN` | **O(N)** | 遍历 key1，逐元素查后续集合是否存在 |
| `SINTER key1...keyM` | **O(N×M)**，N 是**最小**集合大小，M 是集合个数 | 选最小集合做基准，逐元素去其他集合查存在性 |

**`SINTER` 为什么必须从最小集合开始？** 这是 Redis 内置的优化：参与求交的多个集合中，**最小那个决定了候选元素的上界**——一个元素只要不在最小集合里，就不可能在交集里。所以 Redis 先按 `SCARD` 排序找出最小集合做基准遍历，每个元素再逐一去其他集合 `O(1)` 判存。最坏 O(N×M)，最好情况下当最小集合很小时近似 O(M)。

**生产建议**：

- 大集合的 `SINTER / SUNION / SDIFF` 会**长时间阻塞主线程**，请用 `SINTERSTORE / SUNIONSTORE / SDIFFSTORE` 把结果异步落地复用，或在从节点跑。
- 只想要交集的**基数**而不要具体元素？**用 `SINTERCARD`（7.0+）**，可以加 `LIMIT` 提前停，避免算完整个交集。

### 4.4 高频面试点

**Set 和 Hash 都能去重，怎么选？**

- 只关心"存不存在" → **Set**，`SISMEMBER` 一步到位。
- 每个元素还要附加属性（数量、状态、时间） → **Hash**，field 当 key，value 存属性。

**统计超大基数用什么？**

Set 精确但内存随基数线性增长，亿级 UV 直接爆。**HyperLogLog** 是更好的选择：固定约 12KB 空间估算基数，标准误差 0.81%，适合 UV、活跃用户数等"可容忍少量误差"的场景。命令是 `PFADD` / `PFCOUNT` / `PFMERGE`。

**抽奖用 SPOP 还是 SRANDMEMBER？**

`SPOP` 弹出并删除（不重复抽奖）；`SRANDMEMBER` 只读不删（可重复抽，可指定数量，正数不重复负数允许重复）。生产环境一般用 `SPOP` 保证幂等，并配合 Lua 脚本把"判断剩余库存 + 弹出"做成原子操作。

---

## 五、ZSet（有序集合）—— Redis 最精巧的数据结构

ZSet 给每个 member 关联一个 `double` 类型的 `score`，集合按 score 排序。它能同时回答两类问题——"member 的 score 是多少？"和"score 在 [a, b] 范围内的 member 有哪些？"——这正是排行榜、延迟队列、滑动窗口限流的核心需求。

### 5.1 典型应用场景

- **排行榜**：`ZADD rank 100 uid` + `ZREVRANGE rank 0 9 WITHSCORES` 拿 Top 10。
- **延迟队列**：score 设为执行时间戳，消费者轮询 `ZRANGEBYSCORE delay -inf <now>` 拿到期任务。
- **滑动窗口限流**：score 设为请求时间戳，每次请求先 `ZREMRANGEBYSCORE key -inf <now-window>` 清掉过期记录，再 `ZCARD` 看窗口内剩多少，过阈值就拒绝。**比 String 固定窗口精确、无突刺**。
- **带权标签**：score 当权重排序。
- **时间线 Feed**：score 当发布时间戳，逆序取最新。

### 5.2 底层编码：listpack / (skiplist + hashtable)

| 编码 | 触发条件（默认） | 形态 |
|------|----------------|------|
| `listpack` | 元素 ≤ 128 **且** 每个 member ≤ 64 字节 | `[m1, s1, m2, s2, ...]` 按 score 升序紧凑排列 |
| `skiplist + hashtable` | 任一阈值被突破，**只升不降** | 跳表 + 哈希表双结构并存 |

```conf
zset-max-listpack-entries  128
zset-max-listpack-value    64
```

> 7.0 之前对应 `zset-max-ziplist-entries` / `zset-max-ziplist-value`，阈值默认值未变。

#### 为什么大 ZSet 一定要"跳表 + 哈希表"双结构？

这是最高频的追问点，本质是**两种查询模式都要 O(1)/O(log N)**：

| 查询 | 只用 skiplist | 只用 hashtable | skiplist + hashtable |
|------|-------------|---------------|---------------------|
| 按 member 查 score（`ZSCORE`） | O(log N) | **O(1)** ✅ | **O(1)** ✅ |
| 按 score 范围查（`ZRANGEBYSCORE`） | **O(log N)** ✅ | O(N) ❌ | **O(log N)** ✅ |
| 按 rank 查（`ZRANGE`） | **O(log N)** ✅ | O(N×log N) ❌ | **O(log N)** ✅ |

任何一种单结构都顾不全两端，所以 Redis 索性两个一起上：**hashtable 存 `member → score` 映射，跳表按 (score, member) 排序串成有序链。两个结构的 member 字符串是同一份内存（共享指针），只多存了一份指针开销，不会真翻倍**。

### 5.3 跳表（SkipList）核心原理

跳表的设计直觉是：**在有序链表上多盖几层"快速通道"**——上层节点稀疏，跨度大；下层节点密集，跨度小。查找时自顶向下、从左到右跳跃前进，每层走几步就 drop 到下一层。

```
Level 3:  1 -----------→ 7 -----------→ 19
Level 2:  1 -----→ 4 -→ 7 -----→ 12 -→ 19
Level 1:  1 → 3 → 4 → 7 → 9 → 12 → 17 → 19
Level 0:  1 → 3 → 4 → 7 → 9 → 12 → 17 → 19 → 21 → 23
```

**查找 12 的轨迹**：从最高层（Level 3）的头节点出发，看 1 → 7（≤12，前进）→ 19（>12，下降）→ Level 2 的 7 → 12（命中）。整个过程只比较了 4 次。

**关键特性**：

- 每个节点占多少层是**随机决定的**：插入时反复抛硬币，连续中奖就一直加层，期望 `1/(1-p)` 层。
- 期望查找复杂度 **O(log N)**，最坏 O(N) 但概率极低。
- 底层是有序链表，**范围查询天然支持**——这是哈希表/红黑树相比之下不那么自然的能力。

#### 为什么 Redis 选 p = 0.25 而不是 0.5？

Redis 源码 `t_zset.c` 里写死 `#define ZSKIPLIST_P 0.25`，跟教科书的 0.5 不一样。原因是工程权衡：

- **p = 0.5**：每层节点数是下一层的一半，**查询最快**，但平均每个节点会有 `1/(1-0.5) = 2` 层指针，**指针开销大**。
- **p = 0.25**：每层节点数大约是下一层的 1/4，**指针开销减半**（平均 `1/(1-0.25) ≈ 1.33` 层），单次查询多比较几次但常数极小，几乎察觉不到，**总体内存收益巨大**。

> 一句话：**Redis 用稍微多一点的查询比较，换来近一半的指针内存**。这种"内存敏感"的权衡到处都是 Redis 的设计风格。

#### 为什么选跳表不选红黑树？

Antirez 在 Redis 文档里给过明确答复：

| 维度 | 跳表 | 红黑树 |
|------|------|------|
| **实现复杂度** | 简单，几十行核心代码 | 复杂，旋转/变色容易写错 |
| **范围查询** | 底层就是有序链表，**天然 O(log N) 定位 + 顺序扫描** | 中序遍历，常数大、cache 不友好 |
| **内存灵活性** | 可调节 p 在内存和性能间权衡 | 几乎不可调 |
| **并发友好度** | 局部修改，**锁粒度小**（虽然 Redis 单线程暂用不上） | 旋转影响范围大 |
| **复杂度** | O(log N) 期望 | O(log N) 严格保证 |

ZSet 的核心命令几乎都是范围操作（`ZRANGE`、`ZRANGEBYSCORE`、`ZRANGEBYLEX`），跳表是几乎完美的选择。

### 5.4 Redis 跳表的三处工程改进

教科书跳表只能存一个 key，Redis 在它基础上做了三处对 ZSet 极其关键的改造。先看节点定义：

```c
typedef struct zskiplistNode {
    sds ele;                       // member 字符串
    double score;                  // 分数
    struct zskiplistNode *backward;// 后退指针（仅 Level 0）
    struct zskiplistLevel {
        struct zskiplistNode *forward;
        unsigned long span;        // 该 forward 跨越的节点数
    } level[];                     // 柔性数组，每个节点的层数随机
} zskiplistNode;

typedef struct zskiplist {
    struct zskiplistNode *header, *tail;
    unsigned long length;
    int level;                     // 当前最高层
} zskiplist;
```

三处改进对应三个 ZSet 命令：

1. **节点同时存 `score` 和 `ele`，按 `(score, ele)` 双关键字排序**——这是面试问"ZSet score 相同时怎么排"的源头：score 相等就按 `ele` 的字典序（字节序，`memcmp`）排序。教科书跳表只按一个 key 排，没法处理 score 重复的场景。
2. **`backward` 后退指针（只在 Level 0）**：让 `ZREVRANGE` 之类的反向遍历能从尾部往前 O(1) 跳到前一个节点，无需重新从头查。
3. **`span` 跨度字段**：每个 forward 指针记录"跨越了多少个节点"。算 `ZRANK`（成员的排名）时，沿查找路径把所有走过的 `span` 累加起来就是排名，**把原本 O(N) 的排名计算压到 O(log N)**。

> **记一句话**：教科书跳表是"key 的快速查找索引"；Redis 跳表是"按 (score, member) 排序的、能算排名、能反向走的"加强版。

### 5.5 高频面试点

**ZSet 用 listpack 时怎么保证有序？**

listpack 本身是顺序数组，ZSet 把它编排成 `[m1, s1, m2, s2, ...]` 的形式并**按 score 升序排列**（score 相同按 member 字典序）。插入时二分定位 + 顺序挪移（O(N) 但 N ≤ 128），查询时顺序扫描，N 小所以仍然很快。元素超过阈值就升级到 skiplist + hashtable。

**score 相同怎么排？**

按 `member` 的**字典序（字节序）**排序，源码里就是 `memcmp` 一下。这也是为什么排行榜做 tie-breaking 时常常把 uid 编进 member，让相同分数的用户有稳定顺序。

**滑动窗口限流为什么要用 ZSet 而不是 String？**

String 固定窗口在窗口边界会有"双倍突刺"问题（见 1.5）。ZSet 滑动窗口的思路是：**每次请求把当前时间戳作为 score 写入 ZSet，限流即"过去 1 秒内 ZSet 元素数 ≤ N"**：

```lua
-- KEYS[1] = 限流 key, ARGV[1] = 阈值, ARGV[2] = 窗口毫秒数, ARGV[3] = 当前毫秒
redis.call('ZREMRANGEBYSCORE', KEYS[1], 0, ARGV[3] - ARGV[2])  -- 清掉过期
local count = redis.call('ZCARD', KEYS[1])
if count >= tonumber(ARGV[1]) then return 0 end
redis.call('ZADD', KEYS[1], ARGV[3], ARGV[3])                   -- 用毫秒戳做 member 保证唯一
redis.call('PEXPIRE', KEYS[1], ARGV[2])
return 1
```

任何时刻看的都是"过去精确一个窗口长度内"的请求数，**没有窗口边界突刺**。代价是每次请求至少 3 次 ZSet 操作，并且 ZSet 占用内存随 QPS 线性增长，需要靠 ZREMRANGEBYSCORE 自然清理。

---

## 六、常见综合题

### 6.1 编码切换是单向的吗？

**全部都是单向，只升不降，没有例外**。

- intset 升级到 int64 后，哪怕把所有大数都删掉只剩 int16 范围的，也不会缩回 int16。
- listpack 转成 hashtable 后，即使删到只剩 1 个元素也不会转回 listpack。
- 跳表化的大 ZSet 同理，元素再少也不会回到 listpack。

**这么设计的根本原因是防止抖动**——如果允许双向切换，元素数刚好在阈值附近反复增删时，每次都触发"全量重新编码"，性能尖刺无法接受。代价是：极端情况下（先涨到大、再删到只剩几个）会比理论值多占内存，但生产中影响有限。

> 想要"瘦身"只能 `DEL key` 后重新写入，让新 key 走小编码。

### 6.2 大 Key 的判定与处理

#### 判定标准（业界经验值）

| 数据类型 | 关注线 | 大 Key |
|---------|------|------|
| String | value > 10KB | value > 1MB |
| Hash | field 数 > 1000 | field 数 > 5000 |
| List / Set / ZSet | 元素 > 5000 | 元素 > 10000 |
| 任意类型 | 整 key 内存 > 1MB | 整 key 内存 > 10MB |

#### 大 Key 为什么是问题？

- **DEL / EXPIRE 触发释放时阻塞主线程**：删除一个 key 的时间复杂度是 O(N)（N 是元素数 / value 字节数），百万级元素的 hash 一次 DEL 可能阻塞数十毫秒。
- **网络抖动放大**：一次 `HGETALL` 一个大 hash，response 体几 MB，发送时长直接顶满主线程。
- **集群迁移卡顿**：`MIGRATE` 是同步原子的，大 Key 一搬就是好几秒（详见 ClusterArchitecture.md）。
- **内存倾斜**：分片集群里某个槽里有大 Key 会导致单节点内存远超其他节点。

#### 处理方案

1. **拆分**：大 Hash 按 field 前缀 hash 取模拆成 N 个小 Hash；大 List 按时间分片；大 ZSet 按 score 区间拆。
2. **异步删除**：`UNLINK key`（4.0+）只在主线程把 key 从 keyspace 摘除（O(1)），真正释放内存交给 **bio 后台线程**（`lazyfree`），不阻塞命令处理。`FLUSHDB ASYNC` / `FLUSHALL ASYNC` 同理。
3. **分批清理**：必须保留 key 但要清空内容时，用 `HSCAN + HDEL` / `SSCAN + SREM` / `ZSCAN + ZREM` 分批删，每批几百个，给主线程留呼吸空间。
4. **发现工具**：
   - `redis-cli --bigkeys`：抽样扫描，输出每种类型最大的 Key（**只看元素数 / 字符串字节数**，不看实际内存）。
   - `redis-cli --memkeys`（6.0+）：基于 `MEMORY USAGE` 精确扫描。
   - `MEMORY USAGE key`：单 key 的精确内存占用。
   - 离线分析：`bgsave` 后用 `redis-rdb-tools` 解析 RDB 文件统计。

### 6.3 SCAN 的"反向二进制迭代"游标

`SCAN / HSCAN / SSCAN / ZSCAN` 是 `KEYS *` 的安全替代品。每次返回一批元素 + 下一个 cursor，cursor=0 表示遍历结束。**单次调用 O(1) 量级**，主线程几乎无感。

#### 普通顺序游标 0,1,2,3... 行不行？

不行——Redis 的哈希表会扩容/缩容，扩容后桶数从 8 变 16，原本下标 3 的桶里的元素被分流到下标 3 和 11。如果客户端拿着 cursor=4 接着扫，**前面被分流到 11 的元素就漏了**。

#### Redis 的解法：高位进位的反向二进制迭代（reverse binary iteration）

Redis 不按 0→1→2→... 顺序走，而是**让游标在二进制位上从高位向低位进位**。比如桶数 8（3 位下标）的扫描顺序是：

```
000 → 100 → 010 → 110 → 001 → 101 → 011 → 111 → 0（结束）
```

这套规则的神奇之处在于：**当哈希表从 size=8 扩容到 size=16（bit 数 +1），原 cursor 的扩展槽位仍然落在"未来要遍历"的范围内**。

举个例子：扫描进行到 cursor=`010`（已扫过 000、100），此时哈希表从 8 扩容到 16。原 size=8 时下标为 010 的桶，里面元素根据新 hash 会被分到 size=16 下的 `0010` 和 `1010` 两个桶里。

- `0010` 在新顺序里是 `0000 → 1000 → 0100 → 1100 → 0010 ...` 的第 5 个，**还没遍历过**，会被扫到。
- `1010` 在新顺序里更靠后，**也还没遍历**，也会被扫到。

这就保证了 **rehash 期间不漏 key**，代价是**可能重复**——客户端需要自己做幂等去重（`SCAN + MATCH` 后用 Set 二次去重）。

> 一句话总结：**Redis SCAN 不能保证不重复，但能保证"扫描期间一直存在的 key 一定会被遍历到"**。这是它能在 rehash 中安全工作的根本原因。

---

## 七、总结记忆口诀

> - **String 看长度**：整数 → int；≤ 44 字节 → embstr；其余 → raw
> - **List 用 quicklist 套 listpack**：双向链表分段，段内 listpack 紧凑
> - **Hash**：小用 listpack，大用 hashtable
> - **Set**：全整数用 intset，非整数小集合用 listpack，大集合用 hashtable
> - **ZSet**：小用 listpack，大用 **skiplist + hashtable** 双结构
>
> **三大经典设计**：
> - **SDS**：O(1) 取长度、二进制安全、空间预分配 / 惰性释放
> - **渐进式 rehash**：分摊式搬家，新增直接进 ht[1]，避免长停顿
> - **listpack 替代 ziplist**：尾部记录"自己"长度，根除连锁更新

---

## 八、选型速查表

| 需求 | 首选数据类型 | 关键命令 |
|------|------------|---------|
| 计数器 / 限流（粗粒度） | String | `INCR` / `INCRBY` |
| 分布式锁 | String | `SET key val NX EX 30` |
| 用户对象（频繁改字段） | Hash | `HSET` / `HGET` / `HINCRBY` |
| 简单消息队列 | List | `LPUSH` / `BRPOP` |
| 标签 / 去重 / 共同关注 | Set | `SADD` / `SISMEMBER` / `SINTER` |
| 排行榜 / 延迟队列 / 滑动窗口限流 | ZSet | `ZADD` / `ZRANGEBYSCORE` |
| 签到 / 活跃统计 | String 的 BitMap | `SETBIT` / `BITCOUNT` |
| 海量 UV 估算（容忍误差） | HyperLogLog | `PFADD` / `PFCOUNT` |
| 可靠消息队列 | Stream | `XADD` / `XREADGROUP` |
| 地理位置 | Geo（基于 ZSet） | `GEOADD` / `GEOSEARCH` |
