package com.github.huchangjun118.travelexpenseworkflowjava.repository;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.BudgetIntent;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentStatus;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.IntentType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface BudgetIntentRepository extends JpaRepository<BudgetIntent, Long> {
    Optional<BudgetIntent> findByReportIdAndTypeAndCycle(Long reportId, IntentType type, int cycle);
    List<BudgetIntent> findByStatus(IntentStatus status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from BudgetIntent i where i.reportId = :reportId and i.type = :type and i.cycle = :cycle")
    Optional<BudgetIntent> findByReportIdAndTypeAndCycleForUpdate(@Param("reportId") Long reportId,
                                                                  @Param("type") IntentType type,
                                                                  @Param("cycle") int cycle);
}
