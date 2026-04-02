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

---

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

---

## Q8: SpringBoot 自动装配原理？从 @SpringBootApplication 到 Bean 注册的完整链路？

**A：**

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
