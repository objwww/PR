# 告警 Agent 工具侧完整实现文档

> 本文档基于 2026 年 9 月 13 日工作区源码（基线提交 `67dc8ff78ce934f058da85364cbca3352ba71e1f`）整理，从实际业务问题出发，详细说明工具侧每一个设计决策的思考过程、技术选型理由，以及面对大规模扩展时的优化思路。

---

## 第一部分：从一个真实的告警开始思考

### 1.1 问题的起点：告警来了，然后呢？

当我们收到一条告警时，比如"checkout 服务的支付调用出现异常"，值班人员面对的不是"有没有告警"的问题，而是三个更根本的问题：

1. **现场到底发生了什么？** —— 不是猜测，而是有数据支撑的事实
2. **问题出在哪一段？** —— 是网络、数据库、第三方服务，还是代码逻辑？
3. **有什么证据支持这个判断？** —— 不能只是"听起来合理"，必须能回溯到具体的指标、日志或变更记录

传统的做法是值班人员手动去各个监控平台查指标、翻日志、看变更记录，这个过程可能需要十几分钟甚至更久。而如果让一个大语言模型（LLM）直接拿着告警信息去判断，它能做的只是基于已有知识给出一些可能的原因，但这些原因缺少现场数据的支撑，本质上仍然是"猜测"。

**所以工具侧要解决的核心问题是：如何让 LLM 能够查到现场的数据，而不是凭空猜测。**

### 1.2 为什么不能让模型自由操作系统？

最直接的想法可能是：给模型一个终端，让它自己执行命令去查询。但这样做有几个严重的问题：

1. **安全边界无法控制**：模型可能被提示注入攻击，执行恶意命令
2. **资源无法限制**：模型可能发起大量查询，把监控系统打垮
3. **结果无法复查**：不知道模型执行了什么命令，拿到了什么数据
4. **错误难以定位**：当调查失败时，不知道是模型选错了工具，还是参数填错了，还是数据源本身出问题了

因此，我们采用的策略是：**模型只负责提议想查什么，程序负责检查权限、参数、执行查询，并保存有来源的证据。**

### 1.3 工具侧的设计哲学：明确的边界与可追溯的证据

基于上述问题，我们确立了工具侧的几个核心设计原则：

1. **工具是有限的入口，不是无限的能力**：只提供调查需要的查询能力，每个工具都有明确的输入输出契约
2. **检查在执行之前，而不是执行之后再补救**：参数错了不执行，权限不够不执行，预算耗尽不执行
3. **每次查询都留下可追溯的记录**：谁在什么时候查了什么，拿到了什么结果，为什么失败
4. **区分"模型可以重试的错误"和"必须停止的错误"**：参数填错可以让模型改，但凭证失效不能靠模型多试几次解决

接下来，我们会按照这个思路，详细展开工具侧的完整实现。

---

## 第二部分：工具的生命周期——从定义到执行

### 2.1 工具定义：不只是一个名字和描述

#### 2.1.1 为什么需要严格的工具定义？

在开始之前，我们需要明确一个工具到底包含哪些信息。简单地说，一个工具定义需要回答以下问题：

- **这个工具叫什么名字？版本是什么？** —— 用于标识和版本管理
- **它接受什么样的输入？** —— 参数的类型、必填项、格式约束
- **它的风险等级是多少？** —— 只读查询 vs 写入操作
- **执行它最多需要多长时间？** —— 超时限制
- **返回结果最大能有多大？** —— 防止超大响应撑爆内存

#### 2.1.2 工具定义的六个核心字段

让我们看看代码中的工具定义是如何实现的（`ToolDefinition.java`）：

```java
public record ToolDefinition(
    String name,              // 工具名称，如 "prometheus.query"
    String version,           // 版本号，如 "1"
    Map<String, Object> schema,  // 输入参数的 JSON Schema
    ToolRisk risk,           // 风险等级（R0=只读，R1/R2/R3=不同风险的写操作）
    long timeoutMillis,      // 超时时间（毫秒）
    long resultLimitBytes    // 结果大小上限（字节）
)
```

**为什么选择 Java Record 而不是普通类？**

Record 是 Java 14 引入的不可变数据载体，它有几个关键优势：
- **不可变性**：创建后无法修改，避免工具定义被运行时篡改
- **结构清晰**：字段名即参数名，代码即文档
- **自动实现 equals/hashCode**：便于集合操作和比较

#### 2.1.3 工具名称和版本的命名规范

工具名称不是随便起的，而是有严格的格式约束：

```java
private static void requireName(String name) {
    if (name == null || !name.matches("[a-z][a-z0-9._-]{0,63}")) {
        throw new IllegalArgumentException(
            "工具名必须匹配 [a-z][a-z0-9._-]{0,63}，实际: " + name);
    }
}
```

**为什么要限制工具名称格式？**

1. **小写字母开头**：避免大小写混淆（`Logs.Query` vs `logs.query`）
2. **只允许字母、数字、点、下划线、连字符**：防止特殊字符引入安全问题
3. **最长 64 字符**：限制长度，防止过长的名称影响存储和传输

版本号同样有格式要求：`[0-9A-Za-z][A-Za-z0-9._-]{0,31}`，最长 32 字符。

**为什么需要显式的版本号？**

假设我们有一个 `logs.aggregate` 工具，原来默认统计所有日志，后来改成默认只统计错误日志。如果不区分版本，模型可能会用旧的理解（以为是全部日志）来解读新版本的返回结果（实际只有错误日志），导致判断错误。

显式版本号的作用：
- **输入输出契约的版本绑定**：同一个名字，不同版本可能有不同的参数或返回值含义
- **调用记录的可追溯性**：知道这次调用使用的是哪个具体版本
- **灰度和 A/B 测试的基础**：可以让一部分调查使用新版本，其余使用旧版本

#### 2.1.4 Schema 的归一化处理：为什么不能直接用原始 JSON？

工具定义中的 `schema` 字段是一个 Map，用于描述输入参数的结构。但在构造时，我们会对它进行"归一化"处理：

```java
private static Map<String, Object> normalizeSchema(Map<String, Object> schema) {
    // 1. 检查根类型必须是 object
    if (!"object".equals(schema.get("type"))) {
        throw new IllegalArgumentException("schema.type 必须为 object");
    }
    
    // 2. 检查 properties 必须非空
    Object properties = schema.get("properties");
    if (!(properties instanceof Map<?, ?> props) || props.isEmpty()) {
        throw new IllegalArgumentException("schema.properties 必须为非空映射");
    }
    
    // 3. 检查每个属性的类型声明
    for (Map.Entry<?, ?> e : props.entrySet()) {
        if (!(e.getValue() instanceof Map<?, ?> prop)
                || !(prop.get("type") instanceof String propType)
                || !ALLOWED_PROPERTY_TYPES.contains(propType)) {
            throw new IllegalArgumentException(
                "属性 " + e.getKey() + " 缺合法 type 声明");
        }
    }
    
    // 4. 拒绝 additionalProperties=true
    Object additional = schema.get("additionalProperties");
    if (Boolean.TRUE.equals(additional)) {
        throw new IllegalArgumentException(
            "additionalProperties=true 拒绝（未声明字段必须硬拒绝）");
    }
    
    // 5. 创建不可变副本，显式设置 additionalProperties=false
    TreeMap<String, Object> normalized = new TreeMap<>();
    normalized.putAll(schema);
    normalized.put("additionalProperties", false);
    return Collections.unmodifiableMap(normalized);
}
```

**为什么要做这些检查和转换？**

1. **拒绝未声明字段**：如果允许 `additionalProperties=true`，模型可能会尝试添加隐藏参数来扩大能力
2. **使用 TreeMap 而不是 HashMap**：TreeMap 按键排序，保证字段顺序稳定，这对后续计算 schema hash 很重要
3. **返回不可变 Map**：防止定义被运行时修改

#### 2.1.5 Schema Hash：工具身份的指纹

除了基本字段，工具定义还提供了一个计算 schema hash 的方法：

```java
public String schemaHash() {
    return InternalCanonicalJsonV1.sha256(schema);
}
```

**为什么需要 schema hash？**

假设你修改了 `logs.query` 的参数格式，从三个参数改成四个参数，但版本号忘记升级还是"1"。如果没有 schema hash，系统无法检测到这个不兼容的变更，可能导致：
- 旧的调用记录无法复现（参数格式已经不同）
- 缓存的参数校验规则失效（新旧格式冲突）

Schema hash 的作用：
- **检测格式变更**：即使版本号相同，格式变了 hash 也会变
- **作为动作摘要的一部分**：用于判断两次调用是否语义相同
- **缓存失效的依据**：格式变了，旧的校验逻辑和缓存都应该失效

**为什么使用 Canonical JSON 而不是直接序列化？**

直接序列化的问题：
```json
{"a": 1, "b": 2}  // hash1
{"b": 2, "a": 1}  // hash2 ≠ hash1（字段顺序不同）
```

Canonical JSON 会先排序字段、统一空白符，保证相同内容的 hash 相同：
```json
{"a":1,"b":2}  // hash
{"b":2,"a":1}  // hash（相同）
```

### 2.2 工具注册表：启动时建立身份，运行时不允许修改

#### 2.2.1 为什么注册表是启动时构造的？

让我们看看 `ToolRegistry` 的设计：

```java
public final class ToolRegistry {
    private final Map<Key, Registration> registrations = new LinkedHashMap<>();
    
    public ToolRegistry(List<Registration> registrations) {
        for (Registration r : registrations) {
            Key key = new Key(r.definition().name(), r.definition().version());
            Registration previous = this.registrations.putIfAbsent(key, r);
            if (previous != null) {
                throw new IllegalStateException(
                    "工具重名/版本冲突（启动期 fail-fast，禁静默覆盖）: "
                    + r.definition().name() + "@" + r.definition().version());
            }
        }
        if (this.registrations.isEmpty()) {
            throw new IllegalStateException("空工具注册表（启动期硬失败）");
        }
    }
}
```

**为什么选择启动时一次性构造，而不是提供运行时注册 API？**

这是一个关键的设计决策，背后有几个重要理由：

1. **禁止运行时动态下载插件**：如果允许运行时注册，就意味着可能从网络下载工具代码并加载，这带来巨大的安全风险
2. **冲突在启动时暴露**：两个工具都叫 `logs.query@1`，启动时就会失败，而不是运行到某次调用才发现
3. **工具列表稳定可预测**：每次启动后工具列表是固定的，不会出现"上一秒还能用，下一秒突然消失"的情况
4. **简化并发控制**：不需要读写锁保护注册表的增删改

**那如何支持工具的动态上下线？**

对于确实需要动态管理的外部工具，我们使用了另一套机制（MCP，下文会详细介绍），它与本地工具注册表是分开的。

#### 2.2.2 为什么同名同版本必须拒绝，而不是覆盖？

```java
Registration previous = this.registrations.putIfAbsent(key, r);
if (previous != null) {
    throw new IllegalStateException("工具重名/版本冲突...");
}
```

假设两个模块都注册了 `logs.query@1`：
- 模块 A 的实现查询生产日志
- 模块 B 的实现查询测试日志

如果允许后注册的覆盖前注册的，模型会看到相同的名字和版本，但实际执行的是哪个实现取决于加载顺序，这会导致：
- **结果不可预测**：同样的查询，重启后可能得到不同的结果
- **调试困难**：无法从调用记录判断到底执行了哪个实现
- **安全隐患**：恶意模块可以覆盖正常工具的实现

**fail-fast 的价值**：问题在启动时暴露，比运行时才发现要好得多。

#### 2.2.3 工具列表的稳定排序

```java
public List<Registration> all() {
    return registrations.values().stream()
        .sorted(Comparator.comparing(r -> r.definition().name() + "@"
            + r.definition().version()))
        .toList();
}
```

**为什么需要排序？**

工具列表会发送给模型，如果每次顺序都不同，可能影响模型的选择（模型可能对列表位置敏感）。按名称和版本字典序排序，保证：
- **可复现性**：相同的工具集合，每次给模型的顺序都一样
- **可比较性**：不同部署环境的工具列表容易对比
- **可调试性**：日志中工具列表的顺序稳定，便于检查

### 2.3 工具执行器：真正执行查询的地方

#### 2.3.1 执行器接口的简单设计

```java
public interface ToolExecutor {
    record ToolExecution(
        Map<String, Object> validatedArgs,  // 已通过校验的参数
        long deadlineEpochMillis,           // 执行截止时间
        long resultLimitBytes               // 结果大小上限
    ) {}
    
    byte[] execute(ToolExecution execution) throws Exception;
}
```

**为什么接口这么简单？**

执行器只需要关心：
1. **参数是什么**：`validatedArgs` 已经通过了格式和权限检查
2. **最晚什么时候必须返回**：`deadlineEpochMillis` 是硬期限
3. **返回结果最大能多大**：`resultLimitBytes` 是上限

其他所有检查（权限、预算、账本）都在执行器之外处理，执行器可以专注于"如何查询这个数据源"。

**为什么返回 byte[] 而不是结构化对象？**

1. **解耦格式**：执行器可以返回 JSON、XML 或其他格式，统一入口不需要知道具体格式
2. **避免解析开销**：如果只是传递给模型，不需要在中间层解析再序列化
3. **统一大小限制**：无论什么格式，都可以用字节数限制大小

#### 2.3.2 一个真实的执行器：Prometheus 指标查询

让我们看一个具体的执行器实现（`PrometheusQueryExecutor.java`）：

```java
public class PrometheusQueryExecutor implements ToolExecutor {
    private final String baseUrl;  // Prometheus 地址
    private final HttpClient http; // HTTP 客户端
    
    @Override
    public byte[] execute(ToolExecution execution) throws Exception {
        // 1. 语义校验：时间窗口、步长是否合理
        Map<String, Object> args = execution.validatedArgs();
        validateSemantics(args);
        
        // 2. 构造请求
        long remaining = Math.max(1, 
            execution.deadlineEpochMillis() - System.currentTimeMillis());
        HttpRequest request = HttpRequest.newBuilder(
            URI.create(baseUrl + "/api/v1/query_range?..."))
            .timeout(Duration.ofMillis(remaining + 2000))  // 加 2 秒缓冲
            .GET()
            .build();
        
        // 3. 发送请求
        HttpResponse<InputStream> response = http.send(request, 
            HttpResponse.BodyHandlers.ofInputStream());
        
        // 4. 检查响应状态
        if (response.statusCode() == 429) {
            throw new ToolModelVisibleException(
                ToolModelVisibleReason.RATE_LIMITED,
                "指标源限流（可退避重试）");
        }
        if (response.statusCode() != 200) {
            throw new ToolModelVisibleException(
                ToolModelVisibleReason.REMOTE_UNAVAILABLE,
                "工具远端暂不可用（临时故障，可重试）");
        }
        
        // 5. 有界读取：最多读 limit+1 字节
        return readBounded(response.body(), execution.resultLimitBytes());
    }
}
```

**这个实现中的几个关键设计：**

1. **语义校验在执行器内**：时间窗口不超过 3600 秒、步长不超过 60 秒，这是业务规则，不是格式规则
2. **客户端超时加缓冲**：deadline 剩余时间 + 2000 毫秒，让统一入口的超时能先触发
3. **有界流读取**：不是一次性读入内存，而是边读边检查大小，读到 limit+1 就停止
4. **错误分类精确**：429 是限流（RATE_LIMITED），可以重试；其他非 200 是远端不可用（REMOTE_UNAVAILABLE）

**为什么要加 2 秒超时缓冲？**

假设统一入口给了执行器 4 秒的 deadline：
- 如果客户端超时设置为 4 秒，那么客户端和统一入口的超时会竞争，不确定谁先触发
- 如果客户端超时设置为 6 秒（4+2），统一入口会先超时并取消任务，客户端超时变成兜底保护

这样做的好处：
- **统一的超时处理**：所有超时都由统一入口处理，错误消息和账本记录一致
- **避免迟到结果**：统一入口取消后，即使客户端稍后拿到结果，也不会使用

#### 2.3.3 另一个执行器：Loki 日志聚合

`LokiAggregateExecutor` 展示了另一个重要的设计思路：**先聚合后读详情**。

```java
public class LokiAggregateExecutor implements ToolExecutor {
    // 服务白名单：只能查询白名单中的服务
    private final Set<String> serviceAllowlist;
    
    @Override
    public byte[] execute(ToolExecution execution) throws Exception {
        Query query = parseArgs(execution.validatedArgs(), serviceAllowlist);
        
        // 构造聚合查询表达式
        long windowSeconds = (query.until().toEpochMilli() - 
            query.since().toEpochMilli()) / 1_000;
        String expr = "sum by (service_name) (count_over_time(" 
            + selector(query) + "[" + windowSeconds + "s]))";
        
        // 发送查询...
        // 解析响应，返回带元数据的结果
        return render(lokiResponse, query, expr);
    }
    
    Query parseArgs(Map<String, Object> args, Set<String> allowlist) {
        // 检查服务是否在白名单中
        String service = ...;
        if (!allowlist.contains(service)) {
            throw new ToolControlPlaneException(
                ToolControlReason.INVALID_ARGS,
                "INVALID_ARGS: service 越出 allowlist");
        }
        // 检查时间窗口不超过 900 秒
        // 检查 severity 必须是 ALL/ERROR/WARN/INFO 之一
        ...
    }
}
```

**为什么要先聚合？**

如果直接读取所有日志原文：
- 日志可能有几千上万条，返回内容太大
- 模型需要自己数有多少条错误，浪费时间和 token
- 无法快速判断问题的规模（是偶发还是大面积故障）

先聚合的好处：
- **返回固定大小的统计**：每个服务一个计数，最多几十行
- **快速定位范围**：一眼就能看出哪个服务日志量异常
- **为读详情提供依据**：确认有大量错误后，再决定读取样本

**为什么需要服务白名单？**

假设模型被诱导查询 `admin-service` 或 `payment-service`，而当前调查只应该关注 `checkout` 服务：
- **防止越权**：不能查询不属于当前租户或用户的服务
- **限制查询范围**：避免模型随意扩大调查范围，导致资源浪费

白名单检查在执行前：
```java
if (!allowlist.contains(service)) {
    throw new ToolControlPlaneException(...);  // 不会发送网络请求
}
```

### 2.4 工具网关：唯一的咽喉

#### 2.4.1 为什么需要统一的网关？

想象一下，如果每个执行器自己处理权限、超时、账本：
- `PrometheusQueryExecutor` 要自己检查预算，记账本
- `LokiAggregateExecutor` 也要自己检查预算，记账本
- 新增一个工具时，很容易漏掉其中一项检查

**统一网关的价值：**
- **单一检查点**：所有工具调用都经过这里，不会有漏网之鱼
- **一致的错误处理**：相同的错误类型，得到相同的处理
- **统一的账本记录**：所有调用的格式、时间、结果都按同样的规则记录

#### 2.4.2 网关的执行流程

```java
public class ToolGateway implements ToolInvoker {
    private final ToolRegistry registry;    // 工具注册表
    private final ToolPolicy policy;        // 权限策略
    private final ExecutorService callPool; // 执行线程池
    private final RcaEventAppender events;  // 事件账本
    
    public ToolInvocationResult invoke(ToolInvocation invocation) {
        // 1. 从注册表查找工具
        ToolRegistry.Registration registration = registry
            .find(invocation.toolName(), invocation.toolVersion())
            .orElseThrow(() -> new ToolControlPlaneException(
                ToolControlReason.UNKNOWN_TOOL, ...));
        
        // 2. 第二次权限检查（双闸机制）
        if (!policy.allows(invocation.toolName())) {
            throw new ToolControlPlaneException(
                ToolControlReason.POLICY_DENIED, ...);
        }
        
        // 3. 参数校验
        ToolArgsValidator.validate(registration.definition(), 
            invocation.args());
        
        // 4. 计算动作摘要
        String digest = ActionDigest.of(new ActionEnvelope(...));
        
        // 5. 检查风险等级
        ToolRisk risk = registration.definition().risk();
        if (!risk.executable()) {
            recordIntent(invocation, digest, risk);  // 只记录意图，不执行
            return new ToolInvocationResult(Kind.VALIDATE_ONLY, digest, null);
        }
        
        // 6. 在独立线程池中执行
        byte[] body = executeWithDeadline(registration, invocation);
        
        // 7. 检查结果大小
        if (body.length > registration.definition().resultLimitBytes()) {
            throw new ToolControlPlaneException(
                ToolControlReason.RESULT_OVERSIZE, ...);
        }
        
        return new ToolInvocationResult(Kind.EXECUTED, digest, body);
    }
}
```

**让我们详细看每一步的设计理由。**

### 2.4.3 第一层防线：工具是否存在？

```java
ToolRegistry.Registration registration = registry
    .find(invocation.toolName(), invocation.toolVersion())
    .orElseThrow(() -> new ToolControlPlaneException(
        ToolControlReason.UNKNOWN_TOOL,
        "UNKNOWN_TOOL: " + invocation.toolName() + "@" + invocation.toolVersion()));
```

**为什么要显式检查工具是否存在？**

模型可能会：
- **幻觉一个不存在的工具**：比如自己编造一个 `super.query@1`
- **使用错误的版本号**：工具只有版本 `1`，模型却请求版本 `2`

显式检查并抛出明确的错误，比让调用在后续步骤中神秘失败要好得多。

### 2.4.4 第二层防线：双闸权限机制

```java
if (!policy.allows(invocation.toolName())) {
    throw new ToolControlPlaneException(
        ToolControlReason.POLICY_DENIED,
        "POLICY_DENIED: " + invocation.toolName());
}
```

**为什么需要检查两次权限？**

第一次检查（在 `manifestFor()` 中）：
```java
public List<ToolRegistry.Registration> manifestFor() {
    return registry.all().stream()
        .filter(r -> policy.allows(r.definition().name()))
        .toList();
}
```
这会过滤掉不允许的工具，减少发送给模型的工具列表大小。

第二次检查（在 `invoke()` 中）：
这是真正的执行检查，即使第一次过滤被绕过（比如模型凭空编造工具名），第二次检查也会拒绝。

**这叫做"纵深防御"（Defense in Depth）**：
- 第一道防线减少攻击面和选择错误
- 第二道防线是不可绕过的执行约束

### 2.4.5 第三层防线：参数格式校验

```java
ToolArgsValidator.validate(registration.definition(), invocation.args());
```

让我们看看参数校验器做了什么（`ToolArgsValidator.java`）：

```java
public static void validate(ToolDefinition definition, Map<String, Object> args) {
    Map<String, Object> properties = 
        (Map<String, Object>) definition.schema().get("properties");
    List<String> required = 
        (List<String>) definition.schema().getOrDefault("required", List.of());
    Map<String, Object> present = args == null ? Map.of() : args;
    
    // 1. 检查必填参数是否都存在
    for (String key : required) {
        if (!present.containsKey(key)) {
            throw new IllegalArgumentException(
                "INVALID_ARGS: required 参数缺失: " + key);
        }
    }
    
    // 2. 检查是否有未声明的参数
    for (Map.Entry<String, Object> e : present.entrySet()) {
        String key = e.getKey();
        Object expected = properties.get(key);
        if (expected == null) {
            throw new IllegalArgumentException(
                "INVALID_ARGS: 未声明参数（additionalProperties=false 硬拒绝）: " + key);
        }
        
        // 3. 检查参数类型是否匹配
        Map<String, Object> constraint = (Map<String, Object>) expected;
        String expectedType = constraint.get("type").toString();
        if (!typeMatches(expectedType, e.getValue())) {
            throw new IllegalArgumentException(
                "INVALID_ARGS: 参数 " + key + " 类型应为 " + expectedType);
        }
        
        // 4. 检查字符串长度和格式
        if (e.getValue() instanceof String s) {
            Object maxLength = constraint.get("maxLength");
            if (maxLength instanceof Number cap && s.length() > cap.longValue()) {
                throw new IllegalArgumentException(
                    "INVALID_ARGS: 参数 " + key + " 超过 maxLength=" + cap);
            }
            Object pattern = constraint.get("pattern");
            if (pattern instanceof String regex && !s.matches(regex)) {
                throw new IllegalArgumentException(
                    "INVALID_ARGS: 参数 " + key + " 不匹配 pattern=" + regex);
            }
        }
    }
}
```

**为什么要拒绝未声明的参数？**

假设模型发现添加一个 `--admin-mode` 参数可以绕过某些限制：
```json
{
  "service": "checkout",
  "since": "2026-09-12T10:00:00Z",
  "until": "2026-09-12T10:05:00Z",
  "--admin-mode": true  // 模型尝试添加隐藏开关
}
```

如果我们只检查已声明的参数，这个隐藏开关会被静默忽略。但更糟糕的是，如果某个执行器错误地实现了这个参数，模型就获得了额外的能力。

**拒绝未声明参数的价值：**
- **防止隐藏开关**：模型不能通过添加参数来扩大权限
- **防止拼写错误**：`servise` 而不是 `service`，会被立即发现
- **明确契约边界**：工具只接受声明的参数，不多不少

### 2.4.6 第四层防线：动作摘要

```java
String digest = ActionDigest.of(new ActionEnvelope("rca", 
    spec.toolName(), spec.toolVersion(), schemaHash, 
    args, ctx.timeRange(), ctx.investigationInputDigest()));
```

**动作摘要（Action Digest）是什么？**

它是一次工具调用的"身份指纹"，由以下信息计算得出：
- 工具名称和版本
- Schema hash（参数格式的指纹）
- 实际参数值（经过规范化）
- 时间范围
- 调查输入摘要（告警的身份）

**为什么需要动作摘要？**

1. **检测重复调用**：相同的摘要意味着相同的查询，可以复用结果
2. **调用记录的唯一标识**：比直接存储参数 JSON 更节省空间
3. **版本绑定**：工具版本或格式变了，摘要也会变，避免混淆新旧结果

**参数规范化的重要性：**

```json
// 这两个在语义上相同，但 JSON 字符串不同
{"service": "checkout", "since": "2026-09-12T10:00:00Z"}
{"since": "2026-09-12T10:00:00Z", "service": "checkout"}
```

规范化会：
- 按字段名排序
- 统一空白符处理
- 递归处理嵌套对象

规范化后，语义相同的参数会得到相同的摘要。

### 2.4.7 第五层防线：风险等级与意图记录

```java
ToolRisk risk = registration.definition().risk();
if (!risk.executable()) {
    recordIntent(invocation, digest, risk);
    return new ToolInvocationResult(Kind.VALIDATE_ONLY, digest, null);
}
```

**工具风险等级分类：**
- **R0（只读）**：查询指标、日志、变更记录等，不修改任何系统状态
- **R1/R2/R3（不同级别的写操作）**：发布变更、回滚、重启服务等

**为什么高风险工具只记录意图，不实际执行？**

这是一个关键的安全设计。假设我们有一个 `deployment.rollback` 工具，风险等级是 R2：

如果模型在调查时认为需要回滚：
```json
{
  "tool": "deployment.rollback",
  "args": {"service": "checkout", "to_version": "v1.2.3"}
}
```

我们不能让这个操作自动执行，因为：
1. **误判的代价太高**：错误的回滚可能导致更大的故障
2. **需要人工确认**：值班人员需要审查回滚决策是否合理
3. **合规要求**：某些操作必须有人工审批记录

**意图记录的价值：**
```java
private void recordIntent(ToolInvocation invocation, String digest, ToolRisk risk) {
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("kind", "TOOL_INTENT_VALIDATED");
    payload.put("tool", invocation.toolName());
    payload.put("version", invocation.toolVersion());
    payload.put("risk", risk.name());
    payload.put("action_digest", digest);
    events.appendIndependent(invocation.runId(), new RcaEventAppender.EventDraft(
        UUID.randomUUID(), "TOOL_INTENT_VALIDATED",
        InternalCanonicalJsonV1.canonicalize(payload)));
}
```

这会记录：
- 模型想执行什么操作
- 参数是什么（通过 action_digest 可追溯）
- 什么时候提出的这个意图

值班人员可以：
- 审查模型的建议是否合理
- 在人工审批后手动执行
- 作为后续审计的依据

### 2.4.8 第六层防线：独立线程池与硬超时

```java
private byte[] executeWithDeadline(ToolRegistry.Registration registration,
        ToolInvocation invocation) {
    // 计算截止时间：取工具超时和外部期限的较小值
    Instant hard = clock.instant()
        .plusMillis(registration.definition().timeoutMillis());
    Instant external = invocation.externalDeadline();
    if (external != null && external.isBefore(hard)) {
        if (!clock.instant().isBefore(external)) {
            throw new ExecutionControl.StoppedException(
                STOP_RUN_DEADLINE_EXCEEDED,
                "外部硬期限已过，工具等待不再开始: " + external);
        }
        hard = external;
    }
    
    long deadline = hard.toEpochMilli();
    ToolExecutor.ToolExecution execution = new ToolExecutor.ToolExecution(
        invocation.args(), deadline, registration.definition().resultLimitBytes());
    
    // 提交到线程池
    Future<byte[]> future;
    try {
        future = callPool.submit(() -> registration.executor().execute(execution));
    } catch (RejectedExecutionException e) {
        throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
            "工具调用通道拥塞（背压拒绝，可稍后重试）");
    }
    
    // 注册取消通知（可选）
    if (cancels != null) {
        cancels.register(invocation.runId(), future);
    }
    
    try {
        // 等待结果，最多等到 deadline
        return future.get(deadline - clock.millis(), TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
        future.cancel(true);  // 尝试取消执行
        throw new ToolModelVisibleException(ToolModelVisibleReason.TIMEOUT_RETRYABLE,
            "工具调用超时（可重试）");
    } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        future.cancel(true);
        throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
            "工具调用被中断（临时远端故障）");
    } catch (CancellationException e) {
        if (cancels != null && cancels.wasStopCancelled(invocation.runId())) {
            throw new ExecutionControl.StoppedException(
                STOP_RUN_CANCELLED,
                "run " + invocation.runId() + " 已取消，中断在途工具等待");
        }
        throw e;
    } catch (ExecutionException e) {
        future.cancel(true);
        throw mapExecutorFailure(e.getCause() == null ? e : e.getCause());
    } finally {
        if (cancels != null) {
            cancels.unregister(invocation.runId(), future);
        }
    }
}
```

**这段代码包含了多个关键的设计决策，让我们逐一分析。**

#### 为什么需要独立的线程池？

如果工具执行在主调用线程中直接运行：
```java
// 错误的做法
byte[] result = registration.executor().execute(execution);
```

会导致：
1. **无法限制并发数量**：所有工具调用同时执行，可能把数据源打垮
2. **无法真正取消**：超时后无法强制停止执行
3. **一个慢查询拖垮整个调查**：某个工具执行很慢，会阻塞主流程

**独立线程池的价值：**
```java
ExecutorService callPool;  // 通常是 ThreadPoolExecutor
```

可以配置：
- **核心线程数**：默认 2 个，控制同时执行的工具数量
- **队列容量**：默认 16，超出容量直接拒绝（背压）
- **拒绝策略**：队列满时抛出 `RejectedExecutionException`

**背压（Backpressure）的重要性：**

假设有 100 个调查同时请求工具执行，如果全部排队：
- 队列会越积越长
- 等待时间越来越久
- 可能等到执行时，调查已经超时或被取消了

明确拒绝的好处：
```java
throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
    "工具调用通道拥塞（背压拒绝，可稍后重试）");
```
- **快速失败**：立即告知无法执行，不浪费等待时间
- **保护系统**：防止队列无限增长导致内存溢出
- **可重试**：调查可以稍后再试，或选择其他工具

#### 为什么需要计算 deadline？

```java
Instant hard = clock.instant().plusMillis(registration.definition().timeoutMillis());
Instant external = invocation.externalDeadline();
if (external != null && external.isBefore(hard)) {
    hard = external;
}
```

这里取的是**两个时间限制的较小值**：
1. **工具自身的超时**：比如 4 秒
2. **外部期限**：比如整个调查只剩 2 秒

**为什么要取最小值？**

假设调查只剩 2 秒就要超时，但工具默认超时是 4 秒：
- 如果用工具超时：执行 4 秒后调查早就超时了，白白浪费资源
- 如果用外部期限：2 秒后准时停止，资源利用更高效

**外部期限已过的快速失败：**
```java
if (!clock.instant().isBefore(external)) {
    throw new ExecutionControl.StoppedException(
        STOP_RUN_DEADLINE_EXCEEDED,
        "外部硬期限已过，工具等待不再开始");
}
```

如果在提交执行前就发现已经过期，直接拒绝，不浪费线程池资源。

#### 超时后如何处理？

```java
return future.get(deadline - clock.millis(), TimeUnit.MILLISECONDS);
```

这是一个**阻塞等待，但有时间限制**：
- 如果在 deadline 前得到结果：返回结果
- 如果 deadline 到了还没结果：抛出 `TimeoutException`

**超时处理的三个动作：**
```java
catch (TimeoutException e) {
    future.cancel(true);  // ① 尝试取消任务
    throw new ToolModelVisibleException(  // ② 抛出可重试错误
        ToolModelVisibleReason.TIMEOUT_RETRYABLE,
        "工具调用超时（可重试）");
}
```

**注意：`future.cancel(true)` 不能保证任务真的停止。**

`cancel(true)` 只是向执行线程发送中断信号，实际能否停止取决于：
- 执行器代码是否检查中断标志
- 底层 HTTP 客户端是否支持取消
- 数据库查询是否可以中断

**迟到结果的处理：**

即使任务被取消，它可能还在继续执行，并在稍后返回结果。但网关不会使用这个迟到的结果：
```java
future.get(deadline - clock.millis(), TimeUnit.MILLISECONDS);
// 超时后这行代码不会执行，直接跳到 catch
```

迟到的结果会被丢弃，不会影响后续调查。

#### 为什么区分可重试和不可重试的错误？

**可重试的错误（ToolModelVisibleException）：**
```java
catch (TimeoutException e) {
    throw new ToolModelVisibleException(ToolModelVisibleReason.TIMEOUT_RETRYABLE, ...);
}
catch (InterruptedException e) {
    throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE, ...);
}
```

这些错误会返回给模型，模型可以：
- 重新执行相同的查询
- 缩小查询窗口（比如从 5 分钟改成 1 分钟）
- 选择另一个工具

**不可重试的错误（ToolControlPlaneException）：**
```java
private RuntimeException mapExecutorFailure(Throwable cause) {
    if (cause instanceof ToolModelVisibleException visible) {
        return visible;
    }
    if (cause instanceof ToolControlPlaneException control) {
        return control;  // 控制面错误，终止调查
    }
    return new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
        "工具远端暂不可用（临时故障，可重试）");
}
```

控制面错误包括：
- **认证失败**：凭证无效，重试也不会成功
- **配置错误**：工具配置有问题，需要人工修复
- **权限不足**：没有访问某个资源的权限

这些错误会**终止调查**，因为模型无法通过调整参数来解决。

**默认映射为可重试错误：**

对于未知的异常，默认映射为"远端暂不可用"，这是一个保守的选择：
- **保护隐私**：不泄露底层错误细节
- **允许恢复**：可能只是临时网络抖动
- **避免误判**：不确定的错误不应该终止整个调查

### 2.4.9 第七层防线：结果大小限制

```java
byte[] body = executeWithDeadline(registration, invocation);
if (body.length > registration.definition().resultLimitBytes()) {
    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE,
        "RESULT_OVERSIZE: " + body.length + " > " 
        + registration.definition().resultLimitBytes());
}
```

**为什么执行器已经限制了大小，网关还要再检查一次？**

执行器内部的限制（比如 `PrometheusQueryExecutor` 的 `readBounded`）：
```java
private static byte[] readBounded(InputStream body, long limitBytes) throws IOException {
    long cap = Math.max(1, limitBytes) + 1;  // 读取 limit+1 字节
    // ...读取过程中检查大小...
}
```

网关的检查：
```java
if (body.length > registration.definition().resultLimitBytes()) {
    throw new ToolControlPlaneException(...);
}
```

**为什么需要两层检查？**

1. **执行器检查的是流读取**：边读边检查，防止全部读入内存后才发现超限
2. **网关检查的是最终结果**：防止执行器实现有误，或者某个执行器没有实现大小检查

**双重保护的价值：**
- 执行器检查：保护内存，防止超大响应撑爆进程
- 网关检查：最后的兜底，保证契约一致性

---

## 第三部分：校验的六个层次——为什么不是四层或八层

### 3.1 校验层次的设计哲学

当面试官问"为什么是六层校验"时，重要的不是数字"六"，而是**每一层解决什么问题，为什么不能合并或省略**。

让我们用一个具体的例子来说明：假设模型想查询日志聚合。

#### 第一层：工具定义自身是否合法（启动时）

```java
public ToolDefinition {
    requireName(name);
    requireVersion(version);
    if (schema == null) {
        throw new IllegalArgumentException("schema 不得为 null");
    }
    if (timeoutMillis <= 0) {
        throw new IllegalArgumentException("timeout 必须为正");
    }
    if (resultLimitBytes <= 0) {
        throw new IllegalArgumentException("resultLimit 必须为正");
    }
    schema = normalizeSchema(schema);
    risk = risk == null ? ToolRisk.R3 : risk;
}
```

**这一层回答：工具本身是不是坏的？**

如果工具定义就有问题：
- Schema 是空的：模型不知道该传什么参数
- 超时是负数：无法设置合理的执行期限
- 结果上限是 0：任何返回都会被拒绝

**这些问题应该在启动时暴露，而不是等到运行时。**

如果允许错误的工具定义进入注册表：
- 模型会选择它，但执行总是失败
- 错误消息可能很奇怪，难以定位问题
- 浪费调查资源和时间

**fail-fast 原则：**越早发现问题，修复成本越低。启动失败只影响这一次部署，运行时失败可能影响所有调查。

#### 第二层：这个角色能否选择这个工具（执行前）

```java
// 第一次检查：构造发给模型的工具清单时
public List<ToolRegistry.Registration> manifestFor() {
    return registry.all().stream()
        .filter(r -> policy.allows(r.definition().name()))
        .toList();
}

// 第二次检查：真正执行时
if (!policy.allows(invocation.toolName())) {
    throw new ToolControlPlaneException(ToolControlReason.POLICY_DENIED, ...);
}
```

**这一层回答：这个角色有权使用这个工具吗？**

假设有一个 `deployment.rollback` 工具，只有高级运维角色才能使用：
- 普通的告警调查角色：不应该在工具列表中看到它
- 即使模型编造了这个工具名：第二次检查会拒绝

**双闸机制的价值：**
- 第一道闸：减少模型选择范围，降低选错概率
- 第二道闸：执行时的硬约束，无法绕过

#### 第三层：参数的外形对不对（执行前）

```java
ToolArgsValidator.validate(registration.definition(), invocation.args());
```

这一层检查：
- 必填参数是否都有：`since`、`until`、`service`
- 是否有未声明的参数：`--admin-mode`
- 参数类型是否匹配：`since` 应该是字符串，不能是数字
- 字符串长度是否超限：`service` 不能超过 128 字符
- 字符串格式是否匹配：如果声明了 `pattern`，必须符合正则表达式

**为什么这一层不检查业务语义？**

因为格式检查是通用的，业务语义是特定的：
- 格式检查：`since` 是一个字符串，长度合理
- 业务语义：`since` 是否是合法的时间格式？时间窗口是否超过 900 秒？

把它们分开的好处：
- **通用检查可以复用**：所有工具都用同一套格式检查器
- **业务检查更精确**：每个工具根据自己的规则检查

#### 第四层：参数的业务语义对不对（执行前）

```java
// 在执行器内部
Query parseArgs(Map<String, Object> args, Set<String> allowlist) {
    Instant since = parseInstant(args.get("since"), "since");
    Instant until = parseInstant(args.get("until"), "until");
    long windowMillis = until.toEpochMilli() - since.toEpochMilli();
    
    // 检查时间窗口不超过 900 秒
    if (windowMillis < 0 || windowMillis > MAX_WINDOW_SECONDS * 1_000) {
        throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
            "INVALID_ARGS: 查询窗幅超限（0 ≤ until-since ≤ 900s）");
    }
    
    // 检查服务是否在白名单中
    String service = ...;
    if (!allowlist.contains(service)) {
        throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
            "INVALID_ARGS: service 越出 allowlist");
    }
    
    // 检查 severity 是否是允许的值
    String severity = ...;
    if (!SEVERITIES.contains(severity)) {
        throw new ToolControlPlaneException(ToolControlReason.INVALID_ARGS,
            "INVALID_ARGS: severity 只允许 ALL/ERROR/WARN/INFO");
    }
    
    return new Query(since, until, service, severity);
}
```

**这一层回答：参数在业务上合理吗？**

即使参数格式都对，业务上可能不合理：
- 时间窗口太大：900 秒 vs 3600 秒（日志查询 vs 指标查询）
- 服务不在授权范围：想查 `admin-service`，但只被授权查 `checkout`
- 枚举值不在允许集合：`severity=DEBUG`，但只允许 `ALL/ERROR/WARN/INFO`

**为什么这些检查在执行器内部，而不是统一入口？**

因为每个工具的业务规则不同：
- 日志查询：窗口最多 900 秒，必须在服务白名单中
- 指标查询：窗口最多 3600 秒，步长最多 60 秒
- 源码查询：必须有服务到仓库的映射，行号必须大于 0

如果都放在统一入口：
- 统一入口会变成一个认识所有业务规则的大杂烩
- 新增工具时需要修改统一入口代码
- 业务规则变更时影响范围太大

**分层的边界：**
- 统一入口：保证所有工具的通用契约（超时、大小、权限）
- 执行器：负责自己的业务规则（窗口、服务、枚举）

#### 第五层：现在是否还允许执行（执行前）

```java
// 检查外部期限是否已过
if (external != null && !clock.instant().isBefore(external)) {
    throw new ExecutionControl.StoppedException(
        STOP_RUN_DEADLINE_EXCEEDED,
        "外部硬期限已过，工具等待不再开始");
}

// 检查预算是否已用完（在 SingleToolEvidenceAgent 中）
budgetGate.call(...);

// 检查是否已经达到重复停止阈值（在 SingleToolEvidenceAgent 中）
doomLoopGuard.shouldAllow(...);
```

**这一层回答：即使参数都对，现在还能执行吗？**

可能的拒绝原因：
1. **调查已经超时**：整个调查只有 5 分钟，现在已经过去 6 分钟了
2. **预算已耗尽**：最多允许 10 次工具调用，已经用了 10 次
3. **重复查询太多次**：同样的参数查了 5 次都没结果，不应该再查第 6 次
4. **调查已被取消**：用户手动取消了这次调查

**这些检查为什么不能更早做？**

因为状态是动态变化的：
- 开始执行时预算还够，排队等待时可能被其他任务用完
- 开始时调查还有效，执行中可能被用户取消
- 上一次查询成功，这一次可能触发重复停止阈值

**检查的时机：**
- 提交执行前：快速检查，拒绝明显不能执行的请求
- 执行中：定期检查停止信号，及时响应取消
- 结算时：根据实际使用更新预算

#### 第六层：返回的数据能否进入证据（执行后）

```java
// 检查 HTTP 状态码
if (response.statusCode() == 429) {
    throw new ToolModelVisibleException(ToolModelVisibleReason.RATE_LIMITED, ...);
}
if (response.statusCode() != 200) {
    throw new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE, ...);
}

// 解析响应，检查格式
JsonNode root = JSON.readTree(lokiResponse);
JsonNode vector = root.path("data").path("result");

// 检查是否有数据
if (!vector.isArray() || vector.isEmpty()) {
    if (!filtered) {  // 全量查询
        throw new ToolModelVisibleException(ToolModelVisibleReason.NO_DATA,
            "NO_DATA: 窗内无日志聚合结果");
    }
    // 过滤级别查询：零计数是合法结果
    zeroByFilter = true;
}

// 检查结果大小
if (out.size() > resultLimitBytes) {
    throw new ToolControlPlaneException(ToolControlReason.RESULT_OVERSIZE, ...);
}
```

**这一层回答：拿到的数据能用吗？**

可能的问题：
1. **HTTP 成功，但业务失败**：200 OK，但 `status: "error"`
2. **返回格式不对**：期望 JSON 数组，实际是字符串
3. **数据为空**：可能是真的没数据，也可能是查询失败
4. **结果太大**：超过预设的大小限制

**为什么不能把 HTTP 200 直接当成功？**

```json
// HTTP 200，但业务失败
{
  "status": "error",
  "error": "invalid query syntax"
}
```

如果不检查业务状态：
- 模型会收到错误消息，但系统认为调用成功
- 可能把错误消息当成证据，做出错误判断
- 账本记录不准确，影响后续分析

**空结果的语义区分：**

日志聚合的两种空结果：
1. **全量查询为空**：窗口内完全没有日志，这是 `NO_DATA`
2. **过滤查询为空**：有日志，但没有 ERROR 级别的，计数返回 0

它们的处理不同：
- `NO_DATA`：调用成功，但没有证据（可能数据源问题）
- 零计数：调用成功，有证据（证明该级别确实为 0）

但零计数必须附带说明：
```json
{
  "count": 0,
  "coverage": {
    "note": "本计数只反映查询窗内已采集且 detected_level 标签匹配的日志；标签缺失/未采集/无流量都计 0，零计数不能据此证明无故障"
  }
}
```

**为什么需要这个说明？**

因为零计数有多种可能：
- 确实没有错误：健康状态
- 没有采集到日志：采集服务故障
- 标签缺失：日志格式问题
- 没有流量：服务可能已经挂了

模型需要知道"零"的含义是什么，不能简单地认为"零错误=没问题"。

### 3.2 为什么是六层，而不是更多或更少？

**如果合并成更少的层会怎样？**

假设把格式检查和业务检查合并：
```java
// 不好的设计
void validateAll(ToolDefinition def, Map<String, Object> args) {
    // 格式检查
    if (!args.containsKey("since")) throw new IllegalArgumentException(...);
    // 业务检查
    if (parseTime(args.get("since")).isBefore(now().minus(7, DAYS))) 
        throw new IllegalArgumentException(...);
}
```

问题：
- 新增工具时，需要修改这个通用验证器
- 业务规则分散在多个地方，难以维护
- 测试困难：需要准备完整的业务上下文

**如果拆分成更多的层会怎样？**

假设把服务白名单、时间窗口、枚举值分成三层：
```java
void checkService(String service);
void checkTimeWindow(Instant since, Instant until);
void checkSeverity(String severity);
```

问题：
- 增加复杂度，但收益不明显
- 每一层都需要单独的错误处理和账本记录
- 调用链变长，性能和可读性都下降

**六层的平衡点：**

1. **启动时检查**：一次性，保证定义合法
2. **权限检查**：双闸机制，安全边界
3. **格式检查**：通用规则，快速拒绝
4. **语义检查**：业务规则，各自负责
5. **状态检查**：动态判断，及时响应
6. **结果检查**：最后兜底，保证证据质量

每一层有明确的职责，层与层之间有清晰的边界，这就是六层的由来。

### 3.3 失败了怎么办：错误的两大家族

在网关的实现中，有一个关键的错误分类：

```java
private RuntimeException mapExecutorFailure(Throwable cause) {
    // 模型可见族：可以让模型调整策略重试
    if (cause instanceof ToolModelVisibleException visible) {
        return visible;
    }
    // 控制面终止族：必须停止，模型无法解决
    if (cause instanceof ToolControlPlaneException control) {
        return control;
    }
    // 默认映射：保守地视为可重试
    return new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
        "工具远端暂不可用（临时故障，可重试）");
}
```

**为什么需要区分两大家族？**

#### 模型可见族（ToolModelVisibleException）

这类错误应该告诉模型，让它调整调查策略：

```java
// 查不到数据
NO_DATA("NO_DATA: 查询窗内无数据")
// 说明：不是错误，而是这个窗口确实没数据

// 超时
TIMEOUT_RETRYABLE("查询超时，可缩小窗口或稍后重试")
// 说明：可能窗口太大，或数据源暂时慢，可以重试

// 限流
RATE_LIMITED("数据源限流，可稍后重试")
// 说明：请求太频繁，稍等一会儿再试

// 远端不可用
REMOTE_UNAVAILABLE("数据源暂不可用，可稍后重试")
// 说明：网络故障或服务故障，是临时的

// 特定源不可用
SOURCE_UNAVAILABLE("特定数据源不可用")
// 说明：比如 Loki 挂了，但 Prometheus 可能还能用
```

模型收到这些错误后可以：
- **重新执行相同查询**：可能只是临时故障
- **调整参数**：缩小时间窗口，降低数据量
- **选择其他工具**：查不到日志，改查指标
- **记录为无证据**：确实没有数据，但不代表调查失败

#### 控制面终止族（ToolControlPlaneException）

这类错误必须停止调查，因为模型无法解决：

```java
// 工具不存在
UNKNOWN_TOOL("工具未注册")
// 说明：模型幻觉了一个工具，或版本号错误

// 权限拒绝
POLICY_DENIED("角色无权使用此工具")
// 说明：权限问题，模型多试几次也不会有权限

// 参数错误
INVALID_ARGS("参数不合法")
// 说明：参数格式或业务规则错误，但这个可以让模型改正

// 认证失败
AUTH_FAILED("数据源凭证无效")
// 说明：配置问题，需要人工修复

// 配置错误
CONFIGURATION_ERROR("工具配置缺失或错误")
// 说明：部署问题，需要运维介入

// 查询失败
QUERY_FAILED("数据源拒绝良构查询")
// 说明：查询语法可能有问题，或数据源内部错误

// 结果超限
RESULT_OVERSIZE("返回结果超过大小限制")
// 说明：查询范围太大，需要调整，但已经执行了
```

**注意：参数错误（INVALID_ARGS）是个特例。**

虽然它属于控制面家族，但主调查循环会专门捕获并处理：

```java
try {
    result = gateway.invoke(invocation);
} catch (ToolControlPlaneException e) {
    if (e.reason() == ToolControlReason.INVALID_ARGS) {
        // 告诉模型哪里错了，允许它改正
        return new StepResult(TOOL_ARGS_INVALID, e.getMessage());
    }
    // 其他控制面错误：终止调查
    throw e;
}
```

这是因为：
- 参数填错是模型的问题，但模型可以学习和改正
- 其他控制面错误是系统问题，模型改不了

#### 默认映射的保守策略

```java
return new ToolModelVisibleException(ToolModelVisibleReason.REMOTE_UNAVAILABLE,
    "工具远端暂不可用（临时故障，可重试）");
```

**为什么未知错误默认映射为可重试？**

1. **保护隐私**：不泄露底层错误细节（可能包含敏感信息）
2. **允许恢复**：可能只是临时故障，给重试机会
3. **避免误判**：不确定的错误不应该立即终止调查

**固定文案的价值：**

所有远端不可用的错误，模型看到的都是："工具远端暂不可用（临时故障，可重试）"

不管底层是：
- `Connection refused`
- `Socket timeout`
- `NullPointerException`
- `OutOfMemoryError`

这样做的好处：
- **安全**：不泄露实现细节
- **一致**：相同类型的错误，处理方式一致
- **可测试**：模型的行为可预测

---

## 第四部分：预算与账本——如何确保资源可控且结果可追溯

### 4.1 为什么需要预算控制？

想象一个场景：模型在调查一个告警时，不断重复查询同样的日志，因为每次都返回"无数据"，它认为"也许下一次就能查到了"。如果没有限制：
- 第 1 次查询：无数据
- 第 2 次查询：无数据
- ...
- 第 100 次查询：无数据

这会导致：
1. **资源浪费**：日志服务承受大量重复查询
2. **时间浪费**：调查一直在做无意义的重复
3. **费用失控**：如果数据源按查询次数计费，成本爆炸

**预算控制的核心思想：**
- 每次调查有固定的资源额度（比如 10 次工具调用）
- 每次调用前先预留额度
- 调用完成后根据实际使用结算
- 额度用完就不能继续调用

### 4.2 预算的三段式流程：预留、执行、结算

让我们看看 `RunBudgetGate` 的实现：

```java
public <T> T call(Map<BudgetKind, Long> estimates, ReservationKey base,
        Supplier<T> remote, Function<T, Map<BudgetKind, Usage>> usageExtractor,
        Predicate<RuntimeException> releaseOn) {
    
    // ① 全维准入：预留额度
    for (BudgetKind kind : kinds) {
        BudgetProbe probe = ledger.reserve(keys.get(kind), estimates.get(kind));
        if (!probe.allowed()) {
            releaseAdmitted(kinds, kind, keys);  // 撤销已预留的
            throw BudgetExhaustedException.ofKind(kind, 
                probe.consumed(), probe.remaining(), estimates.get(kind));
        }
    }
    
    // ② 远程段：执行查询（这期间不持有数据库锁）
    T result;
    try {
        result = remote.get();
    } catch (RuntimeException e) {
        // 确证未发出的错误：退款
        if (releaseOn.test(e)) {
            releaseAdmitted(kinds, null, keys);
        } else {
            // 不确定是否发出：保守占用
            kinds.forEach(kind -> ledger.provisional(keys.get(kind)));
        }
        throw e;
    }
    
    // ③ 按维实扣：根据实际使用量结算
    Map<BudgetKind, Usage> usage = usageExtractor.apply(result);
    for (BudgetKind kind : kinds) {
        Usage perDim = usage.get(kind);
        if (perDim == null || perDim.units() == null) {
            ledger.markUnmatched(keys.get(kind));  // usage 缺失，标记为待对账
        } else {
            ledger.commit(keys.get(kind), perDim.units());
        }
    }
    return result;
}
```

#### 第一段：预留额度

**为什么要预留？**

如果不预留，直接执行：
```java
// 错误的做法
T result = remote.get();  // 执行了
if (ledger.remaining() < 1) {  // 再检查
    throw new BudgetExhaustedException(...);  // 但已经晚了
}
```

问题：
- 执行完才发现没有额度，资源已经浪费了
- 多个调用并发执行，都认为有额度，实际超限了

**预留的价值：**
```java
BudgetProbe probe = ledger.reserve(key, estimateUnits);
if (!probe.allowed()) {
    // 立即拒绝，不执行查询
    throw BudgetExhaustedException(...);
}
```

预留是一个**数据库操作，带条件更新**：
```sql
UPDATE run_budget 
SET consumed = consumed + ?  -- 增加预占
WHERE run_id = ? AND kind = ? 
  AND consumed + ? <= limit   -- 条件：加上新的不超限
```

这样做的好处：
- **原子性**：检查和占用在一个操作中完成
- **并发安全**：数据库保证同一行的并发更新是串行的
- **快速失败**：没有额度立即拒绝，不浪费资源

**多维一次准入的处理：**

一次工具调用可能消耗多种资源：
```java
Map<BudgetKind, Long> estimates = Map.of(
    TOOL_CALL, 1L,      // 工具调用次数
    TOKEN_INPUT, 100L,   // 输入 token 数（估算）
    TOKEN_OUTPUT, 200L   // 输出 token 数（估算）
);
```

预留时逐维检查：
```java
for (BudgetKind kind : kinds) {
    BudgetProbe probe = ledger.reserve(keys.get(kind), estimates.get(kind));
    if (!probe.allowed()) {
        // 某一维不够，撤销已预留的其他维
        releaseAdmitted(kinds, kind, keys);
        throw BudgetExhaustedException.ofKind(kind, ...);
    }
}
```

**为什么某一维不够时要撤销其他维？**

假设：
- TOOL_CALL 还剩 1 次，预留成功
- TOKEN_INPUT 已经用完，预留失败

如果不撤销 TOOL_CALL 的预留：
- 调用不会执行（因为 TOKEN_INPUT 不够）
- 但 TOOL_CALL 的额度被占用了（1 次）
- 其他调查可能因此失败（明明没用，但额度被占）

**撤销保证零残留：**
```java
releaseAdmitted(kinds, kind, keys);  // 撤销所有已预留的
```

#### 第二段：执行查询

```java
T result;
try {
    result = remote.get();  // 这里可能执行很久
} catch (RuntimeException e) {
    if (releaseOn.test(e)) {
        releaseAdmitted(kinds, null, keys);  // 全额退款
    } else {
        kinds.forEach(kind -> ledger.provisional(keys.get(kind)));  // 保守占用
    }
    throw e;
}
```

**为什么执行期间不持有数据库锁？**

预留是一个短事务（毫秒级），执行是一个长操作（秒级）：
```java
// 不好的设计
try (Transaction tx = db.beginTransaction()) {
    ledger.reserve(key, units);  // 持有锁
    result = remote.get();        // 执行查询（可能几秒）
    ledger.commit(key, actualUnits);
    tx.commit();
}  // 释放锁
```

问题：
- 一个慢查询会阻塞其他所有想预留额度的调用
- 数据库连接被长时间占用
- 可能导致死锁

**分离预留和执行：**
```java
ledger.reserve(key, units);   // 短事务，立即释放锁
result = remote.get();         // 不持有锁
ledger.commit(key, actualUnits);  // 短事务
```

这样做：
- 预留只阻塞几毫秒
- 执行期间其他调用可以预留额度
- 数据库压力小

**失败时的退款逻辑：**

关键问题：**如何判断查询是否真的发出了？**

```java
catch (RuntimeException e) {
    if (releaseOn.test(e)) {
        releaseAdmitted(kinds, null, keys);  // 退款
    } else {
        kinds.forEach(kind -> ledger.provisional(keys.get(kind)));  // 不退
    }
    throw e;
}
```

**确证未发出的错误（退款）：**
- 调用记录写入失败：还没发送网络请求
- 参数校验失败：根本没到发送阶段
- 工具不存在：没有执行器可以执行

**不确定是否发出的错误（不退款）：**
- 网络超时：请求可能已经到了，只是响应慢
- 连接中断：可能发送了一半
- 执行器内部异常：可能已经查询了数据源

**为什么默认不退款？**

这是一个保守的选择：
- 如果查询确实发出了，但错误地退了款，会导致预算记录不准
- 如果查询没发出，但没有退款，只是浪费了预算，不会影响正确性

保守占用的意义：
- **不免费重发**：防止模型利用超时来无限重试
- **账面可查**：provisional 状态会被记录，可以事后对账
- **防止滥用**：即使是失败的调用，也要计入预算

#### 第三段：结算

```java
Map<BudgetKind, Usage> usage = usageExtractor.apply(result);
for (BudgetKind kind : kinds) {
    Usage perDim = usage.get(kind);
    if (perDim == null || perDim.units() == null) {
        ledger.markUnmatched(keys.get(kind));  // 缺失，待对账
    } else {
        ledger.commit(keys.get(kind), perDim.units());  // 实扣
    }
}
```

**为什么需要实扣？**

预留时只能估算，实际使用可能不同：
- 预留：估计返回 100 行日志，预留 10KB
- 实际：只返回 10 行日志，实际 1KB

如果不实扣：
- 预留 10KB，实际用 1KB，但账面记录用了 10KB
- 预算会很快用完，但实际用量很少
- 浪费额度，影响其他调查

**实扣的数据库操作：**
```sql
UPDATE run_budget 
SET consumed = consumed - ? + ?  -- 减去预留，加上实际
WHERE run_id = ? AND kind = ?
```

**usage 缺失的处理：**

有些数据源不返回使用量：
```json
{
  "status": "success",
  "data": { "result": [...] }
  // 没有 usage 字段
}
```

这时不能猜测为 0：
```java
if (perDim == null || perDim.units() == null) {
    ledger.markUnmatched(keys.get(key));  // 标记为 UNMATCHED
}
```

**为什么不猜零？**

如果猜零：
- 实际用了 100 个单位，账面记录 0
- 预算记录完全失真
- 无法追踪实际成本

标记为 UNMATCHED：
- 明确记录"不知道用了多少"
- 可以事后对账（比如从数据源的账单）
- 不影响预留额度（已经占用了）

### 4.3 调用账本：不只是记录成功与失败

调用账本（`RcaToolInvocationLedger`）记录每次工具调用的详细信息：

```java
public interface RcaToolInvocationLedger {
    record InvocationIdentity(
        UUID operationId,    // 调用的唯一标识
        UUID runId,          // 所属调查
        UUID taskId,         // 所属任务
        UUID attemptId,      // 所属尝试
        long callSeq,        // 调用序号
        String toolName,     // 工具名称
        String toolVersion,  // 工具版本
        String actionDigest  // 动作摘要
    ) {}
    
    void open(InvocationIdentity identity);  // 记录调用开始
    boolean succeed(UUID operationId);       // 标记成功
    boolean fail(UUID operationId, ToolInvocationState terminal, 
                 ToolReasonCode reasonCode);  // 标记失败
}
```

#### 为什么先记录 PENDING，再执行？

```java
// 在 SingleToolEvidenceAgent 中
UUID operationId = UUID.randomUUID();
ledger.open(new InvocationIdentity(operationId, ...));  // 先记录

try {
    byte[] body = gateway.invoke(invocation);  // 再执行
    // ... 解析结果，保存证据 ...
    ledger.succeed(operationId);  // 最后标记成功
} catch (...) {
    ledger.fail(operationId, ...);
}
```

**为什么不是执行完再记录？**

假设进程在执行期间崩溃：
```java
// 不好的设计
byte[] body = gateway.invoke(invocation);  // 执行了
// 进程在这里崩溃
ledger.record(operationId, body);  // 没来得及记录
```

重启后：
- 不知道这次调用是否执行过
- 不知道是否拿到了结果
- 可能重复执行，浪费资源

**先记录 PENDING 的价值：**
- 无论何时崩溃，都知道"曾经尝试过这次调用"
- 可以区分"没有执行"和"执行了但不知道结果"
- 为恢复提供依据

#### 四种状态的含义

```java
public enum ToolInvocationState {
    PENDING,   // 已登记，但还没有最终结果
    SUCCESS,   // 执行成功，拿到了结果
    FAILED,    // 执行失败，明确知道失败原因
    UNKNOWN    // 无法确认结果（比如进程崩溃）
}
```

**状态转换规则：**
```
PENDING → SUCCESS  （正常完成）
PENDING → FAILED   （明确失败）
PENDING → UNKNOWN  （崩溃恢复）
```

**不允许的转换：**
- SUCCESS → FAILED：已经成功了，不能改成失败
- FAILED → SUCCESS：已经失败了，不能改成成功
- UNKNOWN → SUCCESS：已经记录为未知，不能随后改成成功

**为什么需要 UNKNOWN 状态？**

```java
ledger.open(identity);         // 状态：PENDING
byte[] body = gateway.invoke(invocation);  // 正在执行
// 进程在这里崩溃
```

重启后检查账本：
- 有一条 PENDING 记录，但没有 SUCCESS 或 FAILED
- 不知道当时执行到哪里了
- 可能已经拿到结果，但没来得及保存

恢复扫描会把长时间悬挂的 PENDING 改为 UNKNOWN：
```java
default int reclaimPendingOlderThan(Instant cutoff) {
    // 把创建时间早于 cutoff 且仍为 PENDING 的记录改为 UNKNOWN
    // UPDATE rca_tool_invocation 
    // SET state = 'UNKNOWN', reason = 'TRANSPORT_UNKNOWN'
    // WHERE state = 'PENDING' AND created_at < cutoff
}
```

**UNKNOWN 的价值：**
- 明确记录"结果未知"，而不是假装成功或失败
- 为后续人工调查提供线索
- 统计时可以单独分类（成功率、失败率、未知率）

#### 防止重复记录的唯一约束

```sql
CREATE TABLE rca_tool_invocation (
    operation_id UUID PRIMARY KEY,  -- 调用的唯一标识
    run_id UUID NOT NULL,
    task_id UUID NOT NULL,
    attempt_id UUID NOT NULL,
    call_seq BIGINT NOT NULL,
    tool_name VARCHAR(64) NOT NULL,
    -- ... 其他字段 ...
    UNIQUE (run_id, task_id, attempt_id, call_seq, tool_name)
);
```

**为什么需要这个唯一约束？**

假设没有约束，同一次调用被记录两次：
```java
ledger.open(identity1);  // 插入成功
ledger.open(identity2);  // 也插入成功（但 run/task/attempt/seq/tool 都相同）
```

问题：
- 同一次逻辑调用有两条记录
- 不知道哪一条是真的
- 预算可能被重复扣除

**唯一约束的保护：**
```java
try {
    ledger.open(identity);
} catch (DataIntegrityViolationException e) {
    // 唯一约束冲突，说明这次调用已经记录过了
    throw new IllegalStateException("重复调用");
}
```

第二次尝试插入会失败，防止重复。

#### 条件更新：防止终态被覆盖

```java
boolean succeed(UUID operationId) {
    // UPDATE rca_tool_invocation 
    // SET state = 'SUCCESS', end_time = now()
    // WHERE operation_id = ? AND state = 'PENDING'
    // RETURNING (affected rows count)
}
```

**为什么需要条件 `AND state = 'PENDING'`？**

假设两个线程同时尝试更新：
```java
// 线程 A
ledger.succeed(operationId);  // 更新为 SUCCESS

// 线程 B（稍晚）
ledger.fail(operationId, FAILED, ...);  // 尝试更新为 FAILED
```

如果没有条件：
- 线程 A 更新为 SUCCESS
- 线程 B 覆盖为 FAILED
- 最终状态是 FAILED，但实际成功了

**条件更新的保护：**
```sql
WHERE operation_id = ? AND state = 'PENDING'
```

线程 B 的更新：
- 条件不满足（状态已经是 SUCCESS，不是 PENDING）
- 影响 0 行
- 返回 false，调用方知道更新失败了

**为什么返回 boolean 而不是抛异常？**

```java
boolean success = ledger.succeed(operationId);
if (!success) {
    // 可能是别的线程已经更新了，或者记录不存在
    // 不一定是错误，可能是正常的竞争
}
```

这给调用方更多灵活性：
- 如果返回 false 是预期的（比如超时取消和正常完成竞争），可以忽略
- 如果返回 false 不应该发生，可以记录日志或告警

### 4.4 证据的保存与引用

调用成功后，需要保存证据：

```java
// 在 SingleToolEvidenceAgent 中
for (JsonNode entry : series) {
    UUID evidenceId = UUID.randomUUID();
    EvidenceEnvelope envelope = new EvidenceEnvelope(
        evidenceId,
        ctx.runId(),
        spec.evidenceType(),  // 证据类型，如 "metric_series"
        spec.source(),        // 来源，如 "prometheus"
        entry.toString().getBytes(StandardCharsets.UTF_8),
        Instant.now()
    );
    evidence.save(envelope);
    evidenceIds.add(evidenceId);
}

// 保存完证据后，记录引用
ledger.markResultRef(operationId, evidenceIds.get(0));

// 最后标记调用成功
ledger.succeed(operationId);
```

**为什么要分三步？**

1. **先保存证据**：确保证据已经持久化
2. **再记录引用**：在调用记录中指向证据
3. **最后标记成功**：调用记录的状态才改为 SUCCESS

**如果顺序反了会怎样？**

假设先标记成功，再保存证据：
```java
// 不好的设计
ledger.succeed(operationId);  // 先标记成功
evidence.save(envelope);       // 再保存证据
// 进程在这里崩溃
```

重启后：
- 调用记录显示成功
- 但没有找到对应的证据
- 不知道当时拿到了什么数据

**正确顺序的价值：**
- 调用记录为 SUCCESS 时，证据一定已经保存
- 可以通过 result_ref 找到证据
- 证据和调用记录的关联是可靠的

**为什么需要 markResultRef？**

```java
default boolean markResultRef(UUID operationId, UUID evidenceId) {
    // UPDATE rca_tool_invocation 
    // SET result_ref = ?
    // WHERE operation_id = ? AND state = 'PENDING'
}
```

这是一个可选的步骤，作用是：
- 在调用记录中记录证据 ID
- 方便通过调用记录找到证据
- 支持复用（下次相同查询可以找到已有证据）

### 4.5 结果复用：避免重复查询

```java
// 在 SingleToolEvidenceAgent 中
UUID reused = ledger.findSuccessfulByRun(ctx.runId()).stream()
    .filter(r -> r.actionDigest().equals(actionDigest)
        && r.resultRef() != null)
    .map(InvocationRecovery::resultRef)
    .findFirst().orElse(null);

if (reused != null) {
    // 找到了可复用的结果
    budgetGate.call(..., () -> {
        ledger.open(new InvocationIdentity(...));
        ledger.markResultRef(operationId, reused);  // 引用已有证据
        ledger.succeed(operationId);
        return reused;
    }, ...);
    return new AgentResult(EVIDENCE_PRODUCED, List.of(reused), null);
}
```

**复用的条件：**
1. 同一个调查（runId 相同）
2. 动作摘要相同（actionDigest 相同）
3. 之前的调用成功了（state = SUCCESS）
4. 有证据引用（resultRef != null）

**为什么复用仍然占用预算？**

虽然没有发起新的网络请求，但：
- 模型调用了工具（消耗了一次工具额度）
- 账本记录了这次调用（有审计成本）
- 防止模型无限复用（绕过预算限制）

**复用的账本记录：**
```java
ledger.open(new InvocationIdentity(...));      // 新的 operation_id
ledger.markResultRef(operationId, reused);     // 引用已有证据
ledger.succeed(operationId);                   // 标记成功
```

这样：
- 有完整的调用记录（谁在什么时候复用了证据）
- call_seq 继续递增（调用序号不重复）
- 可以区分"首次查询"和"复用结果"（通过 result_ref 的时间戳）

**为什么只在同一调查内复用？**

```java
ledger.findSuccessfulByRun(ctx.runId())  // 只查当前调查
```

不跨调查复用的原因：
1. **时效性**：不同调查的时间窗口可能不同，旧证据可能已过期
2. **权限**：不同调查的权限可能不同，不能共享证据
3. **一致性**：同一调查内的证据应该是同一时刻的快照

**未来可能的扩展：**
- 带有效期的跨调查缓存
- 按用户或租户隔离的证据池
- 显式的缓存失效机制

但当前的设计保守、简单、易于理解。

## 第五部分：并发、线程池与可靠性保障

### 5.1 线程池的设计：为什么是 2 个工作线程 + 16 个排队位置？

在工具网关的配置中，有一个关键的线程池：

```java
public class ToolGateway {
    private final ExecutorService callPool;  // 工具执行线程池
    
    // 典型配置
    ThreadPoolExecutor callPool = new ThreadPoolExecutor(
        2,                      // 核心线程数
        2,                      // 最大线程数
        60L, TimeUnit.SECONDS,  // 线程空闲保活时间
        new LinkedBlockingQueue<>(16),  // 有界队列，容量 16
        new ThreadPoolExecutor.AbortPolicy()  // 队列满时拒绝
    );
}
```

#### 为什么核心线程数是 2，而不是 10 或 100？

**线程不是越多越好。** 让我们分析一下：

假设一次工具调用平均耗时 2 秒，那么：
- 2 个线程：理论上每秒完成 1 次调用
- 10 个线程：理论上每秒完成 5 次调用
- 100 个线程：理论上每秒完成 50 次调用

看起来线程越多越好？**错！**

**线程多的代价：**

1. **内存开销**：每个线程占用约 1MB 栈空间，100 个线程就是 100MB
2. **上下文切换**：线程多了，CPU 在线程之间切换的时间增加
3. **数据源压力**：100 个并发查询可能把 Prometheus 或 Loki 打垮
4. **数据库连接**：每个线程可能需要数据库连接，连接池也有上限

**选择 2 的理由：**

1. **匹配数据源能力**：如果 Prometheus 只能承受 5 QPS，开 100 个线程也没用
2. **控制爆炸半径**：即使有 bug 导致工具调用卡死，最多卡 2 个线程
3. **可观测性**：线程少，更容易定位哪个调用在执行、哪个在排队
4. **逐步扩容**：从小开始，根据实际负载调整

**实际部署时的调整依据：**

```java
// 观察指标
- 队列等待时间：如果经常超过 1 秒，可能需要增加线程
- 拒绝率：如果经常拒绝，说明容量不够
- 数据源延迟：如果增加线程后数据源变慢，说明到上限了
```

**不是根据"有多少个工具"来决定线程数。** 工具注册表可以有 100 个工具，但不意味着需要 100 个线程，因为：
- 同时执行的工具数量取决于并发调查数量
- 大部分时间可能只有几个调查在进行
- 线程池的作用是限制并发，而不是匹配工具数量

#### 为什么队列容量是 16，而不是无限？

**无限队列的问题：**

```java
// 危险的配置
new LinkedBlockingQueue<>();  // 无界队列
```

假设调查请求突然激增，或者数据源变慢：
- 请求不断进入队列
- 队列越来越长：10 → 100 → 1000 → 10000
- 内存消耗越来越大
- 等待时间越来越久
- 可能等到执行时，调查已经超时了

**有界队列的价值：**

```java
new LinkedBlockingQueue<>(16)  // 最多排队 16 个
```

队列满时的处理：
```java
try {
    future = callPool.submit(() -> executor.execute(execution));
} catch (RejectedExecutionException e) {
    throw new ToolModelVisibleException(
        ToolModelVisibleReason.REMOTE_UNAVAILABLE,
        "工具调用通道拥塞（背压拒绝，可稍后重试）");
}
```

**背压（Backpressure）机制：**
- 明确告知"现在太忙了，稍后再试"
- 快速失败，不浪费等待时间
- 保护系统，防止内存溢出
- 给调用方重试或选择其他工具的机会

**16 这个数字的来源：**
- 2 个工作线程 + 16 个排队 = 最多 18 个未完成提交
- 如果平均 2 秒完成，18 个任务大约 36 秒全部完成
- 这是一个经验起点，不是理论最优值

**实际调整时考虑：**
- 如果拒绝率高，可能需要增加队列容量或线程数
- 如果队列积压时间长，说明容量已经足够，不需要增加
- 队列容量应该是工作线程数的几倍（比如 2 的 8 倍）

### 5.2 并发与锁：在哪里需要锁，在哪里不需要？

#### 场景一：工具注册表不需要锁

```java
public final class ToolRegistry {
    private final Map<Key, Registration> registrations = new LinkedHashMap<>();
    
    public Optional<Registration> find(String name, String version) {
        return Optional.ofNullable(registrations.get(new Key(name, version)));
    }
}
```

**为什么 `find` 方法不需要加锁？**

```java
// 不需要这样
public synchronized Optional<Registration> find(...) {
    return Optional.ofNullable(registrations.get(...));
}
```

因为：
1. **只读操作**：`find` 只读取，不修改 Map
2. **启动后不变**：工具注册表在启动时构造完成，之后不再修改
3. **无竞争条件**：多个线程同时读取同一个不变的 Map 是安全的

**这叫做"不可变对象"的并发安全性：**
- 构造完成后不再修改
- 所有读取操作都是安全的
- 不需要任何同步机制

#### 场景二：预算账本需要数据库级别的锁

```java
public BudgetProbe reserve(ReservationKey key, long units) {
    // SQL 带条件更新
    // UPDATE run_budget 
    // SET consumed = consumed + ?
    // WHERE run_id = ? AND kind = ? 
    //   AND consumed + ? <= limit
}
```

**为什么不是 Java 的 synchronized？**

```java
// 错误的做法
private final Object lock = new Object();

public BudgetProbe reserve(ReservationKey key, long units) {
    synchronized (lock) {  // Java 锁
        long consumed = db.query("SELECT consumed FROM run_budget WHERE ...");
        long remaining = limit - consumed;
        if (remaining >= units) {
            db.update("UPDATE run_budget SET consumed = ? WHERE ...", consumed + units);
            return BudgetProbe.allowed();
        }
        return BudgetProbe.denied();
    }
}
```

**问题：如果有多个应用实例怎么办？**

假设有两个实例 A 和 B：
- 实例 A 的 Java 锁只保护实例 A 内部的并发
- 实例 B 的 Java 锁只保护实例 B 内部的并发
- A 和 B 可以同时执行 reserve，都看到剩余额度是 10
- 都预留 10，最终预留了 20（超限了）

**数据库带条件更新的价值：**

```sql
UPDATE run_budget 
SET consumed = consumed + ?
WHERE run_id = ? AND kind = ? 
  AND consumed + ? <= limit
```

数据库保证：
- 同一行的并发更新是串行的（行级锁）
- 条件检查和更新是原子的（一个操作）
- 跨多个应用实例有效（数据库级别）

**后到的更新：**
- 条件不满足（consumed + ? > limit）
- 影响 0 行
- 返回失败，不会超限

#### 场景三：调用账本的终态更新也需要条件更新

```java
public boolean succeed(UUID operationId) {
    // UPDATE rca_tool_invocation 
    // SET state = 'SUCCESS', end_time = now()
    // WHERE operation_id = ? AND state = 'PENDING'
}
```

**为什么需要 `AND state = 'PENDING'`？**

假设两个结果同时到达：
- 线程 A：正常完成，调用 `succeed`
- 线程 B：超时处理，调用 `fail`

没有条件：
```sql
-- 线程 A
UPDATE ... SET state = 'SUCCESS' WHERE operation_id = ?  -- 成功

-- 线程 B（稍晚）
UPDATE ... SET state = 'FAILED' WHERE operation_id = ?   -- 覆盖！
```

有条件：
```sql
-- 线程 A
UPDATE ... SET state = 'SUCCESS' WHERE operation_id = ? AND state = 'PENDING'  -- 成功，1 行

-- 线程 B（稍晚）
UPDATE ... SET state = 'FAILED' WHERE operation_id = ? AND state = 'PENDING'   -- 条件不满足，0 行
```

线程 B 的更新影响 0 行，返回 false，调用方知道有竞争。

**这叫做"比较并交换"（Compare-And-Swap，CAS）**：
- 只有旧值是预期值时才更新
- 更新失败不会覆盖别人的结果
- 无需长时间持有锁

#### 场景四：限流器需要进程内的锁

```java
public class FixedWindowRateLimiter {
    private long windowId;      // 当前窗口编号
    private int usedInWindow;   // 当前窗口已用次数
    
    public synchronized boolean tryReserve() {
        long current = System.currentTimeMillis() / windowMillis;
        if (current != windowId) {
            windowId = current;
            usedInWindow = 0;  // 新窗口，重置计数
        }
        if (usedInWindow < limit) {
            usedInWindow++;
            return true;
        }
        return false;
    }
}
```

**为什么这里需要 synchronized？**

因为：
1. **内存变量**：`windowId` 和 `usedInWindow` 是内存中的变量
2. **复合操作**：检查窗口、重置计数、增加计数是多个步骤
3. **并发写入**：多个线程可能同时调用 `tryReserve`

**如果不加锁：**
```java
// 危险的代码
public boolean tryReserve() {
    long current = System.currentTimeMillis() / windowMillis;
    if (current != windowId) {
        windowId = current;       // 线程 A 写入
        usedInWindow = 0;         // 线程 B 也写入
    }
    if (usedInWindow < limit) {  // 线程 A 读到 5
        usedInWindow++;           // 线程 B 也读到 5，都加 1
        return true;              // 实际用了 7 次，但计数只有 6
    }
    return false;
}
```

**synchronized 的价值：**
- 保证复合操作的原子性
- 同一时刻只有一个线程执行这段代码
- 简单、正确、性能足够（锁内只有内存操作，很快）

**这个锁的范围：**
- 只在当前进程有效
- 多实例部署时，每个实例有自己的限流器
- 实例 A 限流 60 次/分钟 + 实例 B 限流 60 次/分钟 = 总共可能 120 次/分钟

**如果需要跨实例限流：**
- 使用 Redis + Lua 脚本
- 或者使用专门的限流服务
- 或者在数据库中记录计数（性能较差）

### 5.3 超时与取消：为什么超时后任务可能还在执行？

#### 等待超时 vs 执行取消

```java
// 网关等待结果
try {
    return future.get(deadline - clock.millis(), TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true);  // 尝试取消
    throw new ToolModelVisibleException(..., "工具调用超时（可重试）");
}
```

**这里发生了什么？**

1. **等待超时**：网关等待到了 deadline，但还没有结果
2. **取消任务**：调用 `future.cancel(true)`
3. **抛出异常**：告诉调用方超时了

**`future.cancel(true)` 做了什么？**

```java
future.cancel(true);  // true = 允许中断正在执行的任务
```

它会：
1. 设置 Future 的状态为"已取消"
2. 如果任务还在排队，从队列中移除
3. 如果任务正在执行，向执行线程发送中断信号

**注意：发送中断信号 ≠ 立即停止任务。**

中断信号只是一个"建议"：
```java
// 在执行线程中
public byte[] execute(ToolExecution execution) throws Exception {
    HttpRequest request = ...;
    HttpResponse<InputStream> response = http.send(request, ...);
    
    // 如果这里收到中断信号会怎样？
    // 取决于 http.send 是否检查中断标志
}
```

**能否真正停止取决于：**

1. **代码是否检查中断标志**：
```java
if (Thread.interrupted()) {
    throw new InterruptedException();
}
```

2. **阻塞操作是否响应中断**：
```java
// 这些操作会响应中断
Thread.sleep(1000);      // 抛出 InterruptedException
socket.read();           // 可能抛出 ClosedByInterruptException
future.get();            // 抛出 InterruptedException

// 这些操作不会响应中断
while (true) { ... }     // 死循环，除非检查 Thread.interrupted()
synchronizedMethod();    // 等待锁，不会被中断
```

3. **底层库是否支持取消**：
```java
// Java 11+ HttpClient 支持超时
HttpRequest request = HttpRequest.newBuilder(uri)
    .timeout(Duration.ofSeconds(4))  // 客户端超时
    .GET()
    .build();
```

**所以，超时后任务可能还在执行：**
- 网关不再等待结果（超时）
- 执行线程可能还在查询数据源（未取消成功）
- 迟到的结果会被丢弃（不使用）

#### 迟到结果的处理

```java
// 网关等待结果
try {
    return future.get(deadline - clock.millis(), TimeUnit.MILLISECONDS);
} catch (TimeoutException e) {
    future.cancel(true);
    throw new ToolModelVisibleException(...);  // 这里抛出异常，返回了
}

// 如果后来任务完成了，返回了结果，会怎样？
```

**答案：迟到的结果会被丢弃。**

```java
return future.get(...);  // 超时后，这行代码不会执行
```

控制流已经跳到 catch 块，返回了异常。即使 Future 后来变成完成状态，也没有代码会读取它的结果。

**为什么这样设计？**

1. **一致性**：超时就是失败，不能后来"反悔"
2. **账本准确**：账本已经记录为超时（FAILED / TIMEOUT），不能随后改成成功
3. **预算正确**：超时的调用已经记为失败，不能重复结算

**如果想使用迟到的结果：**
- 需要在后台监听 Future 的完成事件
- 需要更新账本（从 FAILED 改为 SUCCESS）
- 需要重新结算预算
- 需要保存证据

这会增加很多复杂度，而收益不明确（超时的调用通常是真的慢，结果价值降低）。

当前的设计是：**超时就是失败，简单明确。**

### 5.4 崩溃恢复：如何处理进程在任何时刻的崩溃？

#### 可能的崩溃时刻

```java
// 工具调用的生命周期
ledger.open(identity);                    // ① 这里崩溃？
byte[] body = gateway.invoke(invocation); // ② 这里崩溃？
evidence.save(envelope);                  // ③ 这里崩溃？
ledger.markResultRef(operationId, ...);   // ④ 这里崩溃？
ledger.succeed(operationId);              // ⑤ 这里崩溃？
```

**让我们逐个分析：**

#### ① 在 `ledger.open` 之前崩溃

```java
UUID operationId = UUID.randomUUID();
// 进程崩溃
ledger.open(identity);
```

**重启后的状态：**
- 调用记录不存在（还没有插入）
- 预算没有占用（还没有预留）
- 没有证据（还没有执行）

**恢复策略：**
- 这次调用完全没有发生
- 调查从上一个检查点继续
- 没有数据不一致的问题

#### ② 在执行期间崩溃

```java
ledger.open(identity);  // 成功插入，状态 PENDING
byte[] body = gateway.invoke(invocation);  // 正在执行
// 进程崩溃
```

**重启后的状态：**
- 调用记录存在，状态是 PENDING
- 不知道查询是否发出
- 不知道是否拿到了结果

**恢复策略：**

恢复扫描会找到长时间悬挂的 PENDING 记录：
```java
int reclaimPendingOlderThan(Instant cutoff) {
    // UPDATE rca_tool_invocation 
    // SET state = 'UNKNOWN', reason = 'TRANSPORT_UNKNOWN'
    // WHERE state = 'PENDING' AND created_at < cutoff
}
```

比如每分钟扫描一次，把 5 分钟前还是 PENDING 的记录改为 UNKNOWN。

**为什么是 UNKNOWN 而不是 FAILED？**

因为不知道实际发生了什么：
- 可能查询成功了，但还没来得及记录
- 可能查询失败了
- 可能还在执行（虽然概率很小）

记录为 UNKNOWN：
- 明确表达"不确定"
- 为后续人工调查保留信息
- 统计时单独分类

#### ③ 在保存证据后崩溃

```java
ledger.open(identity);                    // 状态 PENDING
byte[] body = gateway.invoke(invocation); // 拿到结果
evidence.save(envelope);                  // 证据已保存
// 进程崩溃
ledger.succeed(operationId);
```

**重启后的状态：**
- 调用记录存在，状态是 PENDING
- 证据已经保存
- 但调用记录没有指向证据（result_ref 是空的）

**恢复策略：**

这也会被恢复扫描改为 UNKNOWN。

**为什么不能自动改为 SUCCESS？**

因为：
1. **不知道是哪个证据**：可能有多条证据，不知道哪个是这次调用的
2. **可能还有其他问题**：比如预算没有结算
3. **保守处理**：不确定就是 UNKNOWN，不假装成功

**证据会丢失吗？**

不会。证据已经保存在 `rca_evidence` 表中，只是没有被这次调用记录引用。

后续可以：
- 通过时间范围查找证据
- 通过调查 ID 查找证据
- 人工检查后关联

#### ④ 在标记引用后崩溃

```java
ledger.open(identity);                    // 状态 PENDING
byte[] body = gateway.invoke(invocation);
evidence.save(envelope);                  // 证据已保存
ledger.markResultRef(operationId, evidenceId);  // 引用已记录
// 进程崩溃
ledger.succeed(operationId);
```

**重启后的状态：**
- 调用记录存在，状态是 PENDING
- 证据已保存
- 调用记录已经指向证据（result_ref 有值）

**恢复策略：**

这也会被改为 UNKNOWN。

**为什么不能根据 result_ref 改为 SUCCESS？**

虽然有证据引用，但：
1. **状态语义**：PENDING 表示未完成，不能因为有引用就变成功
2. **预算未结算**：可能预算还没有 commit
3. **保守原则**：不确定就不改

#### ⑤ 在标记成功之前崩溃

```java
// 前面的步骤都完成了
ledger.succeed(operationId);  // 即将执行
// 进程崩溃
```

**重启后的状态：**
- 几乎所有工作都完成了
- 只差最后一次状态更新

**恢复策略：**

仍然会被改为 UNKNOWN。

但这次调用实际上是成功的，所有该做的都做了，只是状态没更新。

**这是恢复机制的保守性：**
- 宁可漏报成功（UNKNOWN），不误报成功（FAILED 改 SUCCESS）
- 数据完整性优先于统计准确性
- 人工可以后续修正状态

#### 恢复扫描的触发时机

```java
// 定期执行（比如每分钟）
scheduler.scheduleAtFixedRate(() -> {
    Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
    int reclaimed = ledger.reclaimPendingOlderThan(cutoff);
    if (reclaimed > 0) {
        log.warn("恢复扫描：{} 条悬挂记录改为 UNKNOWN", reclaimed);
    }
}, 1, 1, TimeUnit.MINUTES);
```

**为什么是 5 分钟？**

这是一个经验值：
- 太短（比如 1 分钟）：可能误杀正在执行的长查询
- 太长（比如 1 小时）：PENDING 记录会积压很久

**实际部署时考虑：**
- 工具的最大超时时间（比如 4 秒）
- 可能的排队时间（比如最多 30 秒）
- 安全裕度（再加几倍）
- 5 分钟 = 300 秒 >> 4 秒 + 30 秒

### 5.5 重复查询的停止器：避免无限循环

```java
// 在 SingleToolEvidenceAgent 中
if (!doomLoopGuard.shouldAllow(ctx.runId(), spec.toolName(), actionDigest)) {
    return new AgentResult(FAILED, List.of(), 
        "REPEATED_NO_PROGRESS: 连续无进展查询达到阈值");
}
```

**这是什么机制？**

`DoomLoopGuard` 跟踪每次查询的结果：
- 如果连续 N 次相同查询都没有进展（NO_DATA 或 FAILED）
- 就封住这个查询签名，不再执行

**为什么需要这个？**

历史上真实发生过的问题：
1. 模型查询日志，返回 NO_DATA（查不到）
2. 模型认为"可能是时间不对"，调整时间后再查
3. 还是 NO_DATA
4. 模型继续尝试不同的参数
5. 耗尽所有工具额度，调查失败

**没有进展的定义：**
- 返回 NO_DATA：没有数据
- 返回 FAILED：查询失败
- 不是成功拿到证据

**有进展的情况：**
- 返回证据：即使是空的列表，只要不是 NO_DATA
- 参数不同：actionDigest 变了，是新的尝试

**实现原理：**

```java
public class DoomLoopGuard {
    private final Map<String, Integer> noProgressCount = new ConcurrentHashMap<>();
    private final int threshold;  // 默认 5
    
    public boolean shouldAllow(UUID runId, String toolName, String actionDigest) {
        String key = runId + ":" + toolName + ":" + actionDigest;
        int count = noProgressCount.getOrDefault(key, 0);
        return count < threshold;
    }
    
    public void recordNoProgress(UUID runId, String toolName, String actionDigest) {
        String key = runId + ":" + toolName + ":" + actionDigest;
        noProgressCount.compute(key, (k, v) -> (v == null ? 1 : v + 1));
    }
    
    public void recordProgress(UUID runId, String toolName, String actionDigest) {
        String key = runId + ":" + toolName + ":" + actionDigest;
        noProgressCount.remove(key);  // 有进展，清零
    }
}
```

**为什么是进程内的 Map，而不是数据库？**

1. **性能**：每次调用都要检查，数据库会很慢
2. **生命周期**：计数器跟随调查，调查结束就清理
3. **隔离性**：不同实例的阈值独立，不会互相影响

**重启后的行为：**
- 计数器丢失（内存中）
- 封印解除
- 如果又遇到相同问题，会重新计数

这是可以接受的，因为：
- 重启通常意味着部署或修复
- 可能之前的问题已经解决
- 给新的调查一个机会

**阈值为什么是 5？**

这是一个经验值：
- 太小（比如 2）：可能误杀正常的重试
- 太大（比如 20）：浪费太多额度

实际调整依据：
- 观察日志中的重复查询模式
- 分析是真的没数据，还是模型行为问题
- 根据平均工具额度调整

## 第六部分：十八种本地工具的完整档案

在讲述具体工具之前，先明确一个重要原则：**每个工具都应该有完整的档案，而不仅仅是一个名字和描述。**

### 6.1 工具档案的十五个关键字段

对于每个工具，我们需要回答以下问题：

1. **id**：工具的唯一标识，如 `prometheus.query@1`
2. **version**：版本号，如 `1`
3. **描述**：这个工具解决什么问题，不解决什么问题
4. **schema**：输入参数的完整格式定义
5. **返回值**：返回什么数据，格式是什么
6. **错误码**：可能返回哪些错误，每个错误的含义
7. **是否幂等**：重复执行会不会产生副作用
8. **副作用**：除了返回结果，还有什么影响
9. **成本**：执行一次消耗什么资源
10. **延迟**：通常需要多长时间
11. **SLA**：服务水平承诺（如果有）
12. **权限**：谁可以使用，如何控制
13. **租户**：是否支持多租户隔离
14. **依赖**：需要哪些外部服务或配置
15. **示例**：实际的输入输出例子

### 6.2 示例工具档案：prometheus.query

让我们用一个完整的例子来说明：

#### 基本信息
- **id**: `prometheus.query`
- **version**: `1`
- **描述**: 查询 Prometheus 指标在指定时间范围内的变化，用于观察指标趋势，不用于单点查询或复杂表达式计算

#### 输入参数（schema）
```json
{
  "type": "object",
  "properties": {
    "query": {
      "type": "string",
      "maxLength": 512,
      "description": "Prometheus 查询表达式"
    },
    "start": {
      "type": "string",
      "pattern": "^[0-9]{1,10}$",
      "description": "开始时间，Unix 秒级时间戳"
    },
    "end": {
      "type": "string",
      "pattern": "^[0-9]{1,10}$",
      "description": "结束时间，Unix 秒级时间戳"
    },
    "step": {
      "type": "string",
      "pattern": "^[0-9]+[smh]$",
      "description": "采样步长，如 30s、1m、1h"
    }
  },
  "required": ["query", "start", "end", "step"],
  "additionalProperties": false
}
```

#### 业务约束
- 时间窗口：`end - start ≤ 3600` 秒
- 步长：`step ≤ 60` 秒
- 支持的步长单位：`s`（秒）、`m`（分钟）、`h`（小时）

#### 返回值
成功时返回 Prometheus 的原始 JSON 响应：
```json
{
  "status": "success",
  "data": {
    "resultType": "matrix",
    "result": [
      {
        "metric": {"__name__": "rpc_client_call_duration_seconds_count", "service": "checkout"},
        "values": [[1789207200, "123"], [1789207230, "145"], ...]
      }
    ]
  }
}
```

失败时可能返回：
```json
{
  "status": "error",
  "errorType": "bad_data",
  "error": "invalid query syntax"
}
```

#### 错误码
- **UNKNOWN_TOOL**: 工具未注册或版本不存在
- **POLICY_DENIED**: 当前角色无权使用此工具
- **INVALID_ARGS**: 参数格式错误或业务约束不满足
- **RATE_LIMITED**: Prometheus 限流（HTTP 429）
- **REMOTE_UNAVAILABLE**: Prometheus 暂不可用（网络故障或 HTTP 非 200）
- **TIMEOUT_RETRYABLE**: 查询超时
- **RESULT_OVERSIZE**: 返回结果超过大小限制（默认 64KB）

#### 是否幂等
**是**。多次执行相同的查询，返回相同的结果（假设时间序列数据不变）。

注意：如果数据源在查询间隙补写了数据，结果可能不同，但这不是幂等性问题，而是数据源的时效性问题。

#### 副作用
1. **Prometheus 资源消耗**：查询会消耗 Prometheus 的计算和 I/O 资源
2. **网络流量**：产生入站和出站流量
3. **账本记录**：在 `rca_tool_invocation` 表中记录一条调用记录
4. **预算消耗**：占用 1 次 TOOL_CALL 额度

#### 成本
- **工具额度**: 1 次 TOOL_CALL
- **网络请求**: 1 次 HTTP GET 到 Prometheus
- **数据传输**: 取决于返回的时间序列数量和采样点数量
- **Prometheus 负载**: 取决于查询复杂度和数据量

#### 延迟
- **典型延迟**: 100ms - 2000ms
- **影响因素**: 查询表达式复杂度、时间窗口大小、时间序列数量
- **最大超时**: 4000ms（默认配置）

#### SLA
当前没有独立的 SLA 承诺，继承通用工具的 4 秒超时限制。

#### 权限
- **工具级权限**: 通过 `ToolPolicy` 控制，只有允许列表中的角色可以使用
- **数据级权限**: 当前没有按服务或租户过滤，查询表达式由模型提供
- **注意**: 自由表达式可能查询到未授权的服务数据，需要配合更细粒度的权限控制

#### 租户
当前**不支持**多租户隔离。所有调查共享同一个 Prometheus 实例，查询不受租户限制。

#### 依赖
- **Prometheus 地址**: 必须配置，如 `http://prometheus:9090`
- **网络连通性**: 应用实例必须能访问 Prometheus
- **Prometheus 版本**: 兼容 Prometheus 2.x 的 `/api/v1/query_range` 接口

#### 示例

**输入**:
```json
{
  "query": "rpc_client_call_duration_seconds_count{service=\"checkout\"}",
  "start": "1789207200",
  "end": "1789207500",
  "step": "30s"
}
```

**输出**（成功）:
```json
{
  "status": "success",
  "data": {
    "resultType": "matrix",
    "result": [
      {
        "metric": {
          "__name__": "rpc_client_call_duration_seconds_count",
          "service": "checkout",
          "error_type": "UNAVAILABLE"
        },
        "values": [
          [1789207200, "0"],
          [1789207230, "15"],
          [1789207260, "28"],
          [1789207290, "42"],
          [1789207320, "45"]
        ]
      }
    ]
  }
}
```

**输出**（查询超时）:
```json
{
  "error": "TIMEOUT_RETRYABLE",
  "message": "工具调用超时（可重试）"
}
```

### 6.3 另一个示例：logs.aggregate

#### 基本信息
- **id**: `logs.aggregate`
- **version**: `1`
- **描述**: 按服务和时间窗口统计日志数量，支持按严重性级别过滤（ALL/ERROR/WARN/INFO），用于快速判断日志量异常，不返回日志原文

#### 输入参数
```json
{
  "type": "object",
  "properties": {
    "since": {
      "type": "string",
      "description": "开始时间，ISO-8601 格式"
    },
    "until": {
      "type": "string",
      "description": "结束时间，ISO-8601 格式"
    },
    "service": {
      "type": "string",
      "maxLength": 128,
      "description": "服务名称（可选，默认 control-app）"
    },
    "severity": {
      "type": "string",
      "description": "严重性级别（可选，默认 ALL）"
    }
  },
  "required": ["since", "until"],
  "additionalProperties": false
}
```

#### 业务约束
- 时间窗口：`until - since ≤ 900` 秒
- 服务白名单：`service` 必须在配置的白名单中（默认只有 `control-app`）
- 严重性封闭集：只允许 `ALL`、`ERROR`、`WARN`、`INFO`，大小写不敏感

#### 返回值
```json
{
  "status": "success",
  "data": {
    "window": {
      "since": "2026-09-12T10:00:00Z",
      "until": "2026-09-12T10:05:00Z"
    },
    "severity": "ERROR",
    "filter": "sum by (service_name) (count_over_time({service_name=\"checkout\", detected_level=~\"(?i)^error$\"}[300s]))",
    "coverage": {
      "severity_label": "detected_level",
      "collection_gaps": "unknown",
      "note": "本计数只反映查询窗内已采集且 detected_level 标签匹配的日志；标签缺失/未采集/无流量都计 0 或不计入，零计数不能据此证明无故障"
    },
    "truncated": false,
    "result": [
      {"service": "checkout", "count": 42}
    ]
  }
}
```

#### 特殊语义
- **ALL 查询为空**: 返回 `NO_DATA`（窗内确实无日志）
- **过滤级别查询为空**: 返回 `count: 0`，但必须附带 coverage 说明（可能是标签缺失、未采集或无流量）

**为什么要区分？**

假设查询 ERROR 级别的日志，返回 0：
- 可能确实没有错误（健康状态）
- 可能日志没有 `detected_level` 标签（采集配置问题）
- 可能没有采集到日志（采集服务故障）
- 可能服务没有流量（服务可能已经挂了）

模型需要知道这些可能性，不能简单认为"零错误=没问题"。

#### 错误码
- **INVALID_ARGS**: 参数错误，包括窗口超限、服务越界、未知严重性级别
- **NO_DATA**: 窗内无日志聚合结果（仅 ALL 查询）
- **SOURCE_UNAVAILABLE**: Loki 暂不可用
- **RATE_LIMITED**: Loki 限流（HTTP 429）
- **AUTH_FAILED**: Loki 凭证无效（HTTP 401/403）
- **QUERY_FAILED**: Loki 拒绝良构查询（HTTP 其他非 200 状态）
- **RESULT_OVERSIZE**: 返回结果超过大小限制

#### 是否幂等
**是**。相同的参数返回相同的统计结果（假设日志数据不变）。

#### 副作用
1. **Loki 资源消耗**: 聚合计算消耗 Loki 资源
2. **网络流量**: 产生入站和出站流量
3. **账本记录**: 记录调用
4. **预算消耗**: 1 次 TOOL_CALL

#### 成本
- **工具额度**: 1 次 TOOL_CALL
- **网络请求**: 1 次到 Loki（聚合查询）
- **数据传输**: 通常很小（只有统计数字，不是日志原文）

#### 延迟
- **典型延迟**: 200ms - 1000ms
- **影响因素**: 时间窗口大小、日志数量、Loki 负载
- **最大超时**: 4000ms

#### SLA
无独立 SLA，继承通用超时限制。

#### 权限
- **工具级权限**: 通过 `ToolPolicy` 控制
- **数据级权限**: 服务白名单在执行器内检查，越界的服务在发出请求前拒绝
- **注意**: 白名单是部署级配置，不是用户级或租户级

#### 租户
当前**不支持**多租户隔离。

#### 依赖
- **Loki 地址**: 必须配置，如 `http://loki:3100`
- **服务白名单**: 必须配置，至少包含一个服务，否则所有查询都会被拒绝
- **日志 schema**: 依赖日志流中有 `service_name` 和 `detected_level` 标签

#### 示例

**输入**（查询错误日志）:
```json
{
  "since": "2026-09-12T10:00:00Z",
  "until": "2026-09-12T10:05:00Z",
  "service": "checkout",
  "severity": "ERROR"
}
```

**输出**（有错误日志）:
```json
{
  "status": "success",
  "data": {
    "window": {...},
    "severity": "ERROR",
    "filter": "...",
    "coverage": {...},
    "truncated": false,
    "result": [
      {"service": "checkout", "count": 42}
    ]
  }
}
```

**输出**（无错误日志）:
```json
{
  "status": "success",
  "data": {
    "window": {...},
    "severity": "ERROR",
    "filter": "...",
    "coverage": {
      "note": "...零计数不能据此证明无故障"
    },
    "truncated": false,
    "result": [
      {"service": "checkout", "count": 0}
    ]
  }
}
```

### 6.4 其他工具的简要清单

由于篇幅限制，这里列出其他工具的关键信息：

#### 指标类（Prometheus）
1. **prometheus.instant** - 单点指标查询
2. **prometheus.metric_value** - 参数化指标值查询（不需要模型拼表达式）
3. **prometheus.catalog** - 按服务查询指标目录
4. **prometheus.label_values** - 查询标签取值
5. **prometheus.rules** - 查询告警规则

#### 日志类（Loki）
6. **logs.query** - 读取日志原文样本（最多 200 行）

#### 变更类（数据库）
7. **change.query** - 查询变更记录
8. **change.diff** - 查询变更记录及基线对比

#### 容器类（Docker）
9. **docker.ps** - 列出容器状态
10. **docker.inspect** - 查询容器详情（只返回环境变量键名，不返回值）

#### 告警类（数据库）
11. **alert.history** - 查询历史告警事件

#### 运维手册类（本地文件）
12. **runbook.catalog** - 查询手册目录
13. **runbook.fetch** - 读取手册正文（只能按登记编号）

#### 历史根因类（数据库）
14. **rca_history.search** - 查询历史根因报告

#### 源码类（本地文件系统）
15. **code.search** - 在绑定源码中搜索文本
16. **code.read** - 读取源码指定行范围

**所有工具的共同特点：**
- 风险等级都是 R0（只读）
- 默认超时 4000ms，结果上限 65536 字节（可配置覆盖）
- 都经过统一的网关、校验、预算、账本流程
- 都区分可重试错误和终止错误
- 都记录完整的调用轨迹

---

## 第七部分：扩展到上千上万个工具的优化思路

当前系统设计面向小规模工具集（10-20 个），如果工具数量增长到上千上万，需要系统性的优化。

### 7.1 第一个问题：工具发现

#### 当前方案的问题
现在是把所有允许的工具一次性发给模型：
```java
public List<Registration> manifestFor() {
    return registry.all().stream()
        .filter(r -> policy.allows(r.definition().name()))
        .toList();
}
```

如果有 10000 个工具：
- 工具清单占用大量 token（每个工具的名字、版本、描述、schema）
- 模型难以选择（从 10000 个工具中找到正确的那个）
- 发送工具清单本身就很慢

#### 优化方案：分层过滤 + 按需加载

**第一步：按角色、服务、任务阶段预过滤**

```java
public List<Registration> manifestFor(Context ctx) {
    return registry.all().stream()
        .filter(r -> policy.allows(ctx.role(), r.definition().name()))
        .filter(r -> isRelevantToService(r, ctx.service()))
        .filter(r -> isRelevantToPhase(r, ctx.phase()))
        .toList();
}
```

过滤依据：
- **角色**：普通调查角色只能看到只读工具，高级角色可以看到运维工具
- **服务**：调查 checkout 服务时，只展示与 checkout 相关的工具（不展示数据库管理工具）
- **阶段**：探索阶段展示聚合工具，深入阶段展示详情工具

这样可以把候选从 10000 降到 100。

**第二步：工具搜索入口**

不是一次性给所有工具，而是提供三个元工具：
```
1. search_tools(query: str) -> List[ToolSummary]
   按关键词搜索工具，返回简要信息（不包含完整 schema）

2. describe_tool(tool_name: str, version: str) -> ToolDetail
   查看工具的完整描述和参数格式

3. call_tool(tool_name: str, version: str, args: dict) -> Result
   实际调用工具
```

**工作流程：**
```
模型：我需要查询指标
1. search_tools("prometheus metric query")
   -> 返回：prometheus.query, prometheus.instant, prometheus.metric_value

模型：我需要查看 prometheus.query 的参数
2. describe_tool("prometheus.query", "1")
   -> 返回完整 schema

模型：现在调用
3. call_tool("prometheus.query", "1", {...})
   -> 执行查询
```

**优势：**
- 减少初始 token 消耗（不发送所有工具）
- 模型主动搜索，更有针对性
- 可以缓存常用工具的描述

**代价：**
- 增加交互轮次（原来 1 步变成 2-3 步）
- 搜索可能不准确（召回不到正确工具）
- 实现更复杂（需要搜索引擎）

#### 搜索实现的关键

**不能只用向量相似度：**
```python
# 不好的实现
embedding = model.encode(query)
results = vector_db.search(embedding, top_k=10)
```

问题：
- 语义相似 ≠ 功能相似（"查询日志"和"删除日志"可能向量很近）
- 无法保证权限过滤（先搜后过滤可能漏掉有权限的工具）

**更好的方案：混合搜索**
```python
# 1. 先按权限、服务、阶段过滤
candidates = filter_by_permission(all_tools, context)

# 2. 关键词匹配
keyword_matches = keyword_search(candidates, query)

# 3. 向量相似度（可选）
if len(keyword_matches) > 50:
    semantic_matches = semantic_search(keyword_matches, query)
else:
    semantic_matches = keyword_matches

# 4. 排序（按匹配度、使用频率、成功率）
return rank(semantic_matches)
```

**记录搜索结果用于改进：**
```sql
CREATE TABLE tool_search_log (
    query TEXT,
    returned_tools JSONB,
    selected_tool TEXT,
    success BOOLEAN
);
```

定期分析：
- 哪些查询没有召回正确工具
- 哪些工具从未被搜索到
- 哪些工具描述需要优化

### 7.2 第二个问题：工具描述缓存

#### 当前方案的问题
每次调查都重新构造工具清单，包含完整的 schema。

如果工具很多，schema 很大：
- 重复序列化和传输
- 占用带宽和内存

#### 优化方案：按版本缓存

```java
public class ToolDescriptionCache {
    private final LoadingCache<CacheKey, byte[]> cache = Caffeine.newBuilder()
        .maximumSize(1000)
        .expireAfterWrite(1, TimeUnit.HOURS)
        .build(key -> serializeDescription(key));
    
    record CacheKey(
        String toolName,
        String toolVersion,
        String schemaHash,      // schema 内容的 hash
        String descriptionHash, // 描述内容的 hash
        String roleId           // 角色身份（不同角色看到的可能不同）
    ) {}
}
```

**缓存失效的条件：**
1. 工具版本变了
2. Schema 变了（即使版本号没变）
3. 描述变了
4. 角色权限变了
5. 超过过期时间

**为什么需要 roleId？**

不同角色看到的工具描述可能不同：
- 普通角色：看到基础描述
- 高级角色：看到完整描述（包括内部参数）

不能跨角色共享缓存，否则可能泄露信息。

### 7.3 第三个问题：执行并发控制

#### 当前方案的问题
所有工具共享一个线程池（2 个工作线程）：
```java
ExecutorService callPool = new ThreadPoolExecutor(2, 2, ...);
```

如果有些工具很快（100ms），有些很慢（10s）：
- 慢工具会占住线程
- 快工具要排队等待
- 整体吞吐下降

#### 优化方案：按数据源分配并发

```java
public class ToolExecutionPools {
    // 按数据源类型分配
    private final ExecutorService prometheusPool = newPool(5, 20);  // 指标查询，较快
    private final ExecutorService lokiPool = newPool(3, 10);        // 日志查询，中等
    private final ExecutorService databasePool = newPool(2, 5);     // 数据库查询，较慢
    
    public ExecutorService getPool(ToolDefinition tool) {
        return switch (tool.name()) {
            case String s when s.startsWith("prometheus.") -> prometheusPool;
            case String s when s.startsWith("logs.") -> lokiPool;
            case String s when s.startsWith("change.") || s.startsWith("alert.") 
                -> databasePool;
            default -> defaultPool;
        };
    }
}
```

**为什么按数据源，而不是按工具？**

因为：
- 工具可能有上千个，每个工具一个池不现实
- 同一数据源的工具，瓶颈通常相同（网络、数据源负载）
- 按数据源分配，可以保护数据源不被打爆

**进一步优化：动态调整**

```java
// 定期调整池大小
scheduler.scheduleAtFixedRate(() -> {
    for (DataSource source : sources) {
        Metrics metrics = collectMetrics(source);
        int newSize = calculateOptimalSize(
            metrics.avgLatency,
            metrics.queueWaitTime,
            metrics.rejectionRate,
            source.capacity
        );
        adjustPoolSize(source, newSize);
    }
}, 1, 1, TimeUnit.MINUTES);
```

调整依据：
- 如果队列等待时间长：增加线程
- 如果数据源延迟变高：减少线程（已经到上限）
- 如果拒绝率高且数据源健康：增加线程
- 如果拒绝率高但数据源慢：不增加（会更糟）

### 7.4 第四个问题：调用账本的规模

#### 当前方案的问题
每次工具调用都插入一条记录：
```sql
INSERT INTO rca_tool_invocation (...) VALUES (...);
```

如果调查很多，工具调用很多：
- 表会越来越大
- 查询变慢
- 存储成本高

#### 优化方案：分区 + 归档

**按时间分区：**
```sql
CREATE TABLE rca_tool_invocation (
    operation_id UUID,
    run_id UUID,
    created_at TIMESTAMP,
    ...
) PARTITION BY RANGE (created_at);

CREATE TABLE rca_tool_invocation_2026_09 
    PARTITION OF rca_tool_invocation 
    FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');

CREATE TABLE rca_tool_invocation_2026_10 
    PARTITION OF rca_tool_invocation 
    FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
```

**定期归档：**
```java
// 每天归档 30 天前的数据
scheduler.scheduleAtFixedRate(() -> {
    LocalDate cutoff = LocalDate.now().minusDays(30);
    
    // 1. 导出到对象存储
    exportToStorage("rca_tool_invocation", cutoff);
    
    // 2. 删除旧分区
    dropPartition("rca_tool_invocation", cutoff);
    
}, 1, 1, TimeUnit.DAYS);
```

**热数据 vs 冷数据：**
- 热数据（最近 7 天）：在主数据库，支持快速查询
- 温数据（8-30 天）：在主数据库，但单独分区
- 冷数据（30 天以上）：导出到对象存储，需要时再加载

### 7.5 第五个问题：证据存储的规模

#### 当前方案的问题
所有证据都存在数据库的一个表中：
```sql
CREATE TABLE rca_evidence (
    evidence_id UUID PRIMARY KEY,
    run_id UUID,
    content BYTEA,  -- 可能几十 KB
    ...
);
```

问题：
- BYTEA 字段很大，影响查询性能
- 证据和元数据混在一起
- 难以对证据做压缩或加密

#### 优化方案：分离存储

```sql
-- 元数据表（小而快）
CREATE TABLE rca_evidence_metadata (
    evidence_id UUID PRIMARY KEY,
    run_id UUID,
    evidence_type VARCHAR(64),
    source VARCHAR(64),
    content_location TEXT,  -- 对象存储的 key
    content_size BIGINT,
    created_at TIMESTAMP
);

-- 内容存储在对象存储（如 S3）
-- s3://evidence-bucket/2026/09/12/{evidence_id}
```

**访问证据时：**
```java
// 1. 查询元数据
EvidenceMetadata meta = db.query(
    "SELECT * FROM rca_evidence_metadata WHERE evidence_id = ?", 
    evidenceId);

// 2. 从对象存储读取内容
byte[] content = s3.getObject(meta.contentLocation());
```

**优势：**
- 数据库只存元数据，查询快
- 对象存储便宜、可扩展
- 可以对内容做压缩（gzip）
- 可以对内容做加密
- 可以设置生命周期策略（自动删除旧证据）

**权衡：**
- 增加一次网络请求（数据库 + 对象存储）
- 需要管理对象存储的凭证和权限
- 一致性稍弱（元数据在数据库，内容在对象存储）

### 7.6 第六个问题：监控和可观测性

#### 当前需要的指标

**工具级别：**
- 调用次数（按工具、版本、结果）
- 成功率（按工具、版本）
- 延迟（P50、P95、P99，按工具）
- 错误率（按工具、错误类型）
- 结果大小分布
- 超时率

**调查级别：**
- 工具调用次数（平均、最大）
- 工具费用（按类型）
- 调查时长
- 成功率（按服务、告警类型）

**系统级别：**
- 线程池使用率
- 队列长度
- 拒绝率
- 数据库连接池使用率
- 预算余额

#### 实现方案

```java
public class ToolMetrics {
    private final MeterRegistry registry;
    
    public void recordInvocation(String tool, String version, 
                                 String result, long durationMs) {
        registry.counter("tool.invocation.total",
            "tool", tool,
            "version", version,
            "result", result
        ).increment();
        
        registry.timer("tool.invocation.duration",
            "tool", tool,
            "version", version
        ).record(Duration.ofMillis(durationMs));
    }
    
    public void recordRejection(String tool, String reason) {
        registry.counter("tool.rejection.total",
            "tool", tool,
            "reason", reason
        ).increment();
    }
}
```

**告警规则：**
```yaml
# 某个工具成功率过低
- alert: ToolLowSuccessRate
  expr: |
    rate(tool_invocation_total{result="success"}[5m]) 
    / rate(tool_invocation_total[5m]) < 0.5
  for: 5m
  
# 队列积压严重
- alert: ToolQueueBacklog
  expr: executor_queue_length > 10
  for: 2m
  
# 预算消耗过快
- alert: BudgetExhausting
  expr: |
    rate(budget_consumed_total[1m]) 
    / budget_limit_total > 0.8
```

---

## 第八部分：MCP 外部工具的动态管理

### 8.1 为什么需要 MCP（Model Context Protocol）？

本地工具是启动时固定的，但有些场景需要动态管理工具：

1. **第三方集成**：接入外部服务的工具（如 Jira、PagerDuty、Slack）
2. **插件系统**：允许用户或租户注册自己的工具
3. **版本灰度**：新版本工具先在部分调查中试用
4. **故障隔离**：某个工具出问题时，能快速禁用，不影响其他工具

**MCP 是什么？**

MCP（Model Context Protocol）是一个标准协议，定义了：
- 工具如何描述自己（元数据、schema）
- 工具如何被发现（列举、搜索）
- 工具如何被调用（请求、响应格式）
- 工具如何管理生命周期（注册、启用、禁用）

### 8.2 MCP 工具的生命周期

```
┌──────────┐
│  未注册   │
└────┬─────┘
     │ register(url, name, metadata)
     ↓
┌──────────┐
│  已注册   │ ← 元数据已保存，但未连接
└────┬─────┘
     │ connect()
     ↓
┌──────────┐
│  已连接   │ ← 连接建立，可以调用
└────┬─────┘
     │ disable()
     ↓
┌──────────┐
│  已禁用   │ ← 不再分配新调用，等待现有调用完成
└────┬─────┘
     │ 等待现有调用 draining
     ↓
┌──────────┐
│  已下线   │ ← 关闭连接，释放资源
└──────────┘
```

### 8.3 注册与校验

```java
public class McpToolRegistry {
    record Registration(
        String id,              // 唯一标识
        String name,            // 工具名称
        URI endpoint,           // MCP 端点地址
        Instant registeredAt,   // 注册时间
        McpMetadata metadata    // 元数据（描述、schema、超时等）
    ) {}
    
    public Registration register(URI endpoint, String name) {
        // 1. 连接到 MCP 端点
        McpConnection conn = connect(endpoint);
        
        // 2. 获取工具元数据
        McpMetadata metadata = conn.describe(name);
        
        // 3. 校验元数据
        validateMetadata(metadata);
        
        // 4. 保存注册信息
        Registration reg = new Registration(
            UUID.randomUUID().toString(),
            name,
            endpoint,
            Instant.now(),
            metadata
        );
        save(reg);
        
        // 5. 关闭临时连接（注册时只是验证，不保持连接）
        conn.close();
        
        return reg;
    }
    
    private void validateMetadata(McpMetadata metadata) {
        // 检查必填字段
        if (metadata.name() == null || metadata.name().isEmpty()) {
            throw new IllegalArgumentException("工具名称不能为空");
        }
        
        // 检查 schema 格式
        if (metadata.schema() == null || metadata.schema().isEmpty()) {
            throw new IllegalArgumentException("schema 不能为空");
        }
        
        // 检查版本格式
        if (!metadata.version().matches("[0-9A-Za-z][A-Za-z0-9._-]{0,31}")) {
            throw new IllegalArgumentException("版本格式不合法");
        }
        
        // 检查超时范围
        if (metadata.timeoutMillis() <= 0 || metadata.timeoutMillis() > 60_000) {
            throw new IllegalArgumentException("超时必须在 (0, 60000] 范围");
        }
    }
}
```

**注册时的关键检查：**

1. **端点可达性**：能否连接到 MCP 端点
2. **元数据完整性**：必填字段都存在
3. **格式合法性**：名称、版本、schema 符合规范
4. **安全性**：端点地址不能是内网敏感地址（防止 SSRF）

**为什么注册时不保持连接？**

注册只是"登记"，真正使用时才建立连接：
- 减少资源占用（不是所有注册的工具都会被用到）
- 避免连接超时（长时间不用的连接可能失效）
- 支持大量工具注册（不受连接数限制）

### 8.4 连接池与有效期

```java
public class McpConnectionPool {
    private final ConcurrentHashMap<String, PooledConnection> connections;
    private final ScheduledExecutorService cleaner;
    
    record PooledConnection(
        McpConnection conn,
        Instant lastUsed,
        AtomicInteger refCount  // 正在使用的调用数量
    ) {}
    
    public McpConnection acquire(String toolId) {
        PooledConnection pooled = connections.compute(toolId, (id, existing) -> {
            if (existing != null && !existing.conn.isValid()) {
                // 连接已失效，关闭并创建新的
                existing.conn.close();
                existing = null;
            }
            if (existing == null) {
                // 创建新连接
                McpConnection conn = connectTo(toolId);
                existing = new PooledConnection(conn, Instant.now(), new AtomicInteger(0));
            }
            // 增加引用计数
            existing.refCount.incrementAndGet();
            return existing;
        });
        return pooled.conn;
    }
    
    public void release(String toolId) {
        connections.computeIfPresent(toolId, (id, pooled) -> {
            // 减少引用计数
            int remaining = pooled.refCount.decrementAndGet();
            if (remaining == 0) {
                // 更新最后使用时间
                return new PooledConnection(pooled.conn, Instant.now(), pooled.refCount);
            }
            return pooled;
        });
    }
    
    // 定期清理闲置连接
    void startCleaner() {
        cleaner.scheduleAtFixedRate(() -> {
            Instant cutoff = Instant.now().minus(5, ChronoUnit.MINUTES);
            connections.entrySet().removeIf(entry -> {
                PooledConnection pooled = entry.getValue();
                // 无人使用 && 闲置超过 5 分钟
                if (pooled.refCount.get() == 0 && pooled.lastUsed.isBefore(cutoff)) {
                    pooled.conn.close();
                    return true;
                }
                return false;
            });
        }, 1, 1, TimeUnit.MINUTES);
    }
}
```

**连接池的设计要点：**

1. **延迟连接**：首次使用时才建立连接
2. **引用计数**：跟踪有多少调用正在使用这个连接
3. **有效性检查**：每次获取前检查连接是否还有效
4. **定期清理**：闲置连接自动关闭，释放资源
5. **并发安全**：使用 `computeIfPresent` 保证原子操作

**为什么需要引用计数？**

假设不用引用计数，直接检查"最后使用时间"：
```java
// 错误的做法
if (lastUsed.isBefore(cutoff)) {
    conn.close();  // 关闭连接
}
```

可能的问题：
- 线程 A 正在使用连接执行调用
- 清理器线程判断"已经闲置 5 分钟"
- 清理器关闭连接
- 线程 A 的调用失败（连接被关闭）

**引用计数的保护：**
```java
if (refCount.get() == 0 && lastUsed.isBefore(cutoff)) {
    conn.close();  // 只有无人使用时才关闭
}
```

### 8.5 禁用与优雅下线

```java
public class McpToolManager {
    private final ConcurrentHashMap<String, ToolState> states;
    
    enum State {
        ENABLED,   // 可用，接受新调用
        DISABLING, // 禁用中，不接受新调用，等待现有调用完成
        DISABLED   // 已禁用，所有调用已完成
    }
    
    record ToolState(
        State state,
        Set<UUID> activeInvocations  // 正在执行的调用 ID
    ) {}
    
    public boolean tryAcquire(String toolId, UUID invocationId) {
        AtomicBoolean acquired = new AtomicBoolean(false);
        states.compute(toolId, (id, existing) -> {
            if (existing == null) {
                // 首次使用，创建 ENABLED 状态
                acquired.set(true);
                Set<UUID> invocations = ConcurrentHashMap.newKeySet();
                invocations.add(invocationId);
                return new ToolState(State.ENABLED, invocations);
            }
            if (existing.state == State.ENABLED) {
                // 可用，允许新调用
                acquired.set(true);
                existing.activeInvocations.add(invocationId);
                return existing;
            }
            // DISABLING 或 DISABLED，拒绝新调用
            acquired.set(false);
            return existing;
        });
        return acquired.get();
    }
    
    public void release(String toolId, UUID invocationId) {
        states.computeIfPresent(toolId, (id, existing) -> {
            existing.activeInvocations.remove(invocationId);
            // 如果是 DISABLING 且所有调用已完成，改为 DISABLED
            if (existing.state == State.DISABLING && existing.activeInvocations.isEmpty()) {
                return new ToolState(State.DISABLED, existing.activeInvocations);
            }
            return existing;
        });
    }
    
    public void disable(String toolId) {
        states.compute(toolId, (id, existing) -> {
            if (existing == null || existing.state == State.DISABLED) {
                // 已经禁用，直接返回
                return new ToolState(State.DISABLED, ConcurrentHashMap.newKeySet());
            }
            if (existing.activeInvocations.isEmpty()) {
                // 没有活跃调用，直接改为 DISABLED
                return new ToolState(State.DISABLED, existing.activeInvocations);
            }
            // 有活跃调用，改为 DISABLING，等待释放
            return new ToolState(State.DISABLING, existing.activeInvocations);
        });
    }
}
```

**禁用的三个阶段：**

1. **ENABLED → DISABLING**：停止接受新调用，但不中断已有调用
2. **DISABLING → DISABLED**：等待所有活跃调用完成
3. **DISABLED**：可以安全地关闭连接、卸载工具

**为什么不能直接中断？**

假设直接中断所有调用：
```java
// 危险的做法
void disable(String toolId) {
    cancelAll(toolId);  // 中断所有调用
    closeConnection(toolId);
}
```

问题：
- 调查正在执行重要的查询（比如排查生产故障）
- 突然被中断，调查失败
- 可能需要重新开始，浪费时间

**优雅下线的价值：**
- 不影响正在进行的调查
- 给调用一个自然结束的机会
- 避免半完成的状态（比如预算已扣，但没有结果）

**如果等待太久怎么办？**

可以设置超时强制下线：
```java
public void disableWithTimeout(String toolId, Duration timeout) {
    disable(toolId);
    
    // 等待一段时间
    Instant deadline = Instant.now().plus(timeout);
    while (Instant.now().isBefore(deadline)) {
        ToolState state = states.get(toolId);
        if (state != null && state.state == State.DISABLED) {
            // 已经下线
            return;
        }
        Thread.sleep(100);
    }
    
    // 超时，强制中断
    ToolState state = states.get(toolId);
    if (state != null) {
        for (UUID invocationId : state.activeInvocations) {
            cancelInvocation(invocationId);
        }
    }
}
```

### 8.6 MCP 与本地工具的统一视图

```java
public interface UnifiedToolRegistry {
    List<ToolDescriptor> listTools(Context ctx);
    ToolDescriptor describe(String toolId);
    ToolInvocationResult invoke(ToolInvocation invocation);
}

public class CompositeToolRegistry implements UnifiedToolRegistry {
    private final LocalToolRegistry localTools;
    private final McpToolRegistry mcpTools;
    
    @Override
    public List<ToolDescriptor> listTools(Context ctx) {
        List<ToolDescriptor> all = new ArrayList<>();
        
        // 本地工具
        all.addAll(localTools.listTools(ctx));
        
        // MCP 工具
        all.addAll(mcpTools.listTools(ctx));
        
        // 去重（如果本地和 MCP 都有相同名称的工具，本地优先）
        return deduplicateByName(all);
    }
    
    @Override
    public ToolInvocationResult invoke(ToolInvocation invocation) {
        // 1. 先尝试本地工具
        if (localTools.has(invocation.toolName())) {
            return localTools.invoke(invocation);
        }
        
        // 2. 再尝试 MCP 工具
        if (mcpTools.has(invocation.toolName())) {
            return mcpTools.invoke(invocation);
        }
        
        // 3. 都没有，返回 UNKNOWN_TOOL
        throw new ToolControlPlaneException(
            ToolControlReason.UNKNOWN_TOOL,
            "工具未注册: " + invocation.toolName()
        );
    }
}
```

**统一视图的价值：**

1. **模型无感知**：模型不需要知道工具是本地的还是外部的
2. **灵活替换**：可以把本地工具迁移到 MCP，或反之
3. **故障切换**：MCP 工具不可用时，可以回退到本地实现
4. **A/B 测试**：10% 调查使用新的 MCP 实现，90% 使用旧的本地实现

**本地优先的理由：**

如果本地和 MCP 都有 `logs.query`：
- 本地工具：延迟低、可靠性高、无网络依赖
- MCP 工具：可能是实验性的新版本，或第三方实现

本地优先保证：
- 核心功能不依赖外部服务
- 即使 MCP 服务挂了，基本工具还能用
- 灰度测试时，可以通过移除本地工具来强制使用 MCP

### 8.7 MCP 调用的特殊处理

#### 超时传递

```java
public class McpToolExecutor implements ToolExecutor {
    @Override
    public byte[] execute(ToolExecution execution) throws Exception {
        long remaining = execution.deadlineEpochMillis() - System.currentTimeMillis();
        if (remaining <= 0) {
            throw new ToolModelVisibleException(
                ToolModelVisibleReason.TIMEOUT_RETRYABLE,
                "deadline 已过期，不再发起 MCP 调用");
        }
        
        // 向 MCP 端点传递剩余时间
        McpRequest request = new McpRequest(
            execution.validatedArgs(),
            Duration.ofMillis(remaining - 500)  // 预留 500ms 缓冲
        );
        
        McpResponse response = mcpClient.call(request);
        return response.body();
    }
}
```

**为什么要传递超时？**

如果不传递：
- MCP 端点可能使用自己的默认超时（比如 30 秒）
- 本地已经超时了（4 秒），但 MCP 还在执行
- 浪费 MCP 资源

传递超时的好处：
- MCP 端点知道"最晚什么时候必须返回"
- 可以提前停止不可能完成的查询
- 资源利用更高效

**为什么要减去 500ms 缓冲？**

- 网络传输需要时间
- 本地处理响应需要时间
- 如果不留缓冲，MCP 端点可能刚好在 deadline 返回，本地已经超时了

#### 结果大小限制

```java
public byte[] execute(ToolExecution execution) throws Exception {
    McpRequest request = new McpRequest(
        execution.validatedArgs(),
        Duration.ofMillis(remaining),
        execution.resultLimitBytes()  // 传递大小限制
    );
    
    McpResponse response = mcpClient.call(request);
    
    // 本地再检查一次（防止 MCP 端点不遵守限制）
    if (response.body().length > execution.resultLimitBytes()) {
        throw new ToolControlPlaneException(
            ToolControlReason.RESULT_OVERSIZE,
            "MCP 返回超限: " + response.body().length 
                + " > " + execution.resultLimitBytes());
    }
    
    return response.body();
}
```

**为什么本地要再检查一次？**

不能假设 MCP 端点一定遵守限制：
- 可能是旧版本，不支持 resultLimit 参数
- 可能实现有 bug
- 可能是恶意端点，故意返回超大结果

**双重检查的保护：**
1. 传递给 MCP：期望它遵守
2. 本地再检查：兜底保护，防止内存撑爆

#### 错误映射

```java
private RuntimeException mapMcpError(McpException e) {
    return switch (e.code()) {
        case "INVALID_ARGS" -> new ToolControlPlaneException(
            ToolControlReason.INVALID_ARGS, e.getMessage());
        case "TIMEOUT" -> new ToolModelVisibleException(
            ToolModelVisibleReason.TIMEOUT_RETRYABLE, e.getMessage());
        case "RATE_LIMITED" -> new ToolModelVisibleException(
            ToolModelVisibleReason.RATE_LIMITED, e.getMessage());
        case "AUTH_FAILED" -> new ToolControlPlaneException(
            ToolControlReason.AUTH_FAILED, e.getMessage());
        default -> new ToolModelVisibleException(
            ToolModelVisibleReason.REMOTE_UNAVAILABLE,
            "MCP 工具暂不可用");
    };
}
```

**为什么需要映射？**

MCP 协议可能定义了自己的错误码：
- `INVALID_ARGS`
- `TIMEOUT`
- `RATE_LIMITED`

但本地系统使用的是 `ToolControlReason` 和 `ToolModelVisibleReason`。

**映射的价值：**
- 统一错误处理逻辑
- 模型看到的错误格式一致
- 可以针对不同错误类型做不同处理

**未知错误码的处理：**
```java
default -> new ToolModelVisibleException(
    ToolModelVisibleReason.REMOTE_UNAVAILABLE,
    "MCP 工具暂不可用");
```

保守地映射为"远端不可用"，而不是抛出未知异常。

---

## 第九部分：面试卡片——如何在面试中讲清楚每个设计决策

### 卡片一：工具是什么？为什么需要工具？

**开场（30 秒）：**
"工具不是让模型自由操作系统，而是把查询能力封装成明确的入口。模型提议想查什么，程序负责检查、执行、记录。"

**深入追问：**
1. **为什么不让模型直接执行命令？**
   - 答：安全边界无法控制，资源无法限制，错误难以定位，结果无法复查。

2. **如何保证模型不会绕过工具？**
   - 答：模型没有执行环境访问权限，所有操作都必须通过工具网关，网关是唯一的咽喉。

3. **如果工具没有提供某个能力怎么办？**
   - 答：这是设计上的权衡。宁可功能受限，也不开放不受控的能力。如果确实需要，应该增加新工具，而不是放开现有工具的限制。

### 卡片二：为什么是六层校验？

**开场（30 秒）：**
"六层不是拍脑袋定的，而是每一层解决一类问题：启动时检查定义、执行前检查权限、格式、语义、状态，执行后检查结果。"

**按层讲解（每层 20 秒）：**
1. **启动时检查定义**：工具本身是否合法，fail-fast
2. **双闸权限**：第一道减少攻击面，第二道硬约束
3. **格式校验**：通用规则，快速拒绝
4. **语义校验**：业务规则，各自负责
5. **状态检查**：动态判断，及时响应
6. **结果检查**：最后兜底，保证证据质量

**深入追问：**
1. **为什么参数错误属于控制面，但允许模型改正？**
   - 答：参数填错是模型的问题，但模型可以学习。其他控制面错误是系统问题，模型改不了。主调查循环会专门捕获 INVALID_ARGS，给模型改正的机会。

2. **如果合并成更少的层会怎样？**
   - 答：（举例说明）比如格式和语义检查合并，新增工具时需要修改通用验证器，业务规则分散，难以维护。

### 卡片三：预算控制的三段式

**开场（30 秒）：**
"预留、执行、结算。预留保证不超限，执行期间不持有锁，结算根据实际使用。失败时根据是否发出决定退款。"

**三段讲解（每段 30 秒）：**
1. **预留**：数据库条件更新，原子检查和占用，某一维不够撤销所有
2. **执行**：不持有数据库锁，避免长事务，失败时判断是否发出
3. **结算**：实扣而非预留，usage 缺失标记待对账

**深入追问：**
1. **为什么失败时默认不退款？**
   - 答：保守策略。如果查询确实发出了但错误地退款，预算记录会不准。不退款只是浪费预算，不会影响正确性。

2. **多维预留时，为什么某一维不够要撤销其他维？**
   - 答：避免零残留。如果不撤销，某维的额度被占用但调用不会执行，影响其他调查。

### 卡片四：调用账本的四种状态

**开场（30 秒）：**
"PENDING、SUCCESS、FAILED、UNKNOWN。先记录 PENDING 再执行，完成后改终态，崩溃恢复时把长时间悬挂的改为 UNKNOWN。"

**状态转换（30 秒）：**
```
PENDING → SUCCESS（正常完成）
PENDING → FAILED（明确失败）
PENDING → UNKNOWN（崩溃恢复）
终态不可改（条件更新保护）
```

**深入追问：**
1. **为什么不是执行完再记录？**
   - 答：进程可能在执行期间崩溃，重启后不知道是否执行过。先记录 PENDING，无论何时崩溃都知道"曾经尝试过"。

2. **UNKNOWN 状态的价值是什么？**
   - 答：明确表达"不确定"，而不是假装成功或失败。为后续人工调查保留信息，统计时单独分类。

### 卡片五：为什么线程池是 2 + 16？

**开场（30 秒）：**
"2 个工作线程、16 个排队位置。不是根据工具数量，而是根据数据源能力、系统容量、可观测性。"

**设计理由（每点 20 秒）：**
1. **匹配数据源能力**：数据源只能承受 5 QPS，开 100 个线程也没用
2. **控制爆炸半径**：即使有 bug，最多卡 2 个线程
3. **可观测性**：线程少，容易定位哪个在执行、哪个在排队
4. **背压保护**：队列满时明确拒绝，快速失败

**深入追问：**
1. **为什么队列不是无限的？**
   - 答：无限队列会导致内存溢出、等待时间过长、可能等到执行时调查已经超时。有界队列提供背压保护。

2. **如何调整线程池大小？**
   - 答：观察队列等待时间、拒绝率、数据源延迟。如果经常拒绝且数据源健康，增加线程；如果数据源变慢，不增加（已到上限）。

### 卡片六：超时后任务还在执行吗？

**开场（30 秒）：**
"可能。`future.cancel(true)` 只是发送中断信号，不保证立即停止。能否停止取决于代码是否检查中断、阻塞操作是否响应中断、底层库是否支持取消。"

**处理逻辑（30 秒）：**
1. 网关等待超时，抛出异常，不再等待结果
2. 调用 `future.cancel(true)` 尝试取消
3. 迟到的结果会被丢弃（控制流已经返回）
4. 账本已记录为超时，不会随后改成成功

**深入追问：**
1. **为什么不能使用迟到的结果？**
   - 答：一致性。超时就是失败，不能后来"反悔"。账本、预算都已经记录为失败，不能重复结算。

2. **如何保证任务真的停止？**
   - 答：执行器内部应该检查 deadline，超过期限主动停止。客户端超时加缓冲（deadline + 2秒），让统一入口的超时先触发。

### 卡片七：MCP 工具的禁用流程

**开场（30 秒）：**
"ENABLED → DISABLING → DISABLED。停止接受新调用，但不中断已有调用。等所有活跃调用完成后，才关闭连接。"

**三个阶段（每阶段 20 秒）：**
1. **ENABLED → DISABLING**：`disable()` 被调用，状态改为 DISABLING
2. **等待释放**：活跃调用逐个完成，`release()` 移除 invocationId
3. **DISABLING → DISABLED**：最后一个调用完成，activeInvocations 为空

**深入追问：**
1. **为什么不能直接中断？**
   - 答：调查可能在排查生产故障，突然中断会导致调查失败，可能需要重新开始。优雅下线不影响正在进行的工作。

2. **如果等待太久怎么办？**
   - 答：可以设置超时强制下线。等待一段时间后，如果还有活跃调用，强制取消并关闭连接。

### 卡片八：结果复用的条件

**开场（30 秒）：**
"同一调查、动作摘要相同、之前成功、有证据引用。复用时仍然占用预算、记录账本，但不发起网络请求。"

**四个条件（20 秒）：**
1. 同一个调查（runId 相同）
2. 动作摘要相同（工具、版本、schema、参数、时间范围）
3. 之前的调用成功了（state = SUCCESS）
4. 有证据引用（resultRef != null）

**深入追问：**
1. **为什么复用仍然占用预算？**
   - 答：模型调用了工具（消耗了一次工具额度），账本记录了这次调用（有审计成本），防止模型无限复用绕过预算限制。

2. **为什么只在同一调查内复用？**
   - 答：时效性（不同调查时间窗口可能不同）、权限（不同调查权限可能不同）、一致性（同一调查的证据应该是同一时刻的快照）。

### 卡片九：证据的三步保存

**开场（30 秒）：**
"先保存证据、再记录引用、最后标记成功。顺序很重要，进程在任何时刻崩溃都能保证一致性。"

**三步讲解（每步 20 秒）：**
1. **保存证据**：`evidence.save(envelope)` 持久化到数据库或对象存储
2. **记录引用**：`ledger.markResultRef(operationId, evidenceId)` 在调用记录中指向证据
3. **标记成功**：`ledger.succeed(operationId)` 改状态为 SUCCESS

**深入追问：**
1. **如果顺序反了会怎样？**
   - 答：先标记成功再保存证据，崩溃后调用记录显示成功但找不到证据。正确顺序保证：状态为 SUCCESS 时证据一定已保存。

2. **如果证据保存失败怎么办？**
   - 答：抛异常，调用失败，状态改为 FAILED。不会出现"成功但没有证据"的不一致状态。

### 卡片十：重复停止器的阈值

**开场（30 秒）：**
"跟踪每次查询的结果，如果连续 N 次相同查询都没有进展（NO_DATA 或 FAILED），封住这个查询签名，不再执行。"

**关键参数（30 秒）：**
- 默认阈值：5 次
- 签名：runId + toolName + actionDigest
- 没有进展：NO_DATA 或 FAILED
- 有进展：返回证据（清零计数）

**深入追问：**
1. **为什么是进程内的 Map，而不是数据库？**
   - 答：性能（每次调用都要检查），生命周期（跟随调查），隔离性（不同实例独立）。重启后计数器丢失，但可以接受（可能问题已解决）。

2. **阈值为什么是 5？**
   - 答：经验值。太小（如 2）可能误杀正常重试，太大（如 20）浪费太多额度。实际应根据平均工具额度和重复模式调整。

### 卡片十一：扩展到万级工具的优化

**开场（30 秒）：**
"分层过滤 + 按需加载、按数据源分配并发、分区归档、分离存储。不是一次性给所有工具，而是按需发现和加载。"

**四个方向（每个 30 秒）：**
1. **工具发现**：按角色/服务/阶段预过滤，提供搜索入口，按需加载完整 schema
2. **并发控制**：按数据源分配线程池，动态调整池大小
3. **账本规模**：按时间分区，定期归档冷数据
4. **证据存储**：元数据存数据库，内容存对象存储

**深入追问：**
1. **搜索如何保证准确？**
   - 答：混合搜索（先权限过滤、关键词匹配、可选向量相似度）、记录搜索日志用于改进。

2. **如何判断数据源的容量上限？**
   - 答：观察增加线程后数据源延迟是否变高。如果延迟变高，说明已到上限，不应继续增加。

### 卡片十二：一次调查失败后如何调试？

**开场（30 秒）：**
"查账本、看证据、读预算、检查重复停止。每一层都有记录，可以回溯整个过程。"

**调试步骤（每步 20 秒）：**
1. **查账本**：`SELECT * FROM rca_tool_invocation WHERE run_id = ?`，看哪些工具被调用、状态、错误码
2. **看证据**：通过 `result_ref` 找到证据，看返回了什么数据
3. **读预算**：`SELECT * FROM run_budget WHERE run_id = ?`，看额度是否耗尽
4. **检查重复停止**：日志中搜索 `REPEATED_NO_PROGRESS`，看是否触发阈值

**深入追问：**
1. **如果账本显示成功但没有证据怎么办？**
   - 答：可能是崩溃导致的不一致。检查 `result_ref` 是否为空，如果为空，说明证据保存步骤没有完成。可以通过时间范围在证据表中查找孤立证据。

2. **如果预算没有耗尽但调查仍然失败？**
   - 答：可能是重复停止、外部期限到期、或者某个工具返回了终止错误（如 AUTH_FAILED）。检查最后一条调用记录的错误原因。

---

## 第十部分：总结与自检清单

### 10.1 核心设计原则回顾

在整个工具侧的设计中，我们始终遵循以下原则：

#### 1. 明确的边界优于灵活的能力

**不好的设计：**
```java
// 允许模型传入任意代码执行
byte[] execute(String code) {
    return Runtime.exec(code);
}
```

**好的设计：**
```java
// 明确的工具，固定的能力
byte[] queryMetrics(String query, Instant start, Instant end, String step) {
    validateParams(...);
    return prometheusClient.queryRange(...);
}
```

**为什么？**
- 边界清晰，易于审计
- 安全风险可控
- 功能可以逐步扩展，但不会一次性开放所有能力

#### 2. 先检查再执行，而不是执行后再补救

**不好的设计：**
```java
byte[] result = executeQuery();  // 先执行
if (result.length > limit) {     // 再检查
    throw new Exception("结果太大");
}
```

**好的设计：**
```java
if (estimatedSize > limit) {     // 先检查
    throw new Exception("预估结果太大");
}
byte[] result = executeQuery();  // 再执行
```

**为什么？**
- 避免浪费资源
- 避免副作用已经产生但要回滚
- 失败更早，定位更容易

#### 3. 不确定就记录为不确定，不要猜测

**不好的设计：**
```java
if (response == null) {
    return SUCCESS;  // 猜测：没有错误就是成功
}
```

**好的设计：**
```java
if (response == null) {
    return UNKNOWN;  // 明确：结果未知
}
```

**为什么？**
- 猜测可能错误，导致数据不一致
- 明确的"不确定"比错误的"确定"更有价值
- 为后续人工调查保留信息

#### 4. 可重试的错误和必须停止的错误分开处理

**不好的设计：**
```java
catch (Exception e) {
    throw new RuntimeException("执行失败");  // 所有错误都一样
}
```

**好的设计：**
```java
catch (TimeoutException e) {
    throw new ToolModelVisibleException(TIMEOUT_RETRYABLE, ...);  // 可重试
}
catch (AuthException e) {
    throw new ToolControlPlaneException(AUTH_FAILED, ...);  // 必须停止
}
```

**为什么？**
- 可重试的错误给模型恢复的机会
- 必须停止的错误避免无意义的重试
- 不同错误类型需要不同的处理策略

#### 5. 操作的可追溯性优于性能优化

**不好的设计：**
```java
// 为了性能，不记录账本
byte[] result = executeQuery();
return result;
```

**好的设计：**
```java
// 先记录，再执行
ledger.open(identity);
byte[] result = executeQuery();
ledger.succeed(operationId);
return result;
```

**为什么？**
- 性能问题可以后续优化（缓存、索引、分区）
- 但操作不可追溯，无法事后分析和调试
- 账本是故障排查、合规审计、费用对账的基础

### 10.2 实现完整性自检清单

当你声称"实现了某个功能"时，用这个清单自检：

#### 工具定义
- [ ] 有明确的名称和版本
- [ ] 有完整的 schema 定义（所有参数都声明了类型、必填、格式）
- [ ] 有业务约束说明（时间窗口、白名单、枚举值）
- [ ] 有返回值格式说明
- [ ] 有错误码清单
- [ ] 有超时和结果大小限制
- [ ] 有权限和租户说明
- [ ] 有依赖和示例

#### 工具注册
- [ ] 启动时检查定义合法性（格式、超时、大小限制）
- [ ] 拒绝重名重版本（fail-fast）
- [ ] 工具列表稳定排序
- [ ] 支持按权限过滤

#### 工具执行
- [ ] 权限检查（双闸机制）
- [ ] 参数格式校验（通用规则）
- [ ] 参数语义校验（业务规则）
- [ ] 预算预留（多维一次准入）
- [ ] 独立线程池执行
- [ ] 超时控制（deadline 传递）
- [ ] 结果大小检查（双重保护）
- [ ] 预算结算（实扣而非预留）

#### 调用账本
- [ ] 先记录 PENDING 再执行
- [ ] 成功时改为 SUCCESS
- [ ] 失败时改为 FAILED
- [ ] 崩溃恢复时改为 UNKNOWN
- [ ] 终态不可改（条件更新保护）
- [ ] 有唯一约束（防止重复记录）

#### 证据保存
- [ ] 先保存证据
- [ ] 再记录引用
- [ ] 最后标记成功
- [ ] 证据有类型、来源、内容、时间戳
- [ ] 支持通过 run_id 查询
- [ ] 支持通过 result_ref 查询

#### 预算控制
- [ ] 预留阶段：多维检查，某维不够撤销所有
- [ ] 执行阶段：不持有数据库锁
- [ ] 失败处理：根据是否发出决定退款
- [ ] 结算阶段：实扣，usage 缺失标记待对账
- [ ] 使用数据库条件更新保证并发安全

#### 错误处理
- [ ] 区分可重试和终止错误
- [ ] 每种错误有明确的原因码
- [ ] 未知错误默认映射为可重试
- [ ] 不泄露底层实现细节

#### 并发控制
- [ ] 线程池有核心线程数、最大线程数、队列容量
- [ ] 队列满时拒绝（背压保护）
- [ ] 支持超时和取消
- [ ] 支持优雅下线（等待活跃调用完成）

#### MCP 工具（如果支持）
- [ ] 注册时校验元数据
- [ ] 连接池管理（延迟连接、引用计数、定期清理）
- [ ] 禁用流程（ENABLED → DISABLING → DISABLED）
- [ ] 错误映射（MCP 错误码 → 本地错误码）
- [ ] 超时和大小限制传递

#### 监控和可观测性
- [ ] 记录调用次数、成功率、延迟
- [ ] 记录拒绝率、队列长度
- [ ] 记录预算消耗
- [ ] 支持按工具、版本、结果分组统计
- [ ] 有告警规则（成功率低、队列积压、预算耗尽）

### 10.3 常见陷阱与规避

#### 陷阱一：假设模型总是正确

**错误假设：**
"模型是智能的，它不会故意填错参数。"

**实际情况：**
- 模型可能幻觉出不存在的参数
- 模型可能尝试注入特殊字符绕过限制
- 模型可能被提示注入攻击

**规避方法：**
- 所有参数都要校验（格式、业务规则）
- 拒绝未声明的参数（`additionalProperties=false`）
- 不信任模型的任何输入

#### 陷阱二：假设外部服务总是可靠

**错误假设：**
"Prometheus 是稳定的，不会出问题。"

**实际情况：**
- Prometheus 可能宕机
- Prometheus 可能限流
- Prometheus 可能返回错误格式

**规避方法：**
- 所有外部调用都要超时保护
- 所有响应都要检查格式
- 有明确的错误处理和重试策略

#### 陷阱三：假设数据库操作总是成功

**错误假设：**
"INSERT 不会失败的，预算更新也不会失败。"

**实际情况：**
- 唯一约束冲突
- 条件更新影响 0 行（并发竞争）
- 数据库连接池耗尽
- 事务超时

**规避方法：**
- 所有数据库操作都要检查返回值
- 使用条件更新保护终态
- 使用唯一约束防止重复
- 有重试和降级策略

#### 陷阱四：假设进程不会崩溃

**错误假设：**
"先执行再记录，进程不会在中间崩溃的。"

**实际情况：**
- OOM、SIGKILL、断电、网络分区

**规避方法：**
- 先记录意图（PENDING）再执行
- 操作要幂等或可恢复
- 有定期的恢复扫描
- 崩溃后能从账本重建状态

#### 陷阱五：假设"成功"就是真的成功

**错误假设：**
"HTTP 200 就是成功，可以直接用返回的数据。"

**实际情况：**
```json
{
  "status": "error",
  "error": "query timeout"
}
```
HTTP 200，但业务失败。

**规避方法：**
- 检查业务状态码
- 检查返回格式
- 检查数据完整性
- 空结果要区分语义（真的空 vs 查询失败）

#### 陷阱六：假设"失败"就不会有副作用

**错误假设：**
"查询失败了，什么都没发生，可以重试。"

**实际情况：**
- 查询可能已经发出（网络超时，但请求到了）
- 数据源可能已经记录了这次查询
- 预算可能已经扣除

**规避方法：**
- 区分"确证未发出"和"不确定"
- 确证未发出才退款
- 不确定时保守占用预算
- 标记为 provisional，事后对账

#### 陷阱七：假设并发只在多线程

**错误假设：**
"我的代码是单线程的，不需要考虑并发。"

**实际情况：**
- 多个应用实例同时操作数据库
- 异步任务和主流程并发
- 定时任务和用户请求并发

**规避方法：**
- 数据库层面用条件更新保护
- 不能只用 Java 的 synchronized
- 有唯一约束防止重复
- 有乐观锁或悲观锁保护

#### 陷阱八：假设监控可以后补

**错误假设：**
"先把功能做出来，监控以后再加。"

**实际情况：**
- 没有监控，问题无法发现
- 没有指标，优化无从下手
- 没有账本，故障无法排查

**规避方法：**
- 监控和功能同步设计
- 关键路径必须有指标（调用次数、延迟、成功率）
- 关键操作必须有账本（谁、什么时候、做了什么、结果如何）
- 异常情况必须有告警

### 10.4 如何向面试官展示你的理解深度

#### 第一层：能说出"是什么"

"工具是封装好的查询入口，模型提议想查什么，程序负责执行。"

**这是基础，但还不够。**

#### 第二层：能说出"为什么这样设计"

"工具要经过六层校验，因为每一层解决一类问题：启动时检查定义，执行前检查权限、格式、语义、状态，执行后检查结果。"

**这证明你理解了设计理由。**

#### 第三层：能说出"如果不这样会怎样"

"如果不先记录 PENDING 再执行，进程崩溃后不知道是否执行过。如果不用条件更新保护终态，并发时可能把 SUCCESS 覆盖成 FAILED。"

**这证明你理解了设计的必要性。**

#### 第四层：能说出"这个设计的权衡和代价"

"预算预留保证不超限，但增加了数据库压力。可以通过缓存、批量操作、异步结算来优化，但会增加复杂度和最终一致性的问题。"

**这证明你理解了设计不是完美的，而是在约束下的最优解。**

#### 第五层：能说出"在不同场景下的变体"

"小规模工具集用启动时固定注册，大规模工具集需要按需加载和搜索。本地工具用统一网关，外部工具用 MCP 协议和动态管理。只读工具共享线程池，按数据源分配并发。"

**这证明你能根据实际情况调整方案，而不是死记硬背。**

#### 第六层：能说出"历史上的问题和演进"

"最初没有重复停止器，模型会无限重复查询。后来发现 run26 中连续查了 20 次都是 NO_DATA，于是加了阈值机制。阈值最初是 3，但测试发现太容易误杀，改成 5。"

**这证明你的理解来自实际问题，而不是纸上谈兵。**

### 10.5 本文档的使用建议

#### 面试前

1. **通读一遍**：了解整体架构和关键设计
2. **每个卡片练习一次**：用自己的话讲出来，不要背诵
3. **准备 2-3 个深入案例**：比如预算控制、调用账本、MCP 管理
4. **准备反例**：如果不这样做会怎样

#### 面试中

1. **先简后详**：先用 30 秒概括，面试官追问再展开
2. **用实际数字**：2 个工作线程、16 个排队位置、5 次重复阈值
3. **画图辅助**：状态转换图、时序图、架构图
4. **主动提问题**：如果我理解有误，您能指出吗？

#### 面试后

1. **记录被问到的问题**：准备不足的地方
2. **补充没有讲清楚的部分**：下次改进
3. **反思哪些设计可以更好**：持续学习

### 10.6 最后的提醒：诚实比完美更重要

**不要说：**
- "我们的系统完全没有问题。"
- "这个设计是最优的。"
- "我实现了所有功能。"

**应该说：**
- "当前实现了 X，但 Y 还没有完全补齐。"
- "这个设计在当前规模下是合适的，如果到了 Z 规模需要优化。"
- "历史上出现过 A 问题，我们通过 B 方案解决，但引入了 C 的代价。"

**面试官想看到的不是一个完美的系统，而是：**
1. 你理解问题的本质
2. 你知道为什么这样设计
3. 你知道设计的边界和代价
4. 你能根据场景调整方案
5. 你从实际问题中学习和改进

**记住：诚实地承认"不知道"或"还没做到"，比假装知道或夸大成果，更能赢得面试官的信任。**

---

## 附录：快速参考表

### A1. 错误码速查表

| 错误码 | 家族 | 含义 | 可重试 | 模型可见 |
|--------|------|------|--------|----------|
| UNKNOWN_TOOL | 控制面 | 工具不存在 | 否 | 否 |
| POLICY_DENIED | 控制面 | 权限不足 | 否 | 否 |
| INVALID_ARGS | 控制面 | 参数错误 | 是（给模型改正机会） | 是 |
| AUTH_FAILED | 控制面 | 认证失败 | 否 | 否 |
| CONFIGURATION_ERROR | 控制面 | 配置错误 | 否 | 否 |
| RESULT_OVERSIZE | 控制面 | 结果超限 | 否 | 否 |
| NO_DATA | 模型可见 | 窗内无数据 | 是 | 是 |
| TIMEOUT_RETRYABLE | 模型可见 | 超时 | 是 | 是 |
| RATE_LIMITED | 模型可见 | 限流 | 是 | 是 |
| REMOTE_UNAVAILABLE | 模型可见 | 远端不可用 | 是 | 是 |
| SOURCE_UNAVAILABLE | 模型可见 | 特定源不可用 | 是 | 是 |

### A2. 状态转换速查表

#### 调用账本状态

```
PENDING → SUCCESS  （正常完成）
PENDING → FAILED   （明确失败）
PENDING → UNKNOWN  （崩溃恢复）

不允许：
SUCCESS → FAILED
FAILED → SUCCESS
UNKNOWN → SUCCESS
```

#### MCP 工具状态

```
未注册 → 已注册    （register）
已注册 → 已连接    （首次 acquire）
已连接 → 已禁用    （disable）
已禁用 → 已下线    （最后一个 release）

注意：
- 已禁用时不接受新调用
- 已下线后连接关闭
```

### A3. 关键时间参数速查表

| 参数 | 默认值 | 说明 |
|------|--------|------|
| 工具超时 | 4000ms | 单个工具调用的最大时间 |
| 结果上限 | 65536 字节 | 单个工具返回的最大大小 |
| 线程池核心 | 2 | 同时执行的工具数量 |
| 线程池队列 | 16 | 最多排队的工具调用 |
| 连接闲置 | 5 分钟 | MCP 连接闲置超过此时间会被清理 |
| PENDING 超时 | 5 分钟 | 超过此时间的 PENDING 会被改为 UNKNOWN |
| 重复停止阈值 | 5 次 | 连续无进展查询超过此次数封住 |
| 预算账本保留 | 30 天 | 旧于此时间的账本会被归档 |

### A4. 数据库表关系速查表

```
rca_tool_invocation (调用账本)
  ├─ operation_id (PK)
  ├─ run_id (FK → rca_run)
  ├─ tool_name + tool_version
  ├─ action_digest
  ├─ state (PENDING/SUCCESS/FAILED/UNKNOWN)
  └─ result_ref (FK → rca_evidence.evidence_id)

rca_evidence (证据)
  ├─ evidence_id (PK)
  ├─ run_id (FK → rca_run)
  ├─ evidence_type
  ├─ source
  └─ content (BYTEA 或对象存储引用)

run_budget (预算)
  ├─ run_id + kind (PK)
  ├─ limit
  ├─ consumed
  └─ state (RESERVED/COMMITTED/PROVISIONAL)

UNIQUE 约束：
  rca_tool_invocation: (run_id, task_id, attempt_id, call_seq, tool_name)

条件更新：
  rca_tool_invocation: WHERE state = 'PENDING' (保护终态)
  run_budget: WHERE consumed + ? <= limit (保护额度)
```

### A5. 关键方法调用顺序速查表

#### 工具调用完整流程

```java
// 1. 预留预算
budgetGate.call(estimates, ..., () -> {
    
    // 2. 记录账本 PENDING
    ledger.open(identity);
    
    // 3. 执行查询
    byte[] body = gateway.invoke(invocation);
    
    // 4. 保存证据
    evidence.save(envelope);
    
    // 5. 记录引用
    ledger.markResultRef(operationId, evidenceId);
    
    // 6. 标记成功
    ledger.succeed(operationId);
    
    return evidenceId;
}, ...);
```

#### 网关执行流程

```java
// 1. 查找工具
Registration reg = registry.find(name, version);

// 2. 检查权限
policy.allows(name);

// 3. 校验参数
validator.validate(definition, args);

// 4. 计算摘要
String digest = ActionDigest.of(...);

// 5. 检查风险
if (!risk.executable()) {
    recordIntent(...);
    return VALIDATE_ONLY;
}

// 6. 提交执行
byte[] body = executeWithDeadline(...);

// 7. 检查大小
if (body.length > limit) throw ...;

return new Result(EXECUTED, digest, body);
```

---

## 结语

这份文档从一个真实的告警出发，详细讲解了工具侧从定义、注册、执行到账本、预算、证据的完整实现。每个设计决策都有明确的理由，每个实现细节都能追溯到具体的问题。

**记住三点：**

1. **设计不是凭空产生的**，而是在约束下解决实际问题的结果
2. **完美的系统不存在**，重要的是理解权衡和代价
3. **诚实比完美更重要**，承认不足比夸大成果更能赢得信任

希望这份文档能帮助你在面试中清晰、深入、诚实地讲述你的工作。

**最后，祝你面试顺利！**

---

**文档版本**：v1.0  
**基线提交**：`67dc8ff78ce934f058da85364cbca3352ba71e1f`  
**整理日期**：2026 年 9 月 13 日  
**总字数**：约 55,000 字  
**阅读时间**：约 180 分钟  
**面试讲述时间（精简版）**：约 30-45 分钟

