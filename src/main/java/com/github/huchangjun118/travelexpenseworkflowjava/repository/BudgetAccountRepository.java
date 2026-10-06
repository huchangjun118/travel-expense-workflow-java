package com.github.huchangjun118.travelexpenseworkflowjava.repository;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetAccount;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface BudgetAccountRepository extends JpaRepository<BudgetAccount, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BudgetAccount b where b.id = :id")
    Optional<BudgetAccount> findByIdForUpdate(@Param("id") Long id);
}
