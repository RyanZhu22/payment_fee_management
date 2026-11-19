package com.omsweb.fees;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FeeService {
    private static final String COLUMNS = """
        id, type, application_number, scheme, payer, payer_email, description, amount, due_date,
        source_fee_id, status, settlement_date, payment_method, bank_in_date, reference_number, received_from
        """;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final AuditTrail audit;

    public FeeService(JdbcClient jdbc, Clock clock, AuditTrail audit) {
        this.jdbc = jdbc;
        this.clock = clock;
        this.audit = audit;
    }

    public List<Fee> list() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fees ORDER BY id DESC")
            .query(this::mapFee).list();
    }

    public List<Fee> findByApplicationNumber(String applicationNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fees WHERE application_number = :applicationNumber ORDER BY id")
            .param("applicationNumber", applicationNumber)
            .query(this::mapFee).list();
    }

    public Fee get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fees WHERE id = :id")
            .param("id", id)
            .query(this::mapFee).optional()
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "未找到费用记录"));
    }

    @Transactional
    public Fee create(FeeInput input) {
        validateFee(input, null);
        return insert(input, null, null, null).orElseThrow();
    }

    Optional<Fee> createCalculated(FeeInput input, long schemeId, long ruleId, String periodicKey) {
        return insert(input, schemeId, ruleId, periodicKey);
    }

    private Optional<Fee> insert(FeeInput input, Long schemeId, Long ruleId, String periodicKey) {
        Optional<Fee> created;
        try {
            String onConflict = periodicKey == null ? "" : " ON CONFLICT (periodic_key) DO NOTHING ";
            created = feeParameters(jdbc.sql("""
                INSERT INTO fees (type, application_number, scheme, payer, payer_email, description, amount, due_date,
                    source_fee_id, fee_scheme_id, billing_rule_id, periodic_key)
                VALUES (:type, :applicationNumber, :scheme, :payer, :payerEmail, :description, :amount, :dueDate,
                    :sourceFeeId, :schemeId, :ruleId, :periodicKey)
                """ + onConflict + " RETURNING " + COLUMNS), input)
                .param("schemeId", schemeId).param("ruleId", ruleId).param("periodicKey", periodicKey)
                .query(this::mapFee).optional();
        } catch (DuplicateKeyException error) {
            throw new AppException(HttpStatus.CONFLICT, ApiMessages.text("这笔原费用已有附加费", "This fee already has a surcharge."));
        }
        created.ifPresent(fee -> audit.record("CREATE", "FEE", Long.toString(fee.id()), null, fee));
        return created;
    }

    @Transactional
    public Fee update(long id, FeeInput input) {
        Fee existing = get(id);
        if (existing.status() != FeeStatus.UNPAID) {
            throw postedEdit();
        }
        validateFee(input, existing);
        Fee updated;
        try {
            updated = feeParameters(jdbc.sql("""
                UPDATE fees SET type = :type, application_number = :applicationNumber, scheme = :scheme, payer = :payer,
                    payer_email = :payerEmail, description = :description, amount = :amount, due_date = :dueDate,
                    source_fee_id = :sourceFeeId
                WHERE id = :id AND status = 'UNPAID'
                RETURNING
                """ + COLUMNS), input).param("id", id).query(this::mapFee).optional().orElseThrow(this::postedEdit);
        } catch (DuplicateKeyException error) {
            throw new AppException(HttpStatus.CONFLICT, ApiMessages.text("这笔原费用已有附加费", "This fee already has a surcharge."));
        }
        audit.record("UPDATE", "FEE", Long.toString(id), existing, updated);
        return updated;
    }

    private AppException postedEdit() {
        return new AppException(HttpStatus.CONFLICT, ApiMessages.text("已结算费用已锁定，不能修改", "Settled fees are locked and cannot be edited."));
    }

    @Transactional
    public Fee settle(long id, LocalDate settlementDate, String paymentMethod, LocalDate bankInDate,
                      String referenceNumber, String receivedFrom) {
        return jdbc.sql("""
            UPDATE fees SET status = 'PAID', settlement_date = :settlementDate, payment_method = :paymentMethod,
                bank_in_date = :bankInDate, reference_number = :referenceNumber, received_from = :receivedFrom
            WHERE id = :id AND status = 'UNPAID'
            RETURNING
            """ + COLUMNS)
            .param("id", id)
            .param("settlementDate", settlementDate)
            .param("paymentMethod", paymentMethod)
            .param("bankInDate", bankInDate)
            .param("referenceNumber", referenceNumber)
            .param("receivedFrom", receivedFrom)
            .query(this::mapFee).optional()
            .orElseThrow(() -> {
                get(id);
                return new AppException(HttpStatus.CONFLICT, "费用已结算，请核对文件中的重复记录");
            });
    }

    public record PeriodicRun(int created, String period) {}

    private void validateFee(FeeInput input, Fee existing) {
        if (input.type() == FeeType.APPLICATION && stripOptional(input.applicationNumber()) == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "申请费需要申请编号");
        }

        Long sourceId = input.sourceFeeId();
        if (input.type() != FeeType.SURCHARGE) {
            if (sourceId != null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "只有附加费可关联原费用");
            }
            return;
        }

        if (sourceId == null || (existing != null && sourceId == existing.id())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "请选择有效的原费用");
        }
        if (existing != null && Objects.equals(existing.sourceFeeId(), sourceId)) {
            return;
        }

        Fee source = get(sourceId);
        if (source.type() != FeeType.PERIODIC || source.status() != FeeStatus.UNPAID
            || !source.dueDate().isBefore(LocalDate.now(clock))) {
            throw new AppException(HttpStatus.BAD_REQUEST, ApiMessages.text("附加费须关联逾期未缴的定期费用", "A surcharge must reference an overdue, unpaid periodic fee."));
        }
    }

    private JdbcClient.StatementSpec feeParameters(JdbcClient.StatementSpec statement, FeeInput input) {
        return statement.param("type", input.type().name())
            .param("applicationNumber", stripOptional(input.applicationNumber()))
            .param("scheme", input.scheme().strip())
            .param("payer", input.payer().strip())
            .param("payerEmail", stripOptional(input.payerEmail()))
            .param("description", input.description().strip())
            .param("amount", input.amount())
            .param("dueDate", input.dueDate())
            .param("sourceFeeId", input.sourceFeeId());
    }

    private String stripOptional(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private Fee mapFee(ResultSet row, int rowNumber) throws SQLException {
        return new Fee(row.getLong("id"), FeeType.valueOf(row.getString("type")), row.getString("application_number"),
            row.getString("scheme"), row.getString("payer"), row.getString("payer_email"), row.getString("description"),
            row.getBigDecimal("amount"), row.getObject("due_date", LocalDate.class),
            row.getObject("source_fee_id", Long.class), FeeStatus.valueOf(row.getString("status")),
            row.getObject("settlement_date", LocalDate.class), row.getString("payment_method"),
            row.getObject("bank_in_date", LocalDate.class), row.getString("reference_number"), row.getString("received_from"));
    }
}
