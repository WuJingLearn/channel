# Bean 生命周期详解

> 关注**单个 Bean** 从生到死的完整过程，是 Spring 面试的"地基题"。

## 一、生命周期总览

```
┌────────────────────────────────────────────────────────────────┐
│                    Bean 生命周期 8 大阶段                       │
└────────────────────────────────────────────────────────────────┘

  ① 实例化
      │  反射调用构造器，得到"半成品"对象（属性还都是 null）
      ▼
  ② 属性填充（依赖注入）
      │  populateBean，处理 @Autowired / @Resource / @Value
      ▼
  ③ Aware 回调
      │  注入容器自身的引用（BeanName、BeanFactory、ApplicationContext）
      ▼
  ④ BeanPostProcessor 前置处理
      │  postProcessBeforeInitialization（如 @PostConstruct 在此触发）
      ▼
  ⑤ 初始化方法
      │  InitializingBean#afterPropertiesSet → 自定义 init-method
      ▼
  ⑥ BeanPostProcessor 后置处理
      │  postProcessAfterInitialization（⭐ AOP 代理在此创建）
      ▼
  ⑦ 使用中
      │  Bean 就绪，注入到其他 Bean 或对外提供服务
      ▼
  ⑧ 销毁
         容器关闭：@PreDestroy → DisposableBean#destroy → destroy-method
```

## 二、源码入口：`AbstractAutowireCapableBeanFactory#doCreateBean`

```java
protected Object doCreateBean(String beanName, RootBeanDefinition mbd, Object[] args) {
    // ① 实例化：反射调用构造方法
    BeanWrapper instanceWrapper = createBeanInstance(beanName, mbd, args);
    Object bean = instanceWrapper.getWrappedInstance();

    // ⭐ 提前暴露半成品对象到三级缓存（解决循环依赖）
    if (earlySingletonExposure) {
        addSingletonFactory(beanName, () -> getEarlyBeanReference(beanName, mbd, bean));
    }

    // ② 属性填充
    populateBean(beanName, mbd, instanceWrapper);

    // ③④⑤⑥ 初始化（包含 Aware 回调、BPP 前后置、init 方法）
    Object exposedObject = initializeBean(beanName, bean, mbd);

    // ⑧ 注册销毁回调
    registerDisposableBeanIfNecessary(beanName, bean, mbd);

    return exposedObject;
}
```

`initializeBean` 内部的执行顺序：

```java
protected Object initializeBean(String beanName, Object bean, RootBeanDefinition mbd) {
    invokeAwareMethods(beanName, bean);                              // ③ Aware 回调
    Object wrapped = applyBeanPostProcessorsBeforeInitialization(...); // ④ BPP 前置
    invokeInitMethods(beanName, wrapped, mbd);                       // ⑤ 初始化方法
    wrapped = applyBeanPostProcessorsAfterInitialization(...);       // ⑥ BPP 后置（AOP）
    return wrapped;
}
```

## 三、各阶段扩展点详解

### ① 实例化阶段

| 扩展点 | 时机 | 用途 |
|---|---|---|
| `InstantiationAwareBeanPostProcessor#postProcessBeforeInstantiation` | 实例化**之前** | 返回非 null 可**直接短路**整个生命周期（AOP 代理可以在此实现） |
| `SmartInstantiationAwareBeanPostProcessor#determineCandidateConstructors` | 选构造器时 | 处理 `@Autowired` 标注的构造器 |

### ② 属性填充阶段

| 扩展点 | 用途 |
|---|---|
| `InstantiationAwareBeanPostProcessor#postProcessAfterInstantiation` | 控制是否继续属性填充 |
| `AutowiredAnnotationBeanPostProcessor` | 处理 `@Autowired` / `@Value` |
| `CommonAnnotationBeanPostProcessor` | 处理 `@Resource` / `@PostConstruct` / `@PreDestroy` |

### ③ Aware 回调阶段

执行顺序（**先小后大**）：

```
BeanNameAware#setBeanName              （知道自己叫什么）
  → BeanClassLoaderAware#setBeanClassLoader
  → BeanFactoryAware#setBeanFactory     （拿到 BeanFactory）
  → EnvironmentAware                    （以下由 ApplicationContextAwareProcessor 处理）
  → EmbeddedValueResolverAware
  → ResourceLoaderAware
  → ApplicationEventPublisherAware
  → MessageSourceAware
  → ApplicationContextAware            （拿到完整 ApplicationContext）
```

> 注意：前 3 个由 `invokeAwareMethods` 直接调用；后面的由 `ApplicationContextAwareProcessor` 这个 BPP 在 BPP 前置阶段统一处理。

### ④⑤⑥ 初始化阶段

完整顺序（**面试必背**）：

```
@PostConstruct          ← CommonAnnotationBeanPostProcessor 在 BPP 前置执行
  ↓
InitializingBean#afterPropertiesSet
  ↓
@Bean(initMethod = "xxx") 或 <bean init-method="xxx"/>
  ↓
BeanPostProcessor#postProcessAfterInitialization  ← AOP 代理在此创建（AbstractAutoProxyCreator）
```

### ⑧ 销毁阶段

容器关闭（`ConfigurableApplicationContext#close()`）时触发：

```
@PreDestroy
  ↓
DisposableBean#destroy
  ↓
@Bean(destroyMethod = "xxx")
```

## 四、循环依赖与三级缓存

> Spring **只能解决单例 Bean 通过 setter / 字段注入的循环依赖**，构造器循环依赖**无解**。

### 三级缓存数据结构

```java
public class DefaultSingletonBeanRegistry {
    /** 一级缓存：完整的单例 Bean */
    Map<String, Object> singletonObjects = new ConcurrentHashMap<>(256);

    /** 二级缓存：提前暴露的半成品（已实例化、未完成属性填充和初始化） */
    Map<String, Object> earlySingletonObjects = new ConcurrentHashMap<>(16);

    /** 三级缓存：ObjectFactory，调用 getObject() 时才生成代理对象 */
    Map<String, ObjectFactory<?>> singletonFactories = new HashMap<>(16);
}
```

### 解决循环依赖的执行流程（A → B → A）

```
1. 创建 A：实例化 A → 把 A 的 ObjectFactory 放入【三级缓存】
2. A 注入 B：发现需要 B，触发创建 B
3. 创建 B：实例化 B → 把 B 的 ObjectFactory 放入【三级缓存】
4. B 注入 A：调用 getSingleton("A")
   ├── 一级缓存没有
   ├── 二级缓存没有
   └── 三级缓存有！调用 ObjectFactory.getObject()
       ├── 触发 SmartInstantiationAwareBeanPostProcessor#getEarlyBeanReference
       │   （如果 A 需要 AOP，这里返回的是代理对象）
       ├── 把结果放入【二级缓存】，从【三级缓存】移除
       └── 返回半成品 A 给 B
5. B 完成属性填充和初始化 → 放入【一级缓存】
6. A 拿到完整的 B，完成属性填充和初始化 → 放入【一级缓存】
```

### 为什么需要三级而不是二级？

**核心原因：AOP 代理**。

如果只有二级缓存，提前暴露的就必须直接是代理对象，意味着**每个 Bean 实例化后都要立即判断并创建代理**，违背了"AOP 应该在初始化后才创建代理"的设计。

三级缓存通过 `ObjectFactory` **延迟创建代理**：只有真正发生循环依赖、有别的 Bean 需要它时，才调用 `getEarlyBeanReference` 生成代理。

## 五、面试高频追问

### Q1: `BeanFactory` 和 `FactoryBean` 的区别？

- `BeanFactory`：**容器**本身，所有 IoC 容器的顶层接口
- `FactoryBean`：**特殊的 Bean**，本身实现了 `FactoryBean` 接口，由其 `getObject()` 方法生产真正的 Bean
  - 取 `FactoryBean` 本身：`getBean("&beanName")`
  - 取 `getObject()` 产物：`getBean("beanName")`

### Q2: `BeanPostProcessor` 和 `BeanFactoryPostProcessor` 的区别？

| | `BeanFactoryPostProcessor` (BFPP) | `BeanPostProcessor` (BPP) |
|---|---|---|
| 作用对象 | `BeanDefinition`（Bean 的"图纸"） | Bean 实例 |
| 执行时机 | 所有 Bean 实例化**之前** | 每个 Bean 实例化**之后** |
| 典型实现 | `ConfigurationClassPostProcessor`（解析 `@Configuration`） | `AutowiredAnnotationBeanPostProcessor`（处理 `@Autowired`） |

### Q3: Bean 是单例还是多例？

默认**单例**（`@Scope("singleton")`）。其他作用域：
- `prototype`：每次 `getBean` 都创建新实例，**Spring 不管理其销毁**
- `request` / `session` / `application`：Web 环境专用
- `websocket`：WebSocket 会话级

### Q4: `@Autowired` 是在哪个阶段处理的？

属性填充阶段（②），由 `AutowiredAnnotationBeanPostProcessor` 实现的 `InstantiationAwareBeanPostProcessor#postProcessProperties` 完成。

### Q5: AOP 代理对象是什么时候创建的？

正常情况下，在 BPP 后置处理（⑥）阶段，由 `AbstractAutoProxyCreator#postProcessAfterInitialization` 创建。
**循环依赖**场景下，会提前到第 4 步（B 引用 A 时），由 `getEarlyBeanReference` 创建。
