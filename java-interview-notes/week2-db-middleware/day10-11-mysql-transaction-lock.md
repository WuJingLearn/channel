# Day 10-11：MySQL 事务与锁

> 复习目标：深入理解事务隔离级别、MVCC 实现原理、InnoDB 锁体系与死锁分析。

---

## 一、事务基础

**Q1：事务的 ACID 特性分别靠什么机制保证？**

A：
| 特性 | 含义 | 实现机制 |
|------|------|---------|
| **A（原子性）** | 事务要么全成功要么全失败 | **Undo Log**（回滚日志） |
| **C（一致性）** | 事务前后数据满足约束 | 由 A + I + D 共同保证 + 业务层约束 |
| **I（隔离性）** | 事务之间互不干扰 | **锁 + MVCC** |
| **D（持久性）** | 事务提交后数据不会丢 | **Redo Log**（WAL 机制） |

---

**Q2：MySQL 的四种隔离级别及解决的问题？**

A：
| 隔离级别 | 脏读 | 不可重复读 | 幻读 | 实现方式 |
|---------|------|-----------|------|---------|
| Read Uncommitted | 可能 | 可能 | 可能 | 无控制 |
| **Read Committed (RC)** | 解决 | 可能 | 可能 | 每次读都生成新 ReadView |
| **Repeatable Read (RR)** | 解决 | 解决 | 大部分解决 | 事务内第一次读生成 ReadView |
| Serializable | 解决 | 解决 | 解决 | 读加共享锁，写加排他锁 |

MySQL InnoDB 默认 **RR** 级别。RR 下通过 MVCC 解决快照读的幻读，通过 Next-Key Lock 解决当前读的幻读。

---

## 二、MVCC 原理

**Q3：MVCC 的实现原理是什么？**

A：MVCC（Multi-Version Concurrency Control）实现快照读（SELECT），不加锁也能读到一致性数据。

**三个核心组件**：

1. **隐藏字段**（每行数据额外的列）：
   - `DB_TRX_ID`（6 字节）：最后修改该行的事务 ID
   - `DB_ROLL_PTR`（7 字节）：回滚指针，指向 Undo Log 中该行的上一个版本
   - `DB_ROW_ID`（6 字节）：隐藏主键（无主键时使用）

2. **Undo Log 版本链**：
   - 每次更新都会将旧版本写入 Undo Log，并通过 `DB_ROLL_PTR` 串成链表
   - 形成：当前版本 → 上一版本 → 更早版本 → ...

3. **ReadView（一致性视图）**：
   - `m_ids`：生成 ReadView 时当前活跃（未提交）的事务 ID 列表
   - `min_trx_id`：m_ids 中的最小值
   - `max_trx_id`：下一个即将分配的事务 ID（最大 ID + 1）
   - `creator_trx_id`：创建该 ReadView 的事务 ID

---

**Q4：ReadView 的可见性判断规则是什么？**

A：读取某行数据时，通过版本链逐版本判断可见性：

```
设该版本的 DB_TRX_ID = trx_id

1. trx_id == creator_trx_id → 可见（自己修改的）
2. trx_id < min_trx_id → 可见（该事务在 ReadView 创建前已提交）
3. trx_id >= max_trx_id → 不可见（该事务在 ReadView 创建后才开始）
4. min_trx_id <= trx_id < max_trx_id：
   - trx_id 在 m_ids 中 → 不可见（该事务还未提交）
   - trx_id 不在 m_ids 中 → 可见（该事务已提交）

如果当前版本不可见，沿 DB_ROLL_PTR 找上一个版本继续判断
```

---

**Q5：RC 和 RR 的 MVCC 区别是什么？**

A：
| 维度 | RC（Read Committed） | RR（Repeatable Read） |
|------|---------------------|----------------------|
| ReadView 生成时机 | **每次** SELECT 都生成新的 ReadView | 事务内**第一次** SELECT 生成，后续复用 |
| 效果 | 每次读都能看到已提交的最新数据 | 事务内多次读结果一致（快照不变） |

**示例**：事务 A 先读一行，事务 B 修改并提交该行，事务 A 再读：
- RC：第二次读看到事务 B 的修改（新 ReadView）
- RR：第二次读看不到事务 B 的修改（复用旧 ReadView）

---

**Q6：快照读和当前读的区别？**

A：
| 类型 | 说明 | 示例 |
|------|------|------|
| **快照读** | 读取 MVCC 快照版本，不加锁 | 普通 `SELECT` |
| **当前读** | 读取最新已提交版本，加锁 | `SELECT ... FOR UPDATE`、`SELECT ... LOCK IN SHARE MODE`、INSERT、UPDATE、DELETE |

RR 下的幻读问题：
- **快照读**：MVCC 通过 ReadView 解决（看不到新插入的行）
- **当前读**：通过 **Next-Key Lock** 解决（锁住间隙，阻止其他事务插入）

---

## 三、InnoDB 锁体系

**Q7：InnoDB 有哪些锁？**

A：
| 锁类型 | 粒度 | 说明 |
|--------|------|------|
| **共享锁（S）** | 行锁 | 读锁，多个事务可同时持有 |
| **排他锁（X）** | 行锁 | 写锁，独占 |
| **意向共享锁（IS）** | 表锁 | 事务想给行加 S 锁前，先给表加 IS |
| **意向排他锁（IX）** | 表锁 | 事务想给行加 X 锁前，先给表加 IX |
| **Record Lock** | 行锁 | 锁住索引记录本身 |
| **Gap Lock** | 行锁 | 锁住索引记录之间的间隙（防止插入） |
| **Next-Key Lock** | 行锁 | Record Lock + Gap Lock（左开右闭区间） |
| **插入意向锁** | 行锁 | 插入时在 Gap Lock 之间的特殊等待锁 |
| **AUTO-INC Lock** | 表锁 | 自增列插入时的表级锁（5.1.22 后优化为轻量级互斥量） |

---

**Q8：Next-Key Lock 是如何防止幻读的？**

A：Next-Key Lock = Record Lock + Gap Lock，锁住一个**左开右闭**区间。

```sql
-- 表中有 id: 1, 5, 10, 15
-- 事务 A 执行当前读
SELECT * FROM t WHERE id > 5 AND id < 12 FOR UPDATE;

-- 加锁范围（Next-Key Lock）：(5, 10] 和 (10, 15)
-- 事务 B 想插入 id=7 → 阻塞（落在 Gap Lock 范围内）
-- 事务 B 想插入 id=12 → 阻塞（落在 Gap Lock 范围内）
-- 从而防止了幻读
```

---

**Q9：什么情况下 InnoDB 的行锁会退化为表锁？**

A：

**前提：只有"当前读"才会加锁，普通 SELECT 不加锁**

普通 `SELECT` 走的是 **MVCC 快照读**，读历史版本数据，**完全不加锁**，无论有没有索引：
```sql
-- 不加任何锁，不存在退化为表锁的问题
SELECT * FROM user WHERE name = 'xxx';
```

只有以下**当前读**操作才会加锁，才存在行锁退化为表锁的问题：
```sql
SELECT * FROM user WHERE name = 'xxx' FOR UPDATE;       -- 排他锁
SELECT * FROM user WHERE name = 'xxx' LOCK IN SHARE MODE; -- 共享锁
UPDATE user SET age = 18 WHERE name = 'xxx';            -- 排他锁
DELETE FROM user WHERE name = 'xxx';                    -- 排他锁
```

**行锁退化为表锁的场景**：
1. **没有使用索引**：`WHERE name = 'xxx'`（name 无索引） → 走全表扫描 → 锁住所有扫描到的行 ≈ 表锁
2. **索引失效**：类型转换、函数操作等导致索引失效 → 同上
3. 明确的 `LOCK TABLES` 语句

| 操作类型 | 有无索引 | 加锁情况 |
|----------|----------|----------|
| 普通 `SELECT` | 无所谓 | **不加锁**（MVCC 快照读） |
| `FOR UPDATE` / `UPDATE` / `DELETE` | 有索引 | 精确行锁 |
| `FOR UPDATE` / `UPDATE` / `DELETE` | **无索引** | **全表扫描 ≈ 表锁** |

**关键**：InnoDB 行锁是**加在索引上的**，不是加在数据行上。当前读没有索引就无法精确加行锁，只能退化为表锁。

---

## 四、死锁

**Q10：什么是死锁？InnoDB 如何检测和处理？**

A：死锁是两个或多个事务互相持有对方等待的锁，形成循环等待。

**经典场景**：
```
事务 A：UPDATE t SET ... WHERE id = 1;  -- 持有 id=1 的 X 锁
事务 B：UPDATE t SET ... WHERE id = 2;  -- 持有 id=2 的 X 锁
事务 A：UPDATE t SET ... WHERE id = 2;  -- 等待 id=2 的锁 → 阻塞
事务 B：UPDATE t SET ... WHERE id = 1;  -- 等待 id=1 的锁 → 死锁！
```

**InnoDB 处理**：
1. **等待超时**：`innodb_lock_wait_timeout`（默认 50 秒），超时后回滚
2. **死锁检测**：`innodb_deadlock_detect=ON`（默认开启），主动检测 wait-for graph（等待图）中的环，发现死锁立即回滚代价最小的事务

---

**Q11：如何避免死锁？**

A：
1. **固定加锁顺序**：所有事务按相同顺序操作表和行（如按 ID 升序）
2. **缩短事务**：事务尽量小，减少持锁时间
3. **合理使用索引**：避免全表扫描导致大量行锁
4. **降低隔离级别**：RC 不使用 Gap Lock，死锁概率更低
5. **使用 `SELECT ... FOR UPDATE` 时加 NOWAIT**：避免长时间等待

---

## 五、主从复制

**Q12：MySQL 主从复制的原理？**

A：
```
Master                          Slave
  │                               │
  ├─ 执行 DML/DDL                  │
  ├─ 写入 Binlog                   │
  │                               │
  │ ←── IO Thread 拉取 ──→         │
  │                     写入 Relay Log
  │                               │
  │                     SQL Thread 回放
  │                               │
  │                     写入数据文件
```

**三个线程**：
1. **Master Binlog Dump Thread**：主库响应从库请求，发送 Binlog 事件
2. **Slave IO Thread**：从库拉取主库的 Binlog，写入本地 Relay Log
3. **Slave SQL Thread**：从库读取 Relay Log 并重放

---

**Q13：Binlog 的三种格式各有什么特点？**

A：
| 格式 | 记录内容 | 优点 | 缺点 |
|------|---------|------|------|
| **Statement** | SQL 语句原文 | 日志量小 | 不确定函数（NOW(), RAND()）可能导致主从不一致 |
| **Row** | 每行数据变更的前后值 | 精确，不会不一致 | 日志量大（批量 UPDATE 记录每行） |
| **Mixed** | 默认用 Statement，不安全时自动切 Row | 折中 | 复杂度高 |

**推荐**：生产环境用 **Row** 格式（`binlog_format=ROW`），保证数据一致性。

---

**Q14：主从延迟怎么产生？如何解决？**

A：
**产生原因**：
1. 主库并发写入，从库 SQL Thread 单线程回放
2. 大事务（如大批量 UPDATE/DELETE）
3. 从库性能较差
4. 网络延迟

**解决方案**：
| 方案 | 说明 |
|------|------|
| 并行复制 | `slave_parallel_workers > 0`，多线程回放。MySQL 5.7+ 基于组提交的并行复制 |
| 半同步复制 | 主库等待至少一个从库确认收到 Binlog 后才返回成功 |
| 读写分离降级 | 关键读走主库（强一致），普通读走从库 |
| 中间件路由 | ProxySQL / MyCat 自动判断延迟，超过阈值读主库 |

---

**Q15：什么是半同步复制？和异步复制的区别？**

A：
| 模式 | 流程 | 数据安全 | 性能 |
|------|------|---------|------|
| **异步复制** | 主库写 Binlog 后立即返回客户端，不等从库 | 可能丢数据（主库挂了从库还没拉到） | 高 |
| **半同步复制** | 主库等待**至少一个从库**确认收到 Binlog（写入 Relay Log） | 不丢数据（除非主从同时挂） | 略低 |
| **全同步复制** | 主库等待**所有从库**执行完毕 | 最安全 | 最差（基本不用） |

MySQL 5.7+ 增强半同步（after-sync）：等待从库确认后才将事务标记为 committed，进一步保证一致性。
