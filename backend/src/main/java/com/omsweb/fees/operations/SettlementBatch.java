package com.omsweb.fees.operations;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

public record SettlementBatch(
    long id, String type, String filename, String checksum, String status,
    int total, int success, int failed, BigDecimal amount,
    String createdBy, OffsetDateTime createdAt, String reviewedBy, OffsetDateTime reviewedAt,
    String reason, List<Row> rows
) {
    public record Row(
        long id, int row, String reference, Long feeId, BigDecimal amount,
        LocalDate settlementDate, String paymentMethod, LocalDate bankInDate,
        String referenceNumber, String receivedFrom, boolean success, String message,
        String feeSnapshot, String snapshotHash
    ) {}
}
