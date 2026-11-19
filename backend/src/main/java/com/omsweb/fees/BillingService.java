package com.omsweb.fees;

import com.omsweb.fees.BillingController.CalculationInput;
import com.omsweb.fees.BillingController.IssueInput;
import com.omsweb.fees.BillingController.RuleInput;
import com.omsweb.fees.BillingController.SchemeInput;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class BillingService {
    private static final BigDecimal MAX_AMOUNT = new BigDecimal("999999999999.99");
    private static final Set<RoundingMode> ROUNDING_MODES = Set.of(RoundingMode.HALF_UP, RoundingMode.HALF_EVEN, RoundingMode.DOWN);
    private final JdbcClient jdbc;
    private final FeeService fees;
    private final AuditTrail audit;

    BillingService(JdbcClient jdbc, FeeService fees, AuditTrail audit) {
        this.jdbc = jdbc;
        this.fees = fees;
        this.audit = audit;
    }

    public List<FeeScheme> schemes() {
        return jdbc.sql("SELECT * FROM fee_schemes ORDER BY code").query(this::schemeRow).list();
    }

    public FeeScheme scheme(long id) {
        return jdbc.sql("SELECT * FROM fee_schemes WHERE id = :id").param("id", id)
            .query(this::schemeRow).optional()
            .orElseThrow(() -> error(HttpStatus.NOT_FOUND, "未找到计划", "Scheme not found."));
    }

    @Transactional
    public FeeScheme createScheme(SchemeInput input) {
        SchemeInput clean = validateScheme(input);
        FeeScheme created;
        try {
            created = schemeParameters(jdbc.sql("""
                INSERT INTO fee_schemes (code, name, payer, payer_email, active)
                VALUES (:code, :name, :payer, :email, :active) RETURNING *
                """), clean).query(this::schemeRow).single();
        } catch (DuplicateKeyException duplicate) {
            throw error(HttpStatus.CONFLICT, "计划代码或名称已存在", "The scheme code or name already exists.");
        }
        audit.record("CREATE", "SCHEME", Long.toString(created.id()), null, created);
        return created;
    }

    @Transactional
    public FeeScheme updateScheme(long id, SchemeInput input) {
        SchemeInput clean = validateScheme(input);
        FeeScheme previous = scheme(id);
        FeeScheme updated;
        try {
            updated = schemeParameters(jdbc.sql("""
                UPDATE fee_schemes SET code = :code, name = :name, payer = :payer, payer_email = :email, active = :active
                WHERE id = :id RETURNING *
                """), clean).param("id", id).query(this::schemeRow).single();
        } catch (DuplicateKeyException duplicate) {
            throw error(HttpStatus.CONFLICT, "计划代码或名称已存在", "The scheme code or name already exists.");
        }
        audit.record("UPDATE", "SCHEME", Long.toString(id), previous, updated);
        return updated;
    }

    public List<BillingRule> rules() {
        return jdbc.sql("SELECT * FROM billing_rules ORDER BY scheme_id, type, effective_from DESC")
            .query(this::ruleRow).list();
    }

    @Transactional
    public BillingRule createRule(RuleInput input) {
        validateRule(input);
        scheme(input.schemeId());
        BillingRule created;
        try {
            created = jdbc.sql("""
                INSERT INTO billing_rules (scheme_id, type, mode, rate, effective_from, due_days, cycle_months, rounding_mode)
                VALUES (:schemeId, :type, :mode, :rate, :effectiveFrom, :dueDays, :cycleMonths, :roundingMode)
                RETURNING *
                """)
                .param("schemeId", input.schemeId()).param("type", input.type().name())
                .param("mode", input.mode().name()).param("rate", input.rate())
                .param("effectiveFrom", input.effectiveFrom()).param("dueDays", input.dueDays())
                .param("cycleMonths", input.cycleMonths()).param("roundingMode", input.roundingMode().name())
                .query(this::ruleRow).single();
        } catch (DuplicateKeyException duplicate) {
            throw error(HttpStatus.CONFLICT, "此计划及费用类型在该生效日已有规则，请使用新的生效日", 
                "This scheme and fee type already have a rule on that date. Use a new effective date.");
        }
        audit.record("CREATE", "BILLING_RULE", Long.toString(created.id()), null, created);
        return created;
    }

    public Quote quote(CalculationInput input) {
        return calculate(input).quote();
    }

    @Transactional
    public Fee issue(IssueInput input) {
        if (input == null || input.description() == null || input.description().isBlank() || input.description().length() > 240) {
            throw error(HttpStatus.BAD_REQUEST, "请输入不超过 240 个字符的费用说明", "Enter a fee description of at most 240 characters.");
        }
        String applicationNumber = input.applicationNumber() == null ? null : input.applicationNumber().strip();
        if (applicationNumber != null && applicationNumber.length() > 64) {
            throw error(HttpStatus.BAD_REQUEST, "申请编号最多 64 个字符", "The application number must be at most 64 characters.");
        }
        if (input.type() == FeeType.APPLICATION && (applicationNumber == null || applicationNumber.isBlank())) {
            throw error(HttpStatus.BAD_REQUEST, "申请费需要申请编号", "Application fees require an application number.");
        }
        Calculation calculated = calculate(new CalculationInput(input.schemeId(), input.type(), input.calculationDate(), input.sourceFeeId()));
        String periodicKey = input.type() == FeeType.PERIODIC ? periodicKey(input.schemeId(), input.calculationDate()) : null;
        Fee fee = fees.createCalculated(new FeeInput(input.type(), applicationNumber, calculated.scheme().name(), calculated.scheme().payer(),
            calculated.scheme().payerEmail(), input.description().strip(), calculated.quote().amount(), calculated.quote().dueDate(), input.sourceFeeId()),
            input.schemeId(), calculated.rule().id(), periodicKey)
            .orElseThrow(() -> error(HttpStatus.CONFLICT, "此计划在该计费月份已有定期费用", "This scheme already has a periodic fee for that billing month."));
        audit.record("ISSUE", "FEE", Long.toString(fee.id()), null, fee);
        return fee;
    }

    @Transactional
    public FeeService.PeriodicRun runPeriodic(LocalDate date) {
        int created = 0;
        for (FeeScheme scheme : schemes()) {
            if (!scheme.active()) {
                continue;
            }
            BillingRule rule = effectiveRule(scheme.id(), FeeType.PERIODIC, date);
            if (rule == null || !isCycleMonth(rule, date)) {
                continue;
            }
            BigDecimal amount = rule.rate().setScale(2, rule.roundingMode());
            var result = fees.createCalculated(new FeeInput(FeeType.PERIODIC, null, scheme.name(), scheme.payer(), scheme.payerEmail(),
                YearMonth.from(date) + ApiMessages.text(" 定期费用", " periodic fee"), amount, date.plusDays(rule.dueDays()), null),
                scheme.id(), rule.id(), periodicKey(scheme.id(), date));
            if (result.isPresent()) {
                Fee fee = result.get();
                audit.record("ISSUE", "FEE", Long.toString(fee.id()), null, fee);
                created++;
            }
        }
        return new FeeService.PeriodicRun(created, YearMonth.from(date).toString());
    }

    private Calculation calculate(CalculationInput input) {
        if (input == null || input.schemeId() == null || input.schemeId() <= 0 || input.type() == null || input.calculationDate() == null) {
            throw error(HttpStatus.BAD_REQUEST, "请选择计划、费用类型和计费日期", "Select a scheme, fee type, and calculation date.");
        }
        FeeScheme scheme = scheme(input.schemeId());
        if (!scheme.active()) {
            throw error(HttpStatus.CONFLICT, "计划已停用，无法开立费用", "The scheme is inactive and cannot issue fees.");
        }
        BillingRule rule = effectiveRule(scheme.id(), input.type(), input.calculationDate());
        if (rule == null) {
            throw error(HttpStatus.CONFLICT, "计费日期没有生效的规则", "No billing rule is effective on the calculation date.");
        }
        if (input.type() == FeeType.PERIODIC && !isCycleMonth(rule, input.calculationDate())) {
            throw error(HttpStatus.CONFLICT, "所选月份不在此规则的计费周期", "The selected month is outside this rule's billing cycle.");
        }
        BigDecimal amount = rule.rate();
        if (input.type() == FeeType.SURCHARGE) {
            if (input.sourceFeeId() == null || input.sourceFeeId() <= 0) {
                throw error(HttpStatus.BAD_REQUEST, "请选择有效的原费用", "Select a valid original fee.");
            }
            Fee source = fees.get(input.sourceFeeId());
            if (source.type() != FeeType.PERIODIC || source.status() != FeeStatus.UNPAID || !source.dueDate().isBefore(input.calculationDate())) {
                throw error(HttpStatus.CONFLICT, "附加费须关联逾期未缴的定期费用", "A surcharge must reference an overdue, unpaid periodic fee.");
            }
            boolean matchesScheme = jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM fees WHERE id = :id AND
                    (fee_scheme_id = :schemeId OR (fee_scheme_id IS NULL AND scheme = :schemeName)))
                """).param("id", source.id()).param("schemeId", scheme.id()).param("schemeName", scheme.name())
                .query(Boolean.class).single();
            if (!matchesScheme) {
                throw error(HttpStatus.CONFLICT, "原费用属于其他计划", "The original fee belongs to another scheme.");
            }
            if (rule.mode() == BillingRule.Mode.PERCENT) {
                amount = source.amount().multiply(rule.rate()).movePointLeft(2);
            }
        } else if (input.sourceFeeId() != null) {
            throw error(HttpStatus.BAD_REQUEST, "只有附加费可关联原费用", "Only a surcharge can reference an original fee.");
        }
        amount = amount.setScale(2, rule.roundingMode());
        if (amount.signum() <= 0 || amount.compareTo(MAX_AMOUNT) > 0) {
            throw error(HttpStatus.BAD_REQUEST, "计算金额须大于零且不超过 999999999999.99", "The calculated amount must be positive and no greater than 999999999999.99.");
        }
        return new Calculation(scheme, rule, new Quote(rule.id(), amount, input.calculationDate().plusDays(rule.dueDays())));
    }

    private BillingRule effectiveRule(long schemeId, FeeType type, LocalDate date) {
        return jdbc.sql("""
            SELECT * FROM billing_rules WHERE scheme_id = :schemeId AND type = :type AND effective_from <= :date
            ORDER BY effective_from DESC LIMIT 1
            """).param("schemeId", schemeId).param("type", type.name()).param("date", date)
            .query(this::ruleRow).optional().orElse(null);
    }

    private boolean isCycleMonth(BillingRule rule, LocalDate date) {
        return ChronoUnit.MONTHS.between(YearMonth.from(rule.effectiveFrom()), YearMonth.from(date)) % rule.cycleMonths() == 0;
    }

    private String periodicKey(long schemeId, LocalDate date) {
        return "SCHEME-" + schemeId + "-" + YearMonth.from(date);
    }

    private SchemeInput validateScheme(SchemeInput input) {
        if (input == null || input.code() == null || !input.code().strip().matches("[A-Za-z0-9][A-Za-z0-9_-]{0,39}")
            || input.name() == null || input.name().isBlank() || input.name().strip().length() > 120
            || input.payer() == null || input.payer().isBlank() || input.payer().strip().length() > 120 || input.active() == null) {
            throw error(HttpStatus.BAD_REQUEST, "请输入计划代码、名称、付款人及启用状态；代码可含字母、数字、连字符和下划线", 
                "Enter a scheme code, name, payer, and active status. The code may contain letters, numbers, hyphens, and underscores.");
        }
        String email = input.payerEmail() == null || input.payerEmail().isBlank() ? null : input.payerEmail().strip();
        if (email != null && (email.length() > 254 || !email.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+"))) {
            throw error(HttpStatus.BAD_REQUEST, "请输入有效的邮箱地址", "Enter a valid email address.");
        }
        return new SchemeInput(input.code().strip().toUpperCase(Locale.ROOT), input.name().strip(), input.payer().strip(), email, input.active());
    }

    private void validateRule(RuleInput input) {
        if (input == null || input.schemeId() == null || input.schemeId() <= 0 || input.type() == null || input.mode() == null
            || input.rate() == null || input.rate().signum() <= 0 || input.rate().stripTrailingZeros().scale() > 6
            || input.rate().compareTo(MAX_AMOUNT) > 0 || input.effectiveFrom() == null
            || input.dueDays() == null || input.dueDays() < 0 || input.dueDays() > 3650
            || input.cycleMonths() == null || input.cycleMonths() < 1 || input.cycleMonths() > 120
            || input.roundingMode() == null || !ROUNDING_MODES.contains(input.roundingMode())) {
            throw error(HttpStatus.BAD_REQUEST, "请检查规则字段、正数金额或比率、到期天数（0–3650）及周期月数（1–120）", 
                "Check the rule fields, positive amount or rate, due days (0–3650), and cycle months (1–120).");
        }
        if (input.type() != FeeType.SURCHARGE && input.mode() != BillingRule.Mode.FIXED) {
            throw error(HttpStatus.BAD_REQUEST, "申请费及定期费用须使用固定金额", "Application and periodic fees require a fixed amount.");
        }
        if (input.type() != FeeType.PERIODIC && input.cycleMonths() != 1) {
            throw error(HttpStatus.BAD_REQUEST, "申请费及附加费的周期月数须为 1", "Application fees and surcharges require cycleMonths = 1.");
        }
        if (input.mode() == BillingRule.Mode.FIXED && input.rate().setScale(2, input.roundingMode()).signum() <= 0) {
            throw error(HttpStatus.BAD_REQUEST, "固定金额按规则舍入后须大于零", "The fixed amount must remain positive after rounding.");
        }
    }

    private JdbcClient.StatementSpec schemeParameters(JdbcClient.StatementSpec statement, SchemeInput input) {
        return statement.param("code", input.code()).param("name", input.name()).param("payer", input.payer())
            .param("email", input.payerEmail()).param("active", input.active());
    }

    private FeeScheme schemeRow(ResultSet row, int number) throws SQLException {
        return new FeeScheme(row.getLong("id"), row.getString("code"), row.getString("name"), row.getString("payer"),
            row.getString("payer_email"), row.getBoolean("active"));
    }

    private BillingRule ruleRow(ResultSet row, int number) throws SQLException {
        return new BillingRule(row.getLong("id"), row.getLong("scheme_id"), FeeType.valueOf(row.getString("type")),
            BillingRule.Mode.valueOf(row.getString("mode")), row.getBigDecimal("rate"), row.getObject("effective_from", LocalDate.class),
            row.getInt("due_days"), row.getInt("cycle_months"), RoundingMode.valueOf(row.getString("rounding_mode")));
    }

    private AppException error(HttpStatus status, String chinese, String english) {
        return new AppException(status, ApiMessages.text(chinese, english));
    }

    public record Quote(long ruleId, BigDecimal amount, LocalDate dueDate) {}
    private record Calculation(FeeScheme scheme, BillingRule rule, Quote quote) {}
}
