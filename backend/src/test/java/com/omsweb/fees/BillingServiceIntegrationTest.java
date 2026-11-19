package com.omsweb.fees;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.omsweb.fees.BillingController.CalculationInput;
import com.omsweb.fees.BillingController.IssueInput;
import com.omsweb.fees.BillingController.RuleInput;
import com.omsweb.fees.BillingController.SchemeInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@EnabledIfEnvironmentVariable(named = "OMS_BILLING_TEST_DB_URL", matches = ".+")
class BillingServiceIntegrationTest {
    private static DataSource dataSource;
    private JdbcClient jdbc;
    private TransactionTemplate transaction;
    private FeeService fees;
    private BillingService billing;
    private final LocalDate date = LocalDate.of(2026, 11, 19);

    @BeforeAll
    static void migrate() {
        dataSource = new DriverManagerDataSource(System.getenv("OMS_BILLING_TEST_DB_URL"),
            System.getenv().getOrDefault("OMS_DB_USER", "omsweb"), System.getenv("OMS_DB_PASSWORD"));
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @BeforeEach
    void setUp() {
        jdbc = JdbcClient.create(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        var clock = Clock.fixed(Instant.parse("2026-11-19T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"));
        var audit = new AuditTrail(jdbc, JsonMapper.builder().build());
        fees = new FeeService(jdbc, clock, audit);
        billing = new BillingService(jdbc, fees, audit);
    }

    @Test
    void effectiveDatesKeepOldQuotesAndConfiguredRounding() {
        transaction.executeWithoutResult(status -> {
            var scheme = scheme();
            var earlier = rule(scheme.id(), FeeType.APPLICATION, BillingRule.Mode.FIXED, "10.005", date.minusMonths(1), 7, 1, RoundingMode.HALF_UP);
            var later = rule(scheme.id(), FeeType.APPLICATION, BillingRule.Mode.FIXED, "20.005", date, 12, 1, RoundingMode.HALF_EVEN);
            var oldQuote = billing.quote(new CalculationInput(scheme.id(), FeeType.APPLICATION, date.minusDays(1), null));
            var newQuote = billing.quote(new CalculationInput(scheme.id(), FeeType.APPLICATION, date, null));
            assertEquals(earlier.id(), oldQuote.ruleId());
            assertEquals(new BigDecimal("10.01"), oldQuote.amount());
            assertEquals(later.id(), newQuote.ruleId());
            assertEquals(new BigDecimal("20.00"), newQuote.amount());
            assertEquals(date.plusDays(12), newQuote.dueDate());
            assertEquals(2, billing.rules().size());
            status.setRollbackOnly();
        });
    }

    @Test
    void surchargeUsesOriginalAmountAndPreservesRuleProvenance() {
        transaction.executeWithoutResult(status -> {
            var scheme = scheme();
            var source = manual(scheme.name(), "101.00", date.minusDays(20));
            var rule = rule(scheme.id(), FeeType.SURCHARGE, BillingRule.Mode.PERCENT, "2.5", date.minusMonths(1), 10, 1, RoundingMode.HALF_UP);
            var quote = billing.quote(new CalculationInput(scheme.id(), FeeType.SURCHARGE, date, source.id()));
            assertEquals(new BigDecimal("2.53"), quote.amount());
            var issued = billing.issue(new IssueInput(scheme.id(), FeeType.SURCHARGE, date, null, source.id(), "Configured surcharge"));
            assertEquals(quote.amount(), issued.amount());
            assertEquals(source.id(), issued.sourceFeeId());
            assertEquals(rule.id(), jdbc.sql("SELECT billing_rule_id FROM fees WHERE id = :id").param("id", issued.id()).query(Long.class).single());
            assertEquals(1, jdbc.sql("SELECT count(*) FROM audit_events WHERE action = 'ISSUE' AND entity_id = :id")
                .param("id", Long.toString(issued.id())).query(Integer.class).single());
            status.setRollbackOnly();
        });
    }

    @Test
    void monthlyIssuanceIsDurableAcrossRuleVersionsAndCycles() {
        transaction.executeWithoutResult(status -> {
            var scheme = scheme();
            rule(scheme.id(), FeeType.PERIODIC, BillingRule.Mode.FIXED, "150", date.minusDays(2), 10, 3, RoundingMode.HALF_UP);
            assertEquals(1, billing.runPeriodic(date).created());
            assertEquals(0, billing.runPeriodic(date).created());
            rule(scheme.id(), FeeType.PERIODIC, BillingRule.Mode.FIXED, "175", date, 12, 3, RoundingMode.HALF_UP);
            assertEquals(0, billing.runPeriodic(date).created());
            assertEquals(0, billing.runPeriodic(date.plusMonths(1)).created());
            assertEquals(1, billing.runPeriodic(date.plusMonths(3)).created());
            assertEquals(2, jdbc.sql("SELECT count(*) FROM fees WHERE fee_scheme_id = :id").param("id", scheme.id()).query(Integer.class).single());
            status.setRollbackOnly();
        });
    }

    @Test
    void postedFeesAreLockedWhileUnpaidFeesCanStillBeEdited() {
        transaction.executeWithoutResult(status -> {
            var fee = manual("Manual scheme", "125", date);
            var change = new FeeInput(FeeType.PERIODIC, null, fee.scheme(), fee.payer(), null, "Updated", new BigDecimal("140"), date.plusDays(1), null);
            assertEquals(new BigDecimal("140.00"), fees.update(fee.id(), change).amount());
            fees.settle(fee.id(), date, "FPS", date, "test-payment", fee.payer());
            assertEquals(HttpStatus.CONFLICT, assertThrows(AppException.class, () -> fees.update(fee.id(), change)).status());
            jdbc.sql("UPDATE fees SET status = 'NON_PAYMENT' WHERE id = :id").param("id", fee.id()).update();
            assertEquals(HttpStatus.CONFLICT, assertThrows(AppException.class, () -> fees.update(fee.id(), change)).status());
            assertEquals(new BigDecimal("140.00"), fees.get(fee.id()).amount());
            status.setRollbackOnly();
        });
    }

    @Test
    void duplicateEffectiveDateAndInactiveIssuanceAreRejected() {
        transaction.executeWithoutResult(status -> {
            var scheme = scheme();
            rule(scheme.id(), FeeType.APPLICATION, BillingRule.Mode.FIXED, "10", date, 1, 1, RoundingMode.DOWN);
            billing.updateScheme(scheme.id(), new SchemeInput(scheme.code(), scheme.name(), scheme.payer(), null, false));
            var inactive = assertThrows(AppException.class, () -> billing.quote(new CalculationInput(scheme.id(), FeeType.APPLICATION, date, null)));
            assertEquals(HttpStatus.CONFLICT, inactive.status());
            var duplicate = assertThrows(AppException.class, () -> rule(scheme.id(), FeeType.APPLICATION, BillingRule.Mode.FIXED, "12", date, 1, 1, RoundingMode.DOWN));
            assertEquals(HttpStatus.CONFLICT, duplicate.status());
            status.setRollbackOnly();
        });
    }

    @Test
    void ruleValidationRequiresFixedApplicationFeesAndSupportedRounding() {
        transaction.executeWithoutResult(status -> {
            var scheme = scheme();
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(AppException.class,
                () -> rule(scheme.id(), FeeType.APPLICATION, BillingRule.Mode.PERCENT, "2.5", date, 1, 1, RoundingMode.HALF_UP)).status());
            assertEquals(HttpStatus.BAD_REQUEST, assertThrows(AppException.class,
                () -> rule(scheme.id(), FeeType.SURCHARGE, BillingRule.Mode.FIXED, "10", date, 1, 1, null)).status());
            assertTrue(billing.rules().isEmpty());
            status.setRollbackOnly();
        });
    }

    private FeeScheme scheme() {
        return billing.createScheme(new SchemeInput("BILLING-TEST", "Billing test scheme", "Billing test payer", null, true));
    }

    private BillingRule rule(long id, FeeType type, BillingRule.Mode mode, String rate, LocalDate effectiveFrom,
                             int dueDays, int cycleMonths, RoundingMode rounding) {
        return billing.createRule(new RuleInput(id, type, mode, new BigDecimal(rate), effectiveFrom, dueDays, cycleMonths, rounding));
    }

    private Fee manual(String scheme, String amount, LocalDate dueDate) {
        return fees.create(new FeeInput(FeeType.PERIODIC, null, scheme, "Billing test payer", null,
            "Test fee", new BigDecimal(amount), dueDate, null));
    }
}
