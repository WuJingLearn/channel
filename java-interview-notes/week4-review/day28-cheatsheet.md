# Day 28: 面试速查表

> 面试前 30 分钟快速过一遍的核心知识点速查表
> 每个知识点控制在 1-2 行，帮助快速唤醒记忆

---

## 一、Java 集合

| 知识点 | 核心要点 |
|--------|---------|
| HashMap 结构 | 数组 + 链表/红黑树（链表 ≥8 且数组 ≥64 转红黑树），默认容量 16，负载因子 0.75 |
| HashMap put 流程 | hash(key) → 定位桶 → 空则直接放 → 有则比较 key → 链表/红黑树插入 → 超阈值扩容（2倍） |
| ConcurrentHashMap（JDK8） | Node数组 + CAS + synchronized（锁桶头节点），size 用 CounterCell 分段计数 |
| ArrayList vs LinkedList | ArrayList：数组，随机访问 O(1)，扩容 1.5 倍；LinkedList：双向链表，插入 O(1)，查找 O(n) |
| TreeMap | 红黑树实现，Key 有序，O(logN) 操作 |

## 二、Java 并发

| 知识点 | 核心要点 |
|--------|---------|
| synchronized 锁升级 | 无锁 → 偏向锁（单线程）→ 轻量级锁（CAS 自旋）→ 重量级锁（Monitor） |
| AQS 原理 | state（volatile int）+ CLH 双向队列，ReentrantLock/Semaphore/CountDownLatch 的基础 |
| volatile | 可见性（MESI）+ 禁止指令重排（内存屏障），不保证原子性 |
| ThreadPoolExecutor 参数 | core → 队列 → max → 拒绝策略；Abort/Discard/CallerRuns/DiscardOldest |
| ThreadLocal | Thread 内部 ThreadLocalMap，Key 是弱引用，用完必须 remove 防内存泄漏 |
| CAS | Compare And Swap，CPU 原子指令（cmpxchg），ABA 问题用 AtomicStampedReference |

## 三、JVM

| 知识点 | 核心要点 |
|--------|---------|
| 内存区域 | 堆（对象）、方法区/元空间（类信息）、虚拟机栈（栈帧）、本地方法栈、程序计数器 |
| GC 判断存活 | 可达性分析（GC Roots：栈引用、静态字段、JNI），不是引用计数 |
| GC 算法 | 标记-清除（碎片）、标记-整理（移动）、复制（浪费空间）、分代收集 |
| CMS | 老年代，标记-清除，4 阶段（初始标记STW → 并发标记 → 重新标记STW → 并发清除），碎片问题 |
| G1 | Region 分区，可预测停顿（-XX:MaxGCPauseMillis），Mixed GC 回收价值最高的 Region |
| ZGC | 着色指针 + 读屏障，停顿 < 1ms，支持 TB 级堆 |
| 类加载 | 加载→验证→准备→解析→初始化；双亲委派（安全+避免重复）；打破：SPI/OSGi/Tomcat |

## 四、MySQL

| 知识点 | 核心要点 |
|--------|---------|
| InnoDB 架构 | Buffer Pool（LRU）+ Redo Log（WAL）+ Undo Log（MVCC）+ Binlog（主从） |
| B+ 树索引 | 叶子节点存数据+双向链表，非叶子节点只存键+指针；聚簇索引 vs 二级索引（回表） |
| 索引失效 | 最左前缀、函数/计算、类型转换、LIKE '%xx'、OR 混用、!= / IS NOT NULL |
| MVCC | Read View（m_ids/min_trx_id/max_trx_id/creator_trx_id）+ Undo Log 版本链 |
| 锁体系 | 行锁（Record/Gap/Next-Key）、表锁、意向锁；RR 级别默认 Next-Key Lock 防幻读 |
| 死锁处理 | wait-for graph 检测，回滚代价最小事务；`innodb_deadlock_detect=ON` |
| 主从复制 | Binlog → Relay Log → SQL Thread 回放；半同步/GTID/并行复制 |

## 五、Redis

| 知识点 | 核心要点 |
|--------|---------|
| 数据结构底层 | String(SDS) / List(QuickList) / Hash(Listpack→HashTable) / Set(IntSet→HashTable) / ZSet(Listpack→SkipList+HashTable) |
| 持久化 | RDB（快照，fork子进程COW）、AOF（追加日志，重写压缩）；混合持久化 = RDB + 增量AOF |
| 线程模型 | 单线程事件循环处理命令，6.0+ 多线程处理网络IO，命令执行仍单线程 |
| 缓存三大问题 | 穿透（布隆过滤器/空值缓存）、击穿（互斥锁/永不过期）、雪崩（随机过期/多级缓存） |
| 分布式锁 | `SET key val NX EX` + Lua 原子释放 + 看门狗续期；RedLock 多节点 |
| Cluster | 16384 槽哈希分片，Gossip 协议，主从自动故障转移 |

## 六、消息队列

| 知识点 | 核心要点 |
|--------|---------|
| RocketMQ 架构 | NameServer（无状态注册中心）+ Broker（存储，主从同步）+ Producer/Consumer |
| Kafka 架构 | ZK/KRaft + Broker + Partition（分区有序）+ Consumer Group（rebalance） |
| 消息可靠性 | 生产端（同步发送+重试）→ 服务端（同步刷盘+多副本）→ 消费端（手动ACK+幂等） |
| 顺序消息 | 全局有序：单分区（性能差）；分区有序：相同 Key 路由到同一分区 |
| 消息积压 | 临时扩容消费者、跳过非关键消息、新建 Topic 迁移 |

## 七、分布式

| 知识点 | 核心要点 |
|--------|---------|
| CAP | 一致性 + 可用性 + 分区容忍性，三选二（P 必选）；CP(ZK) vs AP(Eureka) |
| BASE | 基本可用 + 软状态 + 最终一致性，CAP 的实际妥协 |
| Raft | Leader 选举（随机超时+多数票）+ 日志复制（多数确认后 commit） |
| 分布式事务 | 2PC/XA（强一致，阻塞）、TCC（预留-确认-取消）、SAGA（正向+补偿）、本地消息表 |
| Seata | AT（自动补偿，undo_log）、TCC（手动编码）、SAGA（状态机） |

## 八、微服务

| 知识点 | 核心要点 |
|--------|---------|
| 注册中心 | Nacos（AP+CP 可切换，推拉结合）；Eureka（AP，自我保护机制）；ZK（CP，临时节点） |
| 网关 | Spring Cloud Gateway：Predicate 路由 + Filter 链 + 限流/鉴权/灰度 |
| 限流熔断 | Sentinel：滑动窗口统计，令牌桶限流，熔断三态（Closed→Open→Half-Open） |
| 配置中心 | Nacos Config：长轮询推送，@RefreshScope 动态刷新 |
| 链路追踪 | SkyWalking：Java Agent 无侵入字节码增强，TraceId 全链路传递 |

## 九、系统设计

| 场景 | 核心方案 |
|------|---------|
| 秒杀 | 前端限流 → Nginx 限流 → Redis 预扣库存 → MQ 异步下单 → DB 最终扣减 |
| 短链 | 发号器/MurmurHash → Base62 编码 → 301 重定向 → 布隆过滤器判重 |
| 延迟队列 | Redis ZSet（score=执行时间）/ RocketMQ 延迟消息 / 时间轮 |
| 分库分表 | ShardingSphere，哈希取模（均匀）vs 范围分片（扫描友好），扩容翻倍迁移法 |

## 十、Spring

| 知识点 | 核心要点 |
|--------|---------|
| IoC refresh() | 13 步：核心是第 5 步（BeanFactoryPostProcessor 扫描注册 BD）和第 11 步（实例化单例 Bean） |
| Bean 生命周期 | 实例化 → 属性注入 → Aware → BPP.before（@PostConstruct）→ InitializingBean → init-method → BPP.after（AOP 代理） |
| 循环依赖 | 三级缓存：singletonObjects → earlySingletonObjects → singletonFactories；构造器注入不能解决 |
| AOP 代理 | SpringBoot 默认 CGLIB（proxyTargetClass=true），在 BPP.after 阶段创建 |
| 事务传播 | REQUIRED（默认，加入已有）、REQUIRES_NEW（新建独立）、NESTED（Savepoint） |
| 事务失效 | 非 public、自调用、异常被 catch、checked 异常、非 Spring 管理 |
| 自动装配 | @EnableAutoConfiguration → AutoConfigurationImportSelector → spring.factories → @Conditional 过滤 |

## 十一、网络

| 知识点 | 核心要点 |
|--------|---------|
| TCP 三次握手 | SYN → SYN+ACK → ACK；防止历史连接 + 同步序列号 |
| TCP 四次挥手 | FIN → ACK → FIN → ACK；TIME_WAIT 等待 2MSL |
| TCP 可靠性 | 序列号确认 + 超时重传 + 滑动窗口 + 拥塞控制（慢启动→拥塞避免→快速恢复） |
| HTTP/2 | 多路复用 + 二进制分帧 + HPACK 头压缩 + 服务器推送 |
| HTTPS | TLS 握手：非对称协商密钥 → 对称加密传输；证书链验证 + ECDHE 前向安全 |
| epoll | 红黑树 + 就绪链表 + 回调通知，O(1) 就绪查找；ET(边缘触发) vs LT(水平触发) |

## 十二、操作系统

| 知识点 | 核心要点 |
|--------|---------|
| 进程 vs 线程 | 进程：资源分配单位，独立地址空间；线程：调度单位，共享进程空间 |
| 虚拟内存 | 虚拟地址 → 页表(MMU) → 物理地址；缺页中断加载；TLB 缓存加速 |
| IO 模型 | BIO/NIO/IO多路复用/信号驱动/AIO；Java NIO 底层用 epoll（IO多路复用） |
| 零拷贝 | mmap（共享缓冲区）、sendfile（内核直传）；Java：FileChannel.transferTo() |
| 死锁条件 | 互斥 + 持有并等待 + 不可剥夺 + 循环等待；按序加锁 / tryLock 超时 |

---

## 面试当天 Checklist

- [ ] 准备好 2-3 个项目故事（STAR 格式，烂熟于心）
- [ ] 确认目标公司的技术栈（调整侧重点）
- [ ] 准备反问面试官的问题（团队、技术方向、成长空间）
- [ ] 过一遍本速查表（面试前 30 分钟）
- [ ] 准备纸笔（画图讲解架构设计）
