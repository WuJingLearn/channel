# Spring 三大启动流程总览

> 面试高频题：**Bean 生命周期、Spring 启动流程、SpringBoot 启动流程**，这是同一个问题吗？

**不是**。三者是**包含嵌套关系**，粒度从大到小：

```
┌─────────────────────────────────────────────────────────┐
│  SpringBoot 启动流程 (SpringApplication.run)             │
│  ┌─────────────────────────────────────────────────┐    │
│  │  Spring 启动流程 (AbstractApplicationContext     │    │
│  │                   .refresh())                    │    │
│  │   ┌─────────────────────────────────────┐        │    │
│  │   │  Bean 生命周期                       │        │    │
│  │   │  (实例化 → 属性填充 → 初始化 → 销毁)    │        │    │
│  │   └─────────────────────────────────────┘        │    │
│  └─────────────────────────────────────────────────┘    │
└─────────────────────────────────────────────────────────┘
```

## 一句话区分

| 维度 | Bean 生命周期 | Spring 启动流程 | SpringBoot 启动流程 |
|---|---|---|---|
| **粒度** | 单个 Bean | IoC 容器 | 整个应用 |
| **核心方法** | `doCreateBean` | `refresh()` 12 步 | `SpringApplication.run` |
| **关注点** | 一个 Bean 怎么生、怎么死 | 容器怎么把所有 Bean 装好 | 怎么把容器启起来 + 自动装配 + 内嵌容器 |
| **典型扩展点** | `BeanPostProcessor` | `BeanFactoryPostProcessor` | `ApplicationContextInitializer` / `SpringApplicationRunListener` |
| **独有特性** | 三级缓存解决循环依赖 | `@Configuration` 类解析 | 自动装配、`application.yml`、内嵌 Tomcat |

## 三者的衔接点

1. **SpringBoot 启动流程**在 `refreshContext(context)` 这一步**调用了 Spring 的 `refresh()`**
2. **Spring 启动流程**在 `finishBeanFactoryInitialization()` 这一步**触发了所有非懒加载单例 Bean 的生命周期**
3. **Bean 生命周期**完成后，容器就绪，应用对外提供服务

## 串联式回答模板（面试拿高分）

> Bean 生命周期是**单个 Bean** 的事情，发生在 Spring 容器 `refresh()` 的第 11 步 `finishBeanFactoryInitialization` 里；Spring 启动流程整体就是 `refresh()` 这 12 步；而 SpringBoot 启动流程是在 `SpringApplication.run` 中**封装并触发**了这个 `refresh()`，并额外提供了**自动装配、内嵌容器、外部化配置**等能力。三者是嵌套包含关系，而不是并列关系。

## 详细笔记索引
### 一、Bean 生命周期（约 1.5 分钟）
Bean 生命周期是指单个 Bean 在 Spring 容器中从创建到销毁的完整过程，主要可以分为四大阶段、八个步骤。

第一阶段是实例化。Spring 通过反射调用构造方法创建出 Bean 的"半成品"对象，此时所有属性都还是 null。如果是单例 Bean，Spring 会立刻把它的 ObjectFactory 放入三级缓存，这是后面解决循环依赖的关键。

第二阶段是属性填充，也就是依赖注入。Spring 会通过 AutowiredAnnotationBeanPostProcessor 处理 @Autowired 和 @Value，通过 CommonAnnotationBeanPostProcessor 处理 @Resource，把依赖的 Bean 注入进来。循环依赖就发生在这一步——如果当前 Bean 依赖的对象正在创建中，Spring 会从三级缓存里拿到对方的早期引用来完成注入。

第三阶段是初始化，这一步又细分为四小步：

先执行 Aware 回调，比如 BeanNameAware、BeanFactoryAware、ApplicationContextAware，把容器自身的引用注入给 Bean
然后执行 BeanPostProcessor 的前置方法 postProcessBeforeInitialization，@PostConstruct 就是在这里被触发的
接着执行初始化方法，顺序是 InitializingBean#afterPropertiesSet → @Bean(initMethod) 指定的方法
最后执行 BeanPostProcessor 的后置方法 postProcessAfterInitialization，AOP 代理对象通常就是在这一步由 AbstractAutoProxyCreator 创建出来的
第四阶段是销毁。当容器关闭时，Spring 会按 @PreDestroy → DisposableBean#destroy → @Bean(destroyMethod) 的顺序回调销毁方法。

总结来说，整个生命周期可以用四个动词概括："生、填、活、死"——实例化是"生"，属性填充是"填"，初始化让 Bean 真正"活"起来对外提供服务，最后是"死"。其中**BeanPostProcessor 是贯穿始终的最重要扩展点**，AOP、@Async、@Transactional 这些核心能力都是基于它实现的。

### 二、Spring 启动流程（约 2 分钟）
Spring 启动流程指的是 IoC 容器从无到有、把所有 Bean 装配就绪的过程，核心就是 AbstractApplicationContext 中的 refresh() 方法，它由 12 个步骤组成，但可以归纳为四个核心阶段。

第一个阶段是准备 BeanFactory，对应前 4 步：

prepareRefresh 准备环境变量
obtainFreshBeanFactory 创建 DefaultListableBeanFactory 并加载基础的 BeanDefinition
prepareBeanFactory 给 BeanFactory 设置类加载器、注册 ApplicationContextAwareProcessor 等基础组件
postProcessBeanFactory 是给子类预留的扩展点
这一阶段的产物是一个"空壳"BeanFactory，里面只有 BeanDefinition 元数据，还没有真正的 Bean 实例。

第二个阶段是处理 BeanDefinition，也是最关键的一步——第 5 步 invokeBeanFactoryPostProcessors。这一步执行所有的 BeanFactoryPostProcessor，其中最核心的是 ConfigurationClassPostProcessor，它会：

解析 @Configuration 类
处理 @ComponentScan 扫描包路径下所有的 @Component
处理 @Import 导入其他配置类——SpringBoot 的自动装配就是通过 @Import(AutoConfigurationImportSelector) 在这里被触发的
处理 @Bean 方法注册成 BeanDefinition
第三个阶段是初始化容器基础设施，对应第 6 到 10 步：

registerBeanPostProcessors 注册（注意是注册，不是执行）所有 BeanPostProcessor
initMessageSource 初始化国际化
initApplicationEventMulticaster 初始化事件广播器
onRefresh 是子类扩展点，SpringBoot 的内嵌 Tomcat 就是在这里启动的
registerListeners 把所有监听器注册到广播器
第四个阶段也是最重磅的一步——第 11 步 finishBeanFactoryInitialization，它会调用 preInstantiateSingletons 遍历所有 BeanDefinition，对每个非懒加载的单例 Bean 调用 getBean()，触发完整的 Bean 生命周期。所以前面讲的 Bean 生命周期，其实就是发生在这一步。

最后第 12 步 finishRefresh 发布 ContextRefreshedEvent，标志着容器刷新完成。

总结一下：Spring 启动流程可以理解为**"先备好工厂、再读懂图纸、再装好工具、最后批量造零件"。其中第 5 步是元数据处理的核心，第 11 步是 Bean 实例化的核心**，这两步是面试官最爱深挖的地方。

### 三、SpringBoot 启动流程（约 2 分钟）
SpringBoot 启动流程是在 Spring 启动流程之上的一层封装，核心入口是 SpringApplication.run，它的目标是实现约定大于配置、自动装配、内嵌容器。整个流程分为构造和运行两个大阶段。

构造阶段 是 new SpringApplication() 的过程，主要做四件事：

推断应用类型，根据类路径下有没有 Servlet 类判断是 SERVLET、REACTIVE 还是 NONE 三种类型
从 META-INF/spring.factories 加载所有的 ApplicationContextInitializer
从同样的位置加载所有的 ApplicationListener
推断主类，也就是 main 方法所在的类
运行阶段 是 run() 方法，按时序可以拆成 8 步：

第 1 步 启动监听器，发布 ApplicationStartingEvent，告诉外界"我要启动了"。

第 2 步 准备 Environment，加载 application.yml、application-{profile}.yml、命令行参数、JVM 参数、操作系统环境变量等，按优先级合并成一个 Environment 对象，并发布 ApplicationEnvironmentPreparedEvent。

第 3 步 打印 Banner。

第 4 步 创建 ApplicationContext，根据应用类型选择具体的实现：Web 应用就是 AnnotationConfigServletWebServerApplicationContext。

第 5 步 准备 Context，把 Environment 设进去，应用所有 Initializer，注册主配置类作为 BeanDefinition。

第 6 步是最关键的 refreshContext，这里会调用 Spring 的 refresh() 方法——也就是我刚才讲的 Spring 启动流程的 12 步。自动装配和内嵌 Tomcat 的启动都发生在这里：

自动装配的入口是 @SpringBootApplication 里的 @EnableAutoConfiguration，它通过 @Import(AutoConfigurationImportSelector) 在 refresh 的第 5 步被触发，去读取 META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports（2.7 之前是 spring.factories），加载所有候选自动配置类，再通过 @ConditionalOnClass、@ConditionalOnMissingBean、@ConditionalOnProperty 等条件注解过滤出真正生效的配置
Tomcat 的启动则是在 refresh 的第 9 步 onRefresh 中，由 ServletWebServerApplicationContext#createWebServer 触发的
第 7 步 afterRefresh 是预留扩展点。

第 8 步 callRunners，调用所有 ApplicationRunner 和 CommandLineRunner，让用户能在启动后立即执行自定义逻辑。

最后发布 ApplicationReadyEvent，应用就绪，正式对外提供服务。

总结一下：SpringBoot 启动流程的本质是**"封装 Spring + 加 buff"**——它没有改变 Spring 的核心机制，而是在外面包了一层，做了三件 Spring 原本要用户自己做的事：第一是用约定的方式加载配置文件，第二是用 @EnableAutoConfiguration + 条件注解实现按需装配，第三是把 Tomcat 内嵌进来用 java -jar 直接启动。所以一句话概括三者关系就是：SpringBoot 启动流程包含 Spring 启动流程，Spring 启动流程的第 11 步触发 Bean 生命周期。