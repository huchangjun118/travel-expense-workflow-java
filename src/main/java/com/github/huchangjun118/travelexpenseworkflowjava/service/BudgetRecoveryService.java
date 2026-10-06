package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetIntent;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseReport;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.ExpenseStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentType;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetAccountRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.BudgetIntentRepository;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.ExpenseReportRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Optional;

/**
 * 崩溃恢复：进程重启后扫描 PENDING 状态的预算意图，
 * 按 expense.recovery-strategy 配置处理：
 * - COMMIT：继续完成未完成的占用/释放，并把单据推进到意图记录的目标状态；
 * - ROLLBACK：整体回滚，意图标记为 ROLLED_BACK，预算与单据保持原状。
 * PENDING 意图意味着预算变动尚未生效（变动与意图提交在同一事务），因此恢复是安全的。
 */
@Service
public class BudgetRecoveryService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BudgetRecoveryService.class);

    private final BudgetIntentRepository intentRepository;
    private final BudgetAccountRepository accountRepository;
    private final ExpenseReportRepository reportRepository;
    private final AuditService auditService;
    private final ExpenseProperties properties;
    private final TransactionTemplate txTemplate;

    public BudgetRecoveryService(BudgetIntentRepository intentRepository,
                                 BudgetAccountRepository accountRepository,
                                 ExpenseReportRepository reportRepository,
                                 AuditService auditService,
                                 ExpenseProperties properties,
                                 org.springframework.transaction.PlatformTransactionManager txManager) {
        this.intentRepository = intentRepository;
        this.accountRepository = accountRepository;
        this.reportRepository = reportRepository;
        this.auditService = auditService;
        this.properties = properties;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    @Override
    public void run(ApplicationArguments args) {
        int recovered = recover();
        if (recovered > 0) {
            log.info("budget recovery completed, {} pending intent(s) processed with strategy {}",
                    recovered, properties.getRecoveryStrategy());
        }
    }

    /** 处理所有 PENDING 意图，返回处理数量。 */
    public int recover() {
        List<BudgetIntent> pending = intentRepository.findByStatus(IntentStatus.PENDING);
        for (BudgetIntent intent : pending) {
            // 每个意图在独立事务中处理，单个失败不影响其他
            txTemplate.executeWithoutResult(tx -> {
                if ("ROLLBACK".equalsIgnoreCase(properties.getRecoveryStrategy())) {
                    rollback(intent.getId());
                } else {
                    commit(intent.getId());
                }
            });
        }
        return pending.size();
    }

    /** 继续完成：应用预算变动、推进单据到目标状态、意图置为 COMMITTED。 */
    void commit(Long intentId) {
        BudgetIntent intent = intentRepository.findById(intentId).orElseThrow();
        if (intent.getStatus() != IntentStatus.PENDING) {
            return;
        }
        BudgetAccount account = accountRepository.findByIdForUpdate(intent.getAccountId())
                .orElseThrow(() -> new BusinessException(ErrorCodes.NOT_FOUND, "budget account not found"));
        if (intent.getType() == IntentType.HOLD) {
            if (account.available().compareTo(intent.getAmount()) < 0) {
                // 恢复时余额不足：无法完成，回滚该意图
                intent.setStatus(IntentStatus.ROLLED_BACK);
                intentRepository.save(intent);
                auditService.record("system", "RECOVERY", String.valueOf(intent.getReportId()),
                        "REJECTED", "insufficient budget during recovery, intent rolled back");
                return;
            }
            account.setUsedAmount(account.getUsedAmount().add(intent.getAmount()));
        } else {
            account.setUsedAmount(account.getUsedAmount().subtract(intent.getAmount()));
        }
        accountRepository.save(account);
        intent.setStatus(IntentStatus.COMMITTED);
        intentRepository.save(intent);
        advanceReport(intent);
        auditService.record("system", "RECOVERY", String.valueOf(intent.getReportId()),
                "SUCCESS", "committed pending " + intent.getType() + " intent " + intent.getId());
    }

    /** 整体回滚：预算未变动，仅需把意图标记为 ROLLED_BACK。 */
    void rollback(Long intentId) {
        BudgetIntent intent = intentRepository.findById(intentId).orElseThrow();
        if (intent.getStatus() != IntentStatus.PENDING) {
            return;
        }
        intent.setStatus(IntentStatus.ROLLED_BACK);
        intentRepository.save(intent);
        auditService.record("system", "RECOVERY", String.valueOf(intent.getReportId()),
                "SUCCESS", "rolled back pending " + intent.getType() + " intent " + intent.getId());
    }

    private void advanceReport(BudgetIntent intent) {
        Optional<ExpenseReport> opt = reportRepository.findByIdForUpdate(intent.getReportId());
        if (opt.isEmpty()) {
            return;
        }
        ExpenseReport report = opt.get();
        ExpenseStatus target = ExpenseStatus.valueOf(intent.getTargetReportStatus());
        // 仅当单据仍停留在前置状态时才推进，避免覆盖后续操作
        boolean advanceable = switch (target) {
            case SUBMITTED -> report.getStatus() == ExpenseStatus.DRAFT
                    || report.getStatus() == ExpenseStatus.REJECTED
                    || report.getStatus() == ExpenseStatus.WITHDRAWN;
            case REJECTED, WITHDRAWN -> report.getStatus() == ExpenseStatus.SUBMITTED;
            default -> false;
        };
        if (advanceable) {
            report.setStatus(target);
            report.setCurrentLevel(target == ExpenseStatus.SUBMITTED ? 0 : -1);
            reportRepository.save(report);
        }
    }
}
