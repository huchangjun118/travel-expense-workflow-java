# 差旅报销申请提交与审批服务

基于 Spring Boot 3 + Spring Data JPA + H2（文件库）的差旅报销服务，覆盖单据生命周期、
预算占用、多币种换算与追加式审计。可独立本地启动，全部验收项由自动化测试覆盖。

## 快速开始

```bash
mvn package -DskipTests
java -jar target/travel-expense-workflow-java-1.0-SNAPSHOT.jar   # 监听 8080，数据落盘 ./data
mvn test                                                          # 运行全部 29 个验收测试
```

## 状态机语义

```
DRAFT --submit--> SUBMITTED --approve(逐级)--> APPROVED
                    |--reject--> REJECTED --resubmit--> SUBMITTED
                    └--withdraw--> WITHDRAWN --resubmit--> SUBMITTED
```

- 提交时由策略解析审批链并**固化到单据**（`approval_step` 表），后续策略变更不影响在途单据。
- 每次提交（含重新提交）`submitCycle` 递增，预算按周期占用/释放。
- 非法流转返回 `409 ILLEGAL_STATE_TRANSITION`；任一时刻单据只处于一个确定状态，
  状态推进与审批记录（`approval_record`）在同一事务写入，保持一致。
- 审批通过（APPROVED）后预算占用转为实际消耗，不再释放。

## 幂等提交

- `idempotencyKey` 上有唯一约束。重复提交返回 `200 {duplicated: true, report: 同一单据}`，
  不新建单据、不重复占用预算；并发重复提交由唯一约束兜底，只有一个事务成功创建。
- 上次提交中途失败留下的 DRAFT 单据，重试同一 key 会继续完成提交而不是报错。

## 预算占用规则

- 占用/释放采用**两阶段意图**（`budget_intent`）：
  1. 独立事务写入 `PENDING` 意图（`(reportId, type, cycle)` 唯一，天然幂等）；
  2. 业务事务内对预算账户加**悲观锁**后应用变动，并将意图置为 `COMMITTED`。
- 预算变动与单据状态推进处于**同一事务**，失败整体回滚，不留部分写入。
- 余额不足返回 `409 BUDGET_EXCEEDED`（可区分原因），意图标记 `ROLLED_BACK`，预算不变。
- 驳回/撤回时按周期释放，唯一约束 + 意图行锁保证**只释放一次**。
- 并发提交/审批/撤回通过账户悲观锁与意图行锁串行化，不会超额占用。

## 崩溃恢复

进程在"写入 PENDING 意图"与"应用变动"之间崩溃时，重启后 `BudgetRecoveryService`
扫描 `PENDING` 意图，按 `expense.recovery-strategy` 处理：

- `COMMIT`（默认）：继续完成占用/释放，并把单据推进到意图记录的目标状态；
- `ROLLBACK`：整体回滚，意图标记 `ROLLED_BACK`，预算与单据保持原状（单据可重试）。

恢复是幂等的：已处理的意图不会重复应用。

## 多币种与汇率快照

- 提交时按 `fx_rate` 表把金额折算到记账币种（`expense.book-currency`），
  汇率与汇率日期作为**快照**固化在单据上（`fxRateSnapshot`/`fxRateDate`），折算结果可追溯。
- 汇率缺失或账龄超过 `expense.fx-max-age-days` 时返回 `409 FX_RATE_UNAVAILABLE` 拒绝入账，
  **绝不静默使用默认汇率**；换算发生在任何写入之前，失败不留半成品。

## 权限与审计

- 请求头 `X-User-Id` / `X-User-Role` 标识操作者。撤回/重新提交仅申请人本人；
  审批人角色必须匹配当前审批层级要求的角色。越权返回 `403 PERMISSION_DENIED`。
- 关键操作（含越权与被拒尝试）写入 `audit_log`，使用独立事务（REQUIRES_NEW），
  业务回滚不影响审计留痕。审计只追加：仓储层不暴露任何修改/删除入口。

## 配置项

| 配置 | 默认 | 说明 |
|---|---|---|
| `server.port` | 8080 | 服务端口 |
| `spring.datasource.url` | `jdbc:h2:file:./data/expense-db` | H2 文件库，重启不丢数据 |
| `expense.book-currency` | CNY | 记账币种 |
| `expense.fx-max-age-days` | 7 | 汇率最大滞后天数，超过拒绝入账 |
| `expense.recovery-strategy` | COMMIT | 崩溃恢复策略：COMMIT / ROLLBACK |
| `approval.policy.rules[i].max-amount` | - | 规则金额上限（记账币种，含） |
| `approval.policy.rules[i].expense-type` | - | 规则匹配的费用类型 |
| `approval.policy.rules[i].department` | - | 规则匹配的部门 |
| `approval.policy.rules[i].levels[j]` | - | 该规则的审批层级（角色序列） |
| `approval.policy.default-levels` | [MANAGER] | 无规则匹配时的默认审批层级 |

策略匹配：取所有条件都匹配的规则中**最具体**的一条（部门/费用类型/金额权重 4/2/1，
并列取先配置者）；无匹配用默认层级。

## API 一览

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/expenses` | 幂等提交（头：`X-User-Id`） |
| GET | `/api/expenses/{id}` | 查询单据 |
| POST | `/api/expenses/{id}/approve` | 审批通过（头：`X-User-Id`,`X-User-Role`） |
| POST | `/api/expenses/{id}/reject` | 驳回（释放预算） |
| POST | `/api/expenses/{id}/withdraw` | 撤回（仅申请人，释放预算） |
| POST | `/api/expenses/{id}/resubmit` | 重新提交（仅申请人，REJECTED/WITHDRAWN） |
| POST | `/api/admin/budgets` | 创建预算账户 |
| POST | `/api/admin/fx-rates` | 维护汇率 |
| GET | `/api/admin/audits?resourceId=` | 查询审计轨迹 |

错误响应统一为 `{"code": ..., "message": ...}`，错误码：
`NOT_FOUND`(404)、`PERMISSION_DENIED`(403)、
`BUDGET_EXCEEDED`/`FX_RATE_UNAVAILABLE`/`ILLEGAL_STATE_TRANSITION`(409)。

## 测试覆盖（29 个用例）

- `ExpenseSubmissionTest`：重复/并发重复提交幂等、预算只占用一次、汇率缺失/过期拒绝、
  外币折算快照、审批链按策略解析并固化。
- `BudgetConcurrencyTest`：并发提交不超额、超额原因可区分、并发驳回/撤回只释放一次、
  撤回后重新提交重新占用。
- `StateMachineTest`：多级审批逐级推进、非法流转拒绝、驳回/撤回/重新提交、
  状态与审批记录一致。
- `RecoveryTest`：PENDING 意图按 COMMIT/ROLLBACK 恢复、恢复幂等。
- `SecurityAuditTest`：越权拒绝并留痕、审计按序追加、业务回滚不丢审计。
- `ExpenseApiTest`：REST 端到端全生命周期与错误码。
