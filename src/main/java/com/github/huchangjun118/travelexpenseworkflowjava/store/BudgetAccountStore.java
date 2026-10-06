package com.github.huchangjun118.travelexpenseworkflowjava.store;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 预算账户存储。 */
@Component
public class BudgetAccountStore {
    private final ConcurrentHashMap<String, BudgetAccount> accounts = new ConcurrentHashMap<>();

    public BudgetAccount save(BudgetAccount account) {
        accounts.put(account.getId(), account);
        return account;
    }

    public Optional<BudgetAccount> findById(String id) {
        return Optional.ofNullable(accounts.get(id));
    }

    public Optional<BudgetAccount> findByDepartment(String department) {
        return accounts.values().stream()
                .filter(a -> a.getDepartment() != null && a.getDepartment().equals(department))
                .findFirst();
    }
}
