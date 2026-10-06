# 差旅报销申请与审批服务

覆盖报销单全生命周期：提交（幂等）、可配置审批链、并发安全预算占用、
多币种汇率快照折算、崩溃恢复与追加式审计。基于 Spring Boot 3 / Java 17，
内存存储实现，可独立本地启动。

## 启动与测试

```bash
mvn spring-boot:run        # 本地启动，端口 8080，自动预置演示预算账户与汇率
mvn test                   # 运行全部自动化验收测试（22 个）
```

## 状态机语义

```
                 submit
        ┌──────────────────────┐
        ▼                      │
     DRAFT ──submit──► SUBMITTED ──approve(最后一级)──► APPROVED
                        │  │
                reject  │  │  withdraw(仅申请人)
                        ▼  ▼
                 REJECTED  WITHDRAWN
                        │  │
                        └──┴── resubmit ──► SUBMITTED
```

- 任一时刻单据只处于一个确定状态；上表之外的流转一律拒绝，
  返回 `ILLEGAL_STATE_TRANSITION`（HTTP 409）。
- 状态推进与审批记录一致：每级审批通过/驳回都写入对应 `ApprovalStep`
  （决定、操作人、意见），最后一级通过才进入 `APPROVED`。
- 重新提交（`REJECTED`/`WITHDRAWN` → `SUBMITTED`）会按提交时刻重新固化
  审批链与汇率快照，并重新占用预算。

## 审批链策略

- 策略规则按顺序匹配，命中第一条即生效；匹配维度：金额上限（记账币种）、
  费用类型、部门（未配置的维度不参与过滤）。
- **审批链在提交时刻解析并固化到单据上**（`approvalChain` 快照），
  之后修改策略不影响在途单据。
- 审批人角色须与当前层级角色一致（`ADMIN` 可越级），否则 403 `PERMISSION_DENIED`。

## 预算占用规则

- 账户模型：`total`（总额）、`held`（已占用）、`consumed`（已核销），
  可用额度 = `total - held - consumed`。
- 提交时 **占用**（held += 金额）；审批通过时 **核销**（held → consumed）；
  驳回/撤回时 **释放**（held -= 金额）。
- 并发安全：按账户加锁，同一账户下的并发提交/审批/撤回不会超额占用；
  超额请求被拒绝并返回可区分错误码 `BUDGET_EXCEEDED`（HTTP 409）。
- 同一单据的占用只释放一次（`holdState: NONE→HELD→RELEASED/CONSUMED`），
  重复驳回/撤回因状态机约束不会二次释放。
- **崩溃恢复**：所有余额变更先写 PENDING 账务日志再改余额、最后置 DONE。
  进程重启时 `RecoveryRunner` 识别 PENDING 日志并按策略处理：
  - `COMPLETE`（默认）：幂等重放完成（已应用的日志自动跳过，不会重复记账）；
  - `ROLLBACK`：整体回滚——已应用的补偿冲销，未应用的直接作废。
  任一步失败都不会留下部分写入（超额占用等失败会把日志置为 `ROLLED_BACK`）。

## 多币种与汇率

- 提交时按当前汇率折算到记账币种，并把 `{from,to,rate,fetchedAt,ledgerAmount}`
  快照固化到单据上，折算结果可追溯。
- 汇率缺失返回 `RATE_MISSING`，超过有效期返回 `RATE_EXPIRED`，
  均拒绝入账（HTTP 409），**绝不静默使用默认汇率**。

## 权限与审计

- 请求头：`X-User-Id`（必填）、`X-User-Role`（审批/查审计时必填）、
  `X-Idempotency-Key`（提交/重新提交时必填）。
- 权限规则：提交/建草稿任意认证用户；审批需匹配当前层级角色或 `ADMIN`；
  撤回/重新提交仅申请人；查询审计仅 `ADMIN`。
- 越权操作返回 403 `PERMISSION_DENIED` 并留下 `DENIED` 审计记录。
- 审计日志**只追加**：`AuditStore` 仅暴露 `append` 与只读 `list`，
  无修改/删除入口，读出的列表为不可变副本。

## 幂等提交

- 提交携带 `X-Idempotency-Key`；同一键的重复提交（客户端重试）返回
  **同一份单据**且 `duplicate=true`，预算只占用一次，不会报错也不会新建单据。
- 并发重试通过按键加锁保证只产生一份有效单据。

## 配置项（application.properties）

| 配置 | 默认 | 说明 |
| --- | --- | --- |
| `server.port` | 8080 | 服务端口 |
| `expense.ledger-currency` | CNY | 记账币种 |
| `expense.rate-max-age-seconds` | 86400 | 汇率最大有效秒数，超过视为过期 |
| `expense.recovery-strategy` | COMPLETE | 崩溃恢复策略：`COMPLETE` 继续完成 / `ROLLBACK` 整体回滚 |
| `expense.policy-rules[n].max-amount` | - | 规则金额上限（记账币种），不填不限 |
| `expense.policy-rules[n].expense-type` | - | 规则费用类型，不填不限 |
| `expense.policy-rules[n].department` | - | 规则部门，不填不限 |
| `expense.policy-rules[n].approver-roles[m]` | - | 命中后固化的审批角色链（按层级顺序） |

## API 一览

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/expenses` | 新建并提交（幂等键头必填） |
| POST | `/api/expenses/drafts` | 创建草稿 |
| POST | `/api/expenses/drafts/{id}/submit` | 提交草稿（幂等） |
| POST | `/api/expenses/{id}/approve` | 审批通过当前层级 |
| POST | `/api/expenses/{id}/reject` | 驳回 |
| POST | `/api/expenses/{id}/withdraw` | 撤回（仅申请人） |
| POST | `/api/expenses/{id}/resubmit` | 重新提交（幂等） |
| GET | `/api/expenses/{id}` | 查询单据（含审批链与汇率快照） |
| GET | `/api/budgets/{accountId}` | 查询预算账户 |
| GET | `/api/audit` | 查询审计日志（仅 ADMIN） |

## 测试覆盖（src/test）

- `ExpenseServiceTest`：重复提交（含并发重试）、并发超额占用、并发审批与撤回、
  非法流转、驳回释放只一次、重新提交、汇率缺失/过期、审批链固化、
  多级审批、越权拒绝与审计、审计只追加。
- `BudgetRecoveryTest`：PENDING 日志的 COMPLETE 重放（含幂等）、
  ROLLBACK 丢弃与补偿、失败不留部分写入。
- `ExpenseApiTest`：HTTP 端到端——幂等提交、完整生命周期、越权 403、
  汇率缺失错误码。
