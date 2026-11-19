package com.omsweb.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;

public record BillingRule(
    long id, long schemeId, FeeType type, Mode mode, BigDecimal rate, LocalDate effectiveFrom,
    int dueDays, int cycleMonths, RoundingMode roundingMode
) {
    public enum Mode { FIXED, PERCENT }
}
