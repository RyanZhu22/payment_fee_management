package com.omsweb.fees.operations;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

public record SettlementReversal(
    long id, long feeId, long paymentId, String status, String reason,
    String createdBy, OffsetDateTime createdAt, String reviewedBy, OffsetDateTime reviewedAt,
    String reviewReason, BigDecimal amount, String referenceNumber, boolean legacy
) {}
