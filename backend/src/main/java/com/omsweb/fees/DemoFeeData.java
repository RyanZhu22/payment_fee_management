package com.omsweb.fees;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.YearMonth;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ConditionalOnProperty(name = "oms.demo.enabled", havingValue = "true", matchIfMissing = true)
class DemoFeeData implements ApplicationRunner {
    private final FeeService fees;
    private final JdbcClient jdbc;
    private final Clock clock;

    DemoFeeData(FeeService fees, JdbcClient jdbc, Clock clock) {
        this.fees = fees;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (jdbc.sql("SELECT EXISTS (SELECT 1 FROM fees)").query(Boolean.class).single()) {
            return;
        }

        LocalDate today = LocalDate.now(clock);
        YearMonth month = YearMonth.from(today);
        var monthlyAmounts = new String[] {"7200.00", "10400.00", "8600.00", "13200.00", "11300.00", "15800.00"};
        var monthlyPayers = new String[] {
            "启航受托服务", "港湾行政服务", "东岸受托有限公司", "晋远受托服务", "恒安计划管理", "海誉受托有限公司"
        };
        var monthlySchemes = new String[] {
            "启航企业计划", "港湾积金计划", "东岸行业计划", "晋远雇主计划", "恒安退休计划", "海誉综合计划"
        };
        for (int i = 0; i < monthlyAmounts.length; i++) {
            YearMonth historicalMonth = month.minusMonths(monthlyAmounts.length - 1L - i);
            LocalDate credited = historicalMonth.atDay(1).plusDays(5);
            if (credited.isAfter(today)) {
                credited = today;
            }
            Fee fee = fees.create(new FeeInput(FeeType.PERIODIC, null, monthlySchemes[i], monthlyPayers[i], null,
                "计划定期费用", new BigDecimal(monthlyAmounts[i]), historicalMonth.atDay(15), null));
            fees.settle(fee.id(), credited.minusDays(1), i % 2 == 0 ? "FPS" : "BANK_TRANSFER", credited,
                "DEMO-PAY-" + (101 + i), fee.payer());
        }

        Fee overdue = fees.create(new FeeInput(FeeType.PERIODIC, null, "维港退休计划", "维港受托有限公司",
            "finance@example.test", "计划定期费用", new BigDecimal("7800.00"), today.minusDays(20), null));
        fees.create(new FeeInput(FeeType.SURCHARGE, null, overdue.scheme(), overdue.payer(), overdue.payerEmail(),
            "逾期定期费用附加费", new BigDecimal("390.00"), today.minusDays(5), overdue.id()));
        fees.create(new FeeInput(FeeType.APPLICATION, "APP-DEMO-101", "青屿综合计划", "青屿行政服务",
            "finance@example.test", "计划注册申请费", new BigDecimal("4200.00"), today.minusDays(8), null));
        fees.create(new FeeInput(FeeType.APPLICATION, "APP-DEMO-102", "启航企业计划", "启航受托服务",
            null, "计划变更申请费", new BigDecimal("1800.00"), today.plusDays(6), null));
        fees.create(new FeeInput(FeeType.PERIODIC, null, "港湾积金计划", "港湾行政服务",
            null, "计划定期费用", new BigDecimal("6400.00"), today.plusDays(12), null));
        fees.create(new FeeInput(FeeType.PERIODIC, null, "恒安退休计划", "恒安计划管理",
            null, "计划定期费用", new BigDecimal("5300.00"), today.minusDays(3), null));
        fees.create(new FeeInput(FeeType.PERIODIC, null, "晋远雇主计划", "晋远受托服务",
            null, "计划定期费用", new BigDecimal("6900.00"), today.plusDays(23), null));

        Fee waived = fees.create(new FeeInput(FeeType.APPLICATION, "APP-DEMO-103", "海誉综合计划", "海誉受托有限公司",
            null, "计划注册申请费", new BigDecimal("1200.00"), today.minusDays(24), null));
        jdbc.sql("""
            UPDATE fees SET status = 'NON_PAYMENT', settlement_date = :settlementDate, reference_number = 'DEMO-WAIVER'
            WHERE id = :id
            """).param("settlementDate", today.minusDays(12)).param("id", waived.id()).update();
    }
}
