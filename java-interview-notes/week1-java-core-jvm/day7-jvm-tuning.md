# Day 7：JVM 调优与类加载

> 复习目标：掌握类加载机制、JVM 调优参数与排查工具、JIT 编译优化。

---

## 一、类加载机制

**Q1：类的生命周期有哪些阶段？**

A：
```
加载(Loading) → 验证(Verification) → 准备(Preparation) → 解析(Resolution) → 初始化(Initialization) → 使用(Using) → 卸载(Unloading)
              |____________ 连接(Linking) ____________|
```

- **加载**：通过类的全限定名获取字节流 → 转为方法区运行时数据结构 → 在堆中生成 Class 对象
- **验证**：文件格式验证、元数据验证、字节码验证、符号引用验证
- **准备**：为**类变量（static）**分配内存并设置**零值**（不是初始值），`static final` 常量在此阶段直接赋值
- **解析**：将符号引用替换为直接引用
- **初始化**：执行 `<clinit>()`（类构造器），按代码顺序收集所有 static 变量赋值和 static 块

---

**Q2：什么情况会触发类的初始化？**

A：有且仅有以下 6 种情况（主动引用）：
1. `new`、`getstatic`、`putstatic`、`invokestatic` 指令：new 对象、读写静态字段、调用静态方法
2. 反射调用（`Class.forName()`）
3. 初始化子类时，父类尚未初始化
4. 虚拟机启动时，主类（main 方法所在类）
5. JDK 7+ 动态语言 `MethodHandle` 解析结果对应的类
6. 接口定义了 default 方法，实现类初始化时接口也初始化

**不会触发**的情况：子类引用父类静态字段、通过数组定义引用类、引用 static final 常量（编译期进入常量池）。

---

**Q3：什么是双亲委派模型？为什么需要它？**

A：
- **加载器层次**：Bootstrap ClassLoader → Extension/Platform ClassLoader → Application ClassLoader → 自定义 ClassLoader
- **工作流程**：收到加载请求时，先委派给父加载器，父加载器无法加载时自己才尝试加载
- **目的**：
  1. **安全**：防止核心 API 被篡改（用户无法自定义 java.lang.String）
  2. **唯一性**：同一个类被同一个加载器加载才被视为同一个类，双亲委派保证核心类只被 Bootstrap 加载一次

---

**Q4：如何打破双亲委派模型？有哪些场景需要打破？**

A：
- **如何打破**：继承 ClassLoader，重写 `loadClass()` 方法（而非 `findClass()`）
- **打破双亲委派的三个经典场景**：

| 场景 | 方式 | 原因 |
|------|------|------|
| JNDI/JDBC SPI | 线程上下文类加载器（Thread Context ClassLoader） | BootstrapCL 加载的接口需要回调 AppCL 加载的实现类 |
| OSGi 热部署 | 每个 Bundle 独立的 ClassLoader，网状委派 | 模块化需要隔离和热替换 |
| Tomcat | 每个 WebApp 独立的 WebAppClassLoader | 不同 Web 应用可以使用不同版本的同一个库 |

**Tomcat 类加载器架构**（从上到下）：
```
Bootstrap → Extension → Application → CommonClassLoader
                                       ├── CatalinaClassLoader（Tomcat 自身）
                                       ├── SharedClassLoader（所有 Web 应用共享）
                                       └── WebAppClassLoader（每个应用独立，优先自己加载）
```

---

## 二、JVM 调优参数

**Q5：常用 JVM 参数有哪些？**

A：
```bash
# 堆内存
-Xms512m              # 初始堆大小（建议与 -Xmx 相同，避免运行时扩缩容）
-Xmx2g               # 最大堆大小
-Xmn512m             # 新生代大小
-Xss256k             # 线程栈大小（默认 1MB）

# 元空间
-XX:MetaspaceSize=256m       # 初始元空间大小（低于此值会触发 Full GC）
-XX:MaxMetaspaceSize=512m    # 最大元空间（默认无限）

# 新生代比例
-XX:SurvivorRatio=8          # Eden:S0:S1 = 8:1:1
-XX:MaxTenuringThreshold=15  # 晋升老年代年龄阈值

# GC 收集器
-XX:+UseG1GC                 # 使用 G1（JDK 9+ 默认）
-XX:MaxGCPauseMillis=200     # G1 目标停顿时间
-XX:+UseZGC                  # 使用 ZGC（JDK 15+）

# GC 日志
-Xlog:gc*:file=gc.log:time,uptime,level,tags  # JDK 9+ 统一日志
-verbose:gc -XX:+PrintGCDetails -Xloggc:gc.log  # JDK 8

# 诊断
-XX:+HeapDumpOnOutOfMemoryError              # OOM 时自动 dump
-XX:HeapDumpPath=/tmp/heapdump.hprof         # dump 文件路径
-XX:ErrorFile=/tmp/hs_err_%p.log             # JVM crash 日志
```

---

**Q6：如何选择合适的 GC 收集器？**

A：
| 场景 | 推荐收集器 | 原因 |
|------|-----------|------|
| 通用服务端（JDK 8） | CMS 或 G1 | 低延迟优先 |
| 通用服务端（JDK 11+） | G1（默认） | 兼顾吞吐和延迟 |
| 大堆（>8GB） | ZGC 或 G1 | ZGC 停顿与堆大小无关 |
| 超低延迟 | ZGC | 亚毫秒停顿 |
| 吞吐量优先（批处理） | Parallel Scavenge + Parallel Old | 最大化 CPU 利用率 |
| 小内存客户端 | Serial | 简单，无多线程开销 |

---

## 三、OOM 场景与排查

**Q7：常见的 OOM 类型和原因有哪些？**

A：
| OOM 类型 | 常见原因 |
|---------|---------|
| `Java heap space` | 对象太多/内存泄漏/堆设置太小 |
| `Metaspace` / `PermGen space` | 类加载过多（动态代理、CGLIB、大量 JSP） |
| `GC overhead limit exceeded` | GC 时间占比 >98% 且回收不到 2% 堆内存 |
| `Unable to create new native thread` | 线程数过多（受系统 ulimit 限制） |
| `Direct buffer memory` | 堆外内存（NIO DirectByteBuffer）泄漏 |
| `Requested array size exceeds VM limit` | 尝试创建超大数组 |

---

**Q8：如何排查线上 OOM 问题？给出完整思路。**

A：
1. **获取堆转储**：
   - 提前配置 `-XX:+HeapDumpOnOutOfMemoryError`（推荐）
   - 手动：`jmap -dump:live,format=b,file=heap.hprof <pid>`
   - Arthas：`heapdump /tmp/heap.hprof`

2. **分析 dump 文件**：
   - 用 MAT（Eclipse Memory Analyzer）打开
   - 看 **Leak Suspects Report**：自动定位可疑的内存泄漏链
   - 看 **Dominator Tree**：按对象占用内存排序
   - 看 **Histogram**：统计各类实例数量和大小

3. **定位代码**：
   - 找到占用最大的对象 → 查看 GC Root 到该对象的引用链 → 定位持有引用的代码

4. **常见泄漏模式**：
   - 静态集合（static Map/List）不断添加不移除
   - 未关闭的流/连接（InputStream、Connection）
   - ThreadLocal 未 remove
   - 监听器/回调未注销
   - 缓存无淘汰策略

---

**Q9：常用 JVM 排查工具有哪些？**

A：
| 工具 | 用途 | 常用命令 |
|------|------|---------|
| **jps** | 列出 Java 进程 | `jps -lv` |
| **jstat** | GC 统计信息 | `jstat -gcutil <pid> 1000`（每秒打印 GC 情况） |
| **jmap** | 堆转储 / 堆统计 | `jmap -dump:live,format=b,file=heap.hprof <pid>` |
| **jstack** | 线程栈快照 | `jstack <pid> > thread.log`（排查死锁/线程阻塞） |
| **jcmd** | 综合诊断（JDK 7+） | `jcmd <pid> GC.heap_info` |
| **Arthas** | 在线诊断（阿里开源） | `dashboard`、`thread`、`trace`、`watch`、`heapdump` |
| **MAT** | 堆分析（离线） | 分析 hprof 文件 |
| **VisualVM** | 可视化监控 | 实时查看堆、线程、GC |
| **async-profiler** | CPU/内存火焰图 | 定位热点方法 |

---

**Q10：如何排查 CPU 飙高问题？**

A：
```bash
# 1. 找到 CPU 占用最高的 Java 进程
top -c

# 2. 找到进程中 CPU 最高的线程
top -Hp <pid>

# 3. 将线程 ID 转为十六进制
printf "%x\n" <tid>  # 例如 12345 → 0x3039

# 4. 用 jstack 导出线程栈
jstack <pid> | grep '0x3039' -A 30

# 5. 或者用 Arthas 一步到位
thread -n 3  # 显示 CPU 占用最高的 3 个线程的栈
```

常见原因：死循环、频繁 GC（看 jstat -gcutil）、正则回溯、加密/序列化计算密集操作。

---

## 四、JIT 编译优化

**Q11：什么是 JIT 编译？有哪些常见优化？**

A：
- JIT（Just-In-Time）编译器将热点代码从字节码编译为本地机器码，避免重复解释执行
- **热点检测**：方法调用计数器 + 回边计数器，超过阈值触发编译（`-XX:CompileThreshold`，C2 默认 10000）

**常见优化**：
| 优化 | 说明 |
|------|------|
| **方法内联** | 将短方法的代码直接嵌入调用处，消除方法调用开销 |
| **逃逸分析** | 分析对象的作用域，判断对象是否"逃逸"出方法/线程 |
| **标量替换** | 逃逸分析发现对象不逃逸 → 将对象拆解为基本类型，存在栈上而非堆上 |
| **栈上分配** | 逃逸分析发现对象不逃逸 → 在栈帧上分配（理论上，HotSpot 实际通过标量替换间接实现） |
| **锁消除** | 逃逸分析发现锁对象不逃逸 → 去掉无用的 synchronized |
| **锁粗化** | 连续对同一对象加锁解锁 → 合并为一次加锁 |
| **公共子表达式消除** | 重复计算的表达式只计算一次 |

---

**Q12：什么是逃逸分析？举例说明。**

A：逃逸分析判断对象的引用是否会"逃逸"出当前方法或线程。

```java
// 不逃逸：对象仅在方法内使用
public int calculate() {
    Point p = new Point(1, 2);  // p 不会被外部引用
    return p.x + p.y;
    // JIT 优化：标量替换 → int x = 1; int y = 2; return x + y;
    // 根本不创建 Point 对象，无堆分配、无 GC 压力
}

// 逃逸：对象被返回或传给外部
public Point createPoint() {
    Point p = new Point(1, 2);
    return p;  // p 逃逸了，必须在堆上分配
}
```

开启：`-XX:+DoEscapeAnalysis`（JDK 8+ 默认开启）
