package com.omsweb.fees;

import java.math.BigDecimal;
import java.time.LocalDate;

public record Fee(
    long id,
    FeeType type,
    String applicationNumber,
    String scheme,
    String payer,
    String payerEmail,
    String description,
    BigDecimal amount,
    LocalDate dueDate,
    Long sourceFeeId,
    FeeStatus status,
    LocalDate settlementDate,
    String paymentMethod,
    LocalDate bankInDate,
    String referenceNumber,
    String receivedFrom
) {}
