# MyBatis 插件机制实现原理

## 1. 核心结论

MyBatis 插件机制本质上是基于 **责任链模式 + JDK 动态代理 + 拦截器链** 实现的。

它允许开发者在 MyBatis 内部核心流程的特定位置进行增强，例如：

- SQL 执行前后增强
- SQL 改写
- 参数绑定增强
- 查询结果处理增强
- 慢 SQL 统计
- 分页处理
- 数据权限控制
- SQL 审计日志

MyBatis 插件并不是拦截任意对象，而是只支持拦截 MyBatis 内部的四类核心组件。

---

## 2. 插件可以拦截哪些对象

MyBatis 插件默认只能拦截以下四类对象。

### 2.1 Executor

`Executor` 是 MyBatis 的 SQL 执行器，负责执行增删改查、事务提交、事务回滚、一级缓存维护等操作。

常见可拦截方法包括：

- `update()`
- `query()`
- `commit()`
- `rollback()`

典型使用场景：

- SQL 执行耗时统计
- SQL 审计
- 查询缓存增强
- 数据权限控制

---

### 2.2 StatementHandler

`StatementHandler` 负责创建和处理 JDBC 的 `Statement` 或 `PreparedStatement`。

它通常位于 SQL 真正发送给数据库之前，因此非常适合做 SQL 改写。

常见可拦截方法包括：

- `prepare()`
- `parameterize()`
- `query()`
- `update()`

典型使用场景：

- 分页插件
- SQL 改写
- 动态追加查询条件
- 分库分表路由

---

### 2.3 ParameterHandler

`ParameterHandler` 负责将 Java 参数设置到 `PreparedStatement` 中。

常见可拦截方法包括：

- `setParameters()`

典型使用场景：

- 参数脱敏
- 参数加密
- 参数校验
- 自定义参数绑定逻辑

---

### 2.4 ResultSetHandler

`ResultSetHandler` 负责处理 JDBC 返回的 `ResultSet`，并将其映射为 Java 对象。

常见可拦截方法包括：

- `handleResultSets()`
- `handleCursorResultSets()`
- `handleOutputParameters()`

典型使用场景：

- 返回结果脱敏
- 字段解密
- 结果对象增强
- 统一结果处理

---

## 3. 插件核心接口 Interceptor

MyBatis 插件需要实现 `org.apache.ibatis.plugin.Interceptor` 接口。

```java
public interface Interceptor {

    Object intercept(Invocation invocation) throws Throwable;

    default Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    default void setProperties(Properties properties) {
        // NOP
    }
}
```

### 3.1 intercept 方法

`intercept()` 是插件真正执行增强逻辑的地方。

它类似于 Spring AOP 中的环绕通知，可以在目标方法执行前后添加自定义逻辑。

示例：

```java
@Override
public Object intercept(Invocation invocation) throws Throwable {
    long startTime = System.currentTimeMillis();

    Object result = invocation.proceed();

    long cost = System.currentTimeMillis() - startTime;
    System.out.println("SQL 执行耗时：" + cost + "ms");

    return result;
}
```

其中，`invocation.proceed()` 表示继续执行原始方法。

---

### 3.2 plugin 方法

`plugin()` 用来决定是否对目标对象创建代理。

在当前 MyBatis 源码中，`plugin()` 是 `Interceptor` 接口的默认方法，默认实现就是调用 `Plugin.wrap(target, this)`：

```java
default Object plugin(Object target) {
    return Plugin.wrap(target, this);
}
```

因此，普通插件如果没有特殊代理逻辑，可以不重写 `plugin()`。

`Plugin.wrap()` 会根据插件上的 `@Intercepts`、`@Signature` 注解信息判断当前目标对象是否需要被代理。

如果匹配，则返回 JDK 动态代理对象；如果不匹配，则返回原始对象。

---

### 3.3 setProperties 方法

`setProperties()` 用于接收插件配置参数。

在当前 MyBatis 源码中，`setProperties()` 也是 `Interceptor` 接口的默认方法，默认实现为空方法。只有插件需要读取配置参数时，才需要重写它。

例如在 MyBatis 配置中声明插件参数：

```xml
<plugins>
    <plugin interceptor="com.example.SqlCostInterceptor">
        <property name="threshold" value="1000"/>
    </plugin>
</plugins>
```

插件中可以通过 `setProperties()` 获取这些参数：

```java
@Override
public void setProperties(Properties properties) {
    String threshold = properties.getProperty("threshold");
}
```

---

## 4. 拦截点声明方式

MyBatis 插件通过 `@Intercepts` 和 `@Signature` 注解声明要拦截的目标。

示例：

```java
@Intercepts({
    @Signature(
        type = Executor.class,
        method = "query",
        args = {
            MappedStatement.class,
            Object.class,
            RowBounds.class,
            ResultHandler.class
        }
    )
})
public class SqlCostInterceptor implements Interceptor {

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        return invocation.proceed();
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
```

### 4.1 @Intercepts

`@Intercepts` 用于声明当前类是一个 MyBatis 插件，并且可以包含多个 `@Signature`。

### 4.2 @Signature

`@Signature` 用于声明具体拦截哪个类、哪个方法以及方法参数列表。

它包含三个核心属性：

- `type`：要拦截的接口类型
- `method`：要拦截的方法名
- `args`：方法参数类型列表

MyBatis 会通过这三个信息精确匹配目标方法。

---

## 5. 插件注册与加载过程

插件通常配置在 MyBatis 配置文件中。

```xml
<plugins>
    <plugin interceptor="com.example.SqlCostInterceptor"/>
</plugins>
```

MyBatis 启动解析配置时，会在 `XMLConfigBuilder.pluginsElement()` 中完成插件加载。

源码核心流程如下：

```java
private void pluginsElement(XNode context) throws Exception {
    if (context != null) {
        for (XNode child : context.getChildren()) {
            String interceptor = child.getStringAttribute("interceptor");
            Properties properties = child.getChildrenAsProperties();
            Interceptor interceptorInstance = (Interceptor) resolveClass(interceptor)
                .getDeclaredConstructor()
                .newInstance();
            interceptorInstance.setProperties(properties);
            configuration.addInterceptor(interceptorInstance);
        }
    }
}
```

可以看到，插件加载过程包含以下步骤：

1. 读取 `<plugins>` 下每个 `<plugin>` 节点
2. 获取 `interceptor` 属性中的插件类名
3. 通过反射创建插件实例
4. 读取子节点 `<property>` 配置并转换为 `Properties`
5. 调用插件的 `setProperties()` 注入配置参数
6. 调用 `configuration.addInterceptor()` 将插件加入 `InterceptorChain`

`InterceptorChain` 是 MyBatis 内部维护插件列表的对象。

其核心逻辑如下：

```java
public class InterceptorChain {

    private final List<Interceptor> interceptors = new ArrayList<>();

    public Object pluginAll(Object target) {
        for (Interceptor interceptor : interceptors) {
            target = interceptor.plugin(target);
        }
        return target;
    }

    public void addInterceptor(Interceptor interceptor) {
        interceptors.add(interceptor);
    }

    public List<Interceptor> getInterceptors() {
        return Collections.unmodifiableList(interceptors);
    }
}
```

`pluginAll()` 会按照插件加入 `interceptors` 列表的顺序，依次调用每个插件的 `plugin()` 方法。每次调用后，`target` 都可能变成上一层代理对象。

---

## 6. 插件在核心对象创建时生效

MyBatis 会在 `Configuration` 创建核心对象时，通过 `InterceptorChain.pluginAll()` 对对象进行包装。

这不是推测逻辑，而是 `Configuration` 源码中的固定流程。

### 6.1 创建 ParameterHandler

```java
public ParameterHandler newParameterHandler(MappedStatement mappedStatement, Object parameterObject,
    BoundSql boundSql) {
    ParameterHandler parameterHandler = mappedStatement.getLang().createParameterHandler(
        mappedStatement,
        parameterObject,
        boundSql
    );
    return (ParameterHandler) interceptorChain.pluginAll(parameterHandler);
}
```

### 6.2 创建 ResultSetHandler

```java
public ResultSetHandler newResultSetHandler(Executor executor, MappedStatement mappedStatement,
    RowBounds rowBounds, ParameterHandler parameterHandler, ResultHandler resultHandler, BoundSql boundSql) {
    ResultSetHandler resultSetHandler = new DefaultResultSetHandler(
        executor,
        mappedStatement,
        parameterHandler,
        resultHandler,
        boundSql,
        rowBounds
    );
    return (ResultSetHandler) interceptorChain.pluginAll(resultSetHandler);
}
```

### 6.3 创建 StatementHandler

```java
public StatementHandler newStatementHandler(Executor executor, MappedStatement mappedStatement,
    Object parameterObject, RowBounds rowBounds, ResultHandler resultHandler, BoundSql boundSql) {
    StatementHandler statementHandler = new RoutingStatementHandler(
        executor,
        mappedStatement,
        parameterObject,
        rowBounds,
        resultHandler,
        boundSql
    );
    return (StatementHandler) interceptorChain.pluginAll(statementHandler);
}
```

### 6.4 创建 Executor

```java
public Executor newExecutor(Transaction transaction, ExecutorType executorType) {
    executorType = executorType == null ? defaultExecutorType : executorType;
    Executor executor;
    if (ExecutorType.BATCH == executorType) {
        executor = new BatchExecutor(this, transaction);
    } else if (ExecutorType.REUSE == executorType) {
        executor = new ReuseExecutor(this, transaction);
    } else {
        executor = new SimpleExecutor(this, transaction);
    }
    if (cacheEnabled) {
        executor = new CachingExecutor(executor);
    }
    return (Executor) interceptorChain.pluginAll(executor);
}
```

因此，插件不是在方法执行时才临时查找，而是在 `Executor`、`StatementHandler`、`ParameterHandler`、`ResultSetHandler` 创建阶段就已经完成代理包装。

---

## 7. Plugin.wrap 的实现原理

`Plugin.wrap(target, interceptor)` 是 MyBatis 插件机制的关键方法。

它主要做三件事。

### 7.1 解析插件签名

MyBatis 会读取插件类上的 `@Intercepts` 和 `@Signature` 注解，解析出插件要拦截的方法集合。

解析后会形成如下映射关系：

```text
Executor -> query 方法
StatementHandler -> prepare 方法
```

---

### 7.2 判断当前目标对象是否需要代理

`Plugin.wrap()` 不会无条件创建代理，而是会调用 `getAllInterfaces(type, signatureMap)` 收集当前目标类及其父类实现的接口。

源码逻辑可以概括为：

```java
private static Class<?>[] getAllInterfaces(Class<?> type, Map<Class<?>, Set<Method>> signatureMap) {
    Set<Class<?>> interfaces = new HashSet<>();
    while (type != null) {
        for (Class<?> candidateInterface : type.getInterfaces()) {
            if (signatureMap.containsKey(candidateInterface)) {
                interfaces.add(candidateInterface);
            }
        }
        type = type.getSuperclass();
    }
    return interfaces.toArray(new Class<?>[0]);
}
```

也就是说，只有当目标对象自身或父类实现的接口命中了 `signatureMap` 中声明的接口时，MyBatis 才会创建代理。

例如：

- 插件声明拦截 `Executor.query()`
- 当前目标对象是 `SimpleExecutor`
- `SimpleExecutor` 或其父类实现了 `Executor` 接口
- `Executor` 存在于 `signatureMap` 中

此时 MyBatis 才会为该目标对象创建代理。

如果没有任何接口命中 `signatureMap`，`Plugin.wrap()` 会直接返回原对象，避免无意义代理。

---

### 7.3 创建 JDK 动态代理

MyBatis 插件基于 JDK 动态代理实现。

`Plugin.wrap()` 的核心源码如下：

```java
public static Object wrap(Object target, Interceptor interceptor) {
    Map<Class<?>, Set<Method>> signatureMap = getSignatureMap(interceptor);
    Class<?> type = target.getClass();
    Class<?>[] interfaces = getAllInterfaces(type, signatureMap);
    if (interfaces.length > 0) {
        return Proxy.newProxyInstance(
            type.getClassLoader(),
            interfaces,
            new Plugin(target, interceptor, signatureMap)
        );
    }
    return target;
}
```

这里有两个关键点：

- 只有 `interfaces.length > 0` 时才创建代理
- 代理使用的 `InvocationHandler` 是 `Plugin` 对象本身

因为使用的是 JDK 动态代理，所以 MyBatis 插件拦截的是接口方法，而不是普通类方法。

---

## 8. 方法调用时的执行流程

当调用被代理对象的方法时，会进入 `Plugin.invoke()`。

核心逻辑如下：

```java
@Override
public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
    Set<Method> methods = signatureMap.get(method.getDeclaringClass());

    if (methods != null && methods.contains(method)) {
        return interceptor.intercept(new Invocation(target, method, args));
    }

    return method.invoke(target, args);
}
```

执行流程如下：

1. 根据 `method.getDeclaringClass()` 从 `signatureMap` 中获取该接口对应的方法集合
2. 判断当前调用的方法是否命中插件声明的拦截点
3. 如果命中，则创建 `Invocation` 并调用 `interceptor.intercept()`
4. 如果未命中，则直接通过反射调用原始方法
5. 在 `intercept()` 内部通过 `invocation.proceed()` 继续执行原方法

`Plugin.invoke()` 会捕获反射调用产生的异常，并通过 `ExceptionUtil.unwrapThrowable()` 解包后重新抛出，避免业务侧看到过多反射包装异常。

`Invocation` 对象封装了三个关键信息：

- `target`：原始目标对象
- `method`：当前被调用的方法
- `args`：方法参数

同时，`Invocation` 构造方法会校验被拦截方法是否属于 MyBatis 支持的四类插件目标：

```java
private static final List<Class<?>> targetClasses = Arrays.asList(
    Executor.class,
    ParameterHandler.class,
    ResultSetHandler.class,
    StatementHandler.class
);

public Invocation(Object target, Method method, Object[] args) {
    if (!targetClasses.contains(method.getDeclaringClass())) {
        throw new IllegalArgumentException(
            "Method '" + method + "' is not supported as a plugin target."
        );
    }
    this.target = target;
    this.method = method;
    this.args = args;
}
```

这也是 MyBatis 插件只能拦截 `Executor`、`ParameterHandler`、`ResultSetHandler`、`StatementHandler` 四类接口方法的源码依据。

---

## 9. 多插件执行顺序

MyBatis 支持注册多个插件。

多个插件会按照注册顺序依次包装目标对象。

假设注册顺序如下：

```text
PluginA -> PluginB -> PluginC
```

包装过程如下：

```text
原始对象 -> PluginA 代理 -> PluginB 代理 -> PluginC 代理
```

最终方法调用时，执行顺序是：

```text
PluginC before
    PluginB before
        PluginA before
            原始方法执行
        PluginA after
    PluginB after
PluginC after
```

也就是说：

- 先注册的插件先包装，更靠近原始对象
- 后注册的插件后包装，更靠近调用入口
- 调用时，后注册的插件先执行 before 逻辑
- 返回时，先注册的插件先执行 after 逻辑

---

## 10. 分页插件为什么通常拦截 StatementHandler

分页插件通常拦截 `StatementHandler.prepare(Connection connection, Integer transactionTimeout)`。

原因是：

- `prepare()` 执行时，SQL 已经生成
- SQL 还没有真正发送给数据库
- 此时可以安全地改写 SQL

典型分页插件的执行流程如下：

1. 拦截 `StatementHandler.prepare()`
2. 获取 `BoundSql`
3. 从 `BoundSql` 中拿到原始 SQL
4. 根据数据库类型改写 SQL
5. 将原 SQL 替换为分页 SQL
6. 继续执行 `invocation.proceed()`

例如原始 SQL：

```sql
select * from user where age > ?
```

改写为 MySQL 分页 SQL：

```sql
select * from user where age > ? limit ?, ?
```

---

## 11. 插件机制的完整调用链

MyBatis 插件机制的完整链路如下：

```text
MyBatis 启动
  -> 解析插件配置
  -> XMLConfigBuilder.pluginsElement() 创建 Interceptor 实例
  -> 调用 Interceptor.setProperties() 注入插件参数
  -> configuration.addInterceptor() 加入 InterceptorChain
  -> Configuration 创建 Executor / StatementHandler / ParameterHandler / ResultSetHandler
  -> 调用 interceptorChain.pluginAll(target)
  -> Plugin.wrap 解析 @Intercepts / @Signature 并判断是否匹配拦截点
  -> 匹配则创建 JDK 动态代理
  -> 调用目标方法
  -> 进入 Plugin.invoke()
  -> 判断方法是否命中 @Signature
  -> 命中则执行 Interceptor.intercept()
  -> invocation.proceed()
  -> 执行原始方法
```

---

## 12. 与 Spring AOP 的区别

MyBatis 插件机制和 Spring AOP 都可以实现方法增强，但两者关注点不同。

| 对比项 | MyBatis 插件 | Spring AOP |
| --- | --- | --- |
| 增强范围 | MyBatis 内部四大核心组件 | Spring Bean 方法 |
| 代理方式 | JDK 动态代理 | JDK 动态代理或 CGLIB |
| 切点声明 | `@Intercepts` + `@Signature` | `@Pointcut` 表达式 |
| 使用场景 | SQL 执行链路增强 | 通用业务方法增强 |
| 粒度 | 固定核心接口方法 | 灵活切点表达式 |

MyBatis 插件更像是 MyBatis 内部预留的扩展点，而不是一个通用 AOP 框架。

---

## 13. 常见应用场景

### 13.1 分页插件

拦截 `StatementHandler.prepare()`，改写 SQL，追加分页语句。

### 13.2 慢 SQL 监控

拦截 `Executor.query()` 或 `Executor.update()`，统计执行耗时。

### 13.3 SQL 审计日志

拦截 `Executor` 或 `StatementHandler`，记录 SQL、参数、耗时、调用来源等信息。

### 13.4 数据权限控制

拦截 `StatementHandler.prepare()`，在 SQL 中追加租户、部门、用户权限条件。

### 13.5 字段加解密

拦截 `ParameterHandler.setParameters()` 对入参加密，或者拦截 `ResultSetHandler.handleResultSets()` 对结果解密。

---

## 14. 使用插件时的注意事项

### 14.1 拦截点必须准确

`@Signature` 中的方法名和参数类型必须完全匹配。

`Plugin.getSignatureMap()` 会通过如下方式获取目标方法：

```java
Method method = sig.type().getMethod(sig.method(), sig.args());
```

如果方法不存在，会抛出 `PluginException`，而不是静默失效。

### 14.2 不要忘记调用 invocation.proceed

如果插件中没有调用 `invocation.proceed()`，原始方法就不会继续执行。

除非确实希望中断原流程，否则通常必须调用它。

### 14.3 避免重复代理和过度拦截

插件应尽量只拦截必要方法，避免对所有执行链路造成额外性能损耗。

### 14.4 注意多个插件的执行顺序

多个插件同时存在时，后注册的插件会更早进入拦截逻辑。

如果多个插件都修改 SQL，需要特别关注插件顺序。

### 14.5 修改 SQL 需要谨慎

直接修改 `BoundSql` 中的 SQL 通常需要反射，因为部分字段没有公开 setter。

如果同时修改参数，还需要同步维护参数映射，否则可能导致参数绑定异常。

---

## 15. 面试回答模板

MyBatis 插件机制是基于 JDK 动态代理实现的拦截器机制。MyBatis 只允许插件拦截 `Executor`、`StatementHandler`、`ParameterHandler` 和 `ResultSetHandler` 四类核心接口的方法，这一点在 `Invocation` 的 `targetClasses` 校验中有明确限制。

开发者通过实现 `Interceptor` 接口，并使用 `@Intercepts` 和 `@Signature` 声明要拦截的接口、方法和参数。MyBatis 解析配置文件时，会在 `XMLConfigBuilder.pluginsElement()` 中反射创建插件实例，调用 `setProperties()` 注入参数，然后通过 `configuration.addInterceptor()` 加入 `InterceptorChain`。

MyBatis 在 `Configuration` 创建 `Executor`、`StatementHandler`、`ParameterHandler`、`ResultSetHandler` 时，会调用 `InterceptorChain.pluginAll()`。`pluginAll()` 按插件注册顺序依次调用每个插件的 `plugin()` 方法，而 `Interceptor` 的默认 `plugin()` 方法就是调用 `Plugin.wrap()`。

`Plugin.wrap()` 会先解析 `@Intercepts` 和 `@Signature` 生成 `signatureMap`，再通过 `getAllInterfaces()` 判断目标对象及其父类实现的接口是否命中 `signatureMap`。如果命中，就使用 `Proxy.newProxyInstance()` 创建 JDK 动态代理；如果没有命中，就直接返回原对象。

当代理对象的方法被调用时，会进入 `Plugin.invoke()`。它会根据 `method.getDeclaringClass()` 从 `signatureMap` 中查找可拦截方法集合，如果当前方法命中，就创建 `Invocation` 并调用插件的 `intercept()` 方法；插件内部通过 `invocation.proceed()` 继续执行原始方法。

多个插件会形成一层层代理，后注册的插件先进入拦截逻辑，先注册的插件更靠近原始对象。常见的分页插件就是通过拦截 `StatementHandler.prepare()`，在 SQL 发送给数据库之前改写 SQL 来实现分页。

---

## 16. 一句话总结

MyBatis 插件机制就是在解析配置时把插件加入 `InterceptorChain`，在 `Configuration` 创建四类核心组件时通过 `Plugin.wrap()` 按需生成 JDK 动态代理，并在代理方法命中 `@Signature` 声明的方法时执行 `Interceptor.intercept()`，从而实现对 SQL 执行流程的扩展增强。
