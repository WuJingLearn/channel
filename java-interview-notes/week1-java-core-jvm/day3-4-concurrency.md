# Day 3-4：Java 并发编程

> 复习目标：深入理解并发原语的底层原理，重点在机制而非 API 使用。

---

## 一、synchronized 与锁升级

**Q1：synchronized 的底层实现原理是什么？**

A：
- **对象级别**：基于对象头中的 Mark Word 和 Monitor（管程/监视器）
- 编译后生成 `monitorenter` / `monitorexit` 字节码指令（同步方法通过 ACC_SYNCHRONIZED 标志）
- Monitor 内部维护：
  - `_owner`：持有锁的线程
  - `_EntryList`：阻塞等待锁的线程队列
  - `_WaitSet`：调用 `wait()` 后进入等待的线程集合
- 线程进入 synchronized 块时尝试获取 Monitor 的所有权，获取失败则进入 EntryList 阻塞

**深入：未抢到锁的线程是如何变为阻塞状态的？**

从 JVM 到操作系统的完整阻塞链路：

```
monitorenter 字节码
  → JVM ObjectMonitor::enter()
    → CAS 自旋尝试获取锁（轻量级锁阶段）
    → 自旋失败，膨胀为重量级锁
      → ObjectMonitor::EnterI()  // 将线程封装为 ObjectWaiter 加入 _EntryList
        → os::PlatformEvent::park()  // JVM 平台抽象的线程挂起
          → pthread_cond_wait()      // POSIX 条件变量等待（用户态）
            → futex(FUTEX_WAIT)      // Linux 内核系统调用，线程真正阻塞
```

各层关键函数：

| 层级 | 关键函数 | 作用 |
|------|---------|------|
| JVM C++ | `ObjectMonitor::EnterI()` | 自旋失败后将线程加入 EntryList |
| JVM 平台层 | `os::PlatformEvent::park()` | 平台抽象的线程挂起 |
| 用户态（glibc） | `pthread_cond_wait()` | POSIX 条件变量等待 |
| 内核态（Linux） | `futex(FUTEX_WAIT)` | **真正让线程阻塞的系统调用**，线程进入 `TASK_INTERRUPTIBLE` 状态，从 CPU 运行队列移除 |

唤醒过程（反向）：锁释放 `monitorexit` → `ObjectMonitor::exit()` → `unpark()` → `pthread_cond_signal()` → `futex(FUTEX_WAKE)` → 线程重新进入 CPU 运行队列。

> 注意：`LockSupport.park()` 底层也走 `Unsafe.park()` → `os::PlatformEvent::park()` 同一路径，因此 `ReentrantLock` 的阻塞机制在操作系统层面与 `synchronized` 一致。这也是重量级锁开销大的根本原因——涉及用户态到内核态的上下文切换。

---

**Q2：synchronized 锁升级的过程是怎样的？**

A：JDK 1.6 引入锁升级（只能升级，不能降级）：

| 锁状态 | Mark Word 存储 | 触发条件 | 特点 |
|--------|---------------|---------|------|
| 无锁 | 对象 hashCode、GC 分代年龄 | 初始状态 | — |
| 偏向锁 | 线程 ID | 第一个线程访问同步块 | 无竞争时无 CAS 开销，只需检查线程 ID |
| 轻量级锁 | 指向栈中 Lock Record 的指针 | 出现第二个线程竞争（偏向锁撤销） | CAS 自旋获取锁，不阻塞线程 |
| 重量级锁 | 指向 Monitor 的指针 | 自旋次数过多或竞争激烈 | 阻塞线程，涉及内核态切换 |

**升级路径**：无锁 → 偏向锁 → 轻量级锁 → 重量级锁

注意：JDK 15 之后偏向锁默认关闭（`-XX:-UseBiasedLocking`），因为现代应用多为高并发场景，偏向锁撤销的开销反而更大。

**补充：Mark Word 是什么？**

Mark Word 是 Java 对象头（Object Header）的核心部分，用于存储对象的运行时元数据，是 synchronized 锁机制的物理载体。

对象在内存中的布局：
- **对象头**：Mark Word（锁状态、hashCode、GC 信息）+ Klass Pointer（指向 Class 元数据）+ 数组长度（仅数组对象）
- **实例数据**：对象的字段值
- **对齐填充**：补齐到 8 字节整数倍

Mark Word 在 64 位 JVM 下占 8 字节（64 bit），不同锁状态下存储内容完全不同：

| 锁状态 | Mark Word 内容 | 偏向标志 | 锁标志位 |
|--------|---------------|---------|---------|
| 无锁 | unused:25 \| hashCode:31 \| unused:1 \| GC年龄:4 | 0 | 01 |
| 偏向锁 | 线程ID:54 \| epoch:2 \| unused:1 \| GC年龄:4 | 1 | 01 |
| 轻量级锁 | 指向栈中 Lock Record 的指针:62 | — | 00 |
| 重量级锁 | 指向 ObjectMonitor 的指针:62 | — | 10 |
| GC 标记 | 空（GC 标记阶段使用） | — | 11 |

> 注意：hashCode 和偏向锁互斥——一旦对象调用了 `hashCode()`，Mark Word 中存储了 hashCode，就无法再进入偏向锁状态（空间被 hashCode 占用），会导致偏向锁撤销。

**各锁状态的具体示例：**

**① 无锁 → 偏向锁：单线程反复进入**

```java
Object lock = new Object();

// 线程 A 第一次进入
synchronized (lock) {
    // CAS 将线程 A 的 ID 写入 Mark Word，升级为偏向锁
    // Mark Word: [线程A的ID | epoch | GC年龄 | 偏向标志=1 | 锁标志=01]
    doSomething();
}

// 线程 A 第二次进入
synchronized (lock) {
    // 只需检查 Mark Word 中的线程 ID == 当前线程 ID
    // 匹配！直接进入，无需任何 CAS 操作 → 几乎零开销
    doSomethingElse();
}
```

**② 偏向锁 → 轻量级锁：两个线程交替进入（无同时竞争）**

```java
Object lock = new Object();

// 线程 A 进入（lock 处于偏向锁，偏向线程 A）
synchronized (lock) {
    doWork();  // 执行完毕，退出同步块
}

// 线程 B 尝试进入
synchronized (lock) {
    // 发现 Mark Word 中线程 ID 是 A，不是自己
    // 触发偏向锁撤销（需等到全局安全点 safepoint）
    // 升级为轻量级锁：
    //   1. 在线程 B 的栈帧中创建 Lock Record
    //   2. CAS 将 Mark Word 替换为指向 Lock Record 的指针
    //   3. CAS 成功 → 获取轻量级锁
    // Mark Word: [指向 Lock Record 的指针 | 锁标志=00]
    doOtherWork();
}
```

**③ 轻量级锁 → 重量级锁：多线程同时竞争**

```java
public class HeavyweightLockDemo {
    private static final Object lock = new Object();

    public static void main(String[] args) {
        for (int i = 0; i < 3; i++) {
            final int threadId = i;
            new Thread(() -> {
                synchronized (lock) {
                    // 线程 0 先拿到锁
                    // 线程 1、2 CAS 自旋尝试获取轻量级锁 → 失败
                    // 自旋次数超过阈值（JVM 自适应决定）
                    // → 锁膨胀为重量级锁
                    // Mark Word: [指向 ObjectMonitor 的指针 | 锁标志=10]
                    // 线程 1、2 进入 ObjectMonitor._EntryList 阻塞
                    // 底层：park() → pthread_cond_wait() → futex(FUTEX_WAIT)
                    System.out.println("Thread-" + threadId + " acquired lock");
                    try { Thread.sleep(100); } catch (InterruptedException e) {}
                }
            }).start();
        }
    }
}
```

> 面试加分点：JVM 的自适应自旋（Adaptive Spinning）会根据上一次自旋是否成功来动态调整自旋次数——如果上次自旋成功获取了锁，JVM 会允许更多的自旋次数；反之则减少自旋甚至直接膨胀为重量级锁。

---

**Q3：synchronized 和 ReentrantLock 有什么区别？**

A：
| 维度 | synchronized | ReentrantLock |
|------|-------------|---------------|
| 实现层次 | JVM 内置关键字 | JDK API 层面（java.util.concurrent） |
| 锁释放 | 自动释放（退出同步块/异常） | 必须手动 `unlock()`（建议放 finally） |
| 可中断 | 不可中断等待 | `lockInterruptibly()` 支持中断 |
| 公平锁 | 仅非公平 | 可选公平/非公平 |
| 条件变量 | 单一 wait/notify | 多个 Condition（精确唤醒） |
| 尝试获取锁 | 不支持 | `tryLock()` / `tryLock(timeout)` |
| 性能 | JDK 1.6 后优化很大，差距不明显 | 高竞争场景下略优 |

**深入：ReentrantLock.lock() 获取锁的完整流程**

`ReentrantLock` 没有锁升级的概念，但 `lock()` 过程也体现了**从轻到重的获取策略**——先 CAS 快速尝试，失败后入 CLH 队列，最终 `park()` 阻塞。

以非公平锁（默认）为例，完整调用链路：

```
ReentrantLock.lock()
  → NonfairSync.lock()
    → ① CAS 尝试直接获取锁（快速路径）
    → 失败 → AQS.acquire(1)
      → ② tryAcquire()：再次 CAS 尝试 + 检查重入
      → 失败 → ③ addWaiter()：封装为 Node 加入 CLH 等待队列
        → ④ acquireQueued()：自旋 + park 阻塞 为什么要自旋：唤醒不表示一定能获取锁，非公平锁下可能会被一个新线程插队。
          → LockSupport.park()
            → Unsafe.park()
              → os::PlatformEvent::park()  // 和 synchronized 底层一致
                → pthread_cond_wait() → futex(FUTEX_WAIT)
```

**① 第一次 CAS（快速路径）**

```java
// NonfairSync.lock()
final void lock() {
    if (compareAndSetState(0, 1))  // CAS: state 从 0 → 1
        setExclusiveOwnerThread(Thread.currentThread());  // 成功！设置持有线程
    else
        acquire(1);  // 失败，进入 AQS 流程
}
```

`state` 是 AQS 的核心变量（`volatile int`），0 表示无锁，≥1 表示已被持有。CAS 成功则一次原子操作就拿到锁。

**② tryAcquire()（再次尝试 + 重入检查）**

```java
final boolean nonfairTryAcquire(int acquires) {
    Thread current = Thread.currentThread();
    int currentState = getState();
    if (currentState == 0) {
        if (compareAndSetState(0, acquires)) {  // 锁刚好被释放，再次 CAS
            setExclusiveOwnerThread(current);
            return true;
        }
    } else if (current == getExclusiveOwnerThread()) {
        int nextState = currentState + acquires;  // 重入！state + 1
        setState(nextState);
        return true;
    }
    return false;
}
```

**③ addWaiter()（入队）**

将当前线程封装为 Node，CAS 加入 CLH 双向链表队列尾部。

**④ acquireQueued()（自旋 + 阻塞）**

```java
final boolean acquireQueued(final Node node, int arg) {
    boolean interrupted = false;
    for (;;) {  // 关键循环
        final Node predecessor = node.predecessor();
        if (predecessor == head && tryAcquire(arg)) {
            // 前驱是 head，轮到自己了，尝试获取锁
            setHead(node);
            predecessor.next = null; // help GC
            return interrupted;
        }
        if (shouldParkAfterFailedAcquire(predecessor, node))
            interrupted |= parkAndCheckInterrupt();  // park() 阻塞
        // 被 unpark() 唤醒后，回到 for 循环顶部重新竞争
    }
}
```

`for(;;)` 循环承担三重职责：

| 循环目的 | 说明 |
|---------|------|
| 唤醒后重新竞争 | `unpark()` 唤醒 ≠ 获取锁，非公平锁下可能被新线程插队，需要重试 |
| 前驱状态设置 | `shouldParkAfterFailedAcquire` 需要多轮将前驱设为 SIGNAL 才能安全 park |
| 中断恢复 | 被 `interrupt()` 唤醒后不响应中断，继续循环尝试获取锁 |

一次典型的执行流程：
- **第 1 轮**：前驱 waitStatus=0 → CAS 设为 SIGNAL → 返回 false，不 park，继续循环
- **第 2 轮**：前驱 waitStatus=SIGNAL → 返回 true → `park()` 阻塞 💤
- **第 3 轮**（被唤醒后）：前驱是 head → `tryAcquire()` 成功 → 获取锁 ✅

> 总结：synchronized 的锁升级在 JVM 内部自动完成（修改 Mark Word），而 ReentrantLock 的从轻到重策略在 Java 代码层面（AQS）显式实现。两者在操作系统层面的阻塞机制一致，都通过 `futex` 系统调用进入内核态。ReentrantLock 更灵活（支持公平锁、可中断、tryLock），synchronized 更简洁（自动释放、无需 try-finally）。

---

## 二、AQS（AbstractQueuedSynchronizer）

**Q4：AQS 的核心原理是什么？**

A：AQS 是 JUC 锁和同步器的**基础框架**，核心三要素：
1. **state**（volatile int）：表示同步状态
   - ReentrantLock：0 = 未锁定，≥ 1 = 锁定（可重入计数）
   - Semaphore：表示可用许可数
   - CountDownLatch：表示剩余计数
2. **CLH 变体队列**：FIFO 双向链表，存放等待获取锁的线程节点（Node）
3. **独占/共享模式**：
   - 独占（Exclusive）：如 ReentrantLock，同一时刻只有一个线程持有锁
   - 共享（Shared）：如 Semaphore、CountDownLatch，允许多个线程同时获取

**获取锁流程**（以独占锁为例）：
1. 调用 `tryAcquire()`（子类实现）尝试获取锁
2. 失败则将当前线程封装为 Node 加入 CLH 队列尾部
3. 在队列中自旋，只有前驱节点是 head 时才尝试再次获取锁
4. 获取失败则通过 `LockSupport.park()` 挂起线程

---

**Q5：ReentrantLock 的公平锁和非公平锁有什么区别？**

A：
- **非公平锁**（默认）：线程获取锁时先直接 CAS 尝试，失败才入队。新线程可能"插队"抢到锁
  - 优点：吞吐量高（减少线程切换）
  - 缺点：可能导致线程饥饿
- **公平锁**：`tryAcquire()` 时先调用 `hasQueuedPredecessors()` 检查队列中是否有更早的等待者，有则乖乖入队
  - 优点：公平，不会饥饿
  - 缺点：吞吐量低（每次都要检查队列）

实际生产中绝大多数场景用非公平锁。

---

**Q6：基于 AQS 实现的常见同步工具有哪些？**

A：
| 工具 | state 含义 | 模式 |
|------|-----------|------|
| ReentrantLock | 锁重入计数 | 独占 |
| ReentrantReadWriteLock | 高 16 位读锁计数，低 16 位写锁计数 | 共享读 + 独占写 |
| Semaphore | 可用许可数 | 共享 |
| CountDownLatch | 剩余计数 | 共享 |
| CyclicBarrier | 基于 ReentrantLock + Condition 实现 | — |

---

## 三、volatile 与内存模型

**Q7：volatile 的两个语义是什么？底层如何实现？**

A：
1. **可见性**：对 volatile 变量的写立即刷新到主存，读直接从主存读取（不使用工作内存缓存）
2. **有序性**：禁止指令重排序

底层实现：
- 写操作前插入 **StoreStore 屏障**，写操作后插入 **StoreLoad 屏障**
- 读操作前插入 **LoadLoad 屏障**，读操作后插入 **LoadStore 屏障**
- 在 x86 架构上，写 volatile 变量会加 **lock 前缀指令**，触发缓存一致性协议（MESI）

**注意**：volatile 不保证原子性。`volatile int count; count++;` 不是原子操作（读-改-写三步），需要 AtomicInteger 或 synchronized。

---

**Q8：happens-before 规则有哪些？**

A：JMM 定义的 8 条 happens-before 规则：
1. **程序顺序规则**：同一线程中，前面的操作 happens-before 后面的操作
2. **Monitor 锁规则**：unlock happens-before 后续的 lock
3. **volatile 规则**：volatile 写 happens-before 后续的 volatile 读
4. **线程启动规则**：Thread.start() happens-before 线程中的每一个操作
5. **线程终止规则**：线程中所有操作 happens-before Thread.join() 返回
6. **中断规则**：interrupt() 调用 happens-before 被中断线程检测到中断
7. **终结器规则**：构造函数完成 happens-before finalize() 开始
8. **传递性**：A happens-before B，B happens-before C，则 A happens-before C

---

**Q9：什么是指令重排序？volatile 如何防止 DCL 单例的问题？**

A：
- 编译器和 CPU 为提升性能会对指令进行重排序（编译器重排 → CPU 指令重排 → 内存系统重排）
- DCL（Double-Check Locking）问题：`instance = new Singleton()` 实际上分三步：
  1. 分配内存空间
  2. 初始化对象
  3. 将引用指向内存地址
- 步骤 2 和 3 可能被重排为 1→3→2。另一个线程可能在 3 之后 2 之前看到非 null 的 instance，拿到未初始化的对象
- 加 `volatile` 后禁止重排序，保证 2 在 3 之前完成

---

## 四、线程池

**Q10：ThreadPoolExecutor 的 7 个核心参数是什么？**

A：
```java
public ThreadPoolExecutor(
    int corePoolSize,           // 核心线程数（即使空闲也不回收，除非设了 allowCoreThreadTimeOut）
    int maximumPoolSize,        // 最大线程数
    long keepAliveTime,         // 非核心线程空闲存活时间
    TimeUnit unit,              // 存活时间单位
    BlockingQueue<Runnable> workQueue,  // 任务等待队列
    ThreadFactory threadFactory,        // 线程工厂（通常用来命名线程）
    RejectedExecutionHandler handler   // 拒绝策略
)
```

---

**Q11：线程池的任务执行流程是怎样的？**

A：
1. 提交任务时，如果当前线程数 < corePoolSize → 创建核心线程执行
2. 如果当前线程数 ≥ corePoolSize → 放入 workQueue 排队
3. 如果 workQueue 满了且线程数 < maximumPoolSize → 创建非核心线程执行
4. 如果线程数 ≥ maximumPoolSize 且队列满 → 执行拒绝策略

**注意执行顺序**：先核心线程 → 再队列 → 再非核心线程 → 最后拒绝。队列优先于非核心线程。

---

**Q12：线程池的 4 种拒绝策略是什么？**

A：
| 策略 | 行为 | 适用场景 |
|------|------|---------|
| AbortPolicy（默认） | 抛 RejectedExecutionException | 需要感知任务丢失 |
| CallerRunsPolicy | 由提交任务的线程自己执行 | 不想丢任务，可接受降速 |
| DiscardPolicy | 静默丢弃新任务 | 无关紧要的任务 |
| DiscardOldestPolicy | 丢弃队列最旧的任务，再提交新任务 | 新任务优先级更高 |

生产建议：不推荐用默认的 AbortPolicy 不做任何处理，应自定义拒绝策略记录日志 + 告警。

---

**Q13：为什么阿里规约不建议用 Executors 创建线程池？**

A：
| 方法 | 问题 |
|------|------|
| `newFixedThreadPool` | 使用 `LinkedBlockingQueue`（无界队列），任务堆积可能导致 OOM |
| `newSingleThreadExecutor` | 同上，无界队列 |
| `newCachedThreadPool` | maximumPoolSize = Integer.MAX_VALUE，可能创建大量线程导致 OOM |
| `newScheduledThreadPool` | 使用 `DelayedWorkQueue`（无界），同样可能 OOM |

应手动创建 ThreadPoolExecutor 并明确指定队列容量和拒绝策略。

---

**Q14：线程池的状态有哪些？如何转换？**

A：线程池用 `ctl`（AtomicInteger）的高 3 位表示状态，低 29 位表示线程数：
```
RUNNING    (-1 << 29) → 接受新任务，处理队列任务
SHUTDOWN   ( 0 << 29) → 不接受新任务，继续处理队列中的任务
STOP       ( 1 << 29) → 不接受新任务，不处理队列任务，中断正在执行的任务
TIDYING    ( 2 << 29) → 所有任务完成，workerCount 为 0
TERMINATED ( 3 << 29) → terminated() 方法执行完毕
```
转换：RUNNING → (shutdown()) → SHUTDOWN → TIDYING → TERMINATED
     RUNNING → (shutdownNow()) → STOP → TIDYING → TERMINATED

---

## 五、ThreadLocal

**Q15：ThreadLocal 的底层实现原理是什么？**

A：
- 每个 Thread 对象内部持有一个 `ThreadLocalMap`（threadLocals 字段）
- ThreadLocalMap 是一个自定义的哈希表，Entry 继承自 `WeakReference<ThreadLocal<?>>`
- 调用 `threadLocal.set(value)` 时：获取当前线程的 ThreadLocalMap，以 ThreadLocal 实例本身为 key，存入 value
- 调用 `threadLocal.get()` 时：同理，从当前线程的 Map 中取值

**结构**：Thread → ThreadLocalMap → Entry[](key = ThreadLocal 弱引用, value = 实际值)

---

**Q16：ThreadLocal 为什么会内存泄漏？如何避免？**

A：
- Entry 的 key 是 ThreadLocal 的**弱引用**，当外部不再强引用 ThreadLocal 时，GC 会回收 key
- 但 Entry 的 value 是**强引用**，key 被回收后变成 null，value 仍然存在，无法被访问也无法被回收 → **内存泄漏**
- 尤其在线程池场景下，线程长期存活，泄漏的 value 会持续累积

**避免方式**：使用完毕后务必调用 `threadLocal.remove()` 清理。ThreadLocalMap 自身也有一些清理机制（在 set/get/remove 时会探测性地清理 key 为 null 的 Entry），但不能完全依赖。

---

## 六、CompletableFuture

**Q17：CompletableFuture 相比 Future 有什么优势？**

A：
| 维度 | Future | CompletableFuture |
|------|--------|-------------------|
| 获取结果 | `get()` 阻塞 | 回调式（`thenApply`, `thenAccept`, `whenComplete`） |
| 组合操作 | 不支持 | `thenCompose`（串行）、`thenCombine`（并行合并） |
| 异常处理 | 只能 try-catch get() | `exceptionally()`、`handle()` |
| 多任务 | 不支持 | `allOf()`（全部完成）、`anyOf()`（任一完成） |
| 主动完成 | 不支持 | `complete()`、`completeExceptionally()` |

---

**Q18：CompletableFuture 的默认线程池是什么？生产中应该注意什么？**

A：
- 默认使用 `ForkJoinPool.commonPool()`，线程数 = CPU 核心数 - 1
- **生产建议**：不要使用默认线程池，应传入自定义线程池。原因：
  1. 所有 CompletableFuture 共享 commonPool，互相影响
  2. IO 密集型任务用 CPU 核心数的线程池会导致线程不够用
  3. 线程没有业务命名，排查问题困难

```java
ExecutorService bizPool = new ThreadPoolExecutor(10, 20, 60, TimeUnit.SECONDS,
    new LinkedBlockingQueue<>(1000), new ThreadFactory() { /* 自定义命名 */ });
CompletableFuture.supplyAsync(() -> doSomething(), bizPool);
```

---

## 七、其他并发工具

**Q19：CountDownLatch 和 CyclicBarrier 的区别？**

A：
| 维度 | CountDownLatch | CyclicBarrier |
|------|---------------|---------------|
| 核心方法 | `countDown()` + `await()` | `await()` |
| 计数方向 | 递减到 0 | 递增到 parties |
| 可否重用 | 不可（一次性） | 可以（`reset()` 或自动重置） |
| 回调 | 无 | 可指定 barrierAction（最后一个到达的线程执行） |
| 典型场景 | 主线程等待多个子任务完成 | 多个线程互相等待到齐后再同时执行 |

---

**Q20：Semaphore 的原理和使用场景？**

A：
- 基于 AQS 共享模式实现，`state` 表示可用许可数
- `acquire()`：state 减 1，如果 state < 0 则线程入队等待
- `release()`：state 加 1，唤醒队列中等待的线程
- **使用场景**：限流（如限制数据库连接池并发数、接口限流）

---

**Q21：CAS 的原理是什么？有什么问题？**

A：
- CAS（Compare And Swap）：比较并交换。原子操作：如果当前值 == 期望值，则设置为新值，否则不操作
- 底层依赖 CPU 指令（如 x86 的 `CMPXCHG` + `LOCK` 前缀）
- Java 中通过 `Unsafe` 类实现

**三个问题(注意事项)**：
1. **ABA 问题**：值从 A → B → A，CAS 认为没变。解决：`AtomicStampedReference`（带版本号）
2. **自旋开销**：竞争激烈时大量线程自旋消耗 CPU
3. **只能保证单个变量的原子性**：多变量操作需要加锁或用 `AtomicReference` 封装

---

**Q22：LongAdder 为什么比 AtomicLong 性能好？**

A：
- AtomicLong：所有线程 CAS 竞争同一个 `value`，高并发时自旋严重
- LongAdder：采用**分散热点**思路：
  - 维护 `base`（无竞争时 CAS 更新）+ `Cell[]` 数组
  - 有竞争时，不同线程通过 hash 映射到不同 Cell，各自 CAS 更新自己的 Cell
  - `sum()` = base + 所有 Cell 之和
  - 减少了 CAS 冲突，吞吐量大幅提升
- **取舍**：LongAdder 在 `sum()` 时无锁，返回的是近似值（非强一致），适合计数器场景

---

**Q23：什么是 Java 的内存模型（JMM）？**

A：
- JMM 是一种**规范**，定义了多线程环境下共享变量的访问规则
- 核心概念：
  - 主内存（Main Memory）：所有线程共享的变量存储区域
  - 工作内存（Working Memory）：每个线程的私有副本
  - 线程读写变量需要先从主内存拷贝到工作内存，修改后刷回主内存
- JMM 定义了 8 种原子操作：lock、unlock、read、load、use、assign、store、write
- 通过 happens-before 规则保证可见性和有序性

**深入理解 JMM（面试回答）：**

**JMM 是什么**：JMM（Java Memory Model）是 Java 语言规范（JSR-133）中定义的一套抽象模型，规定了多线程环境下共享变量的读写行为。它不是具体的内存区域划分（不是堆/栈），而是对底层硬件内存架构（CPU 多级缓存、Store Buffer）的抽象。

**为什么需要 JMM**：现代 CPU 为了弥补与主内存之间约 100 倍的速度差距（CPU 缓存 ~1ns vs 主内存 ~100ns），引入了 L1/L2/L3 多级缓存。每个 CPU 核心有私有的 L1/L2 缓存，线程对变量的读写实际在 CPU 缓存中进行。这带来了性能提升，但也引入了三个并发问题：

| 问题 | 根因 | 示例 | JMM 解决手段 |
|------|------|------|-------------|
| **可见性** | CPU 多级缓存，各线程读自己缓存中的副本 | 线程 A 修改 flag=true，线程 B 看不到 | `volatile`（强制刷缓存）、`synchronized`（unlock 时刷缓存） |
| **有序性** | 编译器/CPU 指令重排序 | DCL 单例中 new 操作被重排，读到未初始化对象 | `volatile`（内存屏障禁止重排序）、happens-before 规则 |
| **原子性** | 线程切换导致操作被打断 | 两个线程同时 i++，结果只增加 1 | `synchronized`、`Lock`、`Atomic` 原子类 |

**JMM 的核心机制——happens-before 规则**：定义了哪些操作之间存在可见性保证：
- **程序顺序规则**：同一线程中，前面的操作 happens-before 后面的操作
- **volatile 规则**：volatile 写 happens-before 后续的 volatile 读
- **锁规则**：unlock happens-before 后续对同一把锁的 lock
- **线程启动规则**：`Thread.start()` happens-before 该线程的所有操作
- **线程终止规则**：线程的所有操作 happens-before `Thread.join()` 返回
- **传递性**：A happens-before B，B happens-before C → A happens-before C

> 总结：JMM 本质上是在"CPU 缓存带来的性能提升"和"多线程数据一致性"之间做平衡。它默认允许使用缓存，只在程序员通过 volatile、synchronized 等关键字显式声明时，才强制保证可见性和有序性，既保留了性能优势，又在需要的地方保证了线程安全。

---

**Q25：ReentrantReadWriteLock 的原理？什么场景下用？**

A：
- 基于 AQS，用 state 的高 16 位表示读锁计数，低 16 位表示写锁计数
- 读读共享、读写互斥、写写互斥
- 支持锁降级：持有写锁时可以获取读锁，然后释放写锁（写锁降级为读锁）
- 不支持锁升级：持有读锁时不能获取写锁（会死锁）
- **适用场景**：读多写少（如缓存）。注意：如果读操作非常短，synchronized 的锁升级可能更快（省去了 AQS 的队列维护开销）
