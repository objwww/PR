# AI告警分析Agent系统

## 项目背景
在电商业务场景下，运维团队每天需要处理大量告警信息，传统人工分析方式效率低下且易出错。本项目旨在构建一个生产级AI Agent系统，实现告警的智能分析、根因定位（RCA）和处理建议生成，将告警处理效率提升80%以上。

## 技术架构
**核心技术栈：** Spring Boot 3.4 + Spring AI 1.0 + PostgreSQL + Java 21 + LLM（大语言模型）

**系统设计：** 采用DDD四层架构（interfaces/application/domain/infrastructure），多模块Maven工程，实现控制面与执行面分离的微服务架构。

## 核心职责与技术亮点

### 1. 多Agent协作框架设计与实现
- **设计并实现多Agent编排系统**，包含Change Agent、Logs Agent、Metrics Agent等专项分析Agent，通过AgentRegistry实现统一注册与调度
- **实现RoleRunner执行引擎**，支持BoundedLlmRoleRunner和SingleToolRoleRunner两种运行模式，确保Agent执行的可控性和幕等性
- **设计PrimaryClaimAdmission准入控制机制**，通过RcaActionGuard实现Agent行为的安全边界控制，防止误操作
- 通过RunnerDirectory实现Agent动态发现与路由，支持不同告警类型的智能分流

### 2. LLM集成与Prompt工程
- **集成Spring AI框架**，适配OpenAI兼容端点（支持百炼等国产大模型），实现统一的RcaModelGateway抽象层
- **设计Prompt版本管理机制**，通过Skill版本演进支持Prompt的灰度发布与A/B测试，建立评测指标体系
- **实现Tool-MCP-RAG增强链路**，为Agent提供外部知识检索能力，提升根因分析准确率30%
- 设计PrimaryFinalClaimProjector结果投影器，将LLM输出结构化为领域对象

### 3. 分布式任务调度与执行
- **设计可恢复的Agent驱动引擎**，基于PostgreSQL实现分布式租约与提交栅栏机制，保证任务执行的exactly-once语义
- **实现AlertInboxProcessor消息处理器**，通过AlertIntakeLimits实现背压控制和流量整形
- **设计AlertClock时钟抽象**，支持任务超时控制和执行链路的可观测性
- 通过Flyway管理数据库版本迁移，确保多环境部署的一致性

### 4. 测试与质量保障
- **构建Harness测试框架**，设计端到端的Agent评测体系，通过Testcontainers实现测试环境隔离
- **实现Shadow差异检测**，通过对比新旧引擎输出验证改造正确性，覆盖核心场景100%
- **集成ArchUnit架构测试**，通过代码级规则强制DDD分层约束和依赖方向
- 设计评测投影契约（EV03），支持案例与证据的结构化管理和持续评测

### 5. 可观测性与运维
- **集成Micrometer + Brave**，实现分布式追踪和指标采集，支持异步边界的trace传播
- **设计AlertMetrics指标体系**，通过label allowlist控制高基数标签，避免指标爆炸
- **实现Spring Security认证机制**，支持浏览器会话和机器静态bearer双模式
- 通过Spring Boot Actuator暴露健康检查和运维端点，支持Prometheus监控集成

## 技术挑战与解决方案

### 挑战1：LLM输出的不确定性
**解决方案：** 
- 设计BoundedLlmRoleRunner限流执行器，控制单次调用token预算和重试策略
- 通过结构化Prompt模板和JSON Schema输出约束，提升结果可解析性
- 建立评测中心持续验证模型输出质量，支持Prompt迭代优化

### 挑战2：分布式环境下的任务幂等性
**解决方案：**
- 基于PostgreSQL advisory lock实现分布式租约机制
- 设计提交栅栏保证Agent执行结果的原子性提交
- 通过SequenceAllocator实现全局唯一ID分配，支持账本式审计

### 挑战3：多Agent协作的复杂性
**解决方案：**
- 设计PrimaryGatewayToolPort统一Tool调用接口，屏蔽底层数据源差异
- 通过SingleToolEvidenceAgent实现单一职责的证据收集，降低Agent复杂度
- 建立NativeRcaAgent作为协调者，编排多个专项Agent的执行流程

## 项目成果
- **告警处理效率提升80%**，平均响应时间从15分钟降至3分钟以内
- **根因定位准确率达到85%**，覆盖变更、日志、指标三大类告警场景
- **系统可用性达到99.9%**，支持每日处理10000+告警的生产级负载
- **代码质量优秀**，单元测试覆盖率85%+，集成测试全覆盖核心链路

## 技术关键词
Java 21 | Spring Boot 3.4 | Spring AI | LLM/Agent | Multi-Agent System | DDD | PostgreSQL | Distributed System | Testcontainers | Micrometer | Prompt Engineering | RAG | MCP | Tool Calling | Flyway | Maven | 分布式锁 | 幂等性设计 | 可观测性
