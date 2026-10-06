package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetIntent;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentType;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetIntentRepository;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * 预算占用/释放。两阶段：
 * 1) prepare* 在独立事务中写入 PENDING 意图（幂等，唯一约束去重）；
 * 2) apply* 在调用方事务中对账户加悲观锁并应用变动，再把意图置为 COMMITTED。
 * 预算变动与单据状态推进处于同一事务，失败整体回滚，不留部分写入；
 * 两阶段之间崩溃留下的 PENDING 意图由恢复流程识别处理。
 */
@Service
public class BudgetService {

    private final BudgetAccountRepository accountRepository;
    private final BudgetIntentRepository intentRepository;
    private final TransactionTemplate transactionTemplate;

    public BudgetService(BudgetAccountRepository accountRepository,
                         BudgetIntentRepository intentRepository,
                         org.springframework.transaction.PlatformTransactionManager txManager) {
        this.accountRepository = accountRepository;
        this.intentRepository = intentRepository;
        this.transactionTemplate = new TransactionTemplate(txManager);
    }

    /** 第一阶段：写入 PENDING 占用意图。已存在则直接复用，保证只占用一次。 */
    public BudgetIntent prepareHold(Long reportId, Long accountId, BigDecimal amount, int cycle) {
        try {
            return requiresNew(tx -> {
                Optional<BudgetIntent> existing = intentRepository
                        .findByReportIdAndTypeAndCycle(reportId, IntentType.HOLD, cycle);
                if (existing.isPresent()) {
                    BudgetIntent intent = existing.get();
                    if (intent.getStatus() == IntentStatus.ROLLED_BACK) {
                        // 上次因余额不足等原因回滚，重置后重试
                        intent.setStatus(IntentStatus.PENDING);
                        return intentRepository.save(intent);
                    }
                    return intent;
                }
                return intentRepository.save(new BudgetIntent(reportId, accountId, amount,
                        IntentType.HOLD, cycle,
                        com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus.SUBMITTED.name()));
            });
        } catch (DataIntegrityViolationException e) {
            // 并发下唯一约束冲突：其他线程已写入，重查即可
            return requiresNew(tx -> intentRepository
                    .findByReportIdAndTypeAndCycle(reportId, IntentType.HOLD, cycle).orElseThrow());
        }
    }

    /** 第二阶段：应用占用。余额不足时把意图置为 ROLLED_BACK 并抛出 BUDGET_EXCEEDED。 */
    @Transactional
    public void applyHold(Long reportId, int cycle) {
        BudgetIntent intent = intentRepository.findByReportIdAndTypeAndCycleForUpdate(reportId, IntentType.HOLD, cycle)
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "hold intent not found"));
        if (intent.getStatus() == IntentStatus.COMMITTED) {
            return; // 已占用过，幂等返回
        }
        BudgetAccount account = accountRepository.findByIdForUpdate(intent.getAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "budget account not found"));
        if (account.available().compareTo(intent.getAmount()) < 0) {
            markRolledBack(intent.getId());
            throw new BusinessException(ErrorCodes.BUDGET_EXCEEDED,
                    "budget exceeded: account=" + account.getId()
                            + " available=" + account.available() + " required=" + intent.getAmount());
        }
        account.setUsedAmount(account.getUsedAmount().add(intent.getAmount()));
        accountRepository.save(account);
        intent.setStatus(IntentStatus.COMMITTED);
        intentRepository.save(intent);
    }

    /** 释放第一阶段：写入 PENDING 释放意图。仅当本周期占用已提交且未释放过。 */
    public Optional<BudgetIntent> prepareRelease(Long reportId, int cycle, String targetReportStatus) {
        try {
            return requiresNew(tx -> {
                Optional<BudgetIntent> hold = intentRepository
                        .findByReportIdAndTypeAndCycle(reportId, IntentType.HOLD, cycle);
                if (hold.isEmpty() || hold.get().getStatus() != IntentStatus.COMMITTED) {
                    return Optional.<BudgetIntent>empty(); // 没有已生效的占用，无需释放
                }
                Optional<BudgetIntent> existing = intentRepository
                        .findByReportIdAndTypeAndCycle(reportId, IntentType.RELEASE, cycle);
                if (existing.isPresent()) {
                    return existing;
                }
                BudgetIntent h = hold.get();
                return Optional.of(intentRepository.save(
                        new BudgetIntent(reportId, h.getAccountId(), h.getAmount(), IntentType.RELEASE,
                                cycle, targetReportStatus)));
            });
        } catch (DataIntegrityViolationException e) {
            return requiresNew(tx -> intentRepository
                    .findByReportIdAndTypeAndCycle(reportId, IntentType.RELEASE, cycle));
        }
    }

    private <T> T requiresNew(org.springframework.transaction.support.TransactionCallback<T> action) {
        TransactionTemplate template = new TransactionTemplate(transactionTemplate.getTransactionManager());
        template.setPropagationBehavior(org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        return template.execute(action);
    }

    /** 释放第二阶段：应用释放。唯一约束 + 状态检查保证只释放一次。 */
    @Transactional
    public void applyRelease(Long reportId, int cycle) {
        Optional<BudgetIntent> opt = intentRepository.findByReportIdAndTypeAndCycleForUpdate(reportId, IntentType.RELEASE, cycle);
        if (opt.isEmpty()) {
            return;
        }
        BudgetIntent intent = opt.get();
        if (intent.getStatus() == IntentStatus.COMMITTED) {
            return; // 已释放过，幂等返回
        }
        BudgetAccount account = accountRepository.findByIdForUpdate(intent.getAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "budget account not found"));
        account.setUsedAmount(account.getUsedAmount().subtract(intent.getAmount()));
        accountRepository.save(account);
        intent.setStatus(IntentStatus.COMMITTED);
        intentRepository.save(intent);
    }

    private void markRolledBack(Long intentId) {
        transactionTemplate.executeWithoutResult(tx -> {
            BudgetIntent intent = intentRepository.findById(intentId).orElseThrow();
            intent.setStatus(IntentStatus.ROLLED_BACK);
            intentRepository.save(intent);
        });
    }
}
