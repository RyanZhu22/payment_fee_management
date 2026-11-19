package com.omsweb.fees.operations;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record DocumentRequest(
    @Positive(message = "请选择有效的费用") long feeId,
    @NotNull(message = "请选择文档类型") DocumentKind kind
) {}
