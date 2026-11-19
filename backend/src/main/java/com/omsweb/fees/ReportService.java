package com.omsweb.fees;

import static com.omsweb.fees.ApiMessages.text;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
class ReportService {
    private static final List<String> FEE_COLUMNS_ZH =
        List.of("费用编号", "申请编号", "计划", "付款人", "费用类型", "金额（HKD）", "到期日", "状态");
    private static final List<String> FEE_COLUMNS_EN =
        List.of("Fee ID", "Application number", "Scheme", "Payer", "Fee type", "Amount (HKD)", "Due date", "Status");
    private static final List<String> FIS_COLUMNS =
        List.of("transaction_reference", "amount", "debit_credit", "revenue_code", "due_date", "account_identifier");

    private final FeeService fees;
    private final Clock clock;

    ReportService(FeeService fees, Clock clock) {
        this.fees = fees;
        this.clock = clock;
    }

    Report report(String type, LocalDate from, LocalDate to) {
        DateRange range = new DateRange(from, to);
        return switch (type) {
            case "fees" -> feeRows(fees.list().stream()
                .filter(fee -> range.includes(fee.dueDate())).toList(), false);
            case "due" -> feeRows(fees.list().stream()
                .filter(fee -> fee.type() == FeeType.PERIODIC && range.includes(fee.dueDate())).toList(), false);
            case "surcharges" -> feeRows(fees.list().stream()
                .filter(fee -> fee.type() == FeeType.SURCHARGE && fee.status() == FeeStatus.UNPAID)
                .filter(fee -> range.includes(fee.dueDate())).toList(), false);
            case "month-end" -> monthEnd(range);
            case "non-payment" -> feeRows(fees.list().stream()
                .filter(fee -> fee.status() == FeeStatus.NON_PAYMENT && range.includes(fee.settlementDate())).toList(), false);
            case "revenue" -> revenue(range);
            case "tip" -> tip(range);
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "请选择有效的报表类型");
        };
    }

    Report fis(LocalDate from, LocalDate to) {
        var rows = paidFees(new DateRange(from, to)).stream()
            .map(fee -> List.of(
                fee.referenceNumber() == null || fee.referenceNumber().isBlank()
                    ? "FEE-%04d".formatted(fee.id()) : fee.referenceNumber(),
                fee.amount().toPlainString(), "CR", fee.type().name(), fee.dueDate().toString(), fee.scheme()
            ))
            .toList();
        return new Report(FIS_COLUMNS, rows);
    }

    private Report monthEnd(DateRange range) {
        LocalDate cutoff = range.to() == null ? YearMonth.now(clock).atEndOfMonth() : range.to();
        return feeRows(fees.list().stream()
            .filter(fee -> !fee.dueDate().isAfter(cutoff))
            .filter(fee -> fee.status() == FeeStatus.UNPAID
                || (fee.settlementDate() != null && fee.settlementDate().isAfter(cutoff)))
            .toList(), true);
    }

    private Report revenue(DateRange range) {
        var byMonth = new TreeMap<YearMonth, Revenue>();
        for (Fee fee : paidFees(range)) {
            byMonth.merge(YearMonth.from(fee.bankInDate()), new Revenue(1, fee.amount()),
                (left, right) -> new Revenue(left.count() + right.count(), left.total().add(right.total())));
        }
        var rows = byMonth.entrySet().stream().map(entry -> List.of(
            entry.getKey().toString(), Integer.toString(entry.getValue().count()), entry.getValue().total().toPlainString()
        )).toList();
        var columns = ApiMessages.isEnglish()
            ? List.of("Month", "Banked payments", "Amount received (HKD)")
            : List.of("月份", "入账笔数", "实收金额（HKD）");
        return new Report(columns, rows);
    }

    private Report tip(DateRange range) {
        var rows = fees.list().stream()
            .filter(fee -> range.includes(fee.dueDate()))
            .map(fee -> List.of("FEE-%04d".formatted(fee.id()), fee.scheme(), fee.payer(),
                fee.dueDate().toString(), fee.amount().toPlainString(), statusLabel(fee.status())))
            .toList();
        var columns = ApiMessages.isEnglish()
            ? List.of("Fee ID", "Scheme", "Payer", "Due date", "Amount (HKD)", "Status")
            : List.of("费用编号", "计划", "付款人", "到期日", "金额（HKD）", "状态");
        return new Report(columns, rows);
    }

    private List<Fee> paidFees(DateRange range) {
        return fees.list().stream()
            .filter(fee -> fee.status() == FeeStatus.PAID && range.includes(fee.bankInDate())).toList();
    }

    private Report feeRows(List<Fee> selected, boolean asOfCutoff) {
        var rows = selected.stream().map(fee -> List.of(
            "FEE-%04d".formatted(fee.id()), Objects.requireNonNullElse(fee.applicationNumber(), ""), fee.scheme(), fee.payer(),
            typeLabel(fee.type()), fee.amount().toPlainString(), fee.dueDate().toString(),
            statusLabel(asOfCutoff ? FeeStatus.UNPAID : fee.status())
        )).toList();
        return new Report(ApiMessages.isEnglish() ? FEE_COLUMNS_EN : FEE_COLUMNS_ZH, rows);
    }

    private String typeLabel(FeeType type) {
        return switch (type) {
            case APPLICATION -> text("申请费", "Application fee");
            case PERIODIC -> text("定期费用", "Periodic fee");
            case SURCHARGE -> text("附加费", "Surcharge");
        };
    }

    private String statusLabel(FeeStatus status) {
        return switch (status) {
            case UNPAID -> text("未缴", "Unpaid");
            case PAID -> text("已付款", "Paid");
            case NON_PAYMENT -> text("非付款结算", "Non-payment settlement");
        };
    }

    record Report(List<String> columns, List<List<String>> rows) {
        String csv() {
            StringBuilder result = new StringBuilder(csvLine(columns));
            for (List<String> row : rows) {
                result.append(csvLine(row));
            }
            return result.toString();
        }

        private static String csvLine(List<String> values) {
            return values.stream().map(value -> "\"" + value.replace("\"", "\"\"") + "\"")
                .collect(Collectors.joining(",")) + "\r\n";
        }
    }

    private record Revenue(int count, BigDecimal total) {}

    private record DateRange(LocalDate from, LocalDate to) {
        DateRange {
            if (from != null && to != null && from.isAfter(to)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "起始日期必须早于或等于结束日期");
            }
        }

        boolean includes(LocalDate date) {
            return date != null && (from == null || !date.isBefore(from)) && (to == null || !date.isAfter(to));
        }
    }
}
