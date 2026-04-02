# Day 8-9：MySQL 核心原理

> 复习目标：掌握 InnoDB 架构、B+ 树索引、索引优化策略与执行计划解读。

---

## 一、InnoDB 存储引擎架构

**Q1：InnoDB 的核心架构由哪些组件组成？**

A：
```
内存结构                          磁盘结构
├── Buffer Pool（缓冲池）          ├── 表空间文件（.ibd）
│   ├── 数据页缓存                │   ├── 数据页
│   ├── 索引页缓存                │   └── 索引页
│   ├── Change Buffer             ├── Redo Log 文件（ib_logfile0/1）
│   └── 自适应哈希索引             ├── Undo Log（undo tablespace）
├── Log Buffer（日志缓冲）         └── Binlog（server 层）
└── Additional Memory Pool
```

**核心组件**：
- **Buffer Pool**：缓存数据页和索引页，减少磁盘 IO。使用改进的 LRU 算法（分为 young 和 old 区，防止全表扫描冲刷缓存）
- **Redo Log**：WAL（Write-Ahead Logging），保证事务持久性。修改先写 redo log 再写数据页（顺序写 vs 随机写）
- **Undo Log**：记录数据修改前的值，用于事务回滚和 MVCC 快照读
- **Binlog**：Server 层的日志，记录所有修改操作，用于主从复制和数据恢复

---

**Q2：Redo Log 和 Binlog 有什么区别？为什么需要两个日志？**

A：
| 维度 | Redo Log | Binlog |
|------|---------|--------|
| 所属层 | InnoDB 引擎层 | MySQL Server 层 |
| 日志类型 | 物理日志（记录数据页的修改） | 逻辑日志（记录 SQL 语句或行变更） |
| 写入方式 | 循环写（固定大小，写满则覆盖） | 追加写（不会覆盖） |
| 用途 | 崩溃恢复（crash recovery） | 主从复制、数据恢复（point-in-time） |

**为什么需要两个**：
- Redo Log 是循环写的，不能保留所有历史；Binlog 是追加的，可做全量恢复
- Binlog 是 Server 层的，所有引擎都可使用；Redo Log 只有 InnoDB 有
- 两者通过**两阶段提交（2PC）**保持一致性

---

**Q3：什么是两阶段提交？为什么需要它？**

A：为了保证 Redo Log 和 Binlog 的一致性：
1. **Prepare 阶段**：InnoDB 将事务标记为 prepare 状态，写入 redo log
2. **Commit 阶段**：写入 binlog，然后将 redo log 标记为 commit

**如果不用两阶段提交**：
- 先写 redo log 后写 binlog → 崩溃时 binlog 没记录，从库缺数据
- 先写 binlog 后写 redo log → 崩溃时 redo log 没记录，主库缺数据
- 两阶段提交保证两者要么都成功要么都失败

---

**Q4：Buffer Pool 的 LRU 算法做了什么改进？**

A：传统 LRU 问题：全表扫描会把大量冷数据页加载进来，挤掉真正的热数据。

InnoDB 改进（分区 LRU）：
- 将 LRU 链表分为 **young 区**（热数据，约 5/8）和 **old 区**（冷数据，约 3/8）
- 新页先放入 **old 区头部**，不影响 young 区
- 页在 old 区停留超过 `innodb_old_blocks_time`（默认 1000ms）且再次被访问 → 才移入 young 区
- 全表扫描的页因为短时间内被访问后很快不再访问，不会进入 young 区

---

## 二、B+ 树索引

**Q5：为什么 MySQL 使用 B+ 树而不是 B 树、红黑树或 Hash？**

A：
| 数据结构 | 不适合原因 |
|---------|-----------|
| Hash | 只支持等值查询，不支持范围查询和排序 |
| 红黑树/AVL | 树高度太大（二叉树），磁盘 IO 次数多 |
| B 树 | 非叶节点也存数据 → 每个节点容纳的 key 更少 → 树更高 → IO 更多 |

**B+ 树优势**：
1. **非叶节点只存 key**，单个节点可容纳更多 key，树更矮（通常 3-4 层即可存千万级数据）
2. **叶子节点用双向链表连接**，范围查询只需顺序遍历叶子节点，无需回溯
3. **所有数据都在叶子节点**，查询路径长度一致，性能稳定

---

**Q6：聚簇索引和非聚簇索引的区别？什么是回表？**

A：
| 维度 | 聚簇索引（主键索引） | 非聚簇索引（二级索引） |
|------|-------------------|---------------------|
| 叶子节点存储 | 完整的数据行 | 索引列的值 + 主键 ID |
| 数量 | 每个表只有一个 | 可以有多个 |
| 物理排序 | 数据按主键物理排序 | 有自己独立的 B+ 树 |

**回表**：通过二级索引查到主键 ID 后，再回到聚簇索引查完整数据行。每条记录一次回表 = 一次聚簇索引的 B+ 树查找。

**主键选择建议**：自增 ID > 业务 ID。自增 ID 保证顺序插入（避免页分裂），且 int/bigint 类型占用空间小（二级索引叶子节点都要存主键）。

---

**Q7：什么是索引覆盖（Covering Index）？**

A：查询所需的所有列都在索引中，不需要回表到聚簇索引。

```sql
-- 表有联合索引 idx_name_age(name, age)

-- 覆盖索引（不需要回表）
SELECT name, age FROM user WHERE name = '张三';
-- EXPLAIN 的 Extra 列显示 "Using index"

-- 非覆盖索引（需要回表取 email）
SELECT name, age, email FROM user WHERE name = '张三';
```

**优化技巧**：用联合索引覆盖高频查询的所有字段，避免回表。

---

**Q8：什么是索引下推（Index Condition Pushdown, ICP）？**

A：MySQL 5.6 引入的优化。在二级索引遍历过程中，对索引中包含的列先做条件过滤，减少回表次数。

```sql
-- 联合索引 idx_name_age(name, age)
SELECT * FROM user WHERE name LIKE '张%' AND age > 20;
```

- **无 ICP**：通过索引找到所有 `name LIKE '张%'` 的记录 → 逐条回表 → 在 Server 层过滤 age > 20
- **有 ICP**：在索引遍历阶段就用 age > 20 过滤 → 只对满足条件的记录回表

EXPLAIN 的 Extra 列显示 "Using index condition"。

---

## 三、索引失效场景

**Q9：哪些情况下索引会失效？**

A：

| 场景 | 示例 | 原因 |
|------|------|------|
| 最左前缀违反 | 联合索引 (a,b,c)，查 `WHERE b=1` | 跳过了最左列 a |
| 对索引列使用函数 | `WHERE YEAR(create_time) = 2024` | 函数破坏索引有序性 |
| 隐式类型转换 | varchar 列 `WHERE phone = 13800138000`（数字） | MySQL 对 varchar 列使用函数转换 |
| LIKE 左模糊 | `WHERE name LIKE '%张'` | 无法利用索引的有序性 |
| OR 条件 | `WHERE a=1 OR b=2`（b 无索引） | 有一个列无索引则全表扫描 |
| 范围查询后的列 | 联合索引 (a,b,c)，`WHERE a>1 AND b=2` | a 用了范围查询，b 无法走索引 |
| NOT IN / NOT EXISTS | `WHERE id NOT IN (1,2,3)` | 优化器可能选择全表扫描 |
| IS NULL / IS NOT NULL | 取决于数据分布 | NULL 值比例高时优化器可能弃用索引 |

---

**Q10：联合索引的最左前缀匹配原则是什么？**

A：联合索引 `(a, b, c)` 的 B+ 树按照 a → b → c 排序。查询条件必须从最左列开始，依次连续匹配：

| 查询条件 | 走索引情况 |
|---------|-----------|
| `WHERE a=1` | 走索引（a） |
| `WHERE a=1 AND b=2` | 走索引（a, b） |
| `WHERE a=1 AND b=2 AND c=3` | 走索引（a, b, c） |
| `WHERE b=2` | 不走索引（跳过了 a） |
| `WHERE a=1 AND c=3` | 走索引（仅 a，c 无法利用） |
| `WHERE a>1 AND b=2` | 走索引（仅 a 的范围，b 无法利用） |
| `WHERE a=1 AND b>2 AND c=3` | 走索引（a, b 范围，c 无法利用） |
| `WHERE a=1 ORDER BY b` | 走索引（a 过滤，b 排序） |

---

## 四、EXPLAIN 执行计划

**Q11：EXPLAIN 的关键字段怎么看？**

A：
| 字段 | 含义 | 关键值 |
|------|------|--------|
| **type** | 访问类型（最重要） | const > eq_ref > ref > range > index > ALL |
| **key** | 实际使用的索引 | NULL 表示没走索引 |
| **rows** | 预估扫描行数 | 越小越好 |
| **Extra** | 额外信息 | 见下表 |

**type 含义**：
- `const`：主键或唯一索引等值查询，最快
- `eq_ref`：JOIN 使用主键/唯一索引
- `ref`：普通索引等值查询
- `range`：索引范围查询（BETWEEN、>、<、IN）
- `index`：全索引扫描（遍历整棵索引树）
- `ALL`：全表扫描，必须优化

**Extra 关键值**：
| Extra | 含义 |
|-------|------|
| Using index | 覆盖索引，不回表 |
| Using index condition | 索引下推 ICP |
| Using where | Server 层过滤 |
| Using filesort | 需要额外排序（无法利用索引排序） |
| Using temporary | 使用临时表（GROUP BY / DISTINCT 无索引） |

---

## 五、SQL 优化实战

**Q12：慢查询如何定位和优化？**

A：
**1. 开启慢查询日志**：
```sql
SET GLOBAL slow_query_log = 'ON';
SET GLOBAL long_query_time = 1;  -- 超过 1 秒记录
SET GLOBAL slow_query_log_file = '/var/log/mysql/slow.log';
```

**2. 分析慢日志**：`mysqldumpslow -s t -t 10 /var/log/mysql/slow.log`

**3. 常见优化手段**：
- 添加合适的索引（联合索引、覆盖索引）
- 避免 `SELECT *`，只查需要的列
- 大 OFFSET 分页优化：`WHERE id > last_id LIMIT 20`（游标分页）
- `COUNT(*)` 优化：InnoDB 的 `COUNT(*)` 需要遍历，可用 Redis 缓存或近似值
- 子查询改 JOIN
- 避免在 WHERE 中对索引列做运算

---

**Q13：深分页问题怎么解决？**

A：`LIMIT 100000, 20` 需要扫描前 100020 行再丢弃前 100000 行，性能极差。

**方案 1：游标分页（推荐）**
```sql
-- 前端传上一页最后一条记录的 ID
SELECT * FROM orders WHERE id > 100000 ORDER BY id LIMIT 20;
-- 走主键索引，非常快
```

**方案 2：延迟关联（子查询优化）**
```sql
SELECT * FROM orders
INNER JOIN (SELECT id FROM orders ORDER BY id LIMIT 100000, 20) AS t
ON orders.id = t.id;
-- 子查询走覆盖索引，只回表 20 条
```

---

**Q14：count(*) 和 count(1) 和 count(列名) 的区别？**

A：
| 写法 | 含义 | 是否统计 NULL |
|------|------|-------------|
| `COUNT(*)` | 统计所有行数 | 包含 NULL |
| `COUNT(1)` | 等同于 COUNT(*)  | 包含 NULL |
| `COUNT(列名)` | 统计该列非 NULL 值的行数 | 不包含 NULL |

性能：`COUNT(*)` ≈ `COUNT(1)` > `COUNT(主键)` > `COUNT(普通列)`。MySQL 对 `COUNT(*)` 做了特殊优化，InnoDB 会选择最小的索引树遍历。

---

**Q15：MySQL 的 order by 是如何工作的？如何优化？**

A：
**两种排序方式**：
1. **索引排序**：如果 ORDER BY 的列有索引且与查询条件匹配最左前缀 → 直接利用索引有序性，无需额外排序
2. **filesort**：需要额外排序（在 sort_buffer 中排，放不下则使用临时文件归并排序）

**filesort 两种算法**：
- **全字段排序**：取出所有需要的列到 sort_buffer，排序后直接返回。内存消耗大但无需回表
- **rowid 排序**：只取排序字段和主键到 sort_buffer，排序后回表取其他列。省内存但多一次回表

**优化**：
- 给 ORDER BY 列加索引（特别是联合索引覆盖 WHERE + ORDER BY）
- 增大 `sort_buffer_size` 减少临时文件排序
- 只查需要的列，减少单行数据量

---

**Q16：JOIN 的底层实现有哪些？如何优化？**

A：
| 算法 | 条件 | 原理 | 性能 |
|------|------|------|------|
| **Index Nested-Loop Join** | 被驱动表有索引 | 遍历驱动表，对每行在被驱动表上走索引查找 | O(N × log M) |
| **Block Nested-Loop Join** | 被驱动表无索引（8.0.18 前） | 批量加载驱动表数据到 join_buffer，减少被驱动表扫描次数 | O(N × M / buffer_size) |
| **Hash Join** | 被驱动表无索引（8.0.18+） | 对小表建哈希表，大表逐行探测 | O(N + M) |

**优化**：
- **小表驱动大表**（MySQL 优化器通常会自动选择）
- 被驱动表的 JOIN 列加索引
- 减少不必要的 JOIN 列
- `straight_join` 强制指定驱动表顺序（慎用）

---

**Q17：什么是 Change Buffer？作用是什么？**

A：
- 对于**非唯一**的二级索引页，如果不在 Buffer Pool 中，InnoDB 不会立即从磁盘读入该页来做更新
- 而是将修改记录在 **Change Buffer** 中，等到该页因为查询被读入 Buffer Pool 时，再合并（merge）
- **作用**：减少随机磁盘 IO，写密集场景下提升性能
- **限制**：唯一索引不能使用（因为插入前必须读页来检查唯一性）
- 由 `innodb_change_buffer_max_size` 控制大小（默认占 Buffer Pool 的 25%）
