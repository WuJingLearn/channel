# Spring 启动流程详解

> 关注 **IoC 容器**（`ApplicationContext`）如何从 0 到 1 启动起来。
> 核心就是一个方法：`AbstractApplicationContext#refresh()`，俗称 **"refresh 12 步"**。

## 一、启动入口

无论是传统 Spring 还是 SpringBoot，最终都会走到 `refresh()`：

```java
// 传统 Spring
ApplicationContext ctx = new ClassPathXmlApplicationContext("applicationContext.xml");
ApplicationContext ctx = new AnnotationConfigApplicationContext(AppConfig.class);

// SpringBoot
SpringApplication.run(Application.class, args);
// → 内部调用 refreshContext(context) → context.refresh()
```

## 二、refresh() 核心源码

```java
// org.springframework.context.support.AbstractApplicationContext#refresh
public void refresh() throws BeansException, IllegalStateException {
    synchronized (this.startupShutdownMonitor) {
        // 1. 准备刷新
        prepareRefresh();

        // 2. 创建 BeanFactory，加载 BeanDefinition
        ConfigurableListableBeanFactory beanFactory = obtainFreshBeanFactory();

        // 3. BeanFactory 预处理
        prepareBeanFactory(beanFactory);

        try {
            // 4. 子类扩展点（SpringBoot 在此注册 WebApplicationContext 相关 BPP）
            postProcessBeanFactory(beanFactory);

            // 5. ⭐ 执行 BeanFactoryPostProcessor（@Configuration 解析、@ComponentScan 扫描）
            invokeBeanFactoryPostProcessors(beanFactory);

            // 6. 注册 BeanPostProcessor（注意：只是注册，不执行）
            registerBeanPostProcessors(beanFactory);

            // 7. 初始化 MessageSource（国际化）
            initMessageSource();

            // 8. 初始化事件广播器
            initApplicationEventMulticaster();

            // 9. 子类扩展（⭐ SpringBoot 在此创建并启动 Tomcat！）
            onRefresh();

            // 10. 注册监听器
            registerListeners();

            // 11. ⭐⭐⭐ 实例化所有非懒加载的单例 Bean（Bean 生命周期在此触发）
            finishBeanFactoryInitialization(beanFactory);

            // 12. 完成刷新，发布 ContextRefreshedEvent
            finishRefresh();
        }
        catch (BeansException ex) {
            destroyBeans();
            cancelRefresh(ex);
            throw ex;
        }
    }
}
```

## 三、12 步逐个拆解

### Step 1: `prepareRefresh()` —— 准备刷新

- 记录启动时间、设置容器状态为 `active`
- 初始化 `Environment`，处理占位符
- 校验必需的属性（`requiredProperties`）
- 初始化早期事件集合 `earlyApplicationEvents`

### Step 2: `obtainFreshBeanFactory()` —— 获取 BeanFactory

```java
// 不同子类的实现差异最大的一步
// XML 模式：解析 XML，注册 BeanDefinition
// 注解模式：等到第 5 步 ConfigurationClassPostProcessor 才扫描
```

- 创建 `DefaultListableBeanFactory`（最强大的 `BeanFactory` 实现）
- 此时容器内只有少量"种子"BeanDefinition

### Step 3: `prepareBeanFactory()` —— 预配置 BeanFactory

- 设置类加载器、SpEL 解析器、属性编辑器
- 注册 **`ApplicationContextAwareProcessor`**（处理 `EnvironmentAware`、`ApplicationContextAware` 等回调）
- 设置忽略自动装配的接口（如 `BeanFactoryAware`，避免被 `@Autowired` 错误注入）
- 注册可解析依赖（`BeanFactory.class`、`ApplicationContext.class` → 当前容器自身）

### Step 4: `postProcessBeanFactory()` —— 子类扩展

模板方法，由子类覆写。例如：

- `GenericWebApplicationContext` 注册 `ServletContextAwareProcessor`
- SpringBoot 的 `AnnotationConfigServletWebServerApplicationContext` 注册 Web 相关组件

### Step 5: `invokeBeanFactoryPostProcessors()` ⭐ —— 执行 BFPP

**全场最重要的一步**。BFPP 在这里被执行，可以修改/新增 `BeanDefinition`。

核心 BFPP：**`ConfigurationClassPostProcessor`**

```
ConfigurationClassPostProcessor 的工作：
  ├── 解析 @Configuration 类
  ├── 处理 @ComponentScan      ← 扫描包，注册所有 @Component
  ├── 处理 @Import              ← 导入其他配置类（自动装配核心机制！）
  ├── 处理 @ImportResource      ← 导入 XML 配置
  ├── 处理 @PropertySource      ← 加载 properties 文件
  └── 处理 @Bean 方法           ← 注册 @Bean 标注的方法为 BeanDefinition
```

**SpringBoot 的自动装配就是通过 `@Import(AutoConfigurationImportSelector.class)` 在这里被触发的**。

执行顺序（**面试常考**）：

```
PriorityOrdered 的 BFPP
  → Ordered 的 BFPP
  → 普通 BFPP
（每个分组内：先执行 BeanDefinitionRegistryPostProcessor，再执行 BeanFactoryPostProcessor）
```

### Step 6: `registerBeanPostProcessors()` —— 注册 BPP

只是**注册**，不执行。BPP 会在每个 Bean 创建过程中被调用。

注册顺序与 BFPP 类似：`PriorityOrdered` → `Ordered` → 普通。

常见 BPP：

| BPP | 作用 |
|---|---|
| `AutowiredAnnotationBeanPostProcessor` | 处理 `@Autowired` / `@Value` |
| `CommonAnnotationBeanPostProcessor` | 处理 `@Resource` / `@PostConstruct` / `@PreDestroy` |
| `ApplicationContextAwareProcessor` | 处理 `ApplicationContextAware` 等回调 |
| `AbstractAutoProxyCreator` | AOP 代理创建（如 `AnnotationAwareAspectJAutoProxyCreator`） |

### Step 7: `initMessageSource()` —— 国际化

注册 `MessageSource`，用于 `messageSource.getMessage("welcome.msg", null, locale)` 这类国际化场景。

### Step 8: `initApplicationEventMulticaster()` —— 事件广播器

创建 `SimpleApplicationEventMulticaster`，负责把 `ApplicationEvent` 派发给所有 `ApplicationListener`。

### Step 9: `onRefresh()` ⭐ —— 子类扩展

**SpringBoot 的内嵌容器在这里启动！**

```java
// org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext
@Override
protected void onRefresh() {
    super.onRefresh();
    try {
        createWebServer();  // ⭐ 创建并启动 Tomcat / Jetty / Undertow
    }
    catch (Throwable ex) {
        throw new ApplicationContextException("Unable to start web server", ex);
    }
}
```

### Step 10: `registerListeners()` —— 注册监听器

- 把容器中所有 `ApplicationListener` Bean 注册到广播器
- 派发之前积压的早期事件（`earlyApplicationEvents`）

### Step 11: `finishBeanFactoryInitialization()` ⭐⭐⭐ —— 实例化所有 Bean

**Bean 生命周期主要发生在这一步**。

```java
protected void finishBeanFactoryInitialization(ConfigurableListableBeanFactory beanFactory) {
    // 1. 初始化 ConversionService
    // 2. 注册嵌入式值解析器（处理 ${...} 占位符）
    // 3. 提前初始化 LoadTimeWeaverAware（AOP LTW）
    // 4. 冻结配置（不允许再修改 BeanDefinition）
    beanFactory.freezeConfiguration();

    // 5. ⭐ 实例化所有非懒加载的单例 Bean
    beanFactory.preInstantiateSingletons();
}
```

`preInstantiateSingletons` 内部遍历所有 BeanDefinition，对每个非抽象、非懒加载、单例的 Bean 调用 `getBean(beanName)`，触发完整的 [Bean 生命周期](./BeanLifecycle.md)。

### Step 12: `finishRefresh()` —— 完成刷新

```java
protected void finishRefresh() {
    clearResourceCaches();                   // 清理资源缓存
    initLifecycleProcessor();                // 初始化生命周期处理器
    getLifecycleProcessor().onRefresh();     // 回调 Lifecycle Bean 的 start()
    publishEvent(new ContextRefreshedEvent(this));  // ⭐ 发布刷新完成事件
    LiveBeansView.registerApplicationContext(this);
}
```

至此，容器启动完毕，所有 Bean 就绪，对外提供服务。

## 四、整体时序图

```
ApplicationContext.refresh()
    │
    ├─► [1] prepareRefresh                  环境准备
    ├─► [2] obtainFreshBeanFactory          创建 BeanFactory
    ├─► [3] prepareBeanFactory              BeanFactory 预配置
    ├─► [4] postProcessBeanFactory          子类扩展
    │
    ├─► [5] invokeBeanFactoryPostProcessors ⭐ 执行 BFPP
    │       └── ConfigurationClassPostProcessor
    │           ├── 解析 @Configuration
    │           ├── @ComponentScan 扫描
    │           └── @Import （⭐ SpringBoot 自动装配入口）
    │
    ├─► [6] registerBeanPostProcessors      注册 BPP
    ├─► [7] initMessageSource               国际化
    ├─► [8] initApplicationEventMulticaster 事件广播器
    │
    ├─► [9] onRefresh                       ⭐ SpringBoot 在此启动 Tomcat
    │
    ├─►[10] registerListeners               注册监听器
    │
    ├─►[11] finishBeanFactoryInitialization ⭐⭐⭐ 实例化所有单例 Bean
    │       └── for each BeanDefinition:
    │           └── doCreateBean
    │               ├── ① 实例化
    │               ├── ② 属性填充
    │               ├── ③ Aware 回调
    │               ├── ④ BPP 前置（@PostConstruct）
    │               ├── ⑤ 初始化方法
    │               └── ⑥ BPP 后置（AOP 代理）
    │
    └─►[12] finishRefresh                   发布 ContextRefreshedEvent
```

## 五、面试高频追问

### Q1: BeanDefinition 和 Bean 的区别？

- `BeanDefinition`：Bean 的"图纸"/"元数据"，包含类名、作用域、是否懒加载、依赖关系等
- `Bean`：根据 BeanDefinition **实例化**出来的对象

容器启动过程：**先收集所有 BeanDefinition（Step 5），再统一实例化（Step 11）**。

### Q2: 为什么 BFPP 要在 BPP 之前？

因为 BFPP 可能**新增/修改 BeanDefinition**（比如解析 `@Configuration` 注册新的 Bean），如果 BPP 先于 BFPP，那 BFPP 新增的 Bean 就没机会被 BPP 处理。

### Q3: `ApplicationContext` 和 `BeanFactory` 的区别？

| | `BeanFactory` | `ApplicationContext` |
|---|---|---|
| 角色 | 底层容器接口 | 高级容器接口（继承 BeanFactory） |
| 加载方式 | 懒加载（用到才创建） | 默认饿加载（启动时创建所有单例） |
| 扩展功能 | 仅 IoC | 国际化、事件、资源加载、AOP 等 |

### Q4: Spring 容器启动慢，怎么排查？

1. 加 `-Ddebug` 参数看 BPP / BFPP 执行情况
2. 通过 `ContextRefreshedEvent` 监听器记录启动时间
3. SpringBoot 2.4+ 提供 `ApplicationStartup` API（如 `BufferingApplicationStartup`）
4. 检查 `@PostConstruct` 中是否有耗时操作（如远程调用、加载大文件）
5. 大量 `@ComponentScan` 包扫描可以缩小范围
6. 考虑懒加载：`spring.main.lazy-initialization=true`

### Q5: Spring 的事件机制？

- 事件类：继承 `ApplicationEvent`
- 监听器：实现 `ApplicationListener<E>` 或用 `@EventListener` 注解
- 发布：`ApplicationContext.publishEvent(event)`
- 内置事件：`ContextRefreshedEvent`、`ContextStartedEvent`、`ContextStoppedEvent`、`ContextClosedEvent`

```java
@Component
public class StartupListener {
    @EventListener
    public void onReady(ContextRefreshedEvent event) {
        // 容器刷新完成后执行
    }
}
```
