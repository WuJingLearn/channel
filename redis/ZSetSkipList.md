# Redis ZSet 跳表实现原理

> 主题：深入剖析 Redis ZSet 在大集合编码下的 `skiplist + hashtable` 双结构，以及插入、删除、更新、按分数范围查询的完整流程。
> 同目录 `DataStructures.md` 第五节给出了 ZSet 的高层概览，本篇聚焦"跳表"这一核心结构本身。

---

## 一、ZSet 的双结构设计

### 1.1 为什么不能只用一种结构？

ZSet 同时要支持两类操作：

| 操作 | 代表命令 | 理想复杂度 |
|------|---------|-----------|
| 按 **member** 查 score / 判存在 | `ZSCORE`、`ZADD`（更新场景） | O(1) |
| 按 **score** 范围查、按 rank 取 | `ZRANGEBYSCORE`、`ZRANGE`、`ZRANK` | O(log N) |

| 单一结构 | 按 member 查 | 按 score 范围查 |
|---------|-------------|----------------|
| 哈希表 | O(1) ✅ | **O(N) 全扫** ❌ |
| 跳表 | O(log N) ❌ | O(log N) ✅ |
| 红黑树 | O(log N) ❌ | O(log N) ✅ 但常数大、范围不友好 |

所以 Redis 用 **跳表 + 哈希表** 的组合：

```
ZSet (大集合):
  ┌──────────────────────────────────────────────┐
  │  hashtable                                   │
  │    "uid_a" ──┐                               │
  │    "uid_b" ──┼─→ 指向跳表节点                │
  │    "uid_c" ──┘                               │
  ├──────────────────────────────────────────────┤
  │  skiplist                                    │
  │    按 score 排序，支持范围查询                │
  └──────────────────────────────────────────────┘
       ↑                          ↑
   member→score O(1)        score 范围 O(log N + M)
```

> **内存代价**：member 字符串本身在跳表节点里，哈希表只存指针引用，**不重复拷贝 member**。每个元素额外开销 ~16~32 字节（哈希表桶项 + 指针）。

### 1.2 源码结构

```c
// 对外的 ZSet 容器
typedef struct zset {
    dict     *dict;   // hashtable: member → score
    zskiplist *zsl;   // 跳表
} zset;

// 跳表
typedef struct zskiplist {
    struct zskiplistNode *header, *tail;  // 头尾节点
    unsigned long length;                  // 节点数（不含 header）
    int level;                             // 当前最高层数
} zskiplist;

// 跳表节点
typedef struct zskiplistNode {
    sds      ele;        // member
    double   score;      // 分数
    struct zskiplistNode *backward;  // 第 0 层的后退指针
    struct zskiplistLevel {
        struct zskiplistNode *forward;  // 本层下一个节点
        unsigned long span;             // 到下一个节点跨越了多少个底层节点
    } level[];           // 柔性数组，长度=该节点的随机层数
} zskiplistNode;
```

**两个关键字段**：

- `backward`：只在第 0 层存在，单向后退一格，用于 `ZREVRANGE`。
- `span`：每条前进指针上记录"跨越了多少个底层节点"，**用于 O(log N) 计算 rank**（`ZRANK`、`ZRANGE` 按 index 取）。

---

## 二、跳表结构图解

```
                                                     ↓ tail
Level 4: H ────────────────────────→ N4 ──────────────────────────────→ NIL
         │ span=4                    │ span=3                            │
Level 3: H ──────────→ N2 ─────────→ N4 ──────────→ N6 ────────────────→ NIL
         │ span=2     │ span=2      │ span=2      │ span=3              │
Level 2: H → N1 ────→ N2 ──→ N3 ──→ N4 ──→ N5 ──→ N6 ────────→ N7 ───→ NIL
         │ span=1   span=1 span=1 span=1 span=1 span=1     span=2      │
Level 1: H → N1 → N2 → N3 → N4 → N5 → N6 → N6.5 → N7 → N8 → N9 ──────→ NIL
                                              ↑↑                     ←─backward
                                       第 0 层是完整有序链表
                                       backward 只在第 0 层
```

- 头节点 `H` 是哨兵，不存数据，自带 `ZSKIPLIST_MAXLEVEL`(=32) 层指针。
- 每个真实节点的层数随机（看下文 2.2）。
- **第 0 层是完整有序链表**，所有元素都在；越上层节点越稀疏，是"快速通道索引"。

### 2.1 排序规则

1. 先按 `score` 升序；
2. score 相同时按 `member` 的**字典序（字节序，memcmp）**排序。

> 这就是为什么 `ZRANGE rank 0 9` 拉到的同分用户名是按字母序的。

### 2.2 节点层数的随机化

每次插入新节点时，用以下"抛硬币"算法决定层数：

```c
#define ZSKIPLIST_MAXLEVEL 32
#define ZSKIPLIST_P 0.25

int zslRandomLevel(void) {
    int level = 1;
    while ((random()&0xFFFF) < (ZSKIPLIST_P * 0xFFFF))
        level += 1;
    return (level < ZSKIPLIST_MAXLEVEL) ? level : ZSKIPLIST_MAXLEVEL;
}
```

- 概率 `p = 1/4`：节点 25% 概率涨到 2 层，6.25% 涨到 3 层，依此类推。
- 期望层数 ≈ `1/(1-p) = 1.33` 层，**平均额外指针开销很低**。
- 上限 32 层足以支撑约 2³² ≈ 40 亿个元素的 O(log N) 查找。

> **为什么是 0.25 而不是教科书的 0.5？** Redis 选 0.25 让上层指针更稀疏，**省内存**；查找平均比较次数略增但仍是 O(log N)，工程权衡。

### 2.3 节点内存布局：为什么 `level` 是数组？

回顾 `zskiplistNode` 的定义：

```c
typedef struct zskiplistNode {
    sds      ele;
    double   score;
    struct zskiplistNode *backward;
    struct zskiplistLevel {
        struct zskiplistNode *forward;
        unsigned long span;
    } level[];           // ← 柔性数组（flexible array member）
} zskiplistNode;
```

#### 2.3.1 为什么需要数组？——每个节点层数不同

节点层数由 `zslRandomLevel()` 决定，**每个节点都不一样**——可能 1 层，也可能 8 层、20 层。每一层都需要**独立**的两个字段：

| 字段 | 作用 |
|------|------|
| `forward` | 本层指向下一个节点的指针 |
| `span`    | 本层这条 forward 跨越了多少个底层节点（用于 O(log N) 算 rank） |

所以"节点有几层，就需要几对 `(forward, span)`"，天然就是数组语义：`level[0]` 是第 0 层、`level[1]` 是第 1 层……

#### 2.3.2 为什么是柔性数组 `level[]` 而不是 `level[32]`？

如果固定 `level[ZSKIPLIST_MAXLEVEL]`（32 层），每个节点固定占 32 × 16B = 512 字节。但实际期望层数只有 1.33（p=0.25）：

| 节点层数 | 概率 | 浪费字节（按 16B/层） |
|---------|------|---------------------|
| 1 层 | 75%    | 31 × 16 = **496B** |
| 2 层 | 18.75% | 30 × 16 = 480B |
| 3 层 | 4.7%   | 29 × 16 = 464B |

千万级 ZSet 下这就是**几个 GB 的浪费**。柔性数组让节点按"实际随机出来的层数"动态分配：

```c
zskiplistNode *zslCreateNode(int level, double score, sds ele) {
    zskiplistNode *zn = zmalloc(sizeof(*zn) + level * sizeof(struct zskiplistLevel));
    //                                       ↑ 按需分配
    zn->score = score;
    zn->ele   = ele;
    return zn;
}
```

收益：

- **一次 malloc 搞定**：节点头 + 后面紧跟 `level` 个 `zskiplistLevel`，**连续内存**，cache 友好。
- **大小可变**：1 层节点只占 sizeof(头) + 16B，32 层节点占 sizeof(头) + 512B，按需付费。
- **访问语法不变**：`node->level[i].forward`，跟普通数组一样用。

#### 2.3.3 多层 = 多几对指针，ele 和 score 只存一份

这是非常关键的一点：**一个节点不管随机出几层，`ele` 和 `score` 都只存一份，多出来的只是 `(forward, span)` 这一对指针元数据**。

以一个随机到 3 层的节点为例：

```
zskiplistNode 内存块（一次 malloc 分配）：
┌──────────────────────────────────────────────────────────┐
│ ele (sds 指针, 8B)            ← member, 只存 1 份         │
│ score (double, 8B)            ← 分数, 只存 1 份           │
│ backward (指针, 8B)           ← 后退指针, 只存 1 份        │
├──────────────────────────────────────────────────────────┤
│ level[0]: { forward(8B), span(8B) }   ← 第 0 层的指针对   │
│ level[1]: { forward(8B), span(8B) }   ← 第 1 层的指针对   │
│ level[2]: { forward(8B), span(8B) }   ← 第 2 层的指针对   │
└──────────────────────────────────────────────────────────┘
        ↑
   多一层只多 16 字节（forward 8B + span 8B）
```

#### 2.3.4 跳表的"多层"是逻辑视图

回看分层图：

```
Level 2: H ──→ N2 ──→ N4 ──→ N6 ──→ NIL
Level 1: H ──→ N2 ──→ N4 ──→ N6 ──→ NIL
Level 0: H ─→ N1 → N2 → N3 → N4 → N5 → N6 → NIL
```

看起来 N2、N4、N6 在每层都"出现"了一次，**但物理上每个节点只有一个**。所谓"在第 2 层出现"，本质是：

- N2 节点内部的 `level[2].forward` 指向了 N4
- N4 节点内部的 `level[2].forward` 指向了 N6

也就是**用同一个节点内部不同下标的指针，串成了不同层的链表**。前进指针 forward 在每层指向不同的下一个节点——因为越上层节点越稀疏，N2 在第 0 层的下一个是 N3，但在第 2 层的下一个直接跳到 N4。所以每层确实需要独立的 forward。

| 节点层数 | 同时挂在几条链表上 | 额外内存（相比 1 层） |
|---------|------------------|--------------------|
| 1 层  | 仅第 0 层 | 0 |
| 3 层  | 第 0、1、2 层各一条 | + 2 × 16B = 32B |
| 32 层（极少见） | 每层都参与 | + 31 × 16B ≈ 496B |

#### 2.3.5 与"哈希表共享元素"是不同维度的省

这里要区分**两层"省内存"**：

| 维度 | 怎么省 |
|------|-------|
| **跳表内部多层之间** | 一个节点只存 1 份 ele + score，多层只是多几对 (forward, span) |
| **跳表 ↔ 哈希表之间** | 哈希表里只存 `member→跳表节点指针`，**ele 不再复制一份**到哈希表 |

两层叠加，最终一个 ZSet 元素的真实内存大概是：

```
跳表节点头(24B) + level 数组(平均 1.33 × 16B ≈ 21B) + ele 的 sds 实际大小
+ 哈希表 dictEntry(约 24B)
```

**ele 字符串只在内存里存了 1 份**，跳表节点和哈希表 dictEntry 都只是指过去而已。

#### 2.3.6 为什么不用指针 + 二次分配？

理论上也能写成 `zskiplistLevel *level`（指针指向另一块内存），但：

| 方案 | 缺点 |
|------|------|
| 指针 + 二次分配 | 多一次 malloc/free，多一层指针解引用，cache miss 翻倍 |
| 每层一个独立节点（链式） | 同上，且每层多一个 prev/next 指针，内存翻倍 |
| **柔性数组（Redis 选这个）** | 一次分配、连续内存、按需大小、零额外指针 |

> **一句话**：`level[]` 是柔性数组，因为跳表每个节点的层数随机且不同。用柔性数组可以"按节点实际层数分配恰好够用的连续内存"，**省内存 + cache 友好 + 一次 malloc**，是处理"大小不定的尾部数组"的经典 C 写法（同样手法在 Redis 的 SDS、`listpack`、`dictEntry` 等结构里反复出现）。

---

## 三、按分数范围查询：`ZRANGEBYSCORE` 流程

以 `ZRANGEBYSCORE key min max [LIMIT offset count]` 为例，核心是 `zslFirstInRange` + 顺向遍历。

### 3.1 查找区间起点（O(log N)）

```
目标：找到第一个 score ≥ min 的节点

x = header
for i = level-1 downto 0:
    while x.level[i].forward != NIL
          AND x.level[i].forward.score < min:
        x = x.level[i].forward     # 在当前层尽量前进
    # 当前层不能再前进了，下降一层
x = x.level[0].forward             # 跨过最后一步，正好到第一个 ≥ min 的节点
```

**形象比喻**：从最高层往右走，遇墙（下一个超过 min）就下一层继续走，最后落到底层目标节点的前一格。

### 3.2 顺向收集结果（O(M)，M 是命中元素数）

拿到起点后，沿第 0 层 `forward` 指针往后扫，遇到 `score > max` 时停止：

```c
while (x != NULL && zslValueLteMax(x->score, &range)) {
    addReplyToClient(x->ele, x->score);
    x = x->level[0].forward;
}
```

**总复杂度**：`O(log N + M)`，定位 O(log N)，输出 M 个元素 O(M)。

### 3.3 `LIMIT offset count` 怎么处理？

Redis 在第 0 层先**线性跳过 offset 个节点**，然后输出 count 个：

```
... 定位到起点 ...
跳过 offset 个：循环 forward offset 次
输出 count 个
```

> **避坑**：`ZRANGEBYSCORE key min max LIMIT 100000 10` 这种**大 offset** 会真的跳 10 万次第 0 层指针，O(offset)。生产中分页深翻最好改用"上一页最后一个 score 当 min"的游标式分页。

### 3.4 反向版本 `ZREVRANGEBYSCORE`

利用第 0 层的 `backward` 指针反向走。先用类似 `zslLastInRange` 找到第一个 score ≤ max 的节点，再用 `backward` 往前扫。

---

## 四、插入元素：`ZADD` 流程

### 4.1 双结构同时维护

```
ZADD key score member:

1. 在 hashtable 里查 member
   ├─ 不存在 → 新元素
   │    a. 跳表 zslInsert(score, member) → 返回新节点指针
   │    b. hashtable 写入 member → 新节点指针
   │    c. ZSet.length++
   │
   └─ 已存在 → 更新（看下文第六节）
```

两边都成功后才算插入完成；任何一边失败都要回滚（实际实现里，因为单线程，分配失败直接 OOM panic，不存在部分成功）。

### 4.2 跳表插入：`zslInsert` 核心步骤

**步骤一：查找每层的"前驱"，并记录 rank**

要插入新节点，必须先找到**每一层应该插在谁后面**。同时累加 `span` 算出新节点的全局 rank（用于更新 span 字段）。

```c
zskiplistNode *update[ZSKIPLIST_MAXLEVEL];   // 每层的前驱
unsigned long  rank[ZSKIPLIST_MAXLEVEL];     // 每层前驱的全局排名

x = header;
for (i = zsl->level - 1; i >= 0; i--) {
    rank[i] = (i == zsl->level - 1) ? 0 : rank[i + 1];
    while (x->level[i].forward &&
          (x->level[i].forward->score < score ||
           (x->level[i].forward->score == score &&
            sdscmp(x->level[i].forward->ele, ele) < 0))) {
        rank[i] += x->level[i].level.span;   // 累计跨度
        x = x->level[i].forward;
    }
    update[i] = x;
}
```

`update[i]` 是新节点在第 i 层应该挂在它的 forward 处的那个前驱。

**步骤二：随机出新节点的层数**

```c
int level = zslRandomLevel();
if (level > zsl->level) {
    // 新节点比当前最高层还高 → 高出来的层用 header 当前驱，span 为整个跳表长度
    for (i = zsl->level; i < level; i++) {
        rank[i] = 0;
        update[i] = header;
        update[i]->level[i].span = zsl->length;
    }
    zsl->level = level;
}
```

**步骤三：分配节点 + 在每层插入**

```c
x = zslCreateNode(level, score, ele);
for (i = 0; i < level; i++) {
    // 标准链表插入
    x->level[i].forward = update[i]->level[i].forward;
    update[i]->level[i].forward = x;

    // 更新 span：插入位置把"前驱→后继"的一段切成两段
    x->level[i].span = update[i]->level[i].span - (rank[0] - rank[i]);
    update[i]->level[i].span = (rank[0] - rank[i]) + 1;
}

// 比新节点更高的层，前驱的 span 都要 +1（凭空多了一个底层节点穿过）
for (i = level; i < zsl->level; i++) {
    update[i]->level[i].span++;
}
```

**步骤四：维护 backward 和 tail**

```c
x->backward = (update[0] == header) ? NULL : update[0];
if (x->level[0].forward)
    x->level[0].forward->backward = x;
else
    zsl->tail = x;

zsl->length++;
```

### 4.3 复杂度

- 查找前驱：**O(log N)**
- 节点分配 + 各层挂接：**O(level) ≈ O(log N)**
- 哈希表插入：**O(1) 摊销**
- **总计 O(log N)**

---

## 五、删除元素：`ZREM` 流程

### 5.1 双结构同步删除

```
ZREM key member:
1. hashtable 查 member → 拿到跳表节点指针（O(1)）
2. 跳表 zslDelete(score, member)
3. hashtable 删除 member
4. ZSet.length--；若 length == 0 释放整个 ZSet
```

> **小优化**：因为已经从 hashtable 拿到节点指针，跳表那一步**完全可以直接传节点删除而不用再找一遍**。Redis 内部确实有 `zslDeleteNode(zsl, node, update)` 路径用于这种"已知节点"场景；而 `ZREMRANGEBYSCORE` 这种按 score 范围删的场景，则需要先用 `zslFirstInRange` 定位再删一段。

### 5.2 跳表删除：先找前驱，再断链补 span

**步骤一：和插入对称，记录每层的前驱 `update[i]`**

```c
zskiplistNode *update[ZSKIPLIST_MAXLEVEL];

x = header;
for (i = zsl->level - 1; i >= 0; i--) {
    while (x->level[i].forward &&
          (x->level[i].forward->score < score ||
           (x->level[i].forward->score == score &&
            sdscmp(x->level[i].forward->ele, ele) < 0))) {
        x = x->level[i].forward;
    }
    update[i] = x;
}
x = x->level[0].forward;   // 候选目标节点
```

注意要再校验 `x` 的 score 和 ele 是否匹配（同 score 多元素时可能扫过头）。

**步骤二：在每层把目标节点摘出去**

```c
for (i = 0; i < zsl->level; i++) {
    if (update[i]->level[i].forward == x) {
        // 这一层目标确实存在 → 跳过它
        update[i]->level[i].span += x->level[i].span - 1;
        update[i]->level[i].forward = x->level[i].forward;
    } else {
        // 这一层目标本来就没有（目标的层数更低）→ 仅 span -1
        update[i]->level[i].span -= 1;
    }
}
```

**步骤三：维护 backward、tail、level**

```c
if (x->level[0].forward)
    x->level[0].forward->backward = x->backward;
else
    zsl->tail = x->backward;

// 如果删的是最高层唯一节点，跳表整体高度可能下降
while (zsl->level > 1 && zsl->header->level[zsl->level - 1].forward == NULL)
    zsl->level--;

zsl->length--;
zslFreeNode(x);
```

### 5.3 复杂度

- 定位前驱：**O(log N)**
- 各层摘除：**O(level) ≈ O(log N)**
- 哈希表删除：**O(1) 摊销**
- **总计 O(log N)**

### 5.4 范围删除：`ZREMRANGEBYSCORE` / `ZREMRANGEBYRANK`

实现是 `zslDeleteRangeByScore` / `zslDeleteRangeByRank`：

1. 先 `zslFirstInRange` 定位起点（**O(log N)**）。
2. 沿第 0 层往后**逐个**调用类似上面的删除逻辑，**同时**在更高层正确维护 span 与指针。
3. 复杂度 **O(log N + M)**，M 是被删元素数。

> **特别注意**：每删一个元素都要**同步把哈希表里对应 member 也删掉**，Redis 通过回调 `dictDelete` 完成。一边漏删就会出现"`ZSCORE` 还能查到但 `ZRANGE` 找不到"的诡异不一致。

---

## 六、更新元素：`ZADD` 同 member 不同 score

`ZADD key score member` 当 member 已存在时分两种情况：

### 6.1 score 没变

直接返回，跳表无需任何操作。

### 6.2 score 改变

跳表的"位置"由 score 决定，**改 score 等于元素要换位置**。Redis 的处理是：

```
1. 删除旧节点（zslDelete with 旧 score）
2. 插入新节点（zslInsert with 新 score）
3. hashtable 更新 member → 新节点指针
```

也就是 **"删 + 插"** 而不是原地改 score。原因：

- 改 score 后，原节点在跳表里的位置已经错了，需要在每一层重新挂接，复杂度和"删 + 插"一样是 O(log N)；
- 重用一份"先删再插"的代码更简单可靠，无须维护一份"原地移动"的特殊逻辑。

> Redis 7.0+ 的 `zsetAdd` 在源码里有一处微小优化：**如果新旧 score 落在同一段位置（即在跳表里前后邻居关系不变）也会走 zslUpdateScore 原地改一下 score 字段**。但这种场景需要扫一遍邻居才能判断，整体仍是 O(log N) 量级，主流情况还是删插。

### 6.3 `ZADD` 的修饰符语义

| 修饰符 | 行为 |
|-------|------|
| `XX` | 只更新已存在的，不存在不插入 |
| `NX` | 只插入新元素，已存在不更新 |
| `GT` | 仅当新 score > 旧 score 才更新 |
| `LT` | 仅当新 score < 旧 score 才更新 |
| `CH` | 返回值改为"被改动元素数"（含更新），不带 CH 只返回新插入数 |
| `INCR` | 把 score 加上传入值（等价 `ZINCRBY`），返回新分数 |

`GT` / `LT` 在排行榜"分数只升不降"或"只降不升"的场景非常实用，避免在应用层做读 → 比较 → 写的非原子操作。

---

## 七、`ZRANK` 是怎么 O(log N) 算出来的

很多人以为按 rank 取必须遍历，其实**靠 `span` 字段一路累加就能算**。

```c
unsigned long zslGetRank(zskiplist *zsl, double score, sds ele) {
    unsigned long rank = 0;
    x = zsl->header;
    for (i = zsl->level - 1; i >= 0; i--) {
        while (x->level[i].forward &&
              (x->level[i].forward->score < score ||
               (x->level[i].forward->score == score &&
                sdscmp(x->level[i].forward->ele, ele) <= 0))) {
            rank += x->level[i].level.span;   // 关键：累加 span
            x = x->level[i].forward;
        }
        if (x->ele && sdscmp(x->ele, ele) == 0)
            return rank;   // 命中
    }
    return 0;
}
```

直观理解：每条上层指针 = "底层跨过了 span 个节点"，把走过的指针 span 全加起来就是底层 rank。**复杂度 O(log N)**。

`ZRANGE 0 9` 这种按下标取也是同样的套路：先按 rank 找到 index=0 的起点，再 forward 取 10 个。

---

## 八、与红黑树/B 树的对比

| 维度 | 跳表 | 红黑树 | B+ 树 |
|------|------|--------|------|
| 实现复杂度 | 简单（增删 ~100 行） | 复杂（旋转、变色） | 复杂（分裂、合并） |
| 范围查询 | 底层有序链表 ✅ | 中序遍历，常数大 | 叶子链表 ✅ |
| 内存开销 | 期望 1.33 倍指针 | 每节点 2 指针 + 1 颜色位 | 节点开销大 |
| 锁粒度 | 局部修改，并发友好 | 旋转影响大范围 | 节点级别锁 |
| 单次复杂度 | O(log N) 期望 | O(log N) 严格 | O(log N) |
| 适用场景 | 内存索引、范围查询 | 严格平衡需求 | 磁盘索引 |

> Redis 选跳表：**实现简单 + 范围查询天然 + 概率平衡足够好**，对内存数据库是最佳选择。

---

## 九、面试速记

**Q：ZSet 为什么要 skiplist + hashtable 两个结构？**
A：跳表负责按 score 范围查（O(log N)）和按 rank 取（靠 span，O(log N)），哈希表负责 member→score 的 O(1) 查找。两者共享同一份 member（指针引用），不重复存储。

**Q：跳表节点的层数怎么定？**
A：插入时随机算，每层有 0.25 概率上涨一层，上限 32 层。期望层数 1.33，期望复杂度 O(log N)。

**Q：`ZRANGEBYSCORE` 怎么实现的？**
A：跳表自顶向下找到第一个 score ≥ min 的节点（O(log N)），然后沿第 0 层 forward 输出，直到 score > max（O(M)）。总复杂度 O(log N + M)。

**Q：`ZADD` 改 score 是怎么处理的？**
A：本质是"删旧节点 + 插新节点"，因为 score 决定跳表位置。哈希表里同步更新 member 指针。

**Q：`ZRANK` 怎么做到 O(log N)？**
A：靠跳表节点 `level[i].span`，查找时把走过的所有指针的 span 累加起来就是 rank。

**Q：跳表为什么不用红黑树？**
A：实现简单、范围查询天然支持、并发锁粒度小、可通过 p 调节内存。Antirez 在 Redis 文档亲自给过这个答复。

**Q：score 相同怎么排？**
A：按 member 字典序（memcmp）。这就是排行榜里同分用户按字母序展示的原因。

---

## 十、一句话总结

> **跳表 = 多层有序链表索引**，每层概率上升、底层完整有序。
> **ZSet = 跳表（管排序与范围）+ 哈希表（管 O(1) 查 score）**。
> **插入/删除/更新都是 O(log N)**，靠记录每层前驱 `update[]` 和跨度 `span` 来维护。
> **范围查询 O(log N + M)**，先跳表定位起点，再底层链表顺扫。
