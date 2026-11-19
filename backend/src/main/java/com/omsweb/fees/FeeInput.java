package com.omsweb.fees;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record FeeInput(
    @NotNull(message = "请选择费用类型") FeeType type,
    @Size(max = 64, message = "申请编号最多 64 个字符") String applicationNumber,
    @NotBlank(message = "请输入计划名称") @Size(max = 120, message = "计划名称最多 120 个字符") String scheme,
    @NotBlank(message = "请输入付款人") @Size(max = 120, message = "付款人最多 120 个字符") String payer,
    @Email(message = "请输入有效的邮箱地址") @Size(max = 254, message = "邮箱地址最多 254 个字符") String payerEmail,
    @NotBlank(message = "请输入费用说明") @Size(max = 240, message = "费用说明最多 240 个字符") String description,
    @NotNull(message = "请输入费用金额")
    @DecimalMin(value = "0.01", message = "费用金额必须大于 0")
    @Digits(integer = 12, fraction = 2, message = "金额最多保留两位小数") BigDecimal amount,
    @NotNull(message = "请选择到期日") LocalDate dueDate,
    @Positive(message = "原费用编号无效") Long sourceFeeId
) {}
