# Day 21：设计模式与编码能力

> 复习目标：掌握高频设计模式的原理及在 Spring/中间件源码中的实际运用。

---

## 一、高频设计模式

**Q1：策略模式的原理？在实际项目中怎么用？**

A：定义一组算法，封装每个算法为独立类，使它们可以互换。消除 if-else 分支。

```java
// 策略接口
public interface PayStrategy {
    void pay(BigDecimal amount);
}

// 具体策略
@Component("WECHAT")
public class WechatPay implements PayStrategy { ... }

@Component("ALIPAY")
public class AlipayPay implements PayStrategy { ... }

// 上下文：通过 Spring 自动注入所有策略，按名称查找
@Service
public class PayService {
    @Autowired
    private Map<String, PayStrategy> strategyMap;  // Spring 自动注入

    public void pay(String type, BigDecimal amount) {
        PayStrategy strategy = strategyMap.get(type);
        if (strategy == null) throw new IllegalArgumentException("不支持的支付方式");
        strategy.pay(amount);
    }
}
```

**Spring 中的应用**：`Resource` 接口（ClassPathResource、FileSystemResource、UrlResource）。

---

**Q2：模板方法模式的原理？Spring 中哪些地方用到了？**

A：在父类中定义算法的骨架，将某些步骤延迟到子类实现。

```java
public abstract class AbstractExporter {
    // 模板方法（定义流程骨架）
    public final void export(List<Data> data) {
        validate(data);          // 通用校验
        List<Data> filtered = filter(data);  // 子类可覆盖
        byte[] content = doExport(filtered);  // 抽象方法，子类实现
        upload(content);         // 通用上传
    }

    protected List<Data> filter(List<Data> data) { return data; }  // 钩子方法
    protected abstract byte[] doExport(List<Data> data);  // 抽象方法
}

public class ExcelExporter extends AbstractExporter {
    @Override
    protected byte[] doExport(List<Data> data) { /* EasyExcel 导出 */ }
}
```

**Spring 中的应用**：
- `JdbcTemplate`：定义 SQL 执行流程，回调处理结果
- `AbstractApplicationContext.refresh()`：定义容器启动流程
- `RestTemplate`：定义 HTTP 请求流程

---

**Q3：观察者模式的原理？Spring 中如何实现？**

A：对象之间一对多依赖，一个对象状态变化时通知所有依赖它的对象。

**Spring 事件机制（推荐方式）**：
```java
// 1. 定义事件
public class OrderCreatedEvent extends ApplicationEvent {
    private final Long orderId;
    public OrderCreatedEvent(Object source, Long orderId) {
        super(source);
        this.orderId = orderId;
    }
}

// 2. 发布事件
@Service
public class OrderService {
    @Autowired
    private ApplicationEventPublisher publisher;

    public void createOrder(Order order) {
        // ... 创建订单逻辑
        publisher.publishEvent(new OrderCreatedEvent(this, order.getId()));
    }
}

// 3. 监听事件（可以有多个监听器）
@Component
public class SmsListener {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // 发送短信通知
    }
}

@Component
public class PointsListener {
    @EventListener
    public void onOrderCreated(OrderCreatedEvent event) {
        // 增加积分
    }
}
```

`@Async` + `@EventListener` 可实现异步监听，解耦更彻底。

---

**Q4：责任链模式的原理？哪些框架用到了？**

A：将请求沿着处理者链传递，每个处理者决定是否处理以及是否传递给下一个。

**框架中的应用**：
| 框架 | 应用 |
|------|------|
| **Servlet Filter** | FilterChain，多个 Filter 依次执行 |
| **Spring Interceptor** | HandlerInterceptor，preHandle → 业务 → postHandle → afterCompletion |
| **Netty ChannelPipeline** | ChannelHandler 链式处理入站/出站事件 |
| **Sentinel** | ProcessorSlot 链（FlowSlot → DegradeSlot → ...） |
| **MyBatis Plugin** | Interceptor 拦截器链 |

---

**Q5：代理模式在 Spring 中的应用？**

A：
**JDK 动态代理 vs CGLIB**：
| 维度 | JDK 动态代理 | CGLIB |
|------|------------|-------|
| 要求 | 目标类必须实现接口 | 无需接口（通过继承） |
| 原理 | `Proxy.newProxyInstance` + `InvocationHandler` | 字节码生成目标类的子类 |
| 性能 | JDK 8+ 性能已优化，差距不大 | 生成字节码开销大，调用稍快 |
| Spring 默认 | SpringBoot 2.0+ 默认 CGLIB（`spring.aop.proxy-target-class=true`） | — |

**Spring AOP 代理失效场景**：
1. **同类方法调用**：`this.methodB()` 不经过代理，`@Transactional` 等注解失效
   - 解决：注入自身（`@Autowired self`）或 `AopContext.currentProxy()`
2. **private 方法**：CGLIB 无法代理 private 方法
3. **final 方法/类**：CGLIB 通过继承实现，final 无法覆盖

---

**Q6：工厂模式的三种形式？Spring 中怎么用？**

A：
| 形式 | 说明 | 示例 |
|------|------|------|
| **简单工厂** | 一个工厂类根据参数创建不同对象 | `Calendar.getInstance()` |
| **工厂方法** | 每个产品有对应的工厂类 | `Collection.iterator()` 每个集合返回自己的迭代器 |
| **抽象工厂** | 创建一系列相关对象的工厂 | `DataSource` → 不同数据库的连接、Statement 等 |

**Spring 中**：
- `BeanFactory` / `ApplicationContext`：最核心的工厂，负责创建和管理所有 Bean
- `FactoryBean<T>`：自定义复杂对象的创建逻辑（如 MyBatis 的 `SqlSessionFactoryBean`）

---

## 二、设计模式在源码中的综合运用

**Q7：Spring 中用到了哪些设计模式？**

A：
| 设计模式 | Spring 中的应用 |
|---------|----------------|
| 工厂模式 | BeanFactory、FactoryBean |
| 单例模式 | Bean 默认 scope=singleton，三级缓存解决循环依赖 |
| 代理模式 | AOP（JDK 动态代理 / CGLIB） |
| 模板方法 | JdbcTemplate、RestTemplate、AbstractApplicationContext |
| 观察者模式 | ApplicationEvent + ApplicationListener |
| 策略模式 | Resource 接口的多实现、HandlerMapping |
| 适配器模式 | HandlerAdapter（不同类型 Controller 的适配） |
| 装饰器模式 | BeanWrapper、HttpServletRequestWrapper |
| 责任链模式 | Filter、Interceptor |

---

**Q8：MyBatis 中用到了哪些设计模式？**

A：
| 设计模式 | MyBatis 中的应用 |
|---------|-----------------|
| 工厂模式 | SqlSessionFactory 创建 SqlSession |
| 建造者模式 | SqlSessionFactoryBuilder、XMLConfigBuilder |
| 代理模式 | Mapper 接口的动态代理（MapperProxy） |
| 模板方法 | BaseExecutor（query 流程固定，缓存和 DB 查询由子类实现） |
| 装饰器模式 | Cache 装饰链（LruCache → FifoCache → LoggingCache → PerpetualCache） |
| 责任链/插件 | Interceptor 拦截器链（Plugin.wrap 生成代理） |

---

## 三、编码能力

**Q9：手写 LRU 缓存？**

A：
```java
public class LRUCache<K, V> {
    private final int capacity;
    private final Map<K, Node<K, V>> map;
    private final Node<K, V> head, tail;  // 双向链表哨兵

    public LRUCache(int capacity) {
        this.capacity = capacity;
        this.map = new HashMap<>();
        this.head = new Node<>(null, null);
        this.tail = new Node<>(null, null);
        head.next = tail;
        tail.prev = head;
    }

    public V get(K key) {
        Node<K, V> node = map.get(key);
        if (node == null) return null;
        moveToHead(node);  // 访问后移到头部（最近使用）
        return node.value;
    }

    public void put(K key, V value) {
        Node<K, V> node = map.get(key);
        if (node != null) {
            node.value = value;
            moveToHead(node);
        } else {
            Node<K, V> newNode = new Node<>(key, value);
            map.put(key, newNode);
            addToHead(newNode);
            if (map.size() > capacity) {
                Node<K, V> removed = removeTail();  // 移除尾部（最久未使用）
                map.remove(removed.key);
            }
        }
    }

    private void moveToHead(Node<K, V> node) { removeNode(node); addToHead(node); }
    private void addToHead(Node<K, V> node) { node.prev = head; node.next = head.next; head.next.prev = node; head.next = node; }
    private void removeNode(Node<K, V> node) { node.prev.next = node.next; node.next.prev = node.prev; }
    private Node<K, V> removeTail() { Node<K, V> node = tail.prev; removeNode(node); return node; }

    static class Node<K, V> {
        K key; V value; Node<K, V> prev, next;
        Node(K key, V value) { this.key = key; this.value = value; }
    }
}
```

---

**Q10：手写生产者-消费者模型？**

A：
```java
public class ProducerConsumer {
    private final Queue<Integer> queue = new LinkedList<>();
    private final int capacity = 10;

    public synchronized void produce(int item) throws InterruptedException {
        while (queue.size() == capacity) {
            wait();  // 队列满，等待
        }
        queue.offer(item);
        System.out.println("生产: " + item);
        notifyAll();  // 通知消费者
    }

    public synchronized int consume() throws InterruptedException {
        while (queue.isEmpty()) {
            wait();  // 队列空，等待
        }
        int item = queue.poll();
        System.out.println("消费: " + item);
        notifyAll();  // 通知生产者
        return item;
    }
}
// 也可用 BlockingQueue（ArrayBlockingQueue）一行代码实现
```
