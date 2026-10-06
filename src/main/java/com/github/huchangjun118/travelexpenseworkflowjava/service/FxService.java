package com.github.huchangjun118.travelexpenseworkflowjava.service;

import com.github.huchangjun118.travelexpenseworkflowjava.config.ExpenseProperties;
import com.github.huchangjun118.travelexpenseworkflowjava.domain.FxRate;
import com.github.huchangjun118.travelexpenseworkflowjava.repository.FxRateRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

@Service
public class FxService {

    public record Conversion(BigDecimal bookedAmount, BigDecimal rate, LocalDate rateDate) {
    }

    private final FxRateRepository fxRateRepository;
    private final ExpenseProperties properties;

    public FxService(FxRateRepository fxRateRepository, ExpenseProperties properties) {
        this.fxRateRepository = fxRateRepository;
        this.properties = properties;
    }

    /**
     * 折算到记账币种。汇率缺失或过期时抛出 FX_RATE_UNAVAILABLE，绝不使用默认汇率。
     */
    public Conversion convert(BigDecimal amount, String currency) {
        String bookCurrency = properties.getBookCurrency();
        if (bookCurrency.equals(currency)) {
            return new Conversion(amount.setScale(4, RoundingMode.HALF_UP), BigDecimal.ONE, LocalDate.now());
        }
        FxRate fxRate = fxRateRepository.findByBaseCurrencyAndQuoteCurrency(currency, bookCurrency)
                .orElseThrow(() -> new BusinessException(ErrorCodes.FX_RATE_UNAVAILABLE,
                        "missing fx rate: " + currency + "->" + bookCurrency));
        long ageDays = ChronoUnit.DAYS.between(fxRate.getRateDate(), LocalDate.now());
        if (ageDays > properties.getFxMaxAgeDays()) {
            throw new BusinessException(ErrorCodes.FX_RATE_UNAVAILABLE,
                    "stale fx rate: " + currency + "->" + bookCurrency
                            + " rateDate=" + fxRate.getRateDate() + " ageDays=" + ageDays);
        }
        BigDecimal booked = amount.multiply(fxRate.getRate()).setScale(4, RoundingMode.HALF_UP);
        return new Conversion(booked, fxRate.getRate(), fxRate.getRateDate());
    }
}
