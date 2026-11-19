package com.omsweb.fees;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReportServiceTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"));

    private final FeeService fees = mock(FeeService.class);
    private final ReportService reports = new ReportService(fees, CLOCK);

    @Test
    void revenueGroupsByBankInMonth() {
        when(fees.list()).thenReturn(List.of(
            fee(1, FeeType.PERIODIC, FeeStatus.PAID, "2026-10-15", "2026-10-10", "2026-10-12", "100.00"),
            fee(2, FeeType.PERIODIC, FeeStatus.PAID, "2026-10-15", "2026-10-14", "2026-10-20", "250.00"),
            fee(3, FeeType.APPLICATION, FeeStatus.PAID, "2026-11-05", "2026-11-03", "2026-11-04", "80.00"),
            fee(4, FeeType.PERIODIC, FeeStatus.UNPAID, "2026-11-30", null, null, "999.00")));

        var report = reports.report("revenue", null, null);

        assertThat(report.columns()).containsExactly("月份", "入账笔数", "实收金额（HKD）");
        assertThat(report.rows()).containsExactly(
            List.of("2026-10", "2", "350.00"),
            List.of("2026-11", "1", "80.00"));
    }

    @Test
    void monthEndUsesCutoffAndIncludesSettledAfterCutoff() {
        when(fees.list()).thenReturn(List.of(
            fee(1, FeeType.PERIODIC, FeeStatus.UNPAID, "2026-10-20", null, null, "100.00"),
            fee(2, FeeType.PERIODIC, FeeStatus.PAID, "2026-10-20", "2026-11-02", "2026-11-02", "200.00"),
            fee(3, FeeType.PERIODIC, FeeStatus.UNPAID, "2026-11-20", null, null, "300.00"),
            fee(4, FeeType.SURCHARGE, FeeStatus.NON_PAYMENT, "2026-10-20", null, null, "50.00")));

        var report = reports.report("month-end", null, LocalDate.parse("2026-10-31"));

        assertThat(report.rows()).extracting(row -> row.get(0)).containsExactly("FEE-0001", "FEE-0002");
        assertThat(report.rows()).allMatch(row -> row.get(row.size() - 1).equals("未缴"));
    }

    @Test
    void fisExportUsesTransactionFieldsAndFallsBackToFeeReference() {
        when(fees.list()).thenReturn(List.of(
            fee(7, FeeType.PERIODIC, FeeStatus.PAID, "2026-10-15", "2026-10-14", "2026-10-16", "7800.00")));

        var report = reports.fis(LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31"));

        assertThat(report.columns()).containsExactly(
            "transaction_reference", "amount", "debit_credit", "revenue_code", "due_date", "account_identifier");
        assertThat(report.rows()).containsExactly(
            List.of("FEE-0007", "7800.00", "CR", "PERIODIC", "2026-10-15", "维港退休计划"));
    }

    @Test
    void rejectsReversedDateRange() {
        assertThatThrownBy(() -> reports.report("fees", LocalDate.parse("2026-12-01"), LocalDate.parse("2026-11-01")))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("起始日期");
    }

    @Test
    void csvEscapesQuotesAndUsesCrlf() {
        when(fees.list()).thenReturn(List.of(fee(1, FeeType.APPLICATION, FeeStatus.UNPAID, "2026-11-10", null, null,
            "100.00", "启航\"测试\"服务")));

        String csv = reports.report("fees", null, null).csv();

        assertThat(csv).startsWith("\"费用编号\",\"申请编号\"");
        assertThat(csv).contains("\"启航\"\"测试\"\"服务\"");
        assertThat(csv).endsWith("\r\n");
    }

    private Fee fee(long id, FeeType type, FeeStatus status, String dueDate, String settlementDate, String bankInDate,
                    String amount) {
        return fee(id, type, status, dueDate, settlementDate, bankInDate, amount, "维港受托有限公司");
    }

    private Fee fee(long id, FeeType type, FeeStatus status, String dueDate, String settlementDate, String bankInDate,
                    String amount, String payer) {
        return new Fee(id, type, null, "维港退休计划", payer, null, "计划定期费用",
            new BigDecimal(amount), LocalDate.parse(dueDate), null, status,
            settlementDate == null ? null : LocalDate.parse(settlementDate), "FPS",
            bankInDate == null ? null : LocalDate.parse(bankInDate), null, payer);
    }
}
