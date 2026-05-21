# SpringBoot 启动流程详解

> 关注**整个应用**如何启动起来，在 Spring 启动流程之上叠加了**约定大于配置、自动装配、内嵌容器**等能力。
> 核心入口：`SpringApplication#run`。

## 一、启动入口

```java
@SpringBootApplication
public class Application {
    public static void main(String[] args) {
        SpringApplication.run(Application.class, args);
    }
}
```

`@SpringBootApplication` 是一个组合注解：

```java
@SpringBootConfiguration                    // = @Configuration，标记为配置类
@EnableAutoConfiguration                    // ⭐ 自动装配的核心入口
@ComponentScan(                             // 扫描启动类所在包及其子包
    excludeFilters = {
        @Filter(type = CUSTOM, classes = TypeExcludeFilter.class),
        @Filter(type = CUSTOM, classes = AutoConfigurationExcludeFilter.class)
    }
)
public @interface SpringBootApplication {
    // 省略具体属性
}
```

## 二、SpringApplication.run 核心源码

```java
public ConfigurableApplicationContext run(String... args) {
    long startTime = System.nanoTime();
    DefaultBootstrapContext bootstrapContext = createBootstrapContext();
    ConfigurableApplicationContext context = null;
    configureHeadlessProperty();

    // ① 获取 SpringApplicationRunListeners（核心是 EventPublishingRunListener）
    SpringApplicationRunListeners listeners = getRunListeners(args);
    listeners.starting(bootstrapContext, this.mainApplicationClass);

    try {
        ApplicationArguments applicationArguments = new DefaultApplicationArguments(args);

        // ② 准备 Environment（加载 application.yml、命令行参数、profile）
        ConfigurableEnvironment environment = prepareEnvironment(listeners, bootstrapContext, applicationArguments);

        // ③ 打印 Banner
        Banner printedBanner = printBanner(environment);

        // ④ 创建 ApplicationContext（根据应用类型选择）
        context = createApplicationContext();

        // ⑤ 准备 Context（注册主配置类、应用 Initializer、发布 ContextPrepared/Loaded 事件）
        prepareContext(bootstrapContext, context, environment, listeners, applicationArguments, printedBanner);

        // ⑥ ⭐⭐⭐ 刷新 Context（调用 Spring 的 refresh()）
        refreshContext(context);

        // ⑦ 刷新后处理（空实现，预留扩展）
        afterRefresh(context, applicationArguments);

        Duration timeTakenToStartup = Duration.ofNanos(System.nanoTime() - startTime);
        listeners.started(context, timeTakenToStartup);  // 发布 ApplicationStartedEvent

        // ⑧ 调用 Runner（CommandLineRunner / ApplicationRunner）
        callRunners(context, applicationArguments);
    }
    catch (Throwable ex) {
        handleRunFailure(context, ex, listeners);
        throw new IllegalStateException(ex);
    }

    try {
        Duration timeTakenToReady = Duration.ofNanos(System.nanoTime() - startTime);
        listeners.ready(context, timeTakenToReady);  // 发布 ApplicationReadyEvent
    }
    catch (Throwable ex) {
        handleRunFailure(context, ex, null);
        throw new IllegalStateException(ex);
    }
    return context;
}
```

## 三、构造阶段：`new SpringApplication(...)`

`run` 方法内部第一件事是构造 `SpringApplication` 对象：

```java
public SpringApplication(ResourceLoader resourceLoader, Class<?>... primarySources) {
    this.resourceLoader = resourceLoader;
    this.primarySources = new LinkedHashSet<>(Arrays.asList(primarySources));

    // 1. ⭐ 推断应用类型：SERVLET / REACTIVE / NONE
    this.webApplicationType = WebApplicationType.deduceFromClasspath();

    // 2. 加载 BootstrapRegistryInitializer（从 spring.factories）
    this.bootstrapRegistryInitializers = getBootstrapRegistryInitializersFromSpringFactories();

    // 3. ⭐ 加载 ApplicationContextInitializer（从 spring.factories）
    setInitializers(getSpringFactoriesInstances(ApplicationContextInitializer.class));

    // 4. ⭐ 加载 ApplicationListener（从 spring.factories）
    setListeners(getSpringFactoriesInstances(ApplicationListener.class));

    // 5. 推断主类（包含 main 方法的类）
    this.mainApplicationClass = deduceMainApplicationClass();
}
```

**应用类型推断**：

| 类路径下的类 | 推断结果 |
|---|---|
| 有 `Servlet` + `ConfigurableWebApplicationContext` | `SERVLET`（默认 Web 应用） |
| 有 `DispatcherHandler` 但没有 `DispatcherServlet` | `REACTIVE`（WebFlux） |
| 都没有 | `NONE`（普通应用） |

## 四、运行阶段 8 大步骤

### Step 1: 获取并启动 Listeners

```java
SpringApplicationRunListeners listeners = getRunListeners(args);
listeners.starting(bootstrapContext, mainApplicationClass);  // 发布 ApplicationStartingEvent
```

核心实现：**`EventPublishingRunListener`**，把 SpringApplication 的生命周期事件转换为 `ApplicationEvent` 发布出去。

### Step 2: 准备 Environment

```java
private ConfigurableEnvironment prepareEnvironment(SpringApplicationRunListeners listeners,
        DefaultBootstrapContext bootstrapContext, ApplicationArguments applicationArguments) {
    ConfigurableEnvironment environment = getOrCreateEnvironment();
    configureEnvironment(environment, applicationArguments.getSourceArgs());

    // ⭐ 配置 PropertySource，处理命令行参数
    ConfigurationPropertySources.attach(environment);

    // ⭐ 发布 ApplicationEnvironmentPreparedEvent
    // ↓ EnvironmentPostProcessorApplicationListener 在此监听该事件
    // ↓ 触发 ConfigDataEnvironmentPostProcessor 加载 application.yml / application.properties
    listeners.environmentPrepared(bootstrapContext, environment);

    bindToSpringApplication(environment);
    return environment;
}
```

**配置文件加载顺序**（优先级从低到高）：

```
1. classpath:application.yml / .properties
2. classpath:application-{profile}.yml
3. 当前目录 ./application.yml
4. 当前目录 ./application-{profile}.yml
5. 操作系统环境变量
6. JVM 系统属性（-D 参数）
7. 命令行参数（--key=value）
```

> SpringBoot 2.4+ 改用 `ConfigDataEnvironmentPostProcessor` 替代旧的 `ConfigFileApplicationListener`。

### Step 3: 打印 Banner

读取 `banner.txt` / `banner.gif` / `banner.jpg` / `banner.png`，没有则打印默认 banner。
可通过 `spring.main.banner-mode=off` 关闭。

### Step 4: 创建 ApplicationContext

根据 `webApplicationType` 选择具体的 Context 实现：

| 应用类型 | Context 实现 |
|---|---|
| `SERVLET` | `AnnotationConfigServletWebServerApplicationContext` |
| `REACTIVE` | `AnnotationConfigReactiveWebServerApplicationContext` |
| `NONE` | `AnnotationConfigApplicationContext` |

### Step 5: 准备 Context

```java
private void prepareContext(DefaultBootstrapContext bootstrapContext,
        ConfigurableApplicationContext context, ConfigurableEnvironment environment,
        SpringApplicationRunListeners listeners, ApplicationArguments applicationArguments,
        Banner printedBanner) {
    context.setEnvironment(environment);
    postProcessApplicationContext(context);

    // ⭐ 应用所有 ApplicationContextInitializer
    applyInitializers(context);

    // 发布 ApplicationContextInitializedEvent
    listeners.contextPrepared(context);

    // 注册启动相关的单例 Bean
    beanFactory.registerSingleton("springApplicationArguments", applicationArguments);

    // ⭐ 注册主配置类（@SpringBootApplication 标注的类）作为 BeanDefinition
    load(context, sources.toArray(new Object[0]));

    // 发布 ApplicationPreparedEvent
    listeners.contextLoaded(context);
}
```

### Step 6: ⭐⭐⭐ 刷新 Context

```java
private void refreshContext(ConfigurableApplicationContext context) {
    if (this.registerShutdownHook) {
        context.registerShutdownHook();  // 注册 JVM 关闭钩子，优雅关闭
    }
    refresh(context);  // ← 调用 Spring 的 refresh()
}
```

**这一步会触发完整的 [Spring 启动流程](./SpringStartup.md)**，包括：
- 在 `invokeBeanFactoryPostProcessors` 中触发**自动装配**（详见下文）
- 在 `onRefresh` 中**启动内嵌 Tomcat**
- 在 `finishBeanFactoryInitialization` 中实例化所有 Bean

### Step 7: afterRefresh

`SpringApplication` 中是空实现，预留给子类扩展。

### Step 8: callRunners

```java
private void callRunners(ApplicationContext context, ApplicationArguments args) {
    List<Object> runners = new ArrayList<>();
    runners.addAll(context.getBeansOfType(ApplicationRunner.class).values());
    runners.addAll(context.getBeansOfType(CommandLineRunner.class).values());
    AnnotationAwareOrderComparator.sort(runners);
    for (Object runner : new LinkedHashSet<>(runners)) {
        if (runner instanceof ApplicationRunner) {
            callRunner((ApplicationRunner) runner, args);
        }
        if (runner instanceof CommandLineRunner) {
            callRunner((CommandLineRunner) runner, args);
        }
    }
}
```

**`ApplicationRunner` vs `CommandLineRunner`**：

| | `ApplicationRunner` | `CommandLineRunner` |
|---|---|---|
| 参数类型 | `ApplicationArguments`（结构化） | `String[]`（原始） |
| 可获取 | `getOptionNames()` / `getNonOptionArgs()` | 仅原始数组 |
| 用途 | 复杂命令行解析 | 简单场景 |

## 五、⭐ 自动装配原理（重中之重）

### 触发链路

```
@SpringBootApplication
  └── @EnableAutoConfiguration
       └── @Import(AutoConfigurationImportSelector.class)
            └── 在 Step 5 (refresh 的第 5 步) 由 ConfigurationClassPostProcessor 处理
                 └── AutoConfigurationImportSelector#selectImports()
                      └── 加载所有自动配置类
```

### `AutoConfigurationImportSelector` 核心逻辑

```java
public String[] selectImports(AnnotationMetadata annotationMetadata) {
    // 1. 加载所有候选自动配置类
    List<String> configurations = getCandidateConfigurations(metadata, attributes);

    // 2. 去重
    configurations = removeDuplicates(configurations);

    // 3. 处理 exclude（@SpringBootApplication(exclude = xxx.class)）
    Set<String> exclusions = getExclusions(metadata, attributes);
    configurations.removeAll(exclusions);

    // 4. ⭐ 过滤：根据 @ConditionalOnXxx 条件筛选
    configurations = getConfigurationClassFilter().filter(configurations);

    // 5. 触发 AutoConfigurationImportEvent
    fireAutoConfigurationImportEvents(configurations, exclusions);

    return StringUtils.toStringArray(configurations);
}
```

### 候选配置类的来源

**SpringBoot 2.7 之前**：

```
META-INF/spring.factories
  ├── key: org.springframework.boot.autoconfigure.EnableAutoConfiguration
  └── value: 自动配置类全限定名（逗号分隔）
```

**SpringBoot 2.7+ 推荐方式**（3.0+ 强制）：

```
META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
  └── 每行一个全限定类名
```

### `@ConditionalOnXxx` 条件注解

| 注解 | 含义 |
|---|---|
| `@ConditionalOnClass` | 类路径下存在某个类时生效 |
| `@ConditionalOnMissingClass` | 类路径下不存在某个类时生效 |
| `@ConditionalOnBean` | 容器中存在某个 Bean 时生效 |
| `@ConditionalOnMissingBean` | 容器中不存在某个 Bean 时生效（**用户自定义优先**） |
| `@ConditionalOnProperty` | 配置属性满足条件时生效 |
| `@ConditionalOnWebApplication` | 是 Web 应用时生效 |
| `@ConditionalOnExpression` | SpEL 表达式为 true 时生效 |

### 典型自动配置类示例

```java
@Configuration(proxyBeanMethods = false)
@ConditionalOnClass({ DataSource.class, EmbeddedDatabaseType.class })
@ConditionalOnMissingBean(type = "io.r2dbc.spi.ConnectionFactory")
@EnableConfigurationProperties(DataSourceProperties.class)
@Import(DataSourcePoolMetadataProvidersConfiguration.class)
public class DataSourceAutoConfiguration {

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingBean(DataSource.class)
    @ConditionalOnProperty(name = "spring.datasource.type")
    static class Generic {
        // 通用数据源配置
    }

    @Bean
    @ConfigurationProperties(prefix = "spring.datasource.hikari")
    public HikariDataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }
}
```

**关键点**：
- `@ConditionalOnMissingBean(DataSource.class)` 让用户自定义的 `DataSource` 优先级更高
- `@EnableConfigurationProperties` 把 `DataSourceProperties` 注册为 Bean，自动绑定 `application.yml` 中的 `spring.datasource.*`

## 六、内嵌 Web 容器启动

发生在 `refresh()` 的第 9 步 `onRefresh()`：

```java
// ServletWebServerApplicationContext
@Override
protected void onRefresh() {
    super.onRefresh();
    try {
        createWebServer();
    }
    catch (Throwable ex) {
        throw new ApplicationContextException("Unable to start web server", ex);
    }
}

private void createWebServer() {
    WebServer webServer = this.webServer;
    ServletContext servletContext = getServletContext();
    if (webServer == null && servletContext == null) {
        // ⭐ 通过 ServletWebServerFactory 创建 Tomcat / Jetty / Undertow
        ServletWebServerFactory factory = getWebServerFactory();
        this.webServer = factory.getWebServer(getSelfInitializer());
        // 注册 GracefulShutdown、WebServerStartStopLifecycle 等
    }
    initPropertySources();
}
```

**默认是 Tomcat**：`TomcatServletWebServerFactory` → `new Tomcat()` → `tomcat.start()`。
切换 Jetty / Undertow：排除 `spring-boot-starter-tomcat`，引入对应 starter。

## 七、SpringBoot 应用事件全景

```
ApplicationStartingEvent             ← run() 一开始
  ↓
ApplicationEnvironmentPreparedEvent  ← Environment 准备好（application.yml 已加载）
  ↓
ApplicationContextInitializedEvent   ← Initializer 应用完成，refresh 之前
  ↓
ApplicationPreparedEvent             ← BeanDefinition 加载完，refresh 之前
  ↓
ContextRefreshedEvent                ← refresh 完成（Spring 原生事件）
  ↓
ApplicationStartedEvent              ← refresh 完成后，runner 之前
  ↓
ApplicationReadyEvent                ← runner 执行完毕，应用就绪 ⭐
  ↓
（应用关闭时）
ContextClosedEvent
ApplicationFailedEvent               ← 启动失败时
```

监听示例：

```java
@Component
public class MyListener {
    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        System.out.println("应用启动完毕，可以对外提供服务");
    }
}
```

## 八、完整时序图

```
SpringApplication.run(Application.class, args)
    │
    ├─► new SpringApplication(...)
    │   ├── 推断 webApplicationType
    │   ├── 加载 ApplicationContextInitializer (spring.factories)
    │   ├── 加载 ApplicationListener (spring.factories)
    │   └── 推断主类
    │
    ├─► [1] listeners.starting()                   → ApplicationStartingEvent
    ├─► [2] prepareEnvironment()                   → ApplicationEnvironmentPreparedEvent
    │       └── 加载 application.yml / profile
    ├─► [3] printBanner()
    ├─► [4] createApplicationContext()             ← 根据应用类型选 Context
    ├─► [5] prepareContext()                       → ApplicationContextInitializedEvent
    │       │                                      → ApplicationPreparedEvent
    │       └── 注册主配置类
    │
    ├─► [6] refreshContext(context) ⭐⭐⭐
    │       └── AbstractApplicationContext.refresh()  ← Spring 启动流程
    │           ├── invokeBeanFactoryPostProcessors
    │           │   └── ConfigurationClassPostProcessor
    │           │       └── 解析 @Import(AutoConfigurationImportSelector)
    │           │           └── 加载 META-INF/spring/...AutoConfiguration.imports
    │           │               └── @ConditionalOnXxx 过滤
    │           ├── onRefresh
    │           │   └── createWebServer ← 启动 Tomcat
    │           ├── finishBeanFactoryInitialization
    │           │   └── 实例化所有 Bean (Bean 生命周期)
    │           └── finishRefresh                  → ContextRefreshedEvent
    │
    ├─► [7] afterRefresh()
    ├─►     listeners.started()                    → ApplicationStartedEvent
    ├─► [8] callRunners()                          ← ApplicationRunner / CommandLineRunner
    └─►     listeners.ready()                      → ApplicationReadyEvent
```

## 九、面试高频追问

### Q1: `@SpringBootApplication` 由哪些注解组成？

`@SpringBootConfiguration`（= `@Configuration`） + `@EnableAutoConfiguration` + `@ComponentScan`。

### Q2: 自动装配是如何实现的？

1. `@EnableAutoConfiguration` 通过 `@Import(AutoConfigurationImportSelector.class)` 引入
2. `AutoConfigurationImportSelector` 读取 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`（2.7 之前是 `META-INF/spring.factories`）
3. 通过 `@ConditionalOnXxx` 系列条件注解过滤生效的配置类
4. 最终把符合条件的配置类作为 `@Import` 的目标，由 `ConfigurationClassPostProcessor` 在 `refresh()` 第 5 步注册到容器

### Q3: 怎么自定义 starter？

1. 创建 `xxx-spring-boot-autoconfigure` 模块，写 `@Configuration` 类
2. 在 `META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` 中声明
3. 创建 `xxx-spring-boot-starter` 模块，依赖 autoconfigure 和必要的第三方库
4. 用户引入 starter 后即可自动装配

### Q4: SpringBoot 怎么实现 jar 包直接启动？

`spring-boot-maven-plugin` 打包出的 fat jar：
- 入口是 `JarLauncher`（或 `WarLauncher`）
- 自定义 `LaunchedURLClassLoader` 加载 `BOOT-INF/lib/` 下的依赖 jar
- 找到主类（`Start-Class` 清单属性）反射调用其 `main`

### Q5: SpringBoot 中如何在容器启动后立即执行某段代码？

按推荐程度排序：

1. **`ApplicationRunner` / `CommandLineRunner`**：实现接口，会在 `callRunners` 阶段调用
2. **`@EventListener(ApplicationReadyEvent.class)`**：监听就绪事件
3. **`@PostConstruct`**：Bean 初始化时执行（**容器还没完全就绪，慎用**）
4. **`InitializingBean#afterPropertiesSet`**：同上

### Q6: SpringBoot 启动慢，怎么优化？

1. **懒加载**：`spring.main.lazy-initialization=true`
2. **缩小扫描范围**：`@ComponentScan(basePackages = "...")` 指定具体包
3. **排除不必要的自动配置**：`@SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})`
4. **使用 AOT / GraalVM Native Image**（SpringBoot 3.0+）
5. **分析启动耗时**：`BufferingApplicationStartup`、`spring-boot-starter-actuator` 的 `/actuator/startup`
6. 减少 `@PostConstruct` 中的耗时操作

### Q7: SpringBoot 和 Spring 的核心区别？

| | Spring | SpringBoot |
|---|---|---|
| 配置 | XML / Java Config | 约定大于配置 + `application.yml` |
| 依赖管理 | 手动管理版本 | starter 一站式 |
| Web 容器 | 部署到外部 Tomcat | 内嵌 Tomcat / Jetty / Undertow .|
| 启动方式 | War 包部署 | `java -jar` 直接启动 |
| 自动装配 | 无 | `@EnableAutoConfiguration` |
| 监控 | 需手动集成 | Actuator 开箱即用 |
