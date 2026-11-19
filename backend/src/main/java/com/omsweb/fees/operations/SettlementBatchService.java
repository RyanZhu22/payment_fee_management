package com.omsweb.fees.operations;

import static com.omsweb.fees.ApiMessages.text;

import com.omsweb.fees.AppException;
import com.omsweb.fees.AuditTrail;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;
import com.omsweb.fees.FeeStatus;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

@Service
public class SettlementBatchService {
    private static final String BATCH_COLUMNS = """
        id, type, filename, checksum, status, total, successful, failed, amount,
        created_by, created_at, reviewed_by, reviewed_at, review_reason
        """;
    private static final String ROW_COLUMNS = """
        id, row_number, matching_reference, fee_id, amount, settlement_date, payment_method,
        bank_in_date, reference_number, received_from, valid, message_zh, message_en, fee_snapshot, snapshot_hash
        """;
    private static final String REVERSAL_SELECT = """
        SELECT r.*, p.amount, p.reference_number, p.legacy
        FROM settlement_reversals r JOIN settlement_payments p ON p.id = r.payment_id
        """;

    private final JdbcClient jdbc;
    private final SettlementImportService importer;
    private final FeeService fees;
    private final AuditTrail audit;
    private final Clock clock;

    public SettlementBatchService(JdbcClient jdbc, SettlementImportService importer, FeeService fees,
                                  AuditTrail audit, Clock clock) {
        this.jdbc = jdbc;
        this.importer = importer;
        this.fees = fees;
        this.audit = audit;
        this.clock = clock;
    }

    public List<SettlementBatch> list() {
        return jdbc.sql("SELECT " + BATCH_COLUMNS + " FROM settlement_batches ORDER BY id DESC")
            .query(this::mapBatch).list();
    }

    public SettlementBatch get(long id) {
        return detail(batch(id, false));
    }

    @Transactional
    public SettlementBatch submit(MultipartFile file, SettlementImportService.Type type) {
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException error) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                text("读取结算工作簿失败。", "The settlement workbook could not be read."));
        }
        String checksum = SettlementImportService.checksum(bytes);
        var existing = jdbc.sql("SELECT id FROM settlement_batches WHERE type = :type AND checksum = :checksum")
            .param("type", type.name()).param("checksum", checksum).query(Long.class).optional();
        if (existing.isPresent()) {
            return get(existing.get());
        }

        List<SettlementImportService.PreviewRow> parsed;
        try {
            parsed = importer.preview(bytes, type);
        } catch (AppException error) {
            parsed = List.of(new SettlementImportService.PreviewRow(1, "", null, null, null, null,
                null, null, null, false,
                "工作簿校验失败，请使用未加密的 .xls 或 .xlsx，并核对类型、工作表、表头和数据行。",
                "Workbook validation failed. Use an unencrypted .xls or .xlsx and check the type, worksheet, headings and data rows.",
                null, null));
        }
        List<SettlementImportService.PreviewRow> preview = parsed.stream()
            .map(row -> row.valid() && referenceInUse(row.paymentMethod(), row.referenceNumber())
                ? row.invalid("渠道参考编号已用于有效付款。", "The channel reference is already used by an active payment.")
                : row)
            .toList();
        int valid = (int) preview.stream().filter(SettlementImportService.PreviewRow::valid).count();
        BigDecimal amount = preview.stream().filter(SettlementImportService.PreviewRow::valid)
            .map(SettlementImportService.PreviewRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        String filename = file.getOriginalFilename() == null ? "settlement.xlsx"
            : file.getOriginalFilename().replaceAll("[\\/\\\\\r\n]", "_");
        if (filename.length() > 255) {
            filename = filename.substring(filename.length() - 255);
        }
        String status = valid == preview.size() ? "PENDING_REVIEW" : "VALIDATION_FAILED";
        var inserted = jdbc.sql("""
            INSERT INTO settlement_batches (type, filename, checksum, original_file, status,
                total, successful, failed, amount, created_by, created_at)
            VALUES (:type, :filename, :checksum, :file, :status, :total, :successful, :failed,
                :amount, :actor, :createdAt)
            ON CONFLICT (type, checksum) DO NOTHING RETURNING id
            """).param("type", type.name()).param("filename", filename).param("checksum", checksum)
            .param("file", bytes).param("status", status).param("total", preview.size())
            .param("successful", valid).param("failed", preview.size() - valid).param("amount", amount)
            .param("actor", audit.actor()).param("createdAt", OffsetDateTime.now(clock))
            .query(Long.class).optional();
        if (inserted.isEmpty()) {
            long id = jdbc.sql("SELECT id FROM settlement_batches WHERE type = :type AND checksum = :checksum")
                .param("type", type.name()).param("checksum", checksum).query(Long.class).single();
            return get(id);
        }
        long id = inserted.get();
        for (var row : preview) {
            jdbc.sql("""
                INSERT INTO settlement_batch_rows (batch_id, row_number, matching_reference, fee_id, amount,
                    settlement_date, payment_method, bank_in_date, reference_number, received_from,
                    valid, message_zh, message_en, fee_snapshot, snapshot_hash)
                VALUES (:batchId, :row, :reference, :feeId, :amount, :settlementDate, :paymentMethod,
                    :bankInDate, :referenceNumber, :receivedFrom, :valid, :messageZh, :messageEn,
                    :snapshot, :hash)
                """).param("batchId", id).param("row", row.row()).param("reference", row.reference())
                .param("feeId", row.feeId()).param("amount", row.amount()).param("settlementDate", row.settlementDate())
                .param("paymentMethod", row.paymentMethod()).param("bankInDate", row.bankInDate())
                .param("referenceNumber", row.referenceNumber()).param("receivedFrom", row.receivedFrom())
                .param("valid", row.valid()).param("messageZh", row.messageZh()).param("messageEn", row.messageEn())
                .param("snapshot", row.feeSnapshot()).param("hash", row.snapshotHash()).update();
        }
        SettlementBatch result = get(id);
        audit.record("SETTLEMENT_BATCH_SUBMITTED", "SETTLEMENT_BATCH", Long.toString(id), null, result);
        return result;
    }

    @Transactional
    public SettlementBatch approve(long id, String reason) {
        SettlementBatch before = batch(id, true);
        if ("APPROVED".equals(before.status())) {
            return detail(before);
        }
        requirePending(before.status());
        independent(before.createdBy());
        SettlementBatch batch = detail(before);
        List<Long> ids = batch.rows().stream().map(SettlementBatch.Row::feeId).sorted().toList();
        jdbc.sql("SELECT id FROM fees WHERE id IN (:ids) ORDER BY id FOR UPDATE")
            .param("ids", ids).query(Long.class).list();

        for (var row : batch.rows()) {
            Fee current = fees.get(row.feeId());
            String hash = SettlementImportService.checksum(SettlementImportService.snapshot(current).getBytes(StandardCharsets.UTF_8));
            if (!hash.equals(row.snapshotHash()) || current.status() != FeeStatus.UNPAID
                    || current.amount().compareTo(row.amount()) != 0) {
                throw new AppException(HttpStatus.CONFLICT,
                    text("费用已在提交后变更，请重新上传并核对整个批次。", "A fee changed after submission. Upload and review the batch again."));
            }
            if (referenceInUse(row.paymentMethod(), row.referenceNumber())) {
                throw new AppException(HttpStatus.CONFLICT,
                    text("渠道参考编号已用于有效付款，请重新核对批次。", "A channel reference is already used by an active payment. Review the batch."));
            }
        }

        try {
            for (var row : batch.rows()) {
                Fee beforeFee = fees.get(row.feeId());
                jdbc.sql("""
                    INSERT INTO settlement_payments (fee_id, batch_row_id, amount, settlement_date,
                        payment_method, bank_in_date, reference_number, received_from, method_key,
                        reference_key, posted_by, posted_at)
                    VALUES (:feeId, :rowId, :amount, :settlementDate, :paymentMethod, :bankInDate,
                        :referenceNumber, :receivedFrom, :methodKey, :referenceKey, :actor, :postedAt)
                    """).param("feeId", row.feeId()).param("rowId", row.id()).param("amount", row.amount())
                    .param("settlementDate", row.settlementDate()).param("paymentMethod", row.paymentMethod())
                    .param("bankInDate", row.bankInDate()).param("referenceNumber", row.referenceNumber())
                    .param("receivedFrom", row.receivedFrom()).param("methodKey", normalise(row.paymentMethod()))
                    .param("referenceKey", normalise(row.referenceNumber())).param("actor", audit.actor())
                    .param("postedAt", OffsetDateTime.now(clock)).update();
                Fee afterFee = fees.settle(row.feeId(), row.settlementDate(), row.paymentMethod(), row.bankInDate(),
                    row.referenceNumber(), row.receivedFrom());
                jdbc.sql("UPDATE fps_requests SET status = 'SETTLED' WHERE fee_id = :feeId AND status = 'DEMO'")
                    .param("feeId", row.feeId()).update();
                audit.record("PAYMENT_POSTED", "FEE", Long.toString(row.feeId()), beforeFee, afterFee);
            }
        } catch (DuplicateKeyException error) {
            throw new AppException(HttpStatus.CONFLICT,
                text("费用或渠道参考编号已被其他付款使用，整个批次未入账。", "A fee or channel reference is already used. The batch was not posted."));
        }
        reviewBatch(id, "APPROVED", optionalReason(reason));
        jdbc.sql("""
            UPDATE settlement_batch_rows SET message_zh = '审批通过，结算已登记。',
                message_en = 'Approved and settlement posted.' WHERE batch_id = :id
            """).param("id", id).update();
        SettlementBatch result = get(id);
        audit.record("SETTLEMENT_BATCH_APPROVED", "SETTLEMENT_BATCH", Long.toString(id), before, result);
        return result;
    }

    @Transactional
    public SettlementBatch reject(long id, String reason) {
        SettlementBatch before = batch(id, true);
        requirePending(before.status());
        independent(before.createdBy());
        reviewBatch(id, "REJECTED", requiredReason(reason));
        SettlementBatch result = get(id);
        audit.record("SETTLEMENT_BATCH_REJECTED", "SETTLEMENT_BATCH", Long.toString(id), before, result);
        return result;
    }

    public SourceFile file(long id) {
        SettlementBatch batch = batch(id, false);
        byte[] bytes = jdbc.sql("SELECT original_file FROM settlement_batches WHERE id = :id")
            .param("id", id).query(byte[].class).single();
        audit.record("SETTLEMENT_FILE_DOWNLOADED", "SETTLEMENT_BATCH", Long.toString(id), null,
            java.util.Map.of("filename", batch.filename(), "checksum", batch.checksum()));
        return new SourceFile(batch.filename(), bytes);
    }

    public List<SettlementReversal> reversals() {
        return jdbc.sql(REVERSAL_SELECT + " ORDER BY r.id DESC").query(this::mapReversal).list();
    }

    @Transactional
    public SettlementReversal requestReversal(SettlementReversalRequest request) {
        String reason = requiredReason(request.reason());
        lockFee(request.feeId());
        Fee fee = fees.get(request.feeId());
        if (fee.status() != FeeStatus.PAID) {
            throw new AppException(HttpStatus.CONFLICT,
                text("只有已付款费用可以申请冲正。", "Only paid fees can be reversed."));
        }
        var active = jdbc.sql("SELECT id FROM settlement_payments WHERE fee_id = :id AND reversed_at IS NULL")
            .param("id", fee.id()).query(Long.class).optional();
        long paymentId = active.orElseGet(() -> jdbc.sql("""
            INSERT INTO settlement_payments (fee_id, amount, settlement_date, payment_method, bank_in_date,
                reference_number, received_from, legacy, posted_by, posted_at)
            VALUES (:feeId, :amount, :settlementDate, :paymentMethod, :bankInDate, :referenceNumber,
                :receivedFrom, TRUE, 'LEGACY_MIGRATION', :postedAt) RETURNING id
            """).param("feeId", fee.id()).param("amount", fee.amount()).param("settlementDate", fee.settlementDate())
            .param("paymentMethod", fee.paymentMethod()).param("bankInDate", fee.bankInDate())
            .param("referenceNumber", fee.referenceNumber()).param("receivedFrom", fee.receivedFrom())
            .param("postedAt", OffsetDateTime.now(clock)).query(Long.class).single());
        var pending = jdbc.sql("SELECT id FROM settlement_reversals WHERE payment_id = :paymentId AND status = 'PENDING_REVIEW'")
            .param("paymentId", paymentId).query(Long.class).optional();
        if (pending.isPresent()) {
            return reversal(pending.get(), false);
        }
        long id = jdbc.sql("""
            INSERT INTO settlement_reversals (fee_id, payment_id, status, reason, created_by, created_at)
            VALUES (:feeId, :paymentId, 'PENDING_REVIEW', :reason, :actor, :createdAt) RETURNING id
            """).param("feeId", fee.id()).param("paymentId", paymentId).param("reason", reason)
            .param("actor", audit.actor()).param("createdAt", OffsetDateTime.now(clock))
            .query(Long.class).single();
        SettlementReversal result = reversal(id, false);
        audit.record("PAYMENT_REVERSAL_REQUESTED", "SETTLEMENT_REVERSAL", Long.toString(id), null, result);
        return result;
    }

    @Transactional
    public SettlementReversal approveReversal(long id, String reason) {
        SettlementReversal before = reversal(id, true);
        if ("APPROVED".equals(before.status())) {
            return before;
        }
        requirePending(before.status());
        independent(before.createdBy());
        lockFee(before.feeId());
        Fee beforeFee = fees.get(before.feeId());
        boolean active = jdbc.sql("""
            SELECT EXISTS (SELECT 1 FROM settlement_payments WHERE id = :paymentId AND reversed_at IS NULL)
            """).param("paymentId", before.paymentId()).query(Boolean.class).single();
        if (!active || beforeFee.status() != FeeStatus.PAID) {
            throw new AppException(HttpStatus.CONFLICT,
                text("原付款状态已变更，请重新核对冲正申请。", "The original payment changed. Review the reversal request."));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        jdbc.sql("UPDATE settlement_payments SET reversed_at = :now WHERE id = :id")
            .param("now", now).param("id", before.paymentId()).update();
        jdbc.sql("""
            UPDATE fees SET status = 'UNPAID', settlement_date = NULL, payment_method = NULL,
                bank_in_date = NULL, reference_number = NULL, received_from = NULL WHERE id = :id
            """).param("id", before.feeId()).update();
        jdbc.sql("""
            UPDATE fee_documents SET valid = FALSE, voided_at = :now, void_reason = :reason, reversal_id = :reversalId
            WHERE fee_id = :feeId AND kind = 'RECEIPT' AND valid
            """).param("now", now).param("reason", before.reason()).param("reversalId", id)
            .param("feeId", before.feeId()).update();
        reviewReversal(id, "APPROVED", optionalReason(reason));
        SettlementReversal result = reversal(id, false);
        audit.record("PAYMENT_REVERSED", "FEE", Long.toString(before.feeId()), beforeFee, fees.get(before.feeId()));
        audit.record("PAYMENT_REVERSAL_APPROVED", "SETTLEMENT_REVERSAL", Long.toString(id), before, result);
        return result;
    }

    @Transactional
    public SettlementReversal rejectReversal(long id, String reason) {
        SettlementReversal before = reversal(id, true);
        requirePending(before.status());
        independent(before.createdBy());
        reviewReversal(id, "REJECTED", requiredReason(reason));
        SettlementReversal result = reversal(id, false);
        audit.record("PAYMENT_REVERSAL_REJECTED", "SETTLEMENT_REVERSAL", Long.toString(id), before, result);
        return result;
    }

    private SettlementBatch batch(long id, boolean lock) {
        return jdbc.sql("SELECT " + BATCH_COLUMNS + " FROM settlement_batches WHERE id = :id" + (lock ? " FOR UPDATE" : ""))
            .param("id", id).query(this::mapBatch).optional()
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND,
                text("未找到结算批次。", "Settlement batch not found.")));
    }

    private SettlementBatch detail(SettlementBatch batch) {
        List<SettlementBatch.Row> rows = jdbc.sql("SELECT " + ROW_COLUMNS
            + " FROM settlement_batch_rows WHERE batch_id = :id ORDER BY row_number")
            .param("id", batch.id()).query(this::mapRow).list();
        return new SettlementBatch(batch.id(), batch.type(), batch.filename(), batch.checksum(), batch.status(),
            batch.total(), batch.success(), batch.failed(), batch.amount(), batch.createdBy(), batch.createdAt(),
            batch.reviewedBy(), batch.reviewedAt(), batch.reason(), rows);
    }

    private SettlementReversal reversal(long id, boolean lock) {
        return jdbc.sql(REVERSAL_SELECT + " WHERE r.id = :id" + (lock ? " FOR UPDATE OF r" : ""))
            .param("id", id).query(this::mapReversal).optional()
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND,
                text("未找到冲正申请。", "Reversal request not found.")));
    }

    private boolean referenceInUse(String method, String reference) {
        return jdbc.sql("""
            SELECT EXISTS (
                SELECT 1 FROM settlement_payments WHERE reversed_at IS NULL
                    AND upper(btrim(payment_method)) = :method AND upper(btrim(reference_number)) = :reference
                UNION ALL
                SELECT 1 FROM fees WHERE status = 'PAID'
                    AND upper(btrim(payment_method)) = :method AND upper(btrim(reference_number)) = :reference
            )
            """).param("method", normalise(method)).param("reference", normalise(reference))
            .query(Boolean.class).single();
    }

    private void reviewBatch(long id, String status, String reason) {
        jdbc.sql("""
            UPDATE settlement_batches SET status = :status, reviewed_by = :actor, reviewed_at = :now,
                review_reason = :reason WHERE id = :id
            """).param("status", status).param("actor", audit.actor()).param("now", OffsetDateTime.now(clock))
            .param("reason", reason).param("id", id).update();
    }

    private void reviewReversal(long id, String status, String reason) {
        jdbc.sql("""
            UPDATE settlement_reversals SET status = :status, reviewed_by = :actor, reviewed_at = :now,
                review_reason = :reason WHERE id = :id
            """).param("status", status).param("actor", audit.actor()).param("now", OffsetDateTime.now(clock))
            .param("reason", reason).param("id", id).update();
    }

    private void independent(String maker) {
        if (maker.equals(audit.actor())) {
            throw new AppException(HttpStatus.FORBIDDEN,
                text("提交人与审批人必须不同。", "The maker and checker must be different users."));
        }
    }

    private void requirePending(String status) {
        if (!"PENDING_REVIEW".equals(status)) {
            throw new AppException(HttpStatus.CONFLICT,
                text("当前状态不能审批，请提交校验通过的新申请。", "This item cannot be reviewed in its current state."));
        }
    }

    private void lockFee(long id) {
        if (jdbc.sql("SELECT id FROM fees WHERE id = :id FOR UPDATE").param("id", id)
                .query(Long.class).optional().isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, text("未找到费用记录", "Fee not found."));
        }
    }

    private String requiredReason(String reason) {
        String value = optionalReason(reason);
        if (value == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, text("请填写原因。", "Enter a reason."));
        }
        return value;
    }

    private String optionalReason(String reason) {
        if (reason == null || reason.isBlank()) {
            return null;
        }
        String value = reason.strip();
        if (value.length() > 1000) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                text("原因最长 1000 个字符。", "The reason must be at most 1,000 characters."));
        }
        return value;
    }

    private String normalise(String value) {
        return value.strip().toUpperCase(Locale.ROOT);
    }

    private SettlementBatch mapBatch(ResultSet row, int number) throws SQLException {
        return new SettlementBatch(row.getLong("id"), row.getString("type"), row.getString("filename"),
            row.getString("checksum"), row.getString("status"), row.getInt("total"), row.getInt("successful"),
            row.getInt("failed"), row.getBigDecimal("amount"), row.getString("created_by"),
            row.getObject("created_at", OffsetDateTime.class), row.getString("reviewed_by"),
            row.getObject("reviewed_at", OffsetDateTime.class), row.getString("review_reason"), null);
    }

    private SettlementBatch.Row mapRow(ResultSet row, int number) throws SQLException {
        return new SettlementBatch.Row(row.getLong("id"), row.getInt("row_number"), row.getString("matching_reference"),
            row.getObject("fee_id", Long.class), row.getBigDecimal("amount"), row.getObject("settlement_date", LocalDate.class),
            row.getString("payment_method"), row.getObject("bank_in_date", LocalDate.class),
            row.getString("reference_number"), row.getString("received_from"), row.getBoolean("valid"),
            text(row.getString("message_zh"), row.getString("message_en")), row.getString("fee_snapshot"),
            row.getString("snapshot_hash"));
    }

    private SettlementReversal mapReversal(ResultSet row, int number) throws SQLException {
        return new SettlementReversal(row.getLong("id"), row.getLong("fee_id"), row.getLong("payment_id"),
            row.getString("status"), row.getString("reason"), row.getString("created_by"),
            row.getObject("created_at", OffsetDateTime.class), row.getString("reviewed_by"),
            row.getObject("reviewed_at", OffsetDateTime.class), row.getString("review_reason"),
            row.getBigDecimal("amount"), row.getString("reference_number"), row.getBoolean("legacy"));
    }

    public record SourceFile(String filename, byte[] content) {}
}
