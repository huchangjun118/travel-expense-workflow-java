package com.github.huchangjun118.travelexpenseworkflowjava.repository;

import com.github.huchangjun118.travelexpenseworkflowjava.domain.FxRate;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface FxRateRepository extends JpaRepository<FxRate, Long> {
    Optional<FxRate> findByBaseCurrencyAndQuoteCurrency(String baseCurrency, String quoteCurrency);
}
