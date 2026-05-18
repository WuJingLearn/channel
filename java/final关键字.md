# Java final 关键字深度解析

> `final` 是 Java 中表示"最终的、不可改变的"修饰符，可作用于**类、方法、变量**。它不仅是一个语法层面的"只读"标记，更是 Java 内存模型（JMM）保证多线程安全的重要基石。

---

## 一、基础作用速览

| 修饰位置 | 语义 | 典型场景 |
|---------|------|---------|
| 类 | 不可被继承 | 工具类、不可变类（如 `String`） |
| 方法 | 不可被子类重写 | 模板方法的固定步骤 |
| 基本类型变量 | 值不可改变（常量） | `static final` 常量定义 |
| 引用类型变量 | 引用不可变（对象内部仍可变） | 配置对象、依赖注入字段 |
| 局部变量 / 参数 | 只能赋值一次 | Lambda / 匿名内部类捕获 |

---

## 二、修饰类、方法、变量的细节

### 2.1 修饰类

```java
public final class String { ... }   // String 类不可被继承
```

**作用**：防止类被继承篡改，保证设计的不可变性与安全性。

### 2.2 修饰方法

```java
public class Parent {
    public final void doSomething() { ... }
}

public class Child extends Parent {
    // 编译错误：不能重写 final 方法
    // public void doSomething() { ... }
}
```

**注意**：`private` 方法默认隐式为 final（不可重写）。

### 2.3 修饰变量

```java
final int MAX = 100;             // 基本类型：值不可变
MAX = 200;                       // ❌ 编译错误

final List<String> list = new ArrayList<>();
list.add("a");                   // ✅ 修改对象内容允许
list = new ArrayList<>();        // ❌ 重新指向新引用不允许
```

成员变量必须在**声明时、构造方法中、或实例初始化块**中完成赋值。

---

## 三、final 与并发（核心重点）⭐

### 3.1 问题起源：对象构造的"半初始化"

在 Java 中，创建一个对象可分为 3 步：

```
1. 分配内存空间（在堆上为对象划出一块内存，字段初始化为默认值 0/null/false
2. 初始化对象（执行 <init> 构造方法，把字段赋成业务值）
3. 将对象的内存地址赋给引用变量
```

由于 **JIT 编译器和 CPU 的指令重排序**，第 2 步与第 3 步的顺序可能被调换。这意味着：

> **一个线程拿到对象引用时，对象内部字段可能还没初始化完毕！**

### 3.2 JSR-133 对 final 字段的特殊保证

从 **JDK 5（JSR-133）** 开始，JMM 对 `final` 字段做了特殊的内存可见性保证：

#### ① 写 final 字段的重排序规则

> **构造函数内对 final 字段的写入，与"将该对象引用赋给外部变量"两个操作之间，禁止重排序。**

**底层实现**：编译器会在 final 字段写入之后、构造方法返回之前插入 **StoreStore 内存屏障**。

```
构造方法内：
    final 字段 = 值;
    StoreStore 屏障    ← 编译器插入
    return;
```

#### ② 读 final 字段的重排序规则

> **首次读包含 final 字段的对象引用，与首次读该 final 字段，这两个操作禁止重排序。**

**效果**：只要构造方法没有发生 `this` 引用逸出，**任何线程**拿到对象引用时，看到的 final 字段一定是**构造完成后的最终值**。

### 3.3 final 字段 vs 普通字段的可见性对比

| 特性 | 普通字段 | final 字段 |
|------|---------|-----------|
| 构造方法内赋值的可见性 | ❌ 不保证（其他线程可能看到 0/null） | ✅ 保证 |
| 是否需要 volatile/synchronized | ✅ 需要 | ❌ 不需要（前提：this 不逸出） |
| 是否可以安全发布 | ❌ 必须加同步 | ✅ Safe Publication |

---

## 四、并发代码实例 ⭐

### 4.1 反例：普通字段可能被看到"半成品"

```java
public class UnsafeObject {
    private int x;                  // 普通字段
    private int y;                  // 普通字段

    public UnsafeObject() {
        x = 1;
        y = 2;
    }
}

public class UnsafeDemo {
    static UnsafeObject obj;        // 普通引用，没有 volatile

    // 线程 A：发布对象
    public static void writer() {
        obj = new UnsafeObject();
    }

    // 线程 B：读取对象
    public static void reader() {
        if (obj != null) {
            // ⚠️ 可能读到 x=0, y=0（未初始化的默认值）
            System.out.println(obj.x + ", " + obj.y);
        }
    }
}
```

**问题**：构造过程中的写入可能与引用赋值发生重排序，线程 B 看到 `obj != null` 但内部字段仍为默认值。

---

### 4.2 正例：final 字段保证安全发布

```java
public class SafeObject {
    private final int x;            // final 字段
    private final int y;            // final 字段

    public SafeObject() {
        x = 1;
        y = 2;
        // JVM 在此隐式插入 StoreStore 屏障
    }

    public int getX() { return x; }
    public int getY() { return y; }
}

public class SafeDemo {
    static SafeObject obj;          // 注意：obj 本身仍是普通引用！

    public static void writer() {
        obj = new SafeObject();
    }

    public static void reader() {
        SafeObject o = obj;
        if (o != null) {
            // ✅ 保证读到 x=1, y=2，不会看到默认值
            System.out.println(o.getX() + ", " + o.getY());
        }
    }
}
```

**关键点**：即使外部引用 `obj` 没有用 `volatile`，只要内部字段是 `final`，JMM 就保证其他线程看到的字段值是构造完成后的最终值。

---

### 4.3 反例：this 逸出会破坏 final 的并发保证

```java
public class EscapeObject {
    private final int x;
    static EscapeObject leaked;

    public EscapeObject() {
        leaked = this;              // ⚠️ this 逸出！此时 x 还是默认值 0
        x = 100;                     // 赋值发生在逸出之后
    }
}
```

通过 `leaked` 访问 `x` 的线程**可能看到 0 而非 100**，final 的内存可见性保证完全失效。

> **结论**：构造方法中切勿将 `this` 传递给其他线程或注册回调。

---

### 4.4 进阶：final 与普通字段混合的陷阱

```java
public class MixedObject {
    private final int finalField;       // final 字段
    private int normalField;             // 普通字段

    public MixedObject() {
        finalField = 100;
        normalField = 200;
    }

    public int getFinalField()  { return finalField;  }
    public int getNormalField() { return normalField; }
}

// 多线程使用
static MixedObject obj;

// 线程 A
public static void writer() { obj = new MixedObject(); }

// 线程 B
public static void reader() {
    MixedObject o = obj;
    if (o != null) {
        System.out.println(o.getFinalField());   // ✅ 一定是 100
        System.out.println(o.getNormalField());  // ⚠️ 可能是 0！
    }
}
```

**核心结论**：**final 只保护自己，不保护邻居。**`final` 的内存屏障**只作用于 final 字段本身**，不会"顺带"保护同一对象内的普通字段。

---

### 4.5 实战经典：String 的线程安全本质

```java
public final class String {
    private final char[] value;     // final 数组引用
    private final int hash;         // final 哈希缓存
    // ...
}
```

- 类是 `final` → 不可被继承篡改
- 字段都是 `final` → 多线程间天然安全发布
- 没有任何修改字段的方法 → 状态完全不可变
- **不需要任何同步机制**就能在多线程间共享

---

## 五、解决"混合字段"问题的方案

| 方案 | 写法 | 适用场景 |
|------|------|---------|
| **全部 final**（首选） | 所有字段都改成 final | 不可变对象、配置类、值对象 |
| **volatile 修饰外部引用** | `static volatile MixedObject obj;` | 对象引用会被替换，但内部状态稳定 |
| **synchronized 同步访问** | getter/setter 加锁 | 字段需频繁修改的复合操作 |
| **final + 原子类** | `private final AtomicInteger counter;` | 需要可变状态但避免重锁 |

```java
// 推荐写法：final + 原子类
public class HybridObject {
    private final int id;                       // 不变字段
    private final AtomicInteger counter;        // 可变状态

    public HybridObject(int id) {
        this.id = id;
        this.counter = new AtomicInteger(0);
    }

    public void increment() { counter.incrementAndGet(); }
}
```

---

## 六、final 在并发中的两大价值

| 价值 | 说明 |
|------|------|
| **1. 安全发布（Safe Publication）** | 无需同步，其他线程能看到完整初始化的对象状态 |
| **2. 禁止重排序** | 通过 StoreStore 屏障防止"对象引用先于字段初始化"被观测到 |

> **一句话总结**：`final` 字段让对象在构造完成后具备"**冻结**"语义，是 Java 实现**不可变对象（Immutable Object）线程安全**的底层基石。

---

## 七、面试加分要点

1. **JSR-133 是分水岭**：JDK 5 之前，final 字段没有这种内存语义保证。
2. **final ≠ immutable**：`final List` 仍然可以 `add` 元素，引用不变但内容可变。
3. **this 逸出会破坏 final 的保证**，构造方法中不要将 `this` 暴露给外部线程。
4. **final 只保护 final 字段本身**，不会顺带保护同对象的普通字段。
5. **`final`、`finally`、`finalize` 区别**：
   - `final`：修饰符（类/方法/变量）
   - `finally`：异常处理中保证执行的代码块
   - `finalize`：Object 的方法，GC 前调用（JDK 9 已标记废弃）
6. **`static final` 编译期常量**：基本类型与 String 会被编译期内联到调用方字节码，**修改后未重新编译会出现"幽灵值"问题**。
7. **final + 不可变 = 天然线程安全**，这是函数式编程与 Actor 模型推崇不可变性的根本原因。

---

## 八、一句话记忆

> **final 只保护自己，不保护邻居。**
> 一个对象的线程安全是"全有或全无"——要么所有字段都不可变，要么显式加同步。

---

## 九、面试一句话总结（口播版）⭐

> **`final` 在 Java 中是一个"最终性"修饰符，它的作用可以从两个层面来理解：**
>
> **第一层是语法层面的"不可变"语义**——修饰类时表示该类不可被继承（如 `String`），修饰方法时表示该方法不可被子类重写，修饰变量时表示该变量只能被赋值一次（基本类型是值不可变，引用类型是引用不可变但对象内部状态仍可变）；
>
> **第二层是 JMM 内存模型层面的"并发安全"语义**——这是 JSR-133 在 JDK 5 之后引入的关键增强：JVM 会在构造方法中对 final 字段写入之后插入 StoreStore 内存屏障，保证"final 字段的初始化"与"对象引用的发布"两个操作之间不会被重排序。这意味着只要构造期间 `this` 没有逸出，**任何线程拿到该对象引用时，都一定能看到 final 字段构造完成后的最终值**，从而实现了无需 synchronized 或 volatile 的"安全发布"（Safe Publication）。这正是 `String`、`Integer` 等不可变类天然线程安全的底层基石。
>
> **所以 `final` 的真正威力在于：它既是不可变设计的语法保证，也是并发场景下安全发布的内存屏障，二者结合让 `final + 不可变对象` 成为高并发编程中最简单、最高效的线程安全方案。**
