# Day 1-2：Java 集合框架源码

> 复习目标：掌握核心集合类的底层数据结构、关键方法源码流程、扩容机制和线程安全方案。

---

## 一、HashMap

**Q1：HashMap 的底层数据结构是什么？JDK 1.8 做了什么改进？**

A：JDK 1.7 采用「数组 + 链表」，1.8 改为「数组 + 链表 + 红黑树」。当链表长度 ≥ 8 且数组长度 ≥ 64 时，链表转为红黑树（时间复杂度从 O(n) 降为 O(log n)）；当红黑树节点数 ≤ 6 时退化回链表。

---

**Q2：HashMap 的 put 流程是怎样的？**

A：
1. 计算 key 的 hash 值：`(h = key.hashCode()) ^ (h >>> 16)`（高 16 位异或低 16 位，扰动减少碰撞）
2. 如果 table 为空或长度为 0，先 `resize()` 初始化
3. 根据 `(n - 1) & hash` 定位桶下标 i
4. 如果 `table[i]` 为 null，直接创建 Node 放入
5. 如果 `table[i]` 不为 null：
   - key 相同（hashCode 相等且 equals 为 true）→ 覆盖旧值
   - 节点是 TreeNode → 走红黑树插入逻辑
   - 否则遍历链表，尾插法插入新节点。插入后链表长度 ≥ 8 则调用 `treeifyBin()`
6. `++size > threshold` 则 `resize()` 扩容

---

**Q3：HashMap 的 hash 扰动函数为什么要用高 16 位异或低 16 位？**

A：因为桶定位用的是 `(n - 1) & hash`，当数组长度较小时（如 16），只有低 4 位参与运算，碰撞概率高。高 16 位异或低 16 位可以让高位信息也参与桶定位，在性能开销极低（一次异或）的前提下有效降低碰撞率。

---

**Q4：HashMap 的扩容机制是怎样的？**

A：
- 默认初始容量 16，负载因子 0.75，阈值 = 容量 × 负载因子 = 12
- 当元素数量超过阈值时触发 `resize()`，新容量 = 旧容量 × 2
- 1.8 的 rehash 优化：不需要重新计算 hash，只需判断 `hash & oldCap` 是 0 还是 1：
  - 为 0 → 位置不变
  - 为 1 → 新位置 = 旧位置 + oldCap
- 这个优化的原因：扩容为 2 倍后，掩码多了最高位的一个 1，相当于只需判断 hash 在那一位是 0 还是 1

---

**Q5：HashMap 为什么容量必须是 2 的幂次方？**

A：
1. 方便位运算定位桶：`(n - 1) & hash` 等价于 `hash % n`，位运算效率远高于取模
2. 扩容时 rehash 优化：只需判断高位新增的那一位是 0 还是 1，无需重新计算整个 hash 定位
3. 减少碰撞：2 的幂次方保证掩码全为 1，hash 的各位都能参与定位

---

**Q6：HashMap 在 JDK 1.7 中的并发问题是什么？1.8 解决了吗？**

A：
- **1.7 的问题**：并发 `resize()` 时使用头插法转移链表，可能导致**链表成环**，后续 `get()` 会死循环
- **1.8 的改进**：改用尾插法，不会形成环形链表。但 1.8 仍然**不是线程安全的**，并发 put 可能导致数据覆盖丢失（两个线程同时判断桶为空然后各自插入，后者覆盖前者）

---

**Q7：HashMap 的 key 可以为 null 吗？存在哪个位置？**

A：可以。`key == null` 时 `hash()` 返回 0，所以 null key 永远存储在 `table[0]` 位置。HashMap 允许一个 null key 和多个 null value。

---

## 二、ConcurrentHashMap

**Q8：ConcurrentHashMap 在 JDK 1.7 和 1.8 的实现有什么区别？**

A：
| 维度 | JDK 1.7 | JDK 1.8 |
|------|---------|---------|
| 数据结构 | Segment[] + HashEntry[] + 链表 | Node[] + 链表 + 红黑树（同 HashMap） |
| 锁粒度 | Segment 级别（一个 Segment 锁住一段） | Node 级别（锁单个桶的头节点） |
| 锁实现 | ReentrantLock（Segment 继承自 ReentrantLock） | CAS + synchronized |
| 并发度 | 默认 16（Segment 数量固定） | 与桶数量一致，理论上更高 |
| size 计算 | 先无锁尝试 2 次，不一致再全锁 | baseCount + CounterCell[] 分散计数（类似 LongAdder） |

---

**Q9：ConcurrentHashMap 1.8 的 put 流程是怎样的？**

A：
1. 计算 hash（spread 函数：`(h ^ (h >>> 16)) & 0x7fffffff`）
2. 如果 table 未初始化，CAS 初始化
3. 用 `(n - 1) & hash` 定位桶，如果桶为空，**CAS** 放入新 Node
4. 如果桶的头节点 hash == MOVED（-1），说明正在扩容，当前线程**协助扩容**（helpTransfer）
5. 否则 **synchronized 锁住头节点**：
   - 链表：遍历，key 相同则覆盖，否则尾插
   - 红黑树：走 TreeBin 的 putTreeVal
6. 链表长度 ≥ 8 则转红黑树
7. `addCount()` 更新计数，如果超过阈值则触发扩容

---

**Q10：ConcurrentHashMap 的 size() 是如何高效实现的？**

A：采用类似 `LongAdder` 的分散计数思想：
- 维护 `baseCount`（无竞争时 CAS 更新）和 `CounterCell[]` 数组
- 有竞争时，不同线程更新不同的 CounterCell，减少 CAS 冲突
- `size()` = `baseCount` + 所有 `CounterCell` 之和
- 这种设计在高并发下比单一 volatile 变量性能好得多

---

**Q11：ConcurrentHashMap 为什么不允许 key 或 value 为 null？**

A：为了消除二义性。在并发场景下，`get(key)` 返回 null 时无法判断是 key 不存在还是 value 就是 null。HashMap 单线程使用时可以通过 `containsKey()` 二次确认，但并发场景下两次调用之间状态可能已变化，无法保证一致性。

---

## 三、ArrayList 与 LinkedList

**Q12：ArrayList 的扩容机制是什么？**

A：
- 默认初始容量 10（首次 add 时才真正分配，无参构造器先指向空数组 `DEFAULTCAPACITY_EMPTY_ELEMENTDATA`）
- 扩容为原来的 **1.5 倍**：`newCapacity = oldCapacity + (oldCapacity >> 1)`
- 通过 `Arrays.copyOf()` 创建新数组并复制元素
- 扩容操作是 O(n) 的，所以已知大小时建议指定初始容量

---

**Q13：ArrayList 和 LinkedList 的区别是什么？各自适用场景？**

A：
| 维度 | ArrayList | LinkedList |
|------|-----------|------------|
| 底层结构 | Object[] 动态数组 | 双向链表（Node: prev + item + next） |
| 随机访问 | O(1)，实现 RandomAccess 接口 | O(n)，需从头/尾遍历 |
| 头部插入/删除 | O(n)，需要移动元素 | O(1)，只需改指针 |
| 尾部插入 | 均摊 O(1)（可能触发扩容） | O(1) |
| 内存开销 | 紧凑（连续内存，有部分空位浪费） | 大（每个节点额外存 prev + next 两个指针） |
| 缓存友好 | 好（连续内存，CPU 缓存行命中高） | 差（节点分散在堆中） |

**结论**：绝大多数场景优先用 ArrayList。LinkedList 仅在频繁头部插入/删除且不需要随机访问时考虑（实际工程中很少用）。

---

**Q14：ArrayList 的 `subList()` 有什么坑？**

A：`subList()` 返回的是原 List 的**视图**，不是新的独立 List：
- 对 subList 的修改会反映到原 List
- 对原 List 的结构性修改（add/remove）会导致 subList 后续操作抛 `ConcurrentModificationException`
- 如果需要独立副本，应使用 `new ArrayList<>(list.subList(from, to))`

---

## 四、TreeMap 与 LinkedHashMap

**Q15：TreeMap 的底层结构和排序原理是什么？**

A：
- 底层是**红黑树**（自平衡二叉搜索树）
- 默认按 key 的自然顺序排序（key 必须实现 Comparable），也可以构造时传入自定义 Comparator
- 查找、插入、删除的时间复杂度都是 O(log n)
- 不允许 null key（需要比较），允许 null value

---

**Q16：LinkedHashMap 如何实现有序？如何用它实现 LRU 缓存？**

A：
- LinkedHashMap 继承 HashMap，在每个 Entry 上额外维护 `before` 和 `after` 指针，构成**双向链表**
- 两种排序模式（构造时指定 `accessOrder`）：
  - `false`（默认）：**插入顺序**
  - `true`：**访问顺序**（每次 get/put 都会将节点移到链表尾部）

**LRU 实现**：
```java
public class LRUCache<K, V> extends LinkedHashMap<K, V> {
    private final int capacity;

    public LRUCache(int capacity) {
        super(capacity, 0.75f, true); // accessOrder = true
        this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
        return size() > capacity; // 超过容量时移除最老（最久未访问）的元素
    }
}
```

---

## 五、综合对比与高频追问

**Q17：HashMap、Hashtable、ConcurrentHashMap 三者对比？**

A：
| 维度 | HashMap | Hashtable | ConcurrentHashMap |
|------|---------|-----------|-------------------|
| 线程安全 | 否 | 是（synchronized 方法级） | 是（CAS + synchronized 桶级） |
| null key/value | 允许 | 不允许 | 不允许 |
| 性能 | 最高（无锁开销） | 最低（全表锁） | 高（细粒度锁） |
| 迭代器 | fail-fast | fail-fast | 弱一致性（不抛异常） |
| 推荐使用 | 单线程场景 | 不推荐（已过时） | 并发场景首选 |

---

**Q18：什么是 fail-fast 机制？**

A：
- Java 集合的快速失败机制。每个集合维护一个 `modCount`（修改次数）
- 创建迭代器时记录当时的 `expectedModCount = modCount`
- 迭代过程中检查 `modCount != expectedModCount`，如果不等说明集合被结构性修改，抛出 `ConcurrentModificationException`
- 这是一种**尽早发现错误**的设计，而不是严格的并发保护
- ConcurrentHashMap 使用弱一致性迭代器，不触发 fail-fast

---

**Q19：HashSet 的底层实现是什么？**

A：HashSet 底层就是一个 HashMap，元素存为 HashMap 的 key，所有 value 都指向同一个静态的 `PRESENT` 对象（`new Object()`）。所以 HashSet 的去重依赖 `hashCode()` 和 `equals()`。

---

**Q20：如何选择合适的集合类？给出常见场景的建议。**

A：
| 场景 | 推荐集合 | 原因 |
|------|---------|------|
| 有序不重复 | TreeSet / TreeMap | 红黑树保证排序 |
| 无序不重复 | HashSet / HashMap | O(1) 查找 |
| 保持插入顺序 | LinkedHashMap / LinkedHashSet | 双向链表维护顺序 |
| 线程安全 Map | ConcurrentHashMap | 细粒度锁，高并发 |
| 线程安全 List | CopyOnWriteArrayList | 读多写少场景 |
| 队列/栈 | ArrayDeque | 比 Stack 和 LinkedList 更高效 |
| 频繁随机访问 | ArrayList | O(1) 索引访问 |
