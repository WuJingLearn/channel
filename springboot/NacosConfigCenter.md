# SpringBoot 整合 Nacos 配置中心与动态刷新原理

> 面试高频题：SpringBoot 是在启动的哪个阶段接入 Nacos 配置中心的？Nacos 修改配置后，为什么 Bean 属性可以动态刷新？

## 一句话结论

Nacos 配置中心分为两个阶段：

1. **启动时首次加载配置**：发生在 SpringBoot `refresh()` 之前的 **Environment 准备阶段**。Nacos 会把远程配置拉取下来，封装成 `PropertySource` 加入 `Environment`，保证后续 `@Value`、`@ConfigurationProperties`、`@ConditionalOnProperty` 和自动装配条件判断能读取到远程配置。
2. **运行期动态刷新配置**：发生在应用启动完成之后。Nacos Client 通过长轮询监听配置变化，配置变更后更新本地配置缓存，并触发 Spring Cloud 的刷新机制，使 `@RefreshScope` Bean 重新创建，或者让配置绑定类重新绑定新值。

可以用一句话概括：

> **启动时，Nacos 负责把远程配置加载进 Environment；运行时，Nacos 负责监听配置变化，再通过 Spring Cloud Refresh 机制刷新相关 Bean。**

---

## 一、为什么配置中心必须在 refresh() 之前加载？

SpringBoot 启动主流程可以按以下关键节点理解：

```text
SpringApplication.run
  ↓
1. 启动监听器
2. 准备 Environment
3. 打印 Banner
4. 创建 ApplicationContext
5. 准备 ApplicationContext
6. refreshContext，调用 Spring refresh()
7. afterRefresh
8. callRunners
```

Nacos 的首次配置加载必须发生在第 6 步 `refreshContext` 之前，因为 Spring 容器刷新时会大量依赖配置：

```java
@Value("${demo.name}")
private String name;
```

```java
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {
    private String name;
}
```

```java
@ConditionalOnProperty(name = "demo.enabled", havingValue = "true")
public class DemoAutoConfiguration {
}
```

如果 Nacos 配置加载太晚，就会导致：

- `@Value` 获取不到远程配置；
- `@ConfigurationProperties` 无法绑定远程配置；
- `@ConditionalOnProperty` 判断不准确；
- 自动装配结果会因为缺少远程配置而偏离预期。

所以 Nacos 配置中心的首次加载点，一定在 **ApplicationContext refresh 之前**。

---

## 二、SpringBoot 3.x 推荐整合方式：ConfigData 机制

Spring Boot 2.4 之后引入了 `ConfigData` 机制，用来统一处理外部配置加载。本文采用的 Spring Cloud Alibaba 2025.x 版本组合中，Nacos Config 通过 `spring.config.import` 接入 Spring Boot 的配置加载体系。

典型配置如下：

```yaml
spring:
  application:
    name: nacos-config-demo
  config:
    import:
      - nacos:nacos-config-demo.yaml
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      username: nacos
      password: nacos
      config:
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
```

`nacos:nacos-config-demo.yaml` 的含义是：

- `nacos:` 表示这是一个 Nacos 配置源；
- `nacos-config-demo.yaml` 是要加载的 DataId；
- 没有使用 `optional:`，表示该配置必须存在。如果 Nacos 中不存在这个 DataId，应用启动会失败，这更适合 Demo 验证和生产排错。

如果希望本地开发时 Nacos 配置不存在也能启动，可以改成 `optional:nacos:nacos-config-demo.yaml`。正式演示动态配置中心能力时不要使用 `optional:`，否则远程配置没有加载成功也不会阻断启动，容易误判为动态配置不生效。

### ConfigData 加载链路

以 SpringBoot 3.5.x 为例，Nacos 配置加载链路如下：

```text
SpringApplication.run
  ↓
准备 Environment
  ↓
ConfigDataEnvironmentPostProcessor 执行
  ↓
解析 spring.config.import
  ↓
识别 nacos: 配置源
  ↓
调用 Nacos 对应的 ConfigDataLocationResolver
  ↓
调用 Nacos 对应的 ConfigDataLoader
  ↓
从 Nacos Server 拉取配置
  ↓
封装成 PropertySource
  ↓
加入 Environment
  ↓
后续 refresh() 使用这些配置创建 Bean
```

其中关键点是：

- `ConfigDataEnvironmentPostProcessor` 是 SpringBoot 负责加载外部配置的核心后处理器；
- Nacos 通过实现对应的 `ConfigDataLocationResolver` 和 `ConfigDataLoader` 接入 SpringBoot 配置加载体系；
- 最终远程配置会变成 Spring `Environment` 中的一个或多个 `PropertySource`。

---

## 三、老版本整合方式：Bootstrap Context 机制

在 Spring Boot 2.4 之前，或者部分 Spring Cloud 旧项目里，Nacos 常通过 `bootstrap.yml` 接入。

典型配置如下：

```yaml
spring:
  application:
    name: nacos-config-demo
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      username: nacos
      password: nacos
      config:
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
```

老版本 Bootstrap 接入链路如下：

```text
SpringApplication.run
  ↓
发布 ApplicationEnvironmentPreparedEvent
  ↓
BootstrapApplicationListener 监听事件
  ↓
创建 Bootstrap Context
  ↓
加载 NacosConfigBootstrapConfiguration
  ↓
创建 NacosPropertySourceLocator
  ↓
从 Nacos Server 拉取远程配置
  ↓
封装成 NacosPropertySource
  ↓
加入主应用 Environment
  ↓
主容器 refresh()
```

这个机制的核心是：

- 主应用启动之前，先启动一个轻量级 `Bootstrap Context`；
- 在 Bootstrap Context 里初始化配置中心客户端；
- 通过 `PropertySourceLocator` 拉取远程配置；
- 再把远程配置合并到主应用的 `Environment`。

面试时可以这样区分：

| 机制 | 主要版本 | 配置文件 | 核心入口 |
|---|---|---|---|
| Bootstrap | Spring Boot 2.4 之前常见 | `bootstrap.yml` | `BootstrapApplicationListener` + `PropertySourceLocator` |
| ConfigData | Spring Boot 2.4+ 推荐 | `spring.config.import` | `ConfigDataEnvironmentPostProcessor` |

---

## 四、Demo：SpringBoot 整合 Nacos 配置中心

下面给出一套可以直接参考的 SpringBoot 3.5.x + Spring Cloud 2025.x + Spring Cloud Alibaba 2025.x 的 Demo。

本文固定采用以下版本组合：

| 组件 | 版本 |
|---|---|
| JDK | 17+ |
| Spring Boot | `3.5.0` |
| Spring Cloud | `2025.0.0` |
| Spring Cloud Alibaba | `2025.0.0.0` |
| Nacos Server | `2.x` |

这个版本组合的依据是：`Spring Cloud Alibaba 2025.0.x` 分支对应 `Spring Cloud 2025.0.x` 和 `Spring Boot 3.5.x`，并要求 JDK 17 或更高版本。

### 1. 引入依赖

`pom.xml` 完整写法如下：

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.5.0</version>
        <relativePath/>
    </parent>

    <groupId>com.example</groupId>
    <artifactId>nacos-config-demo</artifactId>
    <version>1.0.0</version>
    <name>nacos-config-demo</name>

    <properties>
        <java.version>17</java.version>
    </properties>

    <dependencyManagement>
        <dependencies>
            <dependency>
                <groupId>org.springframework.cloud</groupId>
                <artifactId>spring-cloud-dependencies</artifactId>
                <version>2025.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
            <dependency>
                <groupId>com.alibaba.cloud</groupId>
                <artifactId>spring-cloud-alibaba-dependencies</artifactId>
                <version>2025.0.0.0</version>
                <type>pom</type>
                <scope>import</scope>
            </dependency>
        </dependencies>
    </dependencyManagement>

    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>

        <dependency>
            <groupId>com.alibaba.cloud</groupId>
            <artifactId>spring-cloud-starter-alibaba-nacos-config</artifactId>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-configuration-processor</artifactId>
            <optional>true</optional>
        </dependency>
    </dependencies>
</project>
```

### 2. 本地 application.yml

```yaml
server:
  port: 8080

spring:
  application:
    name: nacos-config-demo
  config:
    import:
      - nacos:nacos-config-demo.yaml
  cloud:
    nacos:
      server-addr: 127.0.0.1:8848
      username: nacos
      password: nacos
      config:
        namespace: public
        group: DEFAULT_GROUP
        file-extension: yaml
        refresh-enabled: true
```

### 3. Nacos 控制台新增配置

在 Nacos 控制台新增配置：

- **Data ID**：`nacos-config-demo.yaml`
- **Group**：`DEFAULT_GROUP`
- **配置格式**：`YAML`

配置内容：

```yaml
demo:
  title: "Nacos 配置中心 Demo"
  version: "1.0.0"
  enabled: true
```

---

## 五、Demo：使用 @ConfigurationProperties 读取配置

### 1. 配置属性类

```java
package com.example.nacosdemo.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.stereotype.Component;

@Component
@RefreshScope
@ConfigurationProperties(prefix = "demo")
public class DemoProperties {

    private String title;

    private String version;

    private Boolean enabled;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getVersion() {
        return version;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public Boolean getEnabled() {
        return enabled;
    }

    public void setEnabled(Boolean enabled) {
        this.enabled = enabled;
    }
}
```

这里推荐使用 `@ConfigurationProperties`，原因是：

- 类型安全；
- 适合配置项较多的场景；
- 比散落在各处的 `@Value` 更容易维护；
- 配合 `@RefreshScope` 可以在配置变化后重新创建 Bean。

### 2. Controller 验证配置读取

```java
package com.example.nacosdemo.controller;

import com.example.nacosdemo.config.DemoProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class DemoConfigController {

    private final DemoProperties demoProperties;

    public DemoConfigController(DemoProperties demoProperties) {
        this.demoProperties = demoProperties;
    }

    @GetMapping("/demo/config")
    public Map<String, Object> getConfig() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("title", demoProperties.getTitle());
        response.put("version", demoProperties.getVersion());
        response.put("enabled", demoProperties.getEnabled());
        return response;
    }
}
```

启动应用后访问：

```text
http://localhost:8080/demo/config
```

返回示例：

```json
{
  "title": "Nacos 配置中心 Demo",
  "version": "1.0.0",
  "enabled": true
}
```

---

## 六、动态刷新 Bean 属性是怎么实现的？

Nacos 动态刷新的整体链路可以概括为：

```text
Nacos 控制台修改配置
  ↓
Nacos Server 保存新配置
  ↓
应用内 Nacos Client 长轮询感知配置变化
  ↓
Nacos Client 拉取最新配置
  ↓
更新本地 Nacos PropertySource
  ↓
发布 Spring Cloud 刷新事件
  ↓
RefreshScope 清理目标 Bean 缓存
  ↓
下次访问 Bean 时重新创建
  ↓
重新从 Environment 绑定最新配置
```

### 1. Nacos Client 如何感知配置变化？

Nacos Client 不是每次请求都去服务端拉完整配置，而是通过 **HTTP 长轮询** 监听配置变化。

它的核心思想是：

> **客户端携带本地配置的 MD5 去询问服务端：我这份配置还是不是最新的？如果没变，服务端先不返回，而是把请求挂起一段时间；如果期间配置发生变化，服务端立即返回变更通知；如果一直没变化，等到超时后返回空结果，客户端再发起下一轮长轮询。**

整体流程如下：

```text
应用启动
  ↓
Nacos Client 首次拉取配置
  ↓
本地缓存配置内容和 MD5
  ↓
启动长轮询监听任务
  ↓
客户端把 dataId、group、namespace、md5 发送给 Nacos Server
  ↓
服务端比较客户端 MD5 和服务端最新 MD5
  ↓
┌──────────────────────────────────────────────┐
│ 如果 MD5 不一致：说明配置已经变化，立即返回变化的配置标识 │
└──────────────────────────────────────────────┘
  ↓
客户端重新拉取最新配置
  ↓
更新本地缓存和 PropertySource
  ↓
通知配置 Listener
  ↓
触发 Spring Cloud Refresh 机制
```

如果配置没有变化，则流程是：

```text
客户端长轮询请求到达 Nacos Server
  ↓
服务端发现客户端 MD5 和服务端 MD5 一致
  ↓
说明配置暂时没有变化
  ↓
服务端挂起请求，最多等待一段时间
  ↓
等待期间如果配置发生变化，立即返回变化的配置标识
  ↓
等待期间如果一直没有变化，超时返回空结果
  ↓
客户端马上发起下一轮长轮询
```

这里有几个关键点。

**第一，长轮询请求里会带上配置标识和本地 MD5。**

客户端监听的不是一个笼统的“配置中心变更”，而是具体配置项，例如：

```text
dataId = nacos-config-demo.yaml
group = DEFAULT_GROUP
namespace = public
md5 = 当前本地配置内容的 MD5
```

服务端通过比较客户端传来的 MD5 和服务端最新配置 MD5，判断这份配置是否发生变化。

**第二，长轮询返回的是变化的配置标识，完整配置内容由客户端随后再次拉取。**

也就是说，长轮询主要负责“发现变化”：

```text
长轮询返回：nacos-config-demo.yaml 发生变化
  ↓
客户端再调用 getConfig
  ↓
拉取最新配置内容
  ↓
重新计算 MD5
  ↓
更新本地缓存
  ↓
触发监听器
```

这样设计可以避免长轮询响应体过大，也能让“变化发现”和“配置拉取”两个动作解耦。

**第三，服务端不是主动推送，而是挂起客户端请求。**

Nacos 长轮询不是 WebSocket，也不是服务端真正主动建立连接推送。它仍然是客户端主动发起 HTTP 请求，只是服务端在配置未变化时不立即返回，而是挂起请求。

可以理解成：

```text
客户端：我监听的 nacos-config-demo.yaml 变了吗？
服务端：暂时没变，你这个请求先等一会儿。
服务端：如果 30 秒内变了，我立刻告诉你；如果没变，超时后我返回空结果。
```

这种方式相比普通短轮询有几个好处：

- **实时性更好**：配置一变，服务端可以立即结束挂起请求并返回；
- **请求量更少**：配置不变时，不需要频繁短间隔轮询；
- **实现成本较低**：基于 HTTP 即可实现，不强依赖 WebSocket 或额外长连接协议。

所以，Nacos 长轮询在动态刷新链路中的职责是：

> **发现远程配置发生了变化，并通知客户端重新拉取最新配置。它本身不直接负责刷新 Spring Bean，真正刷新 Bean 是后续 Spring Cloud Refresh 和 `@RefreshScope` 完成的。**

### 2. 长轮询和普通 HTTP 请求有什么区别？

长轮询和普通请求在底层协议上没有区别，都是 HTTP 请求。区别在于服务端的响应时机不同。

普通 HTTP 请求的处理方式是：

```text
客户端发起请求
  ↓
服务端立即处理
  ↓
服务端立即返回结果
  ↓
本次请求结束
```

长轮询请求的处理方式是：

```text
客户端发起请求：配置有没有变化？
  ↓
服务端发现配置没有变化
  ↓
服务端不立即返回，而是挂起请求
  ↓
如果等待期间配置变化，立即返回变化标识
  ↓
如果等待超时仍然没有变化，返回空结果
  ↓
客户端马上发起下一轮长轮询
```

所以，长轮询不是新协议，也不是 WebSocket。它仍然是一次普通 HTTP 请求，只是服务端没有立即写响应，而是延迟完成响应。

| 对比项 | 普通 HTTP 请求 | 长轮询请求 |
|---|---|---|
| 底层协议 | HTTP | HTTP |
| 服务端响应 | 处理完立即返回 | 没有变化时延迟返回 |
| 连接持续时间 | 较短 | 较长 |
| 适合场景 | 普通查询、提交 | 等待事件变化 |
| 客户端行为 | 请求一次结束 | 返回后立即发起下一轮 |
| 服务端行为 | 立即处理并响应 | 没变化时挂起请求，有变化或超时时响应 |

一句话总结：

> **长轮询就是一次可能等待较久的 HTTP 请求，本质是普通 HTTP + 服务端延迟响应。**

### 3. 没有数据变更时，服务端如何挂起客户端请求？

当配置没有变化时，服务端不会用业务线程一直 `sleep` 等待，否则大量客户端同时长轮询时会耗尽服务端线程池。

正确做法是使用异步请求机制挂起请求，例如 Servlet 异步能力中的 `AsyncContext`，或者上层框架中的异步响应对象。核心过程是：

```text
客户端发起长轮询请求
  ↓
Tomcat 工作线程接收请求
  ↓
服务端比较 MD5，发现配置没有变化
  ↓
开启异步请求
  ↓
把请求上下文保存到等待队列
  ↓
释放 Tomcat 工作线程
  ↓
TCP 连接保持不关闭，HTTP 请求仍未完成
  ↓
等待配置变更事件或超时任务来完成响应
```

服务端内部可以按配置维度维护等待队列：

```text
nacos-config-demo.yaml + DEFAULT_GROUP + namespace
  ↓
等待中的长轮询请求列表
  ├── request-1
  ├── request-2
  └── request-3
```

这里要注意：**挂起请求不等于断开连接**。

长轮询等待期间：

- 客户端和服务端之间的 TCP 连接仍然保持；
- HTTP 请求还没有完成；
- 服务端没有写响应；
- 处理该请求的业务线程已经释放；
- 服务端保存了请求上下文、异步上下文和超时信息。

当配置变化时：

```text
配置发布事件触发
  ↓
服务端找到监听该 dataId/group/namespace 的挂起请求
  ↓
通过异步请求上下文写入响应
  ↓
complete 请求
  ↓
HTTP 响应返回客户端
  ↓
本轮长轮询请求结束
```

当配置一直没有变化时：

```text
长轮询等待超时
  ↓
服务端返回空结果
  ↓
本轮 HTTP 请求结束
  ↓
客户端立即发起下一轮长轮询
```

所以，长轮询的关键是：

> **连接保持、请求挂起、线程释放、事件或超时唤醒。**

虽然业务线程被释放了，但长轮询仍然会占用连接资源、请求上下文和少量内存，所以它不是零成本，只是避免了“一请求一线程阻塞”。

### 4. 配置变化时，为什么不直接把完整配置返回给客户端？

Nacos 长轮询返回的是变化的配置标识，而不是完整配置内容。客户端收到变化标识后，再调用配置查询接口拉取完整配置。

流程如下：

```text
长轮询返回：nacos-config-demo.yaml 发生变化
  ↓
客户端调用 getConfig
  ↓
拉取完整配置内容
  ↓
更新本地缓存
  ↓
重新计算 MD5
  ↓
触发 Listener
  ↓
更新 Spring Environment / PropertySource
```

这样设计有几个原因。

**第一，职责更清晰。**

长轮询接口负责发现变化，配置查询接口负责获取完整配置内容：

```text
长轮询接口：判断哪些配置变了
配置查询接口：获取具体配置内容
```

这让变化发现和配置拉取解耦，客户端也能复用统一的配置拉取、缓存、MD5 计算、快照保存、监听器通知和 Spring 刷新流程。

**第二，一个长轮询请求可以监听多个配置。**

客户端可能同时监听多个 DataId：

```text
application.yaml
datasource.yaml
redis.yaml
feature-switch.yaml
```

如果多个配置发生变化，长轮询只需要返回变化列表：

```text
application.yaml changed
redis.yaml changed
```

客户端再按需拉取完整配置。如果直接在长轮询响应中返回完整配置，响应体会变大，也会让多配置、多格式的处理逻辑变复杂。

**第三，降低长轮询响应压力。**

长轮询连接数量可能很多。如果配置变更时，服务端直接把完整配置内容返回给所有客户端，会造成瞬时响应体过大。

只返回变化标识时，响应内容很小：

```text
dataId + group + namespace
```

随后客户端再调用配置查询接口拉取完整内容，整体链路更可控。

**第四，避免重复实现配置处理流程。**

完整配置拉取后，客户端还需要执行：

- 更新本地缓存；
- 更新本地快照；
- 重新计算 MD5；
- 触发 Nacos Listener；
- 更新 Spring `PropertySource`；
- 触发 Spring Cloud Refresh；
- 清理 `@RefreshScope` Bean 缓存。

这些逻辑放在统一的配置拉取流程中更清晰。如果长轮询直接返回完整配置，就需要在长轮询分支里重复一套配置处理逻辑。

面试时可以这样总结：

> **Nacos 长轮询负责发现变化，不负责传输完整配置。服务端返回变化标识后，客户端再通过统一的 getConfig 流程拉取完整配置，并复用缓存、MD5、Listener、PropertySource 更新和 Spring Cloud Refresh 逻辑。**

### 5. 长轮询和长连接哪种更好？

有些动态配置中心或协调系统，例如 ZooKeeper，在监听服务端数据变化时使用的是 **长连接**，而不是 HTTP 长轮询。两种方式没有绝对优劣，核心取决于场景。

先看两者的模型差异。

HTTP 长轮询是请求-响应模型：

```text
客户端发起 HTTP 请求
  ↓
服务端没有变化就挂起请求
  ↓
有变化或超时后返回
  ↓
客户端再发起下一轮请求
```

长连接是持续连接模型：

```text
客户端和服务端建立长期 TCP 连接
  ↓
连接持续存在
  ↓
服务端有数据变化时主动通过连接推送事件
  ↓
客户端收到事件后处理
```

#### 5.1 HTTP 长轮询的优缺点

HTTP 长轮询的优点是：

- **实现简单**：基于普通 HTTP 请求即可实现；
- **兼容性好**：对传统 HTTP 代理、负载均衡、防火墙更友好；
- **部署成本低**：不需要额外处理复杂的连接会话状态；
- **适合低频变更**：配置中心的配置变更频率通常不高，长轮询足够使用。

HTTP 长轮询的缺点是：

- **请求有周期性开销**：每次超时后客户端都要重新发起请求；
- **仍然占用连接资源**：挂起期间 TCP 连接、请求上下文和少量内存仍然存在；
- **不是完整的服务端主动推送**：本质仍然是客户端请求驱动；
- **大规模客户端下仍需管理大量挂起请求**。

所以，HTTP 长轮询适合：

```text
配置变更不频繁
客户端数量可控
更重视 HTTP 兼容性和部署简单性
已有 HTTP 基础设施比较多
```

#### 5.2 长连接的优缺点

长连接的优点是：

- **实时性更好**：服务端有事件时可以直接通过连接推送；
- **通信开销更低**：连接建立后可以持续复用，不需要反复建立一轮轮请求；
- **表达能力更强**：支持服务端推送、客户端上报、心跳、双向流等能力；
- **适合高频事件通知**：例如服务实例上下线、注册发现、健康状态变化等。

长连接的缺点是：

- **实现复杂度更高**：需要处理心跳、断线重连、连接状态、会话恢复；
- **对网络设备要求更高**：需要关注 SLB、Nginx、网关、防火墙、K8s Ingress 的连接超时和协议支持；
- **服务端状态更重**：需要长期维护客户端连接、订阅关系、心跳状态和推送队列。

所以，长连接适合：

```text
事件通知频繁
实时性要求更高
客户端规模较大
需要双向通信或连接复用
```

#### 5.3 ZooKeeper 为什么使用长连接？

ZooKeeper 的核心模型天然依赖长连接：

```text
客户端连接 ZooKeeper Server
  ↓
建立 Session
  ↓
客户端注册 Watch
  ↓
服务端数据变化
  ↓
通过 Session 所在连接推送 Watch 事件
```

ZooKeeper 需要维护：

- Session 语义；
- Watch 通知；
- 临时节点；
- 心跳检测；
- 连接断开感知；
- 客户端重连后的状态恢复。

比如临时节点依赖 Session 生命周期：

```text
客户端连接断开
  ↓
Session 超时
  ↓
临时节点删除
```

这些能力更适合使用长连接来实现，而不是使用 HTTP 长轮询。

#### 5.4 Nacos 为什么从长轮询演进到 gRPC 长连接？

Nacos 的演进可以这样理解：

| 版本 | 通信模型 | 说明 |
|---|---|---|
| Nacos 1.x | HTTP 长轮询为主 | 配置监听主要通过 HTTP 长轮询实现 |
| Nacos 2.x | 引入 gRPC 长连接 | 客户端与服务端开始使用 gRPC 长连接通信模型 |
| Nacos 3.x | 继续强化长连接和云原生通信能力 | 在 2.x 基础上继续演进连接管理、协议和云原生能力 |

也就是说，不是 Nacos 3.x 才开始使用 gRPC 长连接，**Nacos 2.x 就已经引入了基于 gRPC 的长连接通信模型**，Nacos 3.x 是在这个方向上继续增强。

Nacos 从 HTTP 长轮询演进到 gRPC 长连接，主要原因是 Nacos 的定位不再只是配置中心，还承担了更多服务治理能力：

- 服务注册发现；
- 实例上下线通知；
- 健康检查；
- 元数据同步；
- 配置变更通知；
- 多语言客户端通信；
- 大规模客户端连接管理。

这些场景比单纯的低频配置变更更适合长连接，因为长连接可以降低频繁 HTTP 请求开销，提升服务变更推送实时性，并支持双向通信和连接复用。

#### 5.5 两种方式怎么选？

可以这样总结：

| 维度 | HTTP 长轮询 | gRPC / ZooKeeper 长连接 |
|---|---|---|
| 通信模型 | 请求-响应 | 持久连接 / 流式通信 |
| 服务端通知 | 通过挂起请求返回 | 通过连接主动推送 |
| 实时性 | 较好 | 更好 |
| 实现复杂度 | 较低 | 较高 |
| 网络兼容性 | 更好 | 对代理、网关、HTTP/2 支持要求更高 |
| 服务端状态 | 挂起请求和订阅关系 | 长连接、心跳、会话、订阅关系 |
| 适合场景 | 低频配置变更、兼容性优先 | 高频事件通知、服务治理、实时推送 |
| 典型系统 | Nacos 1.x Config | ZooKeeper、Nacos 2.x/3.x gRPC |

面试时可以这样回答：

> 长轮询和长连接没有绝对优劣。HTTP 长轮询本质还是请求-响应模型，服务端在没有变化时挂起请求，有变化或超时后返回，客户端再发起下一轮请求。它的优点是实现简单、HTTP 兼容性好、对网关和代理友好，适合配置变更不频繁的场景。长连接则是客户端和服务端维持一条长期连接，服务端有变化时可以直接推送，实时性更好，通信开销更低，更适合服务注册发现、实例上下线、健康状态变化等高频事件通知场景，但需要处理心跳、断线重连、连接状态管理和网关超时等复杂问题。Nacos 1.x 配置监听主要基于 HTTP 长轮询，Nacos 2.x 开始引入 gRPC 长连接通信模型，Nacos 3.x 在这个方向上继续强化。

一句话总结：

> **配置变更低频、追求简单兼容，用长轮询就够；事件通知高频、追求实时和连接复用，用 gRPC 或 ZooKeeper 这类长连接更合适。**

### 6. Environment 是怎么更新的？

Nacos 感知配置变化后，会重新拉取对应 DataId 的配置内容，并更新应用本地维护的 `PropertySource`。

Spring 的配置读取本质上是从 `Environment` 的多个 `PropertySource` 中按优先级查找属性：

```text
Environment
  ├── systemProperties
  ├── systemEnvironment
  ├── application.yml
  └── nacos-config-demo.yaml
```

当 Nacos 对应的 `PropertySource` 内容更新后，后续再从 `Environment` 读取属性，就可以拿到新值。

### 7. 为什么 @Value 默认不会自动变？

普通 Bean 中的 `@Value` 是在 Bean 创建时注入的：

```java
@Value("${demo.title}")
private String title;
```

这个字段本质上是一个普通 Java 字段。Bean 创建完成后，即使 `Environment` 中的配置变了，这个字段也不会自动重新赋值。

所以如果希望 `@Value` 动态刷新，需要把 Bean 放进 `@RefreshScope`：

```java
@RefreshScope
@RestController
public class DemoValueController {

    @Value("${demo.title}")
    private String title;
}
```

配置变化后，`RefreshScope` 会清理这个 Bean 的缓存，下次访问时重新创建 Bean，`@Value` 才会重新注入新值。

### 8. @RefreshScope 的核心原理

`@RefreshScope` 的本质是 Spring Cloud 提供的一个特殊 Scope。

普通单例 Bean 是容器启动时创建一次，之后一直复用；而 `@RefreshScope` Bean 会被代理包装起来，真实对象缓存在 Scope 中。

```text
调用方注入的是代理对象
  ↓
代理对象从 RefreshScope 缓存中获取真实 Bean
  ↓
配置刷新时清空 RefreshScope 缓存
  ↓
下一次方法调用时重新创建真实 Bean
  ↓
重新执行属性注入和配置绑定
```

所以动态刷新的关键不是直接修改原对象字段，而是：

> **清理旧 Bean，让下一次访问重新创建一个绑定了新配置的 Bean。**

---

## 七、Demo：使用 @Value + @RefreshScope 动态刷新

```java
package com.example.nacosdemo.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.context.config.annotation.RefreshScope;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

@RefreshScope
@RestController
public class DemoValueController {

    @Value("${demo.title:default-title}")
    private String title;

    @Value("${demo.version:0.0.0}")
    private String version;

    @GetMapping("/demo/value")
    public Map<String, Object> getValueConfig() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("title", title);
        response.put("version", version);
        return response;
    }
}
```

验证步骤：

1. 启动应用；
2. 访问 `http://localhost:8080/demo/value`；
3. 在 Nacos 控制台修改配置：

```yaml
demo:
  title: "Nacos 配置已刷新"
  version: "2.0.0"
  enabled: true
```

4. 发布配置；
5. 再次访问 `http://localhost:8080/demo/value`；
6. 如果动态刷新生效，会看到新值。

---

## 八、@ConfigurationProperties 和 @RefreshScope 怎么选？

推荐原则：

| 场景 | 推荐方式 |
|---|---|
| 少量配置、简单 Demo | `@Value` + `@RefreshScope` |
| 一组业务配置 | `@ConfigurationProperties` |
| 配置需要运行期刷新 | `@ConfigurationProperties` + `@RefreshScope` |
| 配置影响自动装配条件 | 必须启动前加载，运行期刷新不会重新触发已完成的自动装配流程 |

需要注意：

- `@RefreshScope` 适合刷新普通业务 Bean；
- 对已经完成初始化的基础设施 Bean，比如数据源、线程池、部分自动配置类，是否能安全刷新要谨慎评估；
- 如果配置影响 `@ConditionalOnProperty`，运行期修改配置不会重新执行完整自动装配流程。

---

## 九、完整验证流程

### 1. 启动 Nacos

本地启动 Nacos 后，访问控制台：

```text
http://localhost:8848/nacos
```

Nacos 本地默认账号密码是：

```text
nacos / nacos
```

### 2. 创建配置

- Data ID：`nacos-config-demo.yaml`
- Group：`DEFAULT_GROUP`
- Format：`YAML`

配置内容：

```yaml
demo:
  title: "Nacos 配置中心 Demo"
  version: "1.0.0"
  enabled: true
```

### 3. 启动应用

观察启动日志中是否出现 Nacos 配置加载相关日志。

### 4. 访问接口

```text
GET http://localhost:8080/demo/config
GET http://localhost:8080/demo/value
```

### 5. 修改 Nacos 配置

```yaml
demo:
  title: "配置已经动态刷新"
  version: "2.0.0"
  enabled: false
```

发布后等待几秒，再次访问接口。如果返回新值，说明动态刷新生效。

---

## 十、常见问题

### 1. 为什么启动时读取不到 Nacos 配置？

重点检查：

- `spring.application.name` 是否和 DataId 匹配；
- `spring.config.import` 是否配置了 `nacos:`；
- DataId、Group、Namespace 是否一致；
- Nacos Server 地址是否正确；
- 版本是否匹配；
- 配置格式是否和 `file-extension` 一致。

### 2. 为什么修改 Nacos 后接口没有变化？

重点检查：

- 是否开启 `refresh-enabled: true`；
- 使用 `@Value` 的 Bean 是否加了 `@RefreshScope`；
- 配置是否发布成功；
- DataId、Group、Namespace 是否是应用正在监听的那一份；
- 是否访问的是被代理后的 `@RefreshScope` Bean，而不是自己 new 出来的对象。

### 3. `@ConditionalOnProperty` 能动态刷新吗？

不能按普通业务配置那样理解。

`@ConditionalOnProperty` 主要在容器启动和自动装配阶段生效。运行期修改配置后，不会重新走完整的自动装配流程，也不会自动销毁或创建一批新的自动配置 Bean。

如果业务需要运行期开关，建议自己实现开关判断逻辑，例如通过 `@ConfigurationProperties` 读取开关，并在业务方法中判断。

### 4. 所有 Bean 都适合加 @RefreshScope 吗？

不适合。

`@RefreshScope` 会让 Bean 通过代理和缓存机制延迟获取真实对象，配置刷新时会清空旧对象并重新创建。它适合业务配置类、轻量服务类，不适合随意加在底层基础设施 Bean 上。

比如数据源、连接池、线程池、消息消费者等，如果运行期刷新，需要额外考虑资源关闭、连接迁移、线程安全和请求中的对象引用问题。

---

## 十一、面试回答模板

如果面试官问：Nacos 是在 SpringBoot 启动的哪个阶段接入的？

可以这样回答：

> Nacos 配置中心的首次加载发生在 SpringBoot 启动早期的 Environment 准备阶段，准确说是在 Spring 容器 `refresh()` 之前。因为后续 Bean 创建、`@Value` 注入、`@ConfigurationProperties` 绑定以及 `@ConditionalOnProperty` 自动装配条件判断都依赖 Environment，所以远程配置必须提前加载。
> 
> 在老版本 Spring Cloud 中，Nacos 通过 Bootstrap Context 机制接入，由 `BootstrapApplicationListener` 创建 bootstrap 上下文，再通过 `NacosPropertySourceLocator` 拉取远程配置并加入 Environment。
> 
> 在 Spring Boot 2.4+ 中，更推荐使用 ConfigData 机制，也就是通过 `spring.config.import=nacos:xxx`，由 `ConfigDataEnvironmentPostProcessor` 在准备 Environment 时解析并加载 Nacos 配置。

如果面试官问：Nacos 动态刷新 Bean 属性是怎么实现的？

可以这样回答：

> Nacos 动态刷新分为两步。第一步是 Nacos Client 通过长轮询监听配置变化，发现配置变更后重新拉取配置并更新本地 `PropertySource`。第二步是通过 Spring Cloud Refresh 机制发布刷新事件，`@RefreshScope` 会清理目标 Bean 的缓存。调用方注入的是代理对象，下次访问时代理会重新创建真实 Bean，重新执行 `@Value` 注入或 `@ConfigurationProperties` 绑定，因此就能拿到最新配置。
> 
> 需要注意的是，普通 Bean 中的 `@Value` 字段不会因为 Environment 改变而自动变化，必须配合 `@RefreshScope`，或者使用可重新绑定的配置类。对于 `@ConditionalOnProperty` 这类影响自动装配的配置，运行期修改不会重新触发完整自动装配流程。

---

## 十二、核心总结

- **Nacos 首次加载配置**：发生在 SpringBoot `refresh()` 之前的 Environment 准备阶段。
- **配置进入 Spring 的形式**：远程配置会被封装成 `PropertySource`，加入 `Environment`。
- **老版本接入方式**：Bootstrap Context + `PropertySourceLocator`。
- **新版本接入方式**：ConfigData + `spring.config.import`。
- **动态刷新原理**：Nacos Client 长轮询监听变化，更新 `PropertySource`，触发 Spring Cloud Refresh。
- **@RefreshScope 原理**：调用方拿到代理对象，真实 Bean 缓存在 Scope 中；刷新时清缓存，下次访问重新创建 Bean。
- **不要误解动态刷新**：它适合普通业务配置，不等于整个 Spring 容器重新启动，也不等于所有自动装配条件都会重新计算。
