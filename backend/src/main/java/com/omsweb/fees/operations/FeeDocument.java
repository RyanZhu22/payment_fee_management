package com.omsweb.fees.operations;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record FeeDocument(
    long id,
    long feeId,
    DocumentKind kind,
    String number,
    OffsetDateTime generatedAt,
    LocalDate generatedOn,
    DeliveryStatus deliveryStatus,
    String recipientEmail,
    LocalDate bankInDate,
    BigDecimal amount,
    @JsonIgnore String storageKey,
    boolean valid,
    OffsetDateTime voidedAt,
    String voidReason,
    Long reversalId
) {}
