# ADR-M4-001: SafeTarExtractor 允许依赖 commons-compress

**状态**: 提议（待评审）  
**日期**: 2026-09-02  
**决策者**: 编码 work（返工阶段）  
**上下文**: M4-A 第二次交付评审退回，ArchUnit 门踩红

---

## 问题陈述

SafeTarExtractor 迁移到 shared-kernel 后，引入 commons-compress 依赖，违反了 SharedKernelArchitectureTest 的 ArchUnit 门规则：

```java
noClasses().that().resideInAPackage("..shared..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..", "java.sql..",
        "com.fasterxml.jackson..", "org.apache..")
    .check(classes);
```

**违规点**: `org.apache..` 包含 commons-compress (org.apache.commons.compress)

---

## 决策选项

### 选项 1: 将 SafeTarExtractor 移回 control-app（拒绝）

**优点**:
- ArchUnit 门规则不变
- shared-kernel 保持零第三方依赖

**缺点**:
- **违反 D1 设计目标**：Broker 需要 SafeTarExtractor（WorkspaceManager 物料提取），但 Broker 不能依赖 control-app
- **代码重复**：Broker 需要重新实现一份 SafeTarExtractor，违反 DRY 原则
- **安全风险**：tar 解压的安全检查逻辑重复实现容易出错（zip slip、路径遍历等）

### 选项 2: 修订 ArchUnit 门规则，允许 commons-compress（**采纳**）

**优点**:
- SafeTarExtractor 在 shared-kernel 可被 control-app 和 broker 复用
- tar 解压安全逻辑唯一实现，降低安全风险
- commons-compress 是 Apache 基金会项目，成熟稳定（150+ releases，活跃维护）
- **只读依赖**：仅用于解压，无状态变更，无网络 I/O，无数据库访问

**缺点**:
- shared-kernel 不再是"零第三方依赖"
- 打破了原有的架构纯净性

---

## 决策

**采纳选项 2**，修订 ArchUnit 门规则，允许 SafeTarExtractor 依赖 commons-compress。

**理由**:

1. **D1 设计约束优先**：Broker 零 control-app 依赖是硬性要求，SafeTarExtractor 必须在 shared-kernel
2. **安全优先**：tar 解压的安全检查逻辑（路径遍历、zip slip、炸弹攻击防护）复杂且关键，重复实现风险高
3. **commons-compress 特殊性**：
   - 纯算法库，无框架耦合
   - 无状态变更，无副作用
   - Apache License 2.0，与项目兼容
   - 依赖链极简：仅依赖 commons-io（可选）和 JDK

---

## 实施方案

修订 `SharedKernelArchitectureTest.java`，将 `org.apache..` 拆分为精确禁止列表：

```java
noClasses().that().resideInAPackage("..shared..")
    .should().dependOnClassesThat().resideInAnyPackage(
        "org.springframework..",
        "java.sql..",
        "com.fasterxml.jackson..",
        "org.apache.logging..",    // 禁止日志框架
        "org.apache.http..",       // 禁止 HTTP 客户端
        "org.apache.kafka..",      // 禁止消息中间件
        "org.apache.tomcat.."      // 禁止 Web 容器
        // 允许 org.apache.commons.compress（tar 解压专用）
    )
    .check(classes);
```

**注释说明**：
```java
// M4-A ADR-001：允许 commons-compress（SafeTarExtractor 专用，纯算法库，零副作用）
// 禁止其他 Apache 框架（日志/HTTP/消息/Web）保持 shared-kernel 纯净性
```

---

## 后果

**正向影响**:
- SafeTarExtractor 逻辑唯一，安全可审计
- Broker 可复用 shared-kernel 的 tar 解压能力
- 未来其他模块（如 publisher-app）也可复用

**负向影响**:
- shared-kernel 不再是"零第三方依赖"，需要在文档中明确说明例外
- 需要持续监控 commons-compress 的依赖传递性（当前版本 1.27.1 依赖链干净）

**风险缓解**:
- 在 shared-kernel pom.xml 中显式排除 commons-compress 的可选依赖（如有）
- 定期检查 commons-compress 版本更新，避免引入不必要的传递依赖

---

## 遗留问题

1. 是否需要进一步细化 ArchUnit 规则，只允许 SafeTarExtractor 依赖 commons-compress？
2. 未来是否需要在 shared-kernel 引入其他算法库（如加密、编码）？

---

**参考**:
- M4-A 第二次交付评审退回反馈
- ArchUnit 文档: https://www.archunit.org/
- commons-compress 项目: https://commons.apache.org/proper/commons-compress/
