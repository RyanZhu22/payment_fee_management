package com.omsweb.fees.operations;

import jakarta.validation.constraints.Positive;

public record SettlementReversalRequest(@Positive long feeId, String reason) {}
