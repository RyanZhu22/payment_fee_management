package com.omsweb.fees.operations;

import jakarta.validation.constraints.Positive;

public record FpsRequestInput(
    @Positive(message = "请选择有效的费用") long feeId
) {}
