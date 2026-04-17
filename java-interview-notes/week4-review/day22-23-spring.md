# Day 22-23: Spring 生态巩固

> 复习重点：IoC 容器启动流程、Bean 生命周期、AOP 代理机制、事务传播行为、SpringBoot 自动装配、MyBatis 核心原理
> 用户已有基础：Spring Bean 生命周期、MyBatis 核心原理（此处侧重深挖高频追问点）

---

## Q1: Spring IoC 容器的启动流程是怎样的？核心方法 refresh() 做了哪些事？

**A：**

`AbstractApplicationContext.refresh()` 是容器启动的核心，共 13 个步骤：

```
1. prepareRefresh()            — 设置启动时间、活跃标志、初始化属性源
2. obtainFreshBeanFactory()    — 创建 BeanFactory（DefaultListableBeanFactory）
3. prepareBeanFactory()        — 配置类加载器、注册默认 BeanPostProcessor
4. postProcessBeanFactory()    — 子类扩展（如 Web 容器注册 Scope）
5. invokeBeanFactoryPostProcessors() — ★ 执行 BeanFactoryPostProcessor
   └─ ConfigurationClassPostProcessor 在此解析 @Configuration、@ComponentScan、@Import
6. registerBeanPostProcessors() — 注册 BeanPostProcessor（如 AutowiredAnnotationBPP）
7. initMessageSource()         — 初始化国际化
8. initApplicationEventMulticaster() — 初始化事件广播器
9. onRefresh()                 — 子类扩展（如 SpringBoot 启动内嵌 Tomcat）
10. registerListeners()        — 注册 ApplicationListener
11. finishBeanFactoryInitialization() — ★ 实例化所有非懒加载单例 Bean
12. finishRefresh()            — 发布 ContextRefreshedEvent
13. （异常时）destroyBeans() + cancelRefresh()
```

**面试追问**：第 5 步和第 11 步是最核心的。第 5 步完成了所有 BeanDefinition 的扫描和注册；第 11 步触发了 Bean 的创建、依赖注入、初始化全流程。

---

## Q2: Spring Bean 的完整生命周期有哪些回调点？执行顺序是什么？

**A：**

```
实例化（Instantiation）
  └─ 构造方法 / 工厂方法
      ↓
属性注入（Populate）
  └─ @Autowired / @Value / setter 注入
      ↓
Aware 接口回调
  └─ BeanNameAware → BeanFactoryAware → ApplicationContextAware
      ↓
BeanPostProcessor.postProcessBeforeInitialization()
  └─ 包括 @PostConstruct 的处理（CommonAnnotationBeanPostProcessor）
      ↓
InitializingBean.afterPropertiesSet()
      ↓
自定义 init-method
      ↓
BeanPostProcessor.postProcessAfterInitialization()
  └─ ★ AOP 代理在此生成（AbstractAutoProxyCreator）
      ↓
Bean 就绪，放入单例池
      ↓
容器关闭时：
  @PreDestroy → DisposableBean.destroy() → 自定义 destroy-method
```

**关键点**：`@PostConstruct` 在 `BeanPostProcessor.before` 阶段执行，AOP 代理在 `BeanPostProcessor.after` 阶段创建，所以 `@PostConstruct` 中调用的是原始对象的方法而非代理方法。

---

## Q3: Spring 如何解决循环依赖？三级缓存的作用分别是什么？

**A：**

Spring 通过三级缓存解决 **Setter/字段注入的单例 Bean** 循环依赖：

| 缓存 | 字段名 | 存储内容 | 作用 |
|------|--------|---------|------|
| 一级缓存 | `singletonObjects` | 完整的 Bean 实例 | 最终使用的成品 Bean |
| 二级缓存 | `earlySingletonObjects` | 提前暴露的 Bean（可能是代理） | 避免重复创建代理 |
| 三级缓存 | `singletonFactories` | ObjectFactory lambda | 延迟决定是否需要 AOP 代理 |

**流程（A → B → A 循环）**：
1. 创建 A：实例化后，将 `ObjectFactory(() -> getEarlyBeanReference(A))` 放入三级缓存
2. 填充 A 的属性，发现需要 B
3. 创建 B：同样放入三级缓存
4. 填充 B 的属性，发现需要 A
5. 从三级缓存获取 A 的 ObjectFactory，调用 `getEarlyBeanReference` → 如果 A 需要 AOP 代理，此处提前创建代理对象
6. 将代理/原始 A 放入二级缓存，移除三级缓存
7. B 初始化完成，放入一级缓存
8. A 继续完成初始化，放入一级缓存

**不能解决的场景**：
- 构造器注入的循环依赖（实例化时就需要依赖，还没到放缓存的步骤）
- `@Async` Bean 的循环依赖（后置处理器创建的代理与早期引用不一致）
- prototype 作用域的循环依赖

**深入：为什么需要三级缓存？逐步推导**

Spring Bean 创建分三步：**① 实例化（new）→ ② 属性填充（依赖注入）→ ③ 初始化（AOP 代理等后置处理）**。

**只有一级缓存**：一级缓存只存完整 Bean，半成品不能放进去（否则其他线程拿到未初始化的对象）。A 还没创建完就需要给 B 用 → 死循环。

**只有两级缓存（一级 + 二级）**：实例化后将半成品 A 放入二级缓存，B 可以从二级缓存拿到 A → 循环依赖解决了。**但如果 A 需要 AOP 代理**：B 从二级缓存拿到的是原始 A，而 A 初始化后创建了代理 ProxyA 放入一级缓存 → B 持有原始 A，一级缓存是 ProxyA → **引用不一致！**

**三级缓存解决方案**：三级缓存存的不是对象本身，而是 `ObjectFactory` 工厂。当 B 需要 A 时，调用工厂方法 `getEarlyBeanReference(A)` → 检查 A 是否需要 AOP 代理 → 需要则提前创建代理返回 → 保证 B 拿到的和最终一级缓存里的是同一个对象。

**为什么三级缓存存工厂而不是直接存代理？** 因为大多数 Bean 没有循环依赖。如果实例化后就立即创建代理：
- 所有 Bean 都提前创建代理，即使没有循环依赖 → 浪费性能
- 违背 Spring 设计原则：AOP 代理应在初始化阶段（`BeanPostProcessor.postProcessAfterInitialization`）创建

工厂方法是**懒执行**的——只有被循环依赖的 Bean 才会触发提前创建代理。

## 从 `getBean(A)` 开始的完整流程

以 **A ↔ B 循环依赖，A 需要 AOP 代理** 为例，完整梳理每一步缓存的查找和创建过程。

### 前置知识：`getSingleton()` 的查找顺序

```java
// DefaultSingletonBeanRegistry.getSingleton()
protected Object getSingleton(String beanName, boolean allowEarlyReference) {
    // 第 1 步：查一级缓存
    Object singletonObject = this.singletonObjects.get(beanName);
    if (singletonObject == null && isSingletonCurrentlyInCreation(beanName)) {
        // 第 2 步：查二级缓存
        singletonObject = this.earlySingletonObjects.get(beanName);
        if (singletonObject == null && allowEarlyReference) {
            // 第 3 步：查三级缓存
            ObjectFactory<?> singletonFactory = this.singletonFactories.get(beanName);
            if (singletonFactory != null) {
                singletonObject = singletonFactory.getObject(); // 调用工厂方法
                this.earlySingletonObjects.put(beanName, singletonObject); // 升级到二级
                this.singletonFactories.remove(beanName); // 删除三级
            }
        }
    }
    return singletonObject;
}
```

### 完整流程

```
═══════════════════════════════════════════════════════════════
第一阶段：getBean("A")
═══════════════════════════════════════════════════════════════

1. getSingleton("A")
   → 查一级缓存 singletonObjects        → 没有 A
   → A 不在 singletonsCurrentlyInCreation 中，直接返回 null
   → 结论：A 不存在，需要创建

2. 标记 A 正在创建中
   → singletonsCurrentlyInCreation.add("A")

3. 实例化 A
   → createBeanInstance("A") → 调用构造器 new A()
   → 此时 A 是原始对象，属性都是 null

4. 将 A 的工厂放入三级缓存
   → singletonFactories.put("A", () -> getEarlyBeanReference("A", rawA))
   
   此时缓存状态：
   ┌─────────────────────────────────────────────┐
   │ 一级 singletonObjects:      { }              │
   │ 二级 earlySingletonObjects: { }              │
   │ 三级 singletonFactories:    { A → factory }  │
   └─────────────────────────────────────────────┘

5. 填充 A 的属性（populateBean）
   → 发现 A 依赖 B（@Autowired B b）
   → 触发 getBean("B")

═══════════════════════════════════════════════════════════════
第二阶段：getBean("B")（在填充 A 属性的过程中触发）
═══════════════════════════════════════════════════════════════

6. getSingleton("B")
   → 查一级缓存 singletonObjects        → 没有 B
   → B 不在 singletonsCurrentlyInCreation 中，直接返回 null
   → 结论：B 不存在，需要创建

7. 标记 B 正在创建中
   → singletonsCurrentlyInCreation.add("B")

8. 实例化 B
   → createBeanInstance("B") → new B()

9. 将 B 的工厂放入三级缓存
   → singletonFactories.put("B", () -> getEarlyBeanReference("B", rawB))
   
   此时缓存状态：
   ┌──────────────────────────────────────────────────────┐
   │ 一级 singletonObjects:      { }                      │
   │ 二级 earlySingletonObjects: { }                      │
   │ 三级 singletonFactories:    { A → factory, B → factory } │
   └──────────────────────────────────────────────────────┘

10. 填充 B 的属性（populateBean）
    → 发现 B 依赖 A（@Autowired A a）
    → 触发 getBean("A")

═══════════════════════════════════════════════════════════════
第三阶段：getBean("A")（在填充 B 属性的过程中触发，第二次）
═══════════════════════════════════════════════════════════════

11. getSingleton("A")  ← 关键！这次走的路径不同
    → 查一级缓存 singletonObjects        → 没有 A
    → A 在 singletonsCurrentlyInCreation 中！（步骤 2 标记的）
    → 查二级缓存 earlySingletonObjects   → 没有 A
    → 查三级缓存 singletonFactories      → 有 A 的工厂！
    → 调用工厂方法：singletonFactory.getObject()
      → 执行 getEarlyBeanReference("A", rawA)
      → 遍历所有 SmartInstantiationAwareBeanPostProcessor
      → 发现 A 需要 AOP 代理 → 提前创建 ProxyA
    → 将 ProxyA 放入二级缓存：earlySingletonObjects.put("A", ProxyA)
    → 删除三级缓存：singletonFactories.remove("A")
    → 返回 ProxyA
    
    此时缓存状态：
    ┌──────────────────────────────────────────────────────┐
    │ 一级 singletonObjects:      { }                      │
    │ 二级 earlySingletonObjects: { A → ProxyA }           │
    │ 三级 singletonFactories:    { B → factory }          │
    └──────────────────────────────────────────────────────┘

═══════════════════════════════════════════════════════════════
第四阶段：B 创建完成
═══════════════════════════════════════════════════════════════

12. B 拿到 ProxyA，属性填充完成
    → B.a = ProxyA ✅

13. 初始化 B（initializeBean）
    → 执行 BeanPostProcessor（如果 B 也需要 AOP，在这里创建代理）
    → B 初始化完成

14. 将 B 放入一级缓存
    → singletonObjects.put("B", B)
    → earlySingletonObjects.remove("B")
    → singletonFactories.remove("B")
    → singletonsCurrentlyInCreation.remove("B")
    
    此时缓存状态：
    ┌──────────────────────────────────────────────────────┐
    │ 一级 singletonObjects:      { B → B }                │
    │ 二级 earlySingletonObjects: { A → ProxyA }           │
    │ 三级 singletonFactories:    { }                      │
    └──────────────────────────────────────────────────────┘

═══════════════════════════════════════════════════════════════
第五阶段：A 创建完成
═══════════════════════════════════════════════════════════════

15. 回到步骤 5，A 拿到 B，属性填充完成
    → A.b = B ✅

16. 初始化 A（initializeBean）
    → 执行 BeanPostProcessor
    → 发现 A 已经在步骤 11 提前创建了代理（earlyProxyReference 中有记录）
    → 跳过代理创建，避免重复

17. 将 A 放入一级缓存
    → 检查二级缓存：earlySingletonObjects.get("A") → ProxyA
    → 最终暴露的是 ProxyA（而不是 rawA）
    → singletonObjects.put("A", ProxyA)
    → earlySingletonObjects.remove("A")
    → singletonsCurrentlyInCreation.remove("A")
    
    最终缓存状态：
    ┌──────────────────────────────────────────────────────┐
    │ 一级 singletonObjects:      { A → ProxyA, B → B }   │
    │ 二级 earlySingletonObjects: { }                      │
    │ 三级 singletonFactories:    { }                      │
    └──────────────────────────────────────────────────────┘

验证：B.a == ProxyA，singletonObjects.get("A") == ProxyA ✅ 同一个对象！
```

### 关键转折点

整个流程的**核心转折**在步骤 11：第二次 `getBean("A")` 时，因为 A 已经被标记为"正在创建中"（`singletonsCurrentlyInCreation` 包含 A），所以 `getSingleton()` 会继续往二级、三级缓存查找，而不是再次走创建流程。这就是**打破循环**的关键。

| 问题 | 答案 |
|------|------|
| 一级缓存够吗？ | 不够，半成品不能放一级缓存 |
| 两级缓存够吗？ | 没有 AOP 时够，有 AOP 时引用不一致 |
| 三级缓存为什么存工厂？ | 懒执行，只在被循环依赖时才提前创建代理 |
| 二级缓存的作用？ | 缓存工厂结果，避免多个 Bean 依赖同一个早期引用时重复创建代理 |

---

## 构造器注入的循环依赖
## 构造器注入的循环依赖

### 现象：启动直接报错

Spring 会抛出 `BeanCurrentlyInCreationException`，应用启动失败。

```
BeanCurrentlyInCreationException: Error creating bean with name 'a': 
Requested bean is currently in creation: Is there an unresolvable circular reference?
```

### 为什么三级缓存救不了？

关键在于 Bean 创建的三步顺序：**① 实例化（new）→ ② 属性填充 → ③ 初始化**

三级缓存的工厂是在**步骤 ① 实例化之后**才放入的。而构造器注入的依赖在**步骤 ① 实例化时**就需要了——还没来得及放缓存！

```java
// Setter 注入：实例化和依赖注入是分开的
@Component
public class A {
    @Autowired
    private B b;  // 步骤 ② 才注入，此时 A 已经实例化，工厂已放入三级缓存
}

// 构造器注入：实例化时就需要依赖
@Component
public class A {
    private final B b;
    
    public A(B b) {  // 步骤 ① 实例化时就需要 B，但此时还没放缓存！
        this.b = b;
    }
}
```

### 完整的死循环过程

```
1. getBean("A")
   → 标记 A 正在创建中：singletonsCurrentlyInCreation.add("A")
   → 实例化 A：调用构造器 new A(B b)
     → 需要 B！→ 触发 getBean("B")
     ⚠️ 注意：此时 A 还没实例化成功，工厂还没放入三级缓存！

2. getBean("B")
   → 标记 B 正在创建中：singletonsCurrentlyInCreation.add("B")
   → 实例化 B：调用构造器 new B(A a)
     → 需要 A！→ 触发 getBean("A")

3. getBean("A")（第二次）
   → getSingleton("A")
     → 查一级缓存 → 没有
     → A 在 singletonsCurrentlyInCreation 中 ✅
     → 查二级缓存 → 没有
     → 查三级缓存 → 也没有！（因为 A 还卡在步骤 ① 构造器调用中）
     → 返回 null

   → A 不存在，需要创建
   → 但 A 已经在 singletonsCurrentlyInCreation 中了！
   → 💥 抛出 BeanCurrentlyInCreationException
```

### 对比

```
Setter 注入的时间线：
  new A()  →  放入三级缓存  →  填充属性（需要 B）  →  初始化
  ────①────  ──── 缓存 ────  ────── ② ──────────  ──── ③ ────
                    ↑
              这里已经有缓存了，B 可以从三级缓存拿到 A

构造器注入的时间线：
  new A(需要B)  →  放入三级缓存  →  初始化
  ──── ① ──────  ──── 缓存 ────  ──── ③ ────
       ↑
  这里就卡住了，还没到放缓存的步骤！
```

### 解决方案

| 方案 | 做法 | 原理 |
|------|------|------|
| **`@Lazy`** | 在构造器参数上加 `@Lazy` | 注入的不是真实 B，而是 B 的代理，实际使用时才触发 `getBean("B")` |
| **改为 Setter 注入** | 把其中一个改为 `@Autowired` 字段注入 | 让实例化和依赖注入分离，三级缓存可以介入 |
| **`ObjectProvider`** | 构造器参数改为 `ObjectProvider<B>` | 延迟获取，使用时才调用 `getObject()` |

```java
// 方案一：@Lazy（最常用）
@Component
public class A {
    private final B b;
    
    public A(@Lazy B b) {  // 注入的是 B 的代理，不会立即触发 getBean("B")
        this.b = b;
    }
}
```

> 💡 **面试总结**：构造器注入的循环依赖无法被三级缓存解决，因为构造器调用发生在实例化阶段，此时工厂还没放入三级缓存。Spring 会直接抛出 `BeanCurrentlyInCreationException`。解决方案是用 `@Lazy` 延迟注入代理，或改为 Setter 注入让实例化和依赖注入分离。


## `@Lazy` 为什么能解决构造器注入的循环依赖

### 核心原理：注入的不是真实对象，而是一个代理

```java
@Component
public class A {
    private final B b;
    
    public A(@Lazy B b) {
        this.b = b;
        // 此时 b 不是真正的 B 实例
        // 而是 Spring 通过 CGLIB 生成的一个 B 的代理对象
        // 这个代理对象的创建不需要触发 getBean("B")
    }
}
```

### 对比：没有 `@Lazy` vs 有 `@Lazy`

**没有 `@Lazy`（报错）**：

```
new A(B b)
       ↑
       需要真正的 B 实例
       → getBean("B")
         → new B(A a)
                  ↑
                  需要真正的 A 实例
                  → getBean("A")
                    → A 正在创建中，三级缓存也没有
                    → 💥 BeanCurrentlyInCreationException
```

**有 `@Lazy`（正常）**：

```
new A(B b)
       ↑
       Spring 发现参数上有 @Lazy
       → 不调用 getBean("B")！
       → 而是用 ProxyFactory 立即创建一个 B 的 CGLIB 代理
       → 这个代理是个"空壳"，创建时不需要 B 的任何依赖
       → A 实例化成功 ✅

后续 A 真正调用 b.doSomething() 时：
       → 代理拦截方法调用
       → 此时才触发 getBean("B") 获取真正的 B
       → 此时 A 已经创建完毕，B 可以正常拿到 A
       → 一切正常 ✅
```

### `@Lazy` 代理的生成过程

Spring 在解析构造器参数时，发现 `@Lazy` 注解后的处理逻辑：

```java
// DefaultListableBeanFactory.resolveDependency()
if (descriptor.hasAnnotation(Lazy.class)) {
    // 不去容器中获取真实 Bean
    // 而是创建一个延迟代理
    return buildLazyProxy(descriptor, beanName);
}

// buildLazyProxy 的核心逻辑
private Object buildLazyProxy(DependencyDescriptor descriptor, String beanName) {
    // 创建一个 TargetSource，延迟获取真实对象
    TargetSource targetSource = new LazyTargetSource() {
        @Override
        public Object getTarget() {
            // 真正使用时才调用 getBean()
            return beanFactory.getBean(descriptor.getDependencyType());
        }
    };
    
    // 用 CGLIB/JDK 动态代理创建代理对象
    ProxyFactory proxyFactory = new ProxyFactory();
    proxyFactory.setTargetSource(targetSource);
    return proxyFactory.getProxy();  // 返回代理，不触发 getBean
}
```

### 时间线对比

```
没有 @Lazy：
  new A(需要真实B) → 💥 卡住，B 也需要 A，死循环
  ──── ① 实例化 ────

有 @Lazy：
  new A(B的代理) → A 实例化成功 → 放入三级缓存 → 填充其他属性 → 初始化完成
  ──── ① ──────   ──── 缓存 ────  ──── ② ────   ──── ③ ────
                                                        ↓
                                              后续调用 b.xxx() 时
                                              代理才触发 getBean("B")
                                              此时 A 已在缓存中，B 可以拿到 A ✅
```

### 总结

| 维度 | 没有 `@Lazy` | 有 `@Lazy` |
|------|-------------|-----------|
| **构造器参数** | 真实的 B 实例 | B 的 CGLIB 代理（空壳） |
| **实例化时** | 必须 `getBean("B")` → 死循环 | 创建代理，不触发 `getBean("B")` |
| **真正获取 B** | 构造器调用时 | 第一次调用 `b.xxx()` 方法时 |
| **打破循环的关键** | — | 把"获取依赖"的时机从实例化阶段**延迟**到了使用阶段 |

> 💡 **一句话**：`@Lazy` 的本质是用代理把依赖获取的时机从"构造时"推迟到"使用时"，让 A 的实例化不再依赖 B 的存在，从而打破了构造器注入的循环依赖死锁。

## Q4: @Autowired 的注入流程？和 @Resource 有什么区别？

**A：**

**@Autowired 注入流程**（`AutowiredAnnotationBeanPostProcessor`）：
1. 在 `postProcessMergedBeanDefinition` 阶段收集注入元数据（扫描 @Autowired 字段/方法）
2. 在 `postProcessProperties` 阶段执行注入：
   - 按类型（byType）从容器中查找候选 Bean
   - 如果找到多个候选：`@Qualifier` > `@Primary` > 属性名匹配
   - 通过反射赋值

**@Autowired vs @Resource**：

| 对比项 | @Autowired | @Resource |
|--------|-----------|-----------|
| 来源 | Spring（`org.springframework`） | JSR-250（`javax.annotation`） |
| 注入方式 | 先 byType，再 byName | 先 byName，再 byType |
| 处理器 | AutowiredAnnotationBPP | CommonAnnotationBPP |
| required 属性 | 支持 `required=false` | 不支持 |
| 支持位置 | 字段、构造器、方法 | 字段、方法（不支持构造器） |

---

## Q5: Spring AOP 的代理创建时机和选择策略？JDK 动态代理和 CGLIB 的底层原理区别？

**A：**

**代理创建时机**：在 `BeanPostProcessor.postProcessAfterInitialization` 阶段，由 `AbstractAutoProxyCreator`（实际是 `AnnotationAwareAspectJAutoProxyCreator`）判断 Bean 是否需要增强，如需要则创建代理。

**选择策略**（SpringBoot 2.x 默认 `proxyTargetClass=true`）：

| 条件 | 代理方式 |
|------|---------|
| 目标类实现了接口 && proxyTargetClass=false | JDK 动态代理 |
| 目标类没有接口 \|\| proxyTargetClass=true | CGLIB |

**底层原理对比**：

| 对比项 | JDK 动态代理 | CGLIB |
|--------|-------------|-------|
| 原理 | 基于 `java.lang.reflect.Proxy`，运行时生成实现目标接口的代理类 | 基于 ASM 字节码库，生成目标类的子类 |
| 调用方式 | `InvocationHandler.invoke()` 反射调用 | `MethodInterceptor.intercept()` + FastClass 直接调用 |
| 限制 | 只能代理接口方法 | 不能代理 final 类/方法 |
| 性能 | 创建快、调用稍慢（反射） | 创建慢、调用快（FastClass 索引直调） |

---

## Q6: Spring 事务的传播行为有哪些？REQUIRED 和 REQUIRES_NEW 在嵌套调用中的区别？

**A：**

| 传播行为 | 语义 |
|---------|------|
| **REQUIRED**（默认） | 有事务就加入，没有就新建 |
| **REQUIRES_NEW** | 总是新建，挂起外层事务 |
| **NESTED** | 有事务则使用 Savepoint 嵌套，没有则新建 |
| SUPPORTS | 有事务就加入，没有就非事务执行 |
| NOT_SUPPORTED | 非事务执行，有事务就挂起 |
| MANDATORY | 必须有事务，否则抛异常 |
| NEVER | 非事务执行，有事务就抛异常 |

**REQUIRED vs REQUIRES_NEW 关键区别**：

```java
@Transactional
public void outer() {
    // 操作1
    inner(); // inner 标注了不同的传播行为
    // 操作2
}
```

- **REQUIRED**：inner 加入 outer 的事务。inner 抛异常 → outer 整体回滚；outer 抛异常 → inner 也回滚
- **REQUIRES_NEW**：inner 新开独立事务，outer 事务挂起。inner 提交/回滚不影响 outer（除非异常向上传播未 catch）；outer 回滚不影响已提交的 inner

**常见坑**：同一个类中方法调用（`this.inner()`）不会经过代理，事务传播行为不生效。解决方案：注入自身 / `AopContext.currentProxy()` / 拆分到不同类。

---

## Q7: @Transactional 事务失效的常见场景有哪些？

**A：**

| 失效场景 | 原因 | 解决方案 |
|---------|------|---------|
| 方法非 public | CGLIB/JDK 代理要求 public 方法 | 改为 public |
| 同类内部调用（self-invocation） | 调用 `this.method()` 绕过了代理 | 注入自身 / `AopContext` / 拆类 |
| 异常被 catch 吞掉 | 事务管理器感知不到异常 | 重新抛出 / 手动 `setRollbackOnly()` |
| 抛出 checked 异常 | 默认只回滚 RuntimeException | `@Transactional(rollbackFor = Exception.class)` |
| 非 Spring 管理的类 | 没有代理 | 确保类被 Spring 管理 |
| 数据库引擎不支持事务 | 如 MyISAM | 使用 InnoDB |
| propagation = NOT_SUPPORTED | 明确以非事务方式运行 | 检查传播行为配置 |

**深入：为什么方法必须是 public 的？**

本质是 **代理机制的技术限制 + Spring 框架的设计约束** 共同决定的：

- **JDK 动态代理**：基于接口实现，接口方法天然是 `public` 的，所以只能代理 `public` 方法
- **CGLIB 代理**：基于继承实现（生成目标类的子类），`private` 方法无法被子类重写，因此无法织入事务增强逻辑
- **Spring 框架层面的显式检查**：即使 CGLIB 技术上能代理 `protected` 方法，Spring 也在源码中主动拒绝了非 public 方法

```java
// AbstractFallbackTransactionAttributeSource#computeTransactionAttribute
protected TransactionAttribute computeTransactionAttribute(Method method, Class<?> targetClass) {
    // 非 public 方法直接返回 null，不应用事务增强
    if (allowPublicMethodsOnly() && !Modifier.isPublic(method.getModifiers())) {
        return null;
    }
    // ...
}
```

> 结论：即使用 CGLIB 且方法是 `protected`，Spring 也不会给它加事务。

---

## Q8: SpringBoot 自动装配原理？从 @SpringBootApplication 到 Bean 注册的完整链路？

**A：**

**核心思想：约定优于配置（Convention over Configuration）**

SpringBoot 自动装配的本质是"约定优于配置"——框架预先定义好一套默认约定（如引入 `spring-boot-starter-web` 就自动配置内嵌 Tomcat、DispatcherServlet 等） ，开发者只需引入对应的 starter 依赖，无需手动编写大量 XML 或 Java 配置，框架会根据 classpath 中的依赖自动推断并装配所需的 Bean。只有当默认约定不满足需求时，才需要显式覆盖配置。

**实现机制：**

```
@SpringBootApplication
  ├─ @SpringBootConfiguration    → 标记为配置类
  ├─ @EnableAutoConfiguration    → ★ 自动装配入口
  │    └─ @Import(AutoConfigurationImportSelector.class)
  └─ @ComponentScan              → 扫描当前包及子包

AutoConfigurationImportSelector 核心流程：
1. 调用 getAutoConfigurationEntry()
2. 通过 SpringFactoriesLoader 加载 META-INF/spring.factories
   （SpringBoot 2.7+ 同时支持 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports）
3. 获取所有 EnableAutoConfiguration 对应的候选类（如 RedisAutoConfiguration）
4. 去重 → 排除（exclude 配置）→ 过滤（@Conditional 条件判断）
5. 符合条件的配置类被导入容器，注册其内部的 @Bean
```

**@Conditional 家族**（核心过滤机制）：

| 注解 | 条件 |
|------|------|
| `@ConditionalOnClass` | Classpath 中存在指定类 |
| `@ConditionalOnMissingBean` | 容器中不存在指定 Bean |
| `@ConditionalOnProperty` | 配置属性匹配 |
| `@ConditionalOnWebApplication` | 是 Web 应用 |

**典型面试追问**：为什么自定义的 Bean 能覆盖自动装配的？因为 `@ConditionalOnMissingBean` 使自动配置只在用户没有自定义 Bean 时才生效。

---

## Q9: SpringBoot 的启动流程？从 main 方法到应用就绪经历了什么？

**A：**

```java
SpringApplication.run(App.class, args)
```

核心流程：

```
1. new SpringApplication()
   ├─ 推断应用类型（SERVLET / REACTIVE / NONE）
   ├─ 加载 ApplicationContextInitializer（spring.factories）
   └─ 加载 ApplicationListener（spring.factories）

2. run()
   ├─ 创建 SpringApplicationRunListeners → 发布 ApplicationStartingEvent
   ├─ 准备 Environment → 发布 ApplicationEnvironmentPreparedEvent
   ├─ 创建 ApplicationContext
   │    └─ SERVLET → AnnotationConfigServletWebServerApplicationContext
   ├─ 准备 Context
   │    ├─ 执行 ApplicationContextInitializer
   │    └─ 注册主配置类的 BeanDefinition
   ├─ refresh() → ★ 核心（见 Q1）
   │    └─ onRefresh() 阶段启动内嵌 Tomcat/Jetty/Undertow
   ├─ 发布 ApplicationStartedEvent
   ├─ 执行 ApplicationRunner / CommandLineRunner
   └─ 发布 ApplicationReadyEvent
```

**面试重点**：理解 `SpringApplication` 的构造阶段和 `run` 方法的生命周期事件顺序。内嵌 Web 服务器在 `onRefresh()` 中启动。

---

## Q10: MyBatis 的 SQL 执行全链路？从 Mapper 接口调用到数据库返回的完整流程？

**A：**

```
1. Mapper 接口调用
   └─ MapperProxy（JDK 动态代理）拦截方法调用

2. MapperProxy.invoke()
   └─ 创建 MapperMethod → 判断 SQL 类型（SELECT/INSERT/UPDATE/DELETE）

3. SqlSession.selectList() / insert() / update() / delete()
   └─ DefaultSqlSession 委托给 Executor

4. Executor 执行
   ├─ SimpleExecutor：每次创建新 Statement
   ├─ ReuseExecutor：复用 Statement
   └─ BatchExecutor：批量执行
   ★ CachingExecutor（装饰器）：先查二级缓存

5. 缓存查找
   ├─ 二级缓存（CachingExecutor）→ 命中则返回
   └─ 一级缓存（BaseExecutor.localCache）→ 命中则返回

6. StatementHandler 处理
   ├─ ParameterHandler → TypeHandler 设置参数
   ├─ 执行 JDBC 操作
   └─ ResultSetHandler → TypeHandler 映射结果

7. 返回结果
```

**一级缓存 vs 二级缓存**：

| 对比项 | 一级缓存 | 二级缓存 |
|--------|---------|---------|
| 作用范围 | SqlSession 级别 | Mapper（namespace）级别 |
| 默认状态 | 开启 | 关闭 |
| 失效条件 | update/commit/close/clearCache | 对应 namespace 的 update 操作 |
| 数据安全 | 返回同一对象引用（注意修改风险） | 序列化存储，返回新对象 |
| Spring 集成 | 每次请求新 SqlSession，基本失效 | 需手动开启 `<cache/>` |

---

## Q11: MyBatis 插件（Interceptor）的原理？如何实现分页/SQL 审计？

**A：**

**原理**：MyBatis 使用 JDK 动态代理，对四大核心对象进行拦截：

| 可拦截对象 | 作用 | 典型拦截场景 |
|-----------|------|-------------|
| Executor | 执行器 | 二级缓存、读写分离 |
| StatementHandler | SQL 语句处理 | SQL 改写（分页）、SQL 审计 |
| ParameterHandler | 参数处理 | 参数加密 |
| ResultSetHandler | 结果集处理 | 结果解密、脱敏 |

**插件链**：多个插件形成代理链，按照配置的逆序执行（后配置的先执行）。

**分页插件（PageHelper）核心原理**：
```java
@Intercepts({
    @Signature(type = Executor.class, method = "query",
        args = {MappedStatement.class, Object.class, RowBounds.class, ResultHandler.class})
})
public class PageInterceptor implements Interceptor {
    public Object intercept(Invocation invocation) {
        // 1. 从 ThreadLocal 获取分页参数
        // 2. 改写 SQL 添加 LIMIT/OFFSET（通过方言 Dialect）
        // 3. 执行 COUNT 查询获取总数
        // 4. 执行分页查询
        // 5. 封装为 Page 对象返回
    }
}
```

---

## Q12: Spring 中常见的设计模式及其应用场景？

**A：**

| 设计模式 | Spring 中的应用 |
|---------|----------------|
| **工厂模式** | BeanFactory / ApplicationContext 创建 Bean |
| **单例模式** | Bean 默认 scope=singleton，三级缓存保证 |
| **代理模式** | AOP（JDK 动态代理 / CGLIB） |
| **模板方法** | JdbcTemplate、RestTemplate、AbstractApplicationContext.refresh() |
| **观察者模式** | ApplicationEvent + ApplicationListener |
| **策略模式** | Resource 接口（ClassPathResource / FileSystemResource / UrlResource） |
| **适配器模式** | HandlerAdapter（将不同类型的 Handler 适配为统一接口） |
| **装饰器模式** | BeanWrapper、HttpServletRequestWrapper |
| **责任链模式** | Filter Chain、HandlerInterceptor Chain |
| **委派模式** | DispatcherServlet 委派给 HandlerMapping → HandlerAdapter |

---

## Q13: SpringMVC 请求处理全流程？DispatcherServlet 的工作原理？

**A：**

```
客户端请求
    ↓
DispatcherServlet.doDispatch()
    ↓
1. HandlerMapping.getHandler()
   └─ 根据 URL 匹配 Handler（Controller 方法）+ 拦截器链 → HandlerExecutionChain
    ↓
2. HandlerAdapter.supports() 选择适配器
   └─ RequestMappingHandlerAdapter 处理 @RequestMapping 方法
    ↓
3. 执行拦截器 preHandle()（按顺序）
    ↓
4. HandlerAdapter.handle()
   ├─ 参数解析（HandlerMethodArgumentResolver）
   │    └─ @RequestBody → RequestResponseBodyMethodProcessor → HttpMessageConverter（Jackson）
   ├─ 反射调用 Controller 方法
   └─ 返回值处理（HandlerMethodReturnValueHandler）
    ↓
5. 执行拦截器 postHandle()（逆序）
    ↓
6. ViewResolver 解析视图（如果返回视图名）
   或 @ResponseBody 直接写入响应体（HttpMessageConverter）
    ↓
7. 执行拦截器 afterCompletion()（逆序）
    ↓
响应客户端
```

**面试重点**：理解 `HandlerMapping`（URL → Handler 映射）、`HandlerAdapter`（适配不同类型 Handler 的调用）、`ArgumentResolver`（参数绑定）这三个核心组件的职责分离。
