# 项目总览

## 1. 项目简介

z-llm-gateway 是 z-opc-foundation 的 **LLM 网关**：对外一张 OpenAI Chat Completions / Anthropic Messages
双协议面，对内一张多 vendor 凭据池。

它把「各家模型 API 形状不一样、密钥散落、用量没人记账」这三件事收在一层：调用方只会说 OpenAI 或
Anthropic 的话，网关负责鉴权（自己签发的 ApiKey）、模型路由、采样参数按 vendor 下沉、同 vendor 多凭据
failover 与冷却、RPM/TPM/并发三口径限流，以及按 token 记账。上游真正打 HTTP 的那段代码**不在本仓** ——
由 `z-agent-kernel-llm` 的 provider 承担，本仓只做编排。

**Maven 坐标：** `io.github.yuku123:z-llm-gateway:${revision}`（当前 `0.1.8`，根 POM `packaging=pom`），
父项目 `io.github.yuku123:z-boot-parent:1.1.0`（`<relativePath/>` 留空）。

**技术栈：** Java 8 + Spring Boot 2.7.18 + `javax.servlet` + Maven 多模块（5 个 reactor 模块）；
配置面用 MyBatis-Plus（`z-llm-gateway-config` 一支）。

## 2. 模块架构

```
z-llm-gateway/
├── pom.xml                          # 聚合 POM：父 z-boot-parent:1.1.0，5 个 reactor 模块
├── z-llm-gateway-api/               # 契约层（17 文件 / 1423 行）：统一请求/响应模型 + 凭据模型
│                                   #   api/dto（UnifiedRequest / UnifiedResponse / UnifiedMessage /
│                                   #    UnifiedStreamChunk / ToolCall / ToolSpec / ContentPart / Choice /
│                                   #    UsageInfo / Vendor / ApiKey / LlmCredential / ErrorResponse …）
│                                   #   api/exception（GatewayException）
├── z-llm-gateway-core/              # 网关内核（25 文件 / 5279 行）
│                                   #   controller（OpenAIController / AnthropicController / GlobalExceptionHandler）
│                                   #   mapper（OpenAIRequestMapper / AnthropicRequestMapper）
│                                   #   params（ParamTranslator）/ router（ModelRouter）
│                                   #   credential（ApiKeyService / AuthorizationExtractor / LlmCredentialStore）
│                                   #   registry（LlmProviderRegistry）/ resilience（ProviderInvoker）
│                                   #   usage（UsageLedger）/ auth（AccessControl）
│                                   #   service（ChatGatewayService / EmbeddingService / MultimodalChatRelay /
│                                   #    RateLimiter / ModelNames / SchemaOnlyTool）
│                                   #   upstream（LlmHttpUpstream / UpstreamHttp / UpstreamEndpoints）
│                                   #   properties（GatewayProperties）
├── z-llm-gateway-config/            # 配置面（13 文件 / 1102 行）：provider / model / 工具模板的落库与 CRUD
│                                   #   entity（LlmProvider / LlmModel / AgentToolTemplate）
│                                   #   mapper（LlmProviderMapper / LlmModelMapper / AgentToolTemplateMapper）
│                                   #   controller（LlmConfigController，/api/z-llm/config/*）
│                                   #   config（ZLlmConfigAutoConfiguration / DataSourceConfigForLlm）
├── z-llm-gateway-starter/          # 自动装配（3 文件）：ZLlmAutoConfiguration（12 个 @Bean）+ ZLlmCoreScanConfig
├── z-llm-gateway-admin/             # 控制面（5 文件）：AdminController / AdminUiController / AdminQueryService
│                                   #   + ZLlmAdminAutoConfiguration
├── _frontend/
│   ├── z-llm-suit/                 # 管理台壳（React 19 + antd 6 + @yuku123/z-frontend-common）
│   └── z-llm-component/            # 页面层组件（axios + @ant-design/pro-components）
└── LICENSE
```

## 3. 核心功能

### 3.1 OpenAI 协议面（`OpenAIController`，前缀 `/v1`）

- `POST /v1/chat/completions` —— 非流式与 SSE 两种模式。
- `POST /v1/embeddings` —— 向量端点。
- `GET /v1/models`、`GET /v1/models/**` —— 模型清单与单模型查询。

### 3.2 Anthropic 协议面（`AnthropicController`，前缀 `/v1`）

- `POST /v1/messages` —— 含 Anthropic 原生事件帧的序流式。
- `POST /v1/messages/count_tokens` —— token 计数。

### 3.3 调用方鉴权（`credential/`）

`AuthorizationExtractor` 从 `Authorization: Bearer <key>` 取凭据，也认 Anthropic 客户端习惯的
`x-api-key`；`ApiKeyService` 校验并按 `expiresAt` 判定过期。凭据是网关自己签发的 ApiKey，
不走各上游厂商的密钥。

### 3.4 请求映射与采样参数下沉（`mapper/`、`params/`）

`OpenAIRequestMapper` / `AnthropicRequestMapper` 把两种线格式映射到统一的 `UnifiedRequest`
（Anthropic 的 `system` 上提/回落、`tool_result` 归位）。`ParamTranslator` 按 vendor 过滤并改名采样参数，
经 `providerParams` 透传 —— Anthropic 对多余的 top-level 字段直接报 400，不能无脑全量透传。

### 3.5 模型路由与 provider 装配（`router/`、`registry/`）

`ModelRouter` 按「别名 → vendor 前缀 → provider 能力判定」四步走，四步全空才返回 404。
`LlmProviderRegistry` 按 `(vendor, alias)` 缓存 kernel provider 实例，凭据变更即重建实例
（复用旧实例会把已经轮换掉的 key 留在服务路径上）。

### 3.6 failover、冷却与限流（`resilience/`、`service/RateLimiter`）

`ProviderInvoker` 在同一 vendor 的凭据池内换 key，429 / 5xx 不直接抛给调用方。
`RateLimiter` 按 ApiKey 做 RPM / TPM / 并发三口径限流，被拒时带 `Retry-After`。

### 3.7 用量记账与授权边界（`usage/`、`auth/`）

`UsageLedger` 按 `(key, model)` 累计请求数、token 与成本；未配单价的模型计入 `unpricedRequests`。
`AccessControl` 强制 ApiKey 的 `allowed-models` / `allowed-vendors` 白名单与 `max-tokens-limit`。

### 3.8 多模态直连与向量端点（`service/`）

`MultimodalChatRelay` 对带图请求绕过 kernel provider 直连上游（provider 层传不了图）。
`EmbeddingService` 适配各家形状不同的 embeddings 响应。

### 3.9 vendor 口径

`Vendor` 枚举 6 家：`openai` / `anthropic` / `deepseek` / `qwen` / `dashscope` / `gemini`。
`LlmProviderRegistry#buildProvider` 的 switch 为这 6 家分别构造 `z-agent-kernel-llm` 的 provider，
默认 base URL 引用 kernel provider 的常量，网关不另抄一份。

### 3.10 配置面与控制面

- `z-llm-gateway-config`：`/api/z-llm/config/*` 下 `provider` / `model` / `tool-template` 三组
  增删改查与 `page` / `list`，实体落库用 MyBatis-Plus Mapper。
- `z-llm-gateway-admin`：`/z-llm/admin`（`overview` / `api-keys` / `credentials` / `model` /
  `rate-limit` / `vendors` / `vendors/{vendor}/models` / `health`）与 `/z-llm/admin/ui`。

## 4. 对外集成 / 上下游

**对外 HTTP 边界：** 双协议面在 `/v1/*`（`POST /v1/chat/completions`、`POST /v1/embeddings`、
`GET /v1/models`、`GET /v1/models/**`、`POST /v1/messages`、`POST /v1/messages/count_tokens`）；
配置面在 `/api/z-llm/config/*`；控制面在 `/z-llm/admin/*`。

**本仓无固定端口。** 实测全仓 **0 个 `application*.yml` / `application*.properties`**、**0 个
`@SpringBootApplication`** —— 本仓是库 + starter，没有 bootable 主类，端点挂在宿主应用的端口与
context-path 之下。

**上游依赖：** 父链 `z-boot-parent` → 地板 `z-boot-dependencies` + 兄弟仓权威表 `z-boot-fleet`；
真正打上游 HTTP 的 provider 来自兄弟仓 `z-agent-kernel-llm`（本仓不直接引 SDK）。

**下游消费方：** 业务应用引 `z-llm-gateway-starter`（`z.llm.enabled=true` 才装配），
或直接引 `z-llm-gateway-core` 自建网关面。

**配置前缀：** `z.llm`（`GatewayProperties`），总开关 `z.llm.enabled`。

## 5. 技术特点

1. **网关只做编排，不做 provider：** 真正打上游 HTTP 的代码在 `z-agent-kernel-llm`，
   本仓把它当依赖注入 —— 换厂商实现不动网关，网关也不抄一份 base URL 常量。
2. **凭据变更即重建 provider 实例：** `LlmProviderRegistry` 按 `(vendor, alias)` 缓存，
   凭据一变就重建 —— 复用旧实例会把已轮换掉的 key 留在服务路径上，这是真实事故形态。
3. **采样参数必须按 vendor 过滤：** Anthropic 对多余的 top-level 字段直接报 400，
   所以 `ParamTranslator` 做的是「按 vendor 过滤 + 改名」而不是原样透传。
4. **限流是三口径不是一档：** RPM / TPM / 并发三者分开计数，被拒时带 `Retry-After`，
   调用方能区分「等一会儿」与「别试了」。
5. **未配单价的模型不静默计零：** `UsageLedger` 把它们计入 `unpricedRequests`，
   而不是当成免费 —— 成本账不会因为漏配单价而看着好看。
6. **多模态绕开 provider 层：** 带图请求在 provider 层传不了图，`MultimodalChatRelay` 直连上游，
   这是唯一一条不过 kernel 的通路。
7. **库形态而非进程形态：** 没有主类、没有配置文件、没有端口，所有能力通过 starter 挂到宿主应用上。
8. **配置面与内核分离：** provider / model / 工具模板的落库与 CRUD 在独立的 `z-llm-gateway-config`
   （引 MyBatis-Plus），只想要网关能力时不必背上数据源。

## 6. 目录结构

本仓 Java 包根为 `com.zifang.z.llm.gateway`，按 Maven 模块切分：

```
com.zifang.z.llm.gateway
├── api/          # z-llm-gateway-api：dto（统一模型 + Vendor + 凭据模型）/ exception（GatewayException）
├── core/         # z-llm-gateway-core
│                 #   controller / mapper / params / router / credential / registry / resilience /
│                 #   usage / auth / service / upstream / properties
├── config/       # z-llm-gateway-config：entity / mapper / controller / service / config / dto
├── starter/      # z-llm-gateway-starter：autoconfig（ZLlmAutoConfiguration / ZLlmCoreScanConfig）
└── admin/        # z-llm-gateway-admin：controller（AdminController / AdminUiController）/ service / config
```

**规模（`src/main/java` 实测）：** core 25 文件 / 5279 行，api 17 文件 / 1423 行，
config 13 文件 / 1102 行，admin 5 文件 / 259 行，starter 3 文件 / 159 行。

**测试：** `src/test` 共 26 个 Java 文件，覆盖双协议面映射、参数下沉、路由、限流、用量记账等。
