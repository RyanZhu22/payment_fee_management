package com.omsweb.fees.operations;

import java.time.LocalDate;

public record FpsRequest(
    long id,
    long feeId,
    String reference,
    LocalDate expiresOn,
    String status,
    String qrText
) {}
