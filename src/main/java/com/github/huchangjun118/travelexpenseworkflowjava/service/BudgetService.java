package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalEntry;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.JournalType;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.BusinessException;
import com.github.huchangjun118.travelexpenseworkflowjava.exception.ErrorCode;
import com.github.huchangjun118.travelexpenseworkflowjava.store.BudgetAccountStore;
import com.github.huchangjun118.travelexpenseworkflowjava.store.JournalStore;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 预算占用服务。
 * 并发安全：按账户加锁；可靠性：先写 PENDING 日志再改余额，
 * 崩溃后由 recoverPendingJournals 按策略继续完成或整体回滚。
 */
@Service
public class BudgetService {
    private final BudgetAccountStore accountStore;
    private final JournalStore journalStore;
    private final ExpenseProperties properties;
    private final ConcurrentHashMap<String, ReentrantLock> accountLocks = new ConcurrentHashMap<>();

    public BudgetService(BudgetAccountStore accountStore, JournalStore journalStore,
                         ExpenseProperties properties) {
        this.accountStore = accountStore;
        this.journalStore = journalStore;
        this.properties = properties;
    }

    /** 占用预算；超额抛 BUDGET_EXCEEDED。 */
    public void hold(String reportId, String accountId, BigDecimal amount) {
        execute(new JournalEntry(UUID.randomUUID().toString(), reportId, accountId, amount, JournalType.HOLD));
    }

    /** 释放占用；同一日志只会应用一次。 */
    public void release(String reportId, String accountId, BigDecimal amount) {
        execute(new JournalEntry(UUID.randomUUID().toString(), reportId, accountId, amount, JournalType.RELEASE));
    }

    /** 核销占用（审批通过后 held -> consumed）。 */
    public void consume(String reportId, String accountId, BigDecimal amount) {
        execute(new JournalEntry(UUID.randomUUID().toString(), reportId, accountId, amount, JournalType.CONSUME));
    }

    private void execute(JournalEntry entry) {
        journalStore.save(entry); // 写前日志
        try {
            apply(entry);
            entry.setStatus(JournalStatus.DONE);
        } catch (BusinessException e) {
            entry.setStatus(JournalStatus.ROLLED_BACK);
            throw e;
        }
    }

    /** 应用日志到账户余额，按账户加锁，幂等（已应用的日志直接跳过）。 */
    private void apply(JournalEntry entry) {
        ReentrantLock lock = accountLocks.computeIfAbsent(entry.getAccountId(), k -> new ReentrantLock());
        lock.lock();
        try {
            BudgetAccount account = accountStore.findById(entry.getAccountId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUDGET_NOT_FOUND,
                            "预算账户不存在: " + entry.getAccountId()));
            if (account.getAppliedJournalIds().contains(entry.getId())) {
                return; // 幂等：已应用
            }
            switch (entry.getType()) {
                case HOLD -> {
                    if (account.available().compareTo(entry.getAmount()) < 0) {
                        throw new BusinessException(ErrorCode.BUDGET_EXCEEDED,
                                "预算不足: account=" + account.getId()
                                        + ", available=" + account.available()
                                        + ", requested=" + entry.getAmount());
                    }
                    account.setHeld(account.getHeld().add(entry.getAmount()));
                }
                case RELEASE -> {
                    if (account.getHeld().compareTo(entry.getAmount()) < 0) {
                        throw new BusinessException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                                "可释放占用不足: account=" + account.getId());
                    }
                    account.setHeld(account.getHeld().subtract(entry.getAmount()));
                }
                case CONSUME -> {
                    if (account.getHeld().compareTo(entry.getAmount()) < 0) {
                        throw new BusinessException(ErrorCode.ILLEGAL_STATE_TRANSITION,
                                "可核销占用不足: account=" + account.getId());
                    }
                    account.setHeld(account.getHeld().subtract(entry.getAmount()));
                    account.setConsumed(account.getConsumed().add(entry.getAmount()));
                }
            }
            account.getAppliedJournalIds().add(entry.getId());
        } finally {
            lock.unlock();
        }
    }

    /** 反向补偿一条已应用的日志（用于 ROLLBACK 恢复策略）。 */
    private void compensate(JournalEntry entry) {
        ReentrantLock lock = accountLocks.computeIfAbsent(entry.getAccountId(), k -> new ReentrantLock());
        lock.lock();
        try {
            BudgetAccount account = accountStore.findById(entry.getAccountId())
                    .orElseThrow(() -> new BusinessException(ErrorCode.BUDGET_NOT_FOUND,
                            "预算账户不存在: " + entry.getAccountId()));
            if (!account.getAppliedJournalIds().contains(entry.getId())) {
                return; // 未应用过，无需补偿
            }
            switch (entry.getType()) {
                case HOLD -> account.setHeld(account.getHeld().subtract(entry.getAmount()));
                case RELEASE -> account.setHeld(account.getHeld().add(entry.getAmount()));
                case CONSUME -> {
                    account.setHeld(account.getHeld().add(entry.getAmount()));
                    account.setConsumed(account.getConsumed().subtract(entry.getAmount()));
                }
            }
            account.getAppliedJournalIds().remove(entry.getId());
        } finally {
            lock.unlock();
        }
    }

    /**
     * 崩溃恢复：识别 PENDING 日志。
     * COMPLETE：幂等重放完成；ROLLBACK：已应用的补偿、未应用的丢弃，整体回滚。
     */
    public void recoverPendingJournals() {
        List<JournalEntry> pending = journalStore.findByStatus(JournalStatus.PENDING);
        boolean complete = !"ROLLBACK".equalsIgnoreCase(properties.getRecoveryStrategy());
        for (JournalEntry entry : pending) {
            if (complete) {
                try {
                    apply(entry);
                    entry.setStatus(JournalStatus.DONE);
                } catch (BusinessException e) {
                    entry.setStatus(JournalStatus.ROLLED_BACK);
                }
            } else {
                compensate(entry);
                entry.setStatus(JournalStatus.ROLLED_BACK);
            }
        }
    }

    public BudgetAccount getAccount(String accountId) {
        return accountStore.findById(accountId)
                .orElseThrow(() -> new BusinessException(ErrorCode.BUDGET_NOT_FOUND,
                        "预算账户不存在: " + accountId));
    }
}
