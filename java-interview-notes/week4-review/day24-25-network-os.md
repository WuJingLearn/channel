# Day 24-25: 网络与操作系统

> 复习重点：TCP/IP 协议栈、HTTP/HTTPS、网络编程模型、进程/线程/协程、内存管理、IO 模型
> 面试定位：资深后端工程师必考的计算机基础，重点在网络协议和 Linux IO 模型

---

## 一、网络协议

### Q1: TCP 三次握手和四次挥手的详细过程？为什么握手三次、挥手四次？

**A：**

**三次握手**：
```
Client                    Server
  |--- SYN(seq=x) -------->|     Client → SYN_SENT
  |<-- SYN+ACK(seq=y,ack=x+1) --|  Server → SYN_RCVD
  |--- ACK(ack=y+1) ------>|     双方 → ESTABLISHED
```

**四次挥手**：
```
Client                    Server
  |--- FIN(seq=u) -------->|     Client → FIN_WAIT_1
  |<-- ACK(ack=u+1) -------|     Client → FIN_WAIT_2, Server → CLOSE_WAIT
  |                        |     (Server 可继续发送数据)
  |<-- FIN(seq=w) ---------|     Server → LAST_ACK
  |--- ACK(ack=w+1) ------>|     Client → TIME_WAIT, Server → CLOSED
  |   (等待 2MSL)          |     Client → CLOSED
```

**为什么三次握手**：防止历史连接（已失效的 SYN）导致 Server 错误建立连接，同时同步双方的初始序列号。两次不够（Server 无法确认 Client 收到了 SYN+ACK），四次多余（第三次 ACK 已足够）。

**为什么四次挥手**：TCP 是全双工，关闭需要双方各自关闭。收到 FIN 只表示对方不再发送数据，但自己可能还有数据要发，所以 ACK 和 FIN 不能合并（但如果没有剩余数据，也可以合并为三次）。

**TIME_WAIT 存在的意义**：
1. 确保最后的 ACK 能到达对方（如果丢失，对方会重发 FIN）
2. 等待网络中该连接的残留报文消失（2MSL），避免新连接收到旧数据

---

### Q2: TCP 如何保证可靠传输？

**A：**

| 机制 | 说明 |
|------|------|
| **序列号 + 确认号** | 每个字节编号，接收方 ACK 确认收到的连续字节 |
| **超时重传** | 发送后启动定时器，超时未收到 ACK 则重传（RTO 动态计算） |
| **快速重传** | 收到 3 个重复 ACK，立即重传丢失的报文（不等超时） |
| **滑动窗口** | 接收方通告窗口大小（rwnd），控制发送速率 |
| **拥塞控制** | 慢启动 → 拥塞避免 → 快速恢复，维护拥塞窗口（cwnd） |
| **校验和** | 检测传输错误 |
| **有序交付** | 按序列号重组乱序报文 |

**拥塞控制四阶段**：
```
1. 慢启动（Slow Start）：cwnd 指数增长（1→2→4→8），达到 ssthresh 进入拥塞避免
2. 拥塞避免（Congestion Avoidance）：cwnd 线性增长（每 RTT +1）
3. 快速重传（Fast Retransmit）：3 个重复 ACK 触发
4. 快速恢复（Fast Recovery）：ssthresh = cwnd/2，cwnd = ssthresh + 3，进入拥塞避免
```

---

### Q3: TCP 粘包/拆包的原因和解决方案？

**A：**

**原因**：TCP 是字节流协议，没有消息边界。
- **粘包**：多个小包合并发送（Nagle 算法）或接收方读取不及时
- **拆包**：数据大于 MSS/发送缓冲区，被拆分发送

**解决方案**：

| 方案 | 实现方式 | 典型协议 |
|------|---------|---------|
| 固定长度 | 每个消息固定 N 字节 | 简单但浪费空间 |
| 分隔符 | 用特殊字符（如 `\r\n`）分隔消息 | HTTP/1.1 Header |
| 长度前缀 | 消息头标明 body 长度 | HTTP Content-Length、Dubbo |
| 自定义协议 | 魔数 + 版本 + 长度 + 数据 | RocketMQ、Netty 自定义协议 |

**Netty 中的解码器**：
- `FixedLengthFrameDecoder` — 固定长度
- `DelimiterBasedFrameDecoder` — 分隔符
- `LengthFieldBasedFrameDecoder` — 长度字段（最常用）

---

### Q4: HTTP/1.0、HTTP/1.1、HTTP/2、HTTP/3 的核心区别？

**A：**

| 特性 | HTTP/1.0 | HTTP/1.1 | HTTP/2 | HTTP/3 |
|------|----------|----------|--------|--------|
| 连接方式 | 短连接 | 持久连接（Keep-Alive） | 多路复用 | 多路复用 |
| 传输方式 | 文本 | 文本 | 二进制帧 | 二进制帧 |
| 头部压缩 | 无 | 无 | HPACK | QPACK |
| 队头阻塞 | 有 | 有（管道化仍有） | HTTP 层消除，TCP 层仍有 | 完全消除 |
| 服务器推送 | 无 | 无 | 支持 | 支持 |
| 传输层 | TCP | TCP | TCP | **QUIC（UDP）** |
| 握手延迟 | 1-RTT | 1-RTT（复用连接0） | 1-RTT | **0-RTT / 1-RTT** |

**HTTP/2 核心改进**：
- **多路复用**：一个 TCP 连接上并行传输多个请求/响应（Stream），解决应用层队头阻塞
- **二进制分帧**：将消息拆分为帧（HEADERS帧、DATA帧），可交错传输
- **头部压缩（HPACK）**：静态表 + 动态表 + 哈夫曼编码

**HTTP/3 为什么用 QUIC（基于 UDP）**：TCP 层的队头阻塞无法在应用层解决。一个 TCP 连接中，一个包丢失会阻塞所有 Stream。QUIC 在 UDP 上实现了独立的 Stream，丢包只影响单个 Stream。

---

### Q5: HTTPS 的 TLS 握手过程？如何保证安全？

**A：**

**TLS 1.2 握手（2-RTT）**：
```
Client                              Server
  |--- ClientHello ----------------->|  (支持的密码套件、随机数 Client Random)
  |<-- ServerHello ------------------|  (选定密码套件、随机数 Server Random)
  |<-- Certificate ------------------|  (服务器证书)
  |<-- ServerKeyExchange ------------|  (DH 参数，用于密钥交换)
  |<-- ServerHelloDone --------------|
  |--- ClientKeyExchange ----------->|  (客户端 DH 参数)
  |--- ChangeCipherSpec ------------>|  (切换为加密通信)
  |--- Finished -------------------->|
  |<-- ChangeCipherSpec -------------|
  |<-- Finished ---------------------|
```

**安全保障**：

| 安全目标 | 实现机制 |
|---------|---------|
| 机密性 | 对称加密（AES-GCM），密钥通过 ECDHE 协商 |
| 完整性 | HMAC / AEAD 校验 |
| 认证 | 数字证书（CA 签名链验证） |
| 前向安全 | ECDHE（每次会话独立密钥，泄露私钥不影响历史会话） |

**TLS 1.3 改进**：握手缩短到 1-RTT（合并步骤），移除不安全的密码套件（RC4、SHA-1、RSA 密钥交换），支持 0-RTT 恢复。

---

### Q6: DNS 解析过程？如何优化 DNS 性能？

**A：**

**递归 + 迭代查询过程**：
```
浏览器 DNS 缓存 → OS DNS 缓存 → hosts 文件
    ↓ (未命中)
本地 DNS 服务器（递归解析器）
    ↓ (迭代查询)
根域名服务器 → 返回 .com 顶级域名服务器地址
    ↓
.com 顶级域名服务器 → 返回 example.com 权威域名服务器地址
    ↓
example.com 权威域名服务器 → 返回 IP 地址
    ↓
本地 DNS 服务器缓存结果 → 返回给客户端
```

**优化策略**：
- **DNS 预解析**：`<link rel="dns-prefetch" href="//cdn.example.com">`
- **HTTPDNS**：绕过 Local DNS，防劫持（阿里/腾讯 HTTPDNS 服务）
- **DNS 缓存**：合理设置 TTL
- **就近接入**：CDN + 智能 DNS 调度

---

### Q7: 从浏览器输入 URL 到页面展示，完整的网络请求过程？

**A：**

```
1. URL 解析 → 提取协议、域名、端口、路径
2. DNS 解析 → 域名 → IP 地址（见 Q6）
3. TCP 三次握手 → 建立连接（见 Q1）
4. TLS 握手（HTTPS）→ 协商密钥（见 Q5）
5. 发送 HTTP 请求 → 构建请求报文
6. 服务器处理
   ├─ 负载均衡（Nginx/LVS）→ 选择后端服务器
   ├─ Web 服务器（Nginx）→ 静态资源直接返回 / 反向代理到应用服务器
   ├─ 应用服务器（Tomcat）→ DispatcherServlet → Controller → Service → DAO
   └─ 查询数据库/缓存 → 构建响应
7. 返回 HTTP 响应 → 状态码 + 响应头 + 响应体
8. 浏览器解析渲染
   ├─ 解析 HTML → DOM 树
   ├─ 解析 CSS → CSSOM 树
   ├─ 合并 → 渲染树 → 布局 → 绘制
   └─ 执行 JavaScript（可能修改 DOM）
9. 四次挥手 → 关闭连接（或 Keep-Alive 复用）
```

---

## 二、网络编程模型

### Q8: BIO、NIO、AIO 的区别？Java 中如何实现？

**A：**

| 对比项 | BIO | NIO | AIO |
|--------|-----|-----|-----|
| 全称 | Blocking I/O | Non-blocking I/O | Asynchronous I/O |
| 模型 | 同步阻塞 | 同步非阻塞（多路复用） | 异步非阻塞 |
| 线程模型 | 一连接一线程 | 一个线程管理多个连接 | 回调通知 |
| 核心 API | `ServerSocket` | `Selector` + `Channel` + `Buffer` | `AsynchronousSocketChannel` |
| 适用场景 | 连接数少、延迟低 | 高并发连接（如 Netty） | 文件 IO（网络 IO Linux 不支持真正 AIO） |
| OS 支持 | 所有 | `select/poll/epoll`(Linux) / `kqueue`(Mac) | `io_uring`(Linux 5.1+) / IOCP(Windows) |

**NIO 核心三件套**：
- **Buffer**：数据容器（ByteBuffer），`flip()` 切换读写模式
- **Channel**：双向通道（SocketChannel、FileChannel）
- **Selector**：多路复用器，一个线程监听多个 Channel 的事件（ACCEPT、READ、WRITE）

---

### Q9: select、poll、epoll 的区别？为什么 epoll 性能最好？

**A：**

| 对比项 | select | poll | epoll |
|--------|--------|------|-------|
| 数据结构 | fd_set（位图） | pollfd 链表 | 红黑树 + 就绪链表 |
| 最大连接数 | 1024（FD_SETSIZE） | 无限制 | 无限制 |
| fd 拷贝 | 每次调用全量拷贝到内核 | 每次调用全量拷贝 | `epoll_ctl` 增量注册，无需重复拷贝 |
| 就绪查找 | O(n) 遍历所有 fd | O(n) 遍历所有 fd | O(1) 直接返回就绪链表 |
| 触发模式 | 水平触发（LT） | 水平触发（LT） | 支持 LT 和 **边缘触发（ET）** |

**epoll 高性能原因**：
1. `epoll_ctl` 注册 fd 到内核红黑树，无需每次拷贝
2. 内核通过回调机制将就绪 fd 加入就绪链表，`epoll_wait` 直接返回
3. ET 模式减少 `epoll_wait` 调用次数（只在状态变化时通知）

**水平触发 vs 边缘触发**：
- LT：只要 fd 就绪就一直通知（不读完数据，下次 epoll_wait 还会返回）
- ET：仅在状态变化时通知一次（必须一次性读完所有数据，否则丢失通知）

---

### Q10: Netty 的线程模型？Reactor 模式的几种形式？

**A：**

**Reactor 三种模式**：

| 模式 | 描述 | 适用场景 |
|------|------|---------|
| 单 Reactor 单线程 | 一个线程处理所有事件 | 小型应用、Redis 6.0 前 |
| 单 Reactor 多线程 | 一个线程接收连接，线程池处理业务 | 中等并发 |
| **主从 Reactor**（Netty 默认） | BossGroup 接收连接，WorkerGroup 处理 IO | 高并发 |

**Netty 线程模型**：
```
BossGroup（NioEventLoopGroup，通常 1 个线程）
  └─ 监听 Accept 事件 → 接收新连接
      ↓ 将 SocketChannel 注册到 WorkerGroup 的某个 EventLoop

WorkerGroup（NioEventLoopGroup，通常 CPU*2 个线程）
  └─ 每个 EventLoop 绑定一个线程 + 一个 Selector
      ├─ 监听 Read/Write 事件
      ├─ 执行 ChannelPipeline（ChannelHandler 链）
      └─ 处理定时任务和异步任务队列
```

**ChannelPipeline**：责任链模式，由多个 ChannelHandler 组成：
- `ChannelInboundHandler`：入站处理（解码 → 业务逻辑）
- `ChannelOutboundHandler`：出站处理（编码 → 写入）

---

## 三、操作系统

### Q11: 进程、线程、协程的区别？Java 中的线程和 OS 线程的关系？

**A：**

| 对比项 | 进程 | 线程 | 协程 |
|--------|------|------|------|
| 定义 | 资源分配的基本单位 | CPU 调度的基本单位 | 用户态轻量级线程 |
| 内存 | 独立地址空间 | 共享进程地址空间 | 共享线程栈 |
| 切换成本 | 高（切换页表、TLB 失效） | 中（保存/恢复寄存器） | 极低（用户态切换） |
| 通信 | IPC（管道/共享内存/Socket） | 共享内存 + 同步原语 | 直接调用 |
| 创建开销 | MB 级别 | ~1MB 栈空间 | ~KB 级别 |
| 调度 | 内核调度 | 内核调度 | 用户态调度 |

**Java 线程模型**：
- **JDK 1.2+（HotSpot）**：1:1 模型，一个 Java Thread 对应一个 OS 线程（pthread）
- **JDK 21+ Virtual Threads**：M:N 模型，多个虚拟线程映射到少量 OS 线程（Platform Thread），由 JVM 调度
- 虚拟线程适合 IO 密集型任务，不适合 CPU 密集型（因为仍需 OS 线程执行计算）

---

### Q12: Linux 中进程间通信（IPC）的方式有哪些？

**A：**

| IPC 方式 | 特点 | 适用场景 |
|---------|------|---------|
| **管道（Pipe）** | 半双工，父子进程间 | 简单的父子进程通信 |
| **命名管道（FIFO）** | 半双工，任意进程间 | 无亲缘关系进程通信 |
| **消息队列** | 消息有类型，可按类型读取 | 结构化数据传递 |
| **共享内存** | 最快的 IPC，需配合信号量同步 | 大数据量高频通信 |
| **信号量（Semaphore）** | 计数器，用于同步 | 控制共享资源访问 |
| **信号（Signal）** | 异步通知机制（如 SIGKILL） | 进程控制 |
| **Socket** | 支持跨网络通信 | 分布式系统、微服务 |

**Java 应用中的 IPC 选择**：
- 同机微服务通信：Unix Domain Socket（比 TCP Loopback 更快）
- 跨机通信：TCP Socket
- 共享数据：MappedByteBuffer（mmap 共享内存）

---

### Q13: 虚拟内存的原理？页表和 TLB 的作用？

**A：**

**虚拟内存**：每个进程拥有独立的虚拟地址空间，通过页表（Page Table）映射到物理内存。

**核心机制**：
```
虚拟地址 → MMU 查页表 → 物理地址
              ↓ (页表未命中)
          缺页中断（Page Fault）
              ↓
          OS 从磁盘加载页面到物理内存
          更新页表 → 重新执行指令
```

**页表**：存储虚拟页号到物理页框号的映射。多级页表（Linux 4 级/5 级）减少内存占用。

**TLB（Translation Lookaside Buffer）**：页表缓存，硬件实现的高速缓存。
- 命中：1 个时钟周期
- 未命中：需要多次内存访问（多级页表走查）
- 进程切换会刷新 TLB（PCID 可优化）

**为什么需要虚拟内存**：
1. 内存隔离（进程间互不影响）
2. 按需加载（延迟分配物理内存）
3. 内存超售（虚拟地址空间 > 物理内存）
4. 简化编程模型（每个进程都从 0 开始编址）

---

### Q14: Linux 的 IO 模型有哪几种？

**A：**

| IO 模型 | 阻塞等待数据 | 数据拷贝阻塞 | 说明 |
|---------|-------------|-------------|------|
| **阻塞 IO** | 阻塞 | 阻塞 | `read()` 全程阻塞 |
| **非阻塞 IO** | 轮询（返回 EAGAIN） | 阻塞 | 应用不断调用 `read()` 检查 |
| **IO 多路复用** | `select/poll/epoll` 阻塞 | 阻塞 | 一个线程监听多个 fd |
| **信号驱动 IO** | 不阻塞（内核信号通知） | 阻塞 | 收到 SIGIO 后调用 `read()` |
| **异步 IO（AIO）** | 不阻塞 | 不阻塞 | 内核完成后通知应用 |

**关键区分**：
- 同步 vs 异步：数据从内核缓冲区拷贝到用户缓冲区时，是否需要应用参与
- 前四种都是**同步 IO**（数据拷贝阶段需要应用参与），只有 AIO 是真正的异步
- Java NIO 使用的是 IO 多路复用（底层 epoll），不是真正的"非阻塞"

---

### Q15: 零拷贝（Zero Copy）的原理？Java 中如何实现？

**A：**

**传统 IO（4 次拷贝 + 4 次上下文切换）**：
```
磁盘 → 内核缓冲区（DMA拷贝）→ 用户缓冲区（CPU拷贝）
                                      ↓
Socket缓冲区（CPU拷贝）→ 网卡（DMA拷贝）
```

**零拷贝方案**：

| 方案 | 拷贝次数 | 原理 | Java API |
|------|---------|------|---------|
| `mmap` + write | 3次（1次CPU） | 用户空间和内核空间共享缓冲区 | `MappedByteBuffer` |
| `sendfile` | 3次（1次CPU） | 数据在内核空间直接传输 | `FileChannel.transferTo()` |
| `sendfile` + SG-DMA | **2次（0次CPU）** | 只传递 fd 和偏移量给 Socket | 需要网卡支持 SG-DMA |

**Java 中的零拷贝**：
```java
// FileChannel.transferTo — 底层调用 sendfile
FileChannel src = new FileInputStream("data.bin").getChannel();
SocketChannel dest = SocketChannel.open(addr);
src.transferTo(0, src.size(), dest);

// MappedByteBuffer — 底层调用 mmap
MappedByteBuffer buf = channel.map(FileChannel.MapMode.READ_ONLY, 0, size);
```

**Netty 中的零拷贝**（应用层）：
- `CompositeByteBuf`：合并多个 Buffer，避免内存拷贝
- `slice()` / `duplicate()`：共享底层数组
- `FileRegion`：封装 `FileChannel.transferTo()`

---

### Q16: 进程调度算法有哪些？Linux 使用什么调度算法？

**A：**

| 算法 | 描述 | 特点 |
|------|------|------|
| FCFS（先来先服务） | 按到达顺序执行 | 简单，对短进程不公平 |
| SJF（短作业优先） | 执行时间最短的先执行 | 平均等待时间最短，可能饿死长进程 |
| 优先级调度 | 按优先级执行 | 可能饿死低优先级进程 |
| 时间片轮转（RR） | 每个进程分配时间片 | 公平，时间片大小影响性能 |
| 多级反馈队列 | 多个优先级队列 + 动态调整 | 兼顾响应时间和吞吐量 |

**Linux CFS（Completely Fair Scheduler）**：
- 目标：让每个进程获得公平的 CPU 时间
- 核心思想：维护每个进程的 **vruntime**（虚拟运行时间），总是调度 vruntime 最小的进程
- 数据结构：红黑树（按 vruntime 排序，最左节点即下一个调度对象）
- 优先级影响：nice 值高的进程 vruntime 增长更快（获得更少的 CPU 时间）

---

### Q17: 死锁的条件和解决方案？Java 中如何排查死锁？

**A：**

**四个必要条件**：
1. **互斥**：资源同一时间只能被一个进程占有
2. **持有并等待**：已持有资源的进程等待获取其他资源
3. **不可剥夺**：已获得的资源不能被强行剥夺
4. **循环等待**：多个进程形成环形等待链

**解决策略**：

| 策略 | 方法 |
|------|------|
| 预防 | 破坏四个条件之一（如按序申请资源，破坏循环等待） |
| 避免 | 银行家算法（检查是否存在安全序列） |
| 检测 | 资源分配图检测环路 |
| 恢复 | 终止进程 / 资源抢占 |

**Java 死锁排查**：
```bash
# 方法1：jstack
jstack <pid> | grep -A 20 "deadlock"

# 方法2：jconsole / VisualVM 图形化检测

# 方法3：代码检测
ThreadMXBean bean = ManagementFactory.getThreadMXBean();
long[] deadlockedThreads = bean.findDeadlockedThreads();
```

**编码预防**：
- 统一锁的获取顺序
- 使用 `tryLock(timeout)` 代替 `lock()`
- 减小锁的粒度和持有时间
