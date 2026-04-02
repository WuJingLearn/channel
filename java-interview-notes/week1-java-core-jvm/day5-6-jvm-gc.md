# Day 5-6：JVM 内存模型与垃圾回收

> 复习目标：掌握 JVM 运行时数据区划分、对象生命周期、GC 算法与主流收集器原理。

---

## 一、JVM 运行时数据区

**Q1：JVM 运行时内存区域有哪些？哪些是线程共享的？**

A：
| 区域 | 线程共享 | 存储内容 | 异常 |
|------|---------|---------|------|
| 堆（Heap） | 共享 | 对象实例、数组 | OutOfMemoryError |
| 方法区/元空间（Metaspace） | 共享 | 类信息、常量池、静态变量 | OutOfMemoryError |
| 虚拟机栈（VM Stack） | 私有 | 栈帧（局部变量表、操作数栈、动态链接、返回地址） | StackOverflowError / OOM |
| 本地方法栈（Native Stack） | 私有 | Native 方法的栈帧 | StackOverflowError / OOM |
| 程序计数器（PC Register） | 私有 | 当前线程执行的字节码行号 | 唯一不会 OOM 的区域 |

---

**Q2：堆内存的分代结构是怎样的？**

A：
```
堆（Heap）
├── 新生代（Young Generation）—— 占堆 1/3（默认）
│   ├── Eden 区        —— 占新生代 8/10
│   ├── Survivor From  —— 占新生代 1/10（S0）
│   └── Survivor To    —— 占新生代 1/10（S1）
└── 老年代（Old Generation）—— 占堆 2/3（默认）
```

- 新对象优先在 Eden 分配
- Eden 满触发 Minor GC，存活对象移入 Survivor，age + 1
- 对象年龄达到阈值（默认 15，CMS 默认 6）晋升老年代
- 大对象（超过 `-XX:PretenureSizeThreshold`）直接进入老年代

---

**Q3：方法区在 JDK 7 和 JDK 8 有什么变化？**

A：
- **JDK 7 及之前**：方法区由**永久代（PermGen）**实现，位于 JVM 堆内存中，大小受 `-XX:MaxPermSize` 限制，容易 OOM
- **JDK 8 及之后**：废弃永久代，改用**元空间（Metaspace）**，存储在**本地内存（Native Memory）**中
  - 类的元数据存在元空间
  - 字符串常量池从永久代移到了堆中（JDK 7 开始）
  - 默认不限大小（受物理内存限制），可通过 `-XX:MaxMetaspaceSize` 设置上限

---

## 二、对象创建与内存布局

**Q4：对象的创建过程（new 指令）是怎样的？**

A：
1. **类加载检查**：检查类是否已加载、解析和初始化，若没有则先执行类加载
2. **分配内存**：
   - 指针碰撞（Bump the Pointer）：堆内存规整时使用（如 Serial、ParNew），移动分界指针
   - 空闲列表（Free List）：堆内存不规整时使用（如 CMS），从空闲列表找合适的块
   - 并发安全：CAS + 失败重试 或 TLAB（Thread Local Allocation Buffer，线程私有的 Eden 区块）
3. **初始化零值**：分配到的内存空间初始化为 0（保证字段不赋值也能使用）
4. **设置对象头**：Mark Word、类型指针（Klass Pointer）、数组长度（如果是数组）
5. **执行 `<init>` 方法**：按程序员意愿初始化（构造函数、实例代码块）

---

**Q5：对象的内存布局是怎样的？**

A：
```
对象内存布局（64 位 JVM，开启压缩指针）
├── 对象头（Header）12 字节
│   ├── Mark Word（8 字节）
│   │   ├── 无锁：hashCode(31) + 分代年龄(4) + 偏向标志(1) + 锁标志(2)
│   │   ├── 偏向锁：线程ID(54) + Epoch(2) + 分代年龄(4) + 偏向标志(1) + 锁标志(2)
│   │   ├── 轻量级锁：指向栈中 Lock Record 的指针(62) + 锁标志(2)
│   │   └── 重量级锁：指向 Monitor 的指针(62) + 锁标志(2)
│   └── Klass Pointer（4 字节，压缩后）—— 指向类的元数据
├── 实例数据（Instance Data）—— 字段值，按宽度排序（long/double → int/float → short/char → byte/boolean → reference）
└── 对齐填充（Padding）—— 凑齐 8 字节的倍数
```

---

**Q6：什么是 TLAB？为什么需要它？**

A：
- TLAB（Thread Local Allocation Buffer）：在 Eden 区为每个线程预先分配一小块私有内存
- 线程在自己的 TLAB 上分配对象时不需要加锁（指针碰撞即可），避免了多线程竞争
- TLAB 用完后再 CAS 申请新的 TLAB
- 默认开启（`-XX:+UseTLAB`），大小约 Eden 的 1%
- TLAB 不够大的对象会在 Eden 共享区分配（需要 CAS 同步）

---

## 三、GC Roots 与引用类型

**Q7：哪些对象可以作为 GC Roots？**

A：可达性分析算法从 GC Roots 出发，不可达的对象即为垃圾。GC Roots 包括：
1. **虚拟机栈中引用的对象**：正在执行的方法的局部变量、参数
2. **方法区中静态变量引用的对象**：`static Object obj`
3. **方法区中常量引用的对象**：`static final Object obj`
4. **本地方法栈中 JNI 引用的对象**：Native 方法持有的引用
5. **JVM 内部引用**：基本类型的 Class 对象、系统类加载器、常驻异常对象等
6. **所有被 synchronized 锁持有的对象**
7. **JMX 等注册的回调、本地代码缓存**

---

**Q8：Java 中的四种引用类型是什么？**

A：
| 引用类型 | 回收时机 | 用途 |
|---------|---------|------|
| 强引用（Strong） | 永远不回收（只要可达） | `Object obj = new Object()` 普通引用 |
| 软引用（Soft） | 内存不足时回收 | 缓存（如图片缓存），`SoftReference<T>` |
| 弱引用（Weak） | 下一次 GC 时必定回收 | ThreadLocalMap 的 Entry key、WeakHashMap |
| 虚引用（Phantom） | 随时回收，无法通过虚引用获取对象 | 跟踪对象被回收的活动，管理堆外内存（DirectByteBuffer） |

---

**Q9：对象被判定不可达后一定会被回收吗？**

A：不一定。对象的回收需要经历**两次标记**：
1. 第一次标记：不可达，检查是否有必要执行 `finalize()`
   - 如果没覆盖 `finalize()` 或已经执行过 → 直接回收
   - 如果有必要执行 → 放入 `F-Queue` 队列
2. 低优先级的 Finalizer 线程执行 `finalize()`，如果在 `finalize()` 中重新与引用链上的对象建立了关联 → "逃逸"成功，不被回收
3. 第二次标记：如果仍不可达 → 回收

**注意**：`finalize()` 只会被执行一次，不推荐使用（不确定性大、性能差）。JDK 9+ 已标记为 deprecated。

---

## 四、垃圾回收算法

**Q10：三种基本垃圾回收算法的原理和优缺点？**

A：

**1. 标记-清除（Mark-Sweep）**
- 过程：标记存活对象 → 清除未标记对象
- 优点：简单
- 缺点：产生**内存碎片**；标记和清除效率都不高

**2. 标记-整理（Mark-Compact）**
- 过程：标记存活对象 → 将存活对象向一端移动 → 清理边界外的空间
- 优点：没有碎片
- 缺点：移动对象需要更新引用，有 **STW** 开销

**3. 复制算法（Copying）**
- 过程：将内存分为两半，只用一半，GC 时将存活对象复制到另一半，清空当前半
- 优点：没有碎片，分配时只需移动指针
- 缺点：**内存利用率只有 50%**
- 优化：新生代 Eden:S0:S1 = 8:1:1，利用率 90%（因为新生代对象朝生夕灭，存活率低）

---

**Q11：为什么新生代用复制算法，老年代用标记-整理？**

A：
- **新生代**：对象存活率低（通常 <10%），复制算法只需复制少量存活对象，效率高
- **老年代**：对象存活率高，复制算法需要复制大量对象且浪费 50% 空间，不适合。标记-整理虽然慢，但没有碎片问题，适合长期存活的对象

---

## 五、垃圾收集器

**Q12：主流垃圾收集器有哪些？各自特点？**

A：
| 收集器 | 作用区域 | 算法 | 特点 |
|--------|---------|------|------|
| Serial | 新生代 | 复制 | 单线程，STW，适合客户端小应用 |
| ParNew | 新生代 | 复制 | Serial 的多线程版，配合 CMS 使用 |
| Parallel Scavenge | 新生代 | 复制 | 吞吐量优先，自适应调节策略 |
| Serial Old | 老年代 | 标记-整理 | 单线程 |
| Parallel Old | 老年代 | 标记-整理 | 配合 Parallel Scavenge |
| **CMS** | 老年代 | 标记-清除 | 低延迟优先，并发标记和清除 |
| **G1** | 全堆 | Region 化复制 + 标记-整理 | 可预测停顿时间，JDK 9 默认 |
| **ZGC** | 全堆 | 染色指针 + 读屏障 | 停顿 <1ms（JDK 15+） |

---

**Q13：CMS 收集器的工作流程和缺点？**

A：CMS（Concurrent Mark Sweep）以最短停顿时间为目标：

**四个阶段**：
1. **初始标记（STW）**：只标记 GC Roots 直接关联的对象，速度很快
2. **并发标记**：从 GC Roots 遍历整个对象图，**与用户线程并发执行**
3. **重新标记（STW）**：修正并发标记期间因用户线程继续运行产生的变动（增量更新）
4. **并发清除**：清除标记为垃圾的对象，**与用户线程并发执行**

**三大缺点**：
1. **CPU 敏感**：并发阶段占用 CPU 资源，默认回收线程数 = (CPU 数 + 3) / 4
2. **浮动垃圾**：并发清除阶段用户线程产生的新垃圾只能下次 GC 处理
3. **内存碎片**：标记-清除算法本身的问题，可能导致大对象无法分配而触发 Full GC

---

**Q14：G1 收集器的核心设计和工作流程？**

A：G1（Garbage First）的核心理念：将堆划分为大小相等的 **Region**（1-32MB），不再物理隔离新生代和老年代。

**核心概念**：
- **Region 类型**：Eden、Survivor、Old、Humongous（大对象，≥ Region 的 50%）
- **Remembered Set（RSet）**：每个 Region 维护一个 RSet，记录其他 Region 中有引用指向自己的指针，避免全堆扫描
- **Collection Set（CSet）**：每次 GC 选择回收价值最高的 Region 集合（Garbage First 名称由来）

**工作流程**：
1. **初始标记（STW）**：标记 GC Roots 直接关联的对象，修改 TAMS 指针
2. **并发标记**：从 GC Roots 做可达性分析，与用户线程并发
3. **最终标记（STW）**：处理 SATB（Snapshot-At-The-Beginning）记录的引用变更
4. **筛选回收（STW）**：按回收价值排序 Region，选择 CSet，复制存活对象到空 Region

**优势**：可通过 `-XX:MaxGCPauseMillis`（默认 200ms）设置停顿时间目标，G1 会智能选择回收的 Region 数量。

---

**Q15：G1 的 Mixed GC 是什么？什么条件触发？**

A：
- **Young GC**：只回收 Eden + Survivor Region
- **Mixed GC**：回收所有 Young Region + **部分** Old Region（回收价值高的）
- **触发条件**：并发标记完成后，老年代占用达到 `-XX:InitiatingHeapOccupancyPercent`（默认 45%）
- Mixed GC 会执行多次（`-XX:G1MixedGCCountTarget` 默认 8 次），逐步回收，避免一次性停顿太久
- 如果 Mixed GC 跟不上分配速度 → 退化为 Serial Old Full GC（要极力避免）

---

**Q16：ZGC 的核心原理是什么？为什么能做到亚毫秒停顿？**

A：ZGC 的两个核心技术：

1. **染色指针（Colored Pointers）**：在 64 位指针中利用高位存储 GC 元信息（Marked0、Marked1、Remapped、Finalizable），不需要额外的 Mark Bitmap
2. **读屏障（Load Barrier）**：在对象引用被加载时插入屏障代码，检查指针颜色，如果指向旧地址则自修复（转发到新地址）

**关键特性**：
- 几乎所有操作都与用户线程**并发执行**，包括对象的移动和引用的修复
- STW 仅限于 GC Roots 扫描（通常 <1ms）
- 支持 TB 级堆内存
- JDK 15 正式生产可用

---

**Q17：什么是 Safe Point 和 Safe Region？**

A：
- **Safe Point（安全点）**：JVM 在特定位置设置安全点（方法调用、循环跳转、异常跳转等），线程只有在安全点才能暂停进行 GC
  - GC 发起时，通过设置标志位通知所有线程在最近的安全点停下（主动式中断）
- **Safe Region（安全区域）**：线程处于 Sleep 或 Blocked 状态时无法走到安全点。安全区域是引用关系不会变化的代码片段，线程进入安全区域时标记自身，GC 可以直接忽略这些线程

---

## 六、GC 日志与调优基础

**Q18：Minor GC、Major GC、Full GC 的区别？**

A：
| 类型 | 回收区域 | 触发条件 | 停顿 |
|------|---------|---------|------|
| Minor GC（Young GC） | 新生代 | Eden 区满 | 较短（毫秒级） |
| Major GC | 老年代 | 老年代空间不足 | 较长 |
| Full GC | 整个堆 + 方法区 | 老年代不足、方法区不足、System.gc()、CMS 并发失败 | 最长（需要避免） |

**触发 Full GC 的常见原因**：
1. 老年代空间不足
2. 方法区/元空间不足
3. `System.gc()`（建议 `-XX:+DisableExplicitGC` 禁用）
4. CMS GC 期间老年代空间不足（Concurrent Mode Failure） → 退化 Serial Old
5. 新生代对象晋升时老年代放不下（Promotion Failed）

---

**Q19：对象什么时候会进入老年代？**

A：四种情况：
1. **年龄达到阈值**：每经历一次 Minor GC 存活，age + 1，达到 `-XX:MaxTenuringThreshold`（默认 15）晋升
2. **大对象直接进入**：超过 `-XX:PretenureSizeThreshold` 的对象直接在老年代分配（避免 Eden 和 Survivor 之间大量复制）
3. **动态年龄判断**：Survivor 中相同年龄所有对象大小之和 > Survivor 空间的 50%，则年龄 ≥ 该年龄的对象直接晋升
4. **空间担保失败**：Minor GC 后存活对象无法全部放入 Survivor，溢出的对象直接进入老年代

---

**Q20：什么是 STW（Stop-The-World）？为什么无法避免？**

A：
- STW 是指 GC 发生时，**所有用户线程暂停**，直到 GC 完成
- 无法完全避免的原因：
  1. GC Roots 枚举需要确保引用关系不变化（一致性快照）
  2. 对象移动需要更新所有引用（除非像 ZGC 用读屏障延迟修复）
- 优化方向：减少 STW 的时间和频率，而非消除它
  - CMS/G1：将大部分工作并发执行，STW 只做必要的标记/修正
  - ZGC：STW 仅限 GC Roots 扫描，<1ms
