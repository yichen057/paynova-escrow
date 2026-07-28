# PayNova 1.0 — 开源项目调研与实现方案

> ⚠️ **Status: Superseded（2026-07-25）**
> 本文第 1–2 节仅作为开源调研记录保留。第 3–6 节的商户收单实现方案（payment_orders/payment_attempts、MERCHANT 角色、PaymentChannel、收款码、风控、DECLINED/ERROR/UNKNOWN、八张表）**已被《PayNova_Escrow_详细设计文档》取代，不作为开发依据**。
> 唯一实施依据（Source of Truth）：`docs/PayNova_Escrow_详细设计文档_v1.2.md`。其中商户收单、Connector/attempt、风控等内容仅在 Phase 2 讨论时按新文档流程重新评估，不得直接回搬。

> 目标：基于 DAMG7350 期末项目的 PayNova 安全架构，实现一个"小而完整"的模拟支付平台，作为美西 SDE Intern 求职的核心简历项目
> 日期：2026-07-25 ｜ 状态：**待确认，未动工**
> 时间线约束：8 月开始投递 → 项目需在 8 月第一周内完成并上线 GitHub

---

## 1. 你的 PayNova 目标架构（回顾）

从 `PayNova_Architecture_v2.drawio` 提取的组件清单：

| 分区 | 组件 |
|---|---|
| DMZ | CloudFlare CDN/DDoS、WAF、API Gateway（限流/认证/TLS）、HMAC 接口签名层、LB |
| 应用区 | Merchant Dashboard (React)、Payment API (REST+mTLS)、Transaction Engine（支付+Tokenization）、Fraud Detection（ML 评分）、Kafka、Consul/Vault/Sentinel 服务治理 |
| 数据区 (CDE) | PostgreSQL 主从（AES-256）、Token Vault、HSM/KMS、Redis、S3 审计、Elasticsearch |
| 安全管理区 | IAM（Okta，MFA+RBAC+SSO）、日志管道 Filebeat→Kafka→Logstash→Splunk SIEM、Vault、Nessus |
| 外部 | 发卡行、卡组织（Visa/MC）、收单行、KYC/欺诈情报 API |

**定位结论**：这张图保留为 **目标架构（Target Architecture）**，放进 README。实际实现采用**模块化单体（Modular Monolith）**，README 中给出"单体模块 → 目标架构组件"的演进映射表（见 §5.1）。这个"我知道生产级长什么样，但我在约束下有意识地做了取舍"的叙事，本身就是面试加分项。

---

## 2. 开源项目调研对比

调研了 7 个项目（含源码 clone 阅读 Jeepay/roncoo-pay，其余读官方架构文档）。

### 2.1 总览表

| 项目 | 定位 | 技术栈 | Stars | 活跃度 | License |
|---|---|---|---|---|---|
| [Jeepay](https://github.com/jeequan/jeepay) | 国内聚合收单网关（微信/支付宝/云闪付） | Spring Boot 3.3 + JDK17 + MyBatis-Plus + MySQL + Redis + MQ×3 | ~6.2k | 活跃（2026-05 发版） | LGPL-3.0 |
| [roncoo-pay](https://github.com/roncoo/roncoo-pay) | 收单 + **账户/账务** + 对账/结算 | Spring Boot 2.1 + JDK8 + Shiro + JSP + ActiveMQ | ~5.0k | **停更 4 年** | Apache-2.0 |
| [Hyperswitch](https://github.com/juspay/hyperswitch) | 支付编排/智能路由（120+ 连接器） | Rust (actix + Diesel) + PG + Redis + Kafka | ~43k | 非常活跃 | Apache-2.0 |
| [Kill Bill](https://github.com/killbill/killbill) | 订阅计费 + 支付编排（15 年历史） | Java + OSGi 插件 + **纯 DB 队列/总线** | ~5.6k | 稳定维护 | Apache-2.0 |
| [Apache Fineract](https://github.com/apache/fineract) | 核心银行系统（贷款/储蓄/总账） | Java 21 + Spring Boot + PG | ~2.2k | 活跃 | Apache-2.0 |
| [Blnk](https://github.com/blnkfinance/blnk) | 双分录账本引擎（钱包场景） | Go + PG + Redis | ~460 | 活跃 | Apache-2.0 |
| [Formance Ledger](https://github.com/formancehq/ledger) | 可编程金融账本 | Go + PG | ~1.2k | 活跃 | MIT |

### 2.2 各项目关键发现与优缺点

**Jeepay** —— 功能：商户/服务商体系、统一下单、退款、转账、分账、商户异步通知。架构是"共享 service 层的 3 应用单体"（payment/manager/merchant），**不是微服务**。
- ✅ 值得借鉴：**条件 UPDATE 状态机**（`UPDATE ... WHERE state=旧状态`，一行 SQL 同时解决状态约束、并发安全、回调幂等）；`IPaymentService/IRefundService/IChannelNoticeService` 渠道策略接口按 ifCode 路由；MchNotifyRecord 通知表 + 延迟递增重试（上限 6 次）；"不信任回调"的定时查单补偿任务。
- ❌ 不抄：3 个部署应用 + 4 套 MQ 适配层；真实微信/支付宝对接（需国内牌照）；**没有钱包/账本模块**（纯收单网关）；LGPL 协议大段抄代码有传染风险，只学设计。

**roncoo-pay** —— 唯一带完整**账户体系**的：账户表（可用/冻结）+ 流水表 + credit/debit/freeze/unfreeze 原子操作 + requestNo 幂等，另有对账、结算模块。
- ✅ 值得借鉴：钱包/账务领域模型；`SELECT ... FOR UPDATE` 悲观行锁扣款；DelayQueue 递增间隔通知重试（单体友好）。Apache-2.0 抄表结构无顾虑。
- ❌ 不抄：技术栈全面过时（JDK8/Shiro/JSP/fastjson）；渠道路由是 if/else 硬编码；已停止维护。

**Hyperswitch** —— 43k stars 的行业标杆。Router（六边形架构）+ Scheduler（Redis 队列异步任务）+ Drainer（Redis→PG 异步落库）。
- ✅ 值得借鉴：**payment_intent / payment_attempt 两层拆分**（重试/换通道 = 新增 attempt，intent 聚合状态）——这是整个调研里最值得抄的模型，也是 Stripe 的 API 词汇，**美国面试官一眼就懂**；Connector 接口抽象（authorize/capture/refund/query）；webhook 阶梯退避重试表（1m→5m→10m→1h→6h，HMAC 签名 + event_id 去重）。
- ❌ 不抄：Rust；Redis KV 热写 + Drainer（为 25ms 延迟设计）；120+ 真实连接器；Kafka/ClickHouse 分析。

**Kill Bill** —— 对 Spring 学生最友好的 Java 源码。核心亮点是 **killbill-commons/queue：完全用 PostgreSQL 表实现的事件总线和通知队列**（bus_events/notifications 表 + 轮询认领 + error_count 重试 + history 归档）。
- ✅ 值得借鉴：**这就是 Transactional Outbox 的完整生产级参考实现**，与"只有 PG + Spring"的选型完全同构；Payment → PaymentTransaction 建模（退款是新 transaction，不改原记录）；`PAYMENT_FAILURE` vs `PLUGIN_FAILURE` vs `UNKNOWN` 三分法（"超时≠失败"，面试金句）；externalKey 幂等；Janitor 定时修复未决状态。
- ❌ 不抄：OSGi 插件容器、订阅目录/账期/催收，全部与本项目无关。**注意它没有复式账本**。

**Fineract / Blnk / Formance（账本专项）** —— Fineract 的 `acc_gl_journal_entry` 表教科书级：借贷方向用独立 enum 列、transaction_id 分组、`reversed + reversal_id` 冲正对（不删不改）、running_balance 作为可重算缓存。Blnk 的 Balance 模型：`balance + credit_balance/debit_balance 只增累计列`（廉价对账手段）+ inflight 在途余额（两阶段交易）+ 强制唯一 reference 幂等 + 整数最小单位。Formance：transaction = 多条 posting 原子集合、`@world` 外部世界账户（资金守恒全局 SUM=0）、余额从不可变日志派生、交易哈希链防篡改。
- ❌ Fineract 整体过重（官方建议 16GB 内存）；Blnk/Formance 是 Go，只抄 schema 思想。

### 2.3 一个重要的调研发现

GitHub 上 **不存在 100+ stars 的"小而正确的 Spring Boot 双分录钱包/支付系统"**（扫描了 double-entry、wallet、payment-system 等 topic）。这个生态位是空的——意味着你把这个项目做规范（整数最小单位、不可变分录、幂等、冲正退款、并发测试），它本身就有区分度，甚至有机会攒星。

---

## 3. 设计决策：从谁身上抄什么

| 设计问题 | 采用方案 | 出处 |
|---|---|---|
| 支付建模 | `payment_orders`(intent) + `payment_attempts` 拆分 | Hyperswitch / Stripe |
| 订单状态机 | 条件 UPDATE（`WHERE status=旧值`）推进，返回 boolean | Jeepay |
| 失败语义 | DECLINED（业务失败）/ ERROR（技术失败）/ UNKNOWN（超时待查）三分 | Kill Bill |
| 账本 schema | 不可变 `ledger_entries`：一笔业务 N 行、`transaction_id` 分组、组内借贷平衡校验、BIGINT 分单位 | Formance + Fineract |
| 余额 | accounts 存快照 + version 乐观锁；扣款走 `SELECT FOR UPDATE`；entries 可 SUM 重算对账 | roncoo-pay + Blnk |
| 外部资金 | `system:cash_in` 等系统账户（=Formance 的 @world），充值/收款/退款全走双分录，全局 SUM 恒为 0 | Formance / Blnk |
| 退款 | 反向分录 + `reversal_of` 引用原 transaction_id，绝不 UPDATE 原分录 | Fineract |
| 幂等 | `Idempotency-Key` 请求头 + 唯一索引，冲突返回原结果 | Stripe 惯例 / Blnk |
| 异步通知 | **DB Outbox 表**（同事务写入）+ @Scheduled 轮询 + 阶梯退避（1m/5m/10m/1h）+ HMAC 签名 + event_id | Kill Bill 队列 + Hyperswitch 重试表 |
| 渠道抽象 | `PaymentChannel` 接口（authorize/capture/refund/query）+ MockChannel 实现 | Jeepay + Hyperswitch |
| 补偿 | @Scheduled "Janitor"：轮询 PROCESSING/UNKNOWN 的订单主动查渠道 | Kill Bill + Jeepay |
| 风控 | 同步规则引擎（限额/限频/余额）+ risk_events 落库 | 自研，对齐 PayNova SOC 叙事 |

**对美西求职的一个关键选择**：API 词汇全部用 Stripe 体系（payment_intent、idempotency key、webhook signature），产品叙事才用"Alipay 1.0 式钱包+收单"。美国面试官对 Stripe API 的熟悉度远高于支付宝。

---

## 4. 实现方案：PayNova 1.0

### 4.1 形态与技术栈

单仓库、单个 Spring Boot 3 应用（JDK 17），按 package 分模块：

```
com.paynova
 ├── auth        # JWT + RBAC（USER / MERCHANT / ADMIN）
 ├── wallet      # 账户、充值、转账
 ├── acquiring   # 商户下单、收款码、支付、查单
 ├── ledger      # 双分录账本（核心，独立模块）
 ├── refund      # 退款
 ├── risk        # 规则风控
 ├── outbox      # Outbox 轮询 + Webhook 投递
 └── channel     # PaymentChannel SPI + MockChannel
```

技术栈：Spring Boot 3 / Spring Security + JWT / Spring Data JPA / PostgreSQL / Flyway / springdoc-openapi / Docker Compose / GitHub Actions CI / JUnit 5（含并发测试）。**不用**：Kafka、Redis、Elasticsearch、K8s、微服务拆分、真实渠道（全部进 Future Work）。前端最小化：Swagger 演示 + 单页静态收银台（一个 HTML，扫码→支付→结果，录 GIF 用）。

### 4.2 单体模块 → 目标架构映射（写进 README）

| PayNova 1.0 模块 | 目标架构组件（drawio 图） |
|---|---|
| Spring Security + JWT + RBAC | API Gateway 认证 + IAM (Okta MFA/RBAC/SSO) |
| acquiring + channel SPI | Payment API Service + Transaction Engine |
| ledger | Transaction Engine 账务核心 + PostgreSQL (CDE) |
| risk + risk_events | Fraud Detection Engine (ML Scoring) |
| outbox 表 + @Scheduled | Kafka 异步消息 |
| 结构化 JSON 审计日志 | Filebeat→Kafka→Logstash→Splunk SIEM 管道 |
| Idempotency-Key + HMAC webhook 签名 | 接口安全层（HMAC Signing / MAC Verify） |
| （Future）真实渠道对接 | 卡组织/收单行/Token Vault/HSM |

### 4.3 数据模型（8 张表，Flyway 管理）

```
users            # 含 role（MERCHANT 是角色不是独立表）
accounts         # user_id, type(USER/MERCHANT/SYSTEM), balance BIGINT(cents),
                 # version(乐观锁), currency; 系统账户: system:cash_in, system:refund
payment_orders   # intent: merchant_order_no(唯一), amount, status 状态机,
                 # CREATED→PROCESSING→SUCCEEDED/DECLINED/ERROR; SUCCEEDED→REFUNDING→REFUNDED
payment_attempts # 每次执行尝试: channel, attempt_status, error_code; 重试=新行
ledger_entries   # 不可变: transaction_id 分组, account_id, direction(DEBIT/CREDIT),
                 # amount BIGINT, reversal_of(冲正引用); 无 UPDATE/DELETE
refunds          # 全额退款, 关联 payment_order, 独立状态机
risk_events      # rule_code, decision(PASS/REVIEW/REJECT), snapshot JSON
outbox_events    # event_id, type, payload, status, retry_count, next_retry_at
idempotency_keys # key 唯一索引, request_hash, response_snapshot
```

硬约束：金额一律 BIGINT 最小货币单位；`merchant_order_no`、`idempotency_key`、`ledger_entries(transaction_id, account_id, direction)` 相关唯一索引；每个 transaction_id 组内 `SUM(DEBIT)=SUM(CREDIT)`（服务层校验 + 测试断言全表 SUM=0）；不存卡号/CVV 等真实敏感数据。

### 4.4 功能范围（与上轮讨论一致，裁剪版）

用户侧：注册登录、查余额、模拟充值、转账、扫商户收款码付款、交易记录。
商户侧（MERCHANT 角色）：创建支付订单、生成收款码、查询状态、全额退款、接收 Webhook（模拟接收端一个 endpoint 即可）。
风控规则（同步执行，命中落 risk_events）：单笔 > $5,000 → REVIEW；1 分钟 > 5 笔 → REJECT；余额不足 → REJECT。
**Stretch goal（时间富余才做）**：担保交易（利用 ledger 的 HOLD/inflight 思路）、部分退款、Splunk 摄入演示截图。

### 4.5 两个杀手级演示（自动化测试固化）

1. **幂等**：同一 `Idempotency-Key` 对 `POST /payments` 重试 10 次 → 只产生 1 笔扣款、10 次返回相同响应。
2. **并发防超付**：余额 $100，两个线程同时发起 $80 支付 → 恰好一笔 SUCCEEDED、一笔 DECLINED，账本全表借贷合计仍为 0。

这两个测试写成 JUnit 用例进 CI，README 里贴运行输出。面试演示 = 跑测试 + 讲原理。

---

## 5. 执行计划（对齐 8 月投递）

**开发方式**：Claude 在云端先把工程骨架 + 核心模块 + 测试搭好（分 8–12 个有意义的 commit 交付），你的"一天"花在跑通、读懂、破坏-修复实验上——这是上轮已确认的策略。

| 时间 | 内容 |
|---|---|
| 确认方案后（本周内） | Claude 交付可运行工程：8 张表 + auth/wallet/acquiring/ledger 四模块 + 两个杀手测试跑绿 |
| 周末 Day 1 上午 | 本地跑通（docker compose up）；Swagger 走通 充值→下单→支付→退款 全链路；精读 ledger 与状态机代码 |
| 周末 Day 1 下午 | 破坏性实验：删行锁复现双花、删唯一索引复现重复扣款，再修复；跑通 outbox 重试（关掉模拟商户端观察退避） |
| 周末 Day 1 晚上 | Draw.io 画 1.0 实际架构图（与目标架构图并列）；README（含演进映射表、设计决策、测试输出）；推 GitHub |
| 8 月第 1 周晚间×2–3 | 风控规则 + risk_events、收银台 GIF、GitHub Actions 徽章、（可选）Splunk 截图 |
| 8/8 前 | 简历 bullet 定稿，项目冻结，开始投递 |

**面试防守清单**（投递前吃透）：为什么 intent/attempt 拆分；条件 UPDATE 状态机 vs 状态机框架；悲观锁 vs 乐观锁（本项目两处各用一种，正好对比）；DECLINED/ERROR/UNKNOWN 三分法；Outbox 为什么能保证"本地事务与通知最终一致"；幂等键存储与 TTL。

### 简历 Bullet（英文，可直接用）

> **PayNova — Mock Payment Platform** *(Java 17, Spring Boot 3, PostgreSQL, Flyway, Docker, GitHub Actions)*
> - Designed and built a payment platform covering merchant order creation, wallet payments, refunds, and async merchant notifications, modeled after Stripe's payment-intent/attempt API design
> - Guaranteed money conservation with an immutable double-entry ledger (integer minor units, reversal entries); enforced correctness under failure with idempotency keys, a conditional-update state machine, and row-level locking — verified by automated concurrency tests (no double-spend at 100 concurrent requests)
> - Implemented transactional-outbox webhooks with HMAC signatures and exponential-backoff retries; added rule-based risk screening with audit events aligned to my PayNova security architecture (STRIDE threat model, IAM, Splunk SIEM design)

---

## 6. 风险与裁剪预案

时间不够时的砍单顺序（从后往前砍）：风控规则 → 收银台页面（只留 Swagger）→ 退款模块（留接口 TODO）。**绝不砍**：ledger、状态机、幂等、并发测试——这四样是项目的全部含金量。

License 合规：只借鉴设计与表结构，不复制 Jeepay（LGPL）代码；自己的仓库用 MIT。

---

*调研执行：3 个并行 research agent，Jeepay/roncoo-pay 为源码级阅读，其余为官方架构文档。完整调研笔记可另发。*
