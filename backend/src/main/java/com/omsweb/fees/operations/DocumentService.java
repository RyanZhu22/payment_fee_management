package com.omsweb.fees.operations;

import com.omsweb.fees.ApiMessages;
import com.omsweb.fees.AppException;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;
import com.omsweb.fees.FeeStatus;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ResourceBundle;
import net.sf.jasperreports.engine.JRParameter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Generates and stores fee notices, reminders and receipts. Numbers are assigned from a
 * database sequence using the configured prefixes; the PDF is rendered before the row is
 * written so a document record always points at stored content.
 */
@Service
public class DocumentService {
    public static final ZoneId HONG_KONG = ZoneId.of("Asia/Hong_Kong");

    private static final Logger LOG = LoggerFactory.getLogger(DocumentService.class);
    private static final String COLUMNS = """
        id, fee_id, kind, document_number, generated_at, generated_on, delivery_status,
        recipient_email, bank_in_date, amount, storage_key, valid, voided_at, void_reason, reversal_id
        """;

    private final JdbcClient jdbc;
    private final FeeService fees;
    private final DocumentPdfRenderer renderer;
    private final DocumentStorage storage;
    private final FpsService fpsRequests;
    private final QrCodeRenderer qrCodes;
    private final Clock clock;
    private final String noticePrefix;
    private final String reminderPrefix;
    private final String receiptPrefix;
    private final String receiptQrBaseUrl;

    public DocumentService(
            JdbcClient jdbc,
            FeeService fees,
            DocumentPdfRenderer renderer,
            DocumentStorage storage,
            FpsService fpsRequests,
            QrCodeRenderer qrCodes,
            Clock clock,
            @Value("${oms.documents.prefix.notice:NOT}") String noticePrefix,
            @Value("${oms.documents.prefix.reminder:REM}") String reminderPrefix,
            @Value("${oms.documents.prefix.receipt:RCP}") String receiptPrefix,
            @Value("${oms.documents.receipt-qr-base-url:https://fps.example.test/receipt}") String receiptQrBaseUrl
    ) {
        this.jdbc = jdbc;
        this.fees = fees;
        this.renderer = renderer;
        this.storage = storage;
        this.fpsRequests = fpsRequests;
        this.qrCodes = qrCodes;
        this.clock = clock;
        this.noticePrefix = noticePrefix;
        this.reminderPrefix = reminderPrefix;
        this.receiptPrefix = receiptPrefix;
        String base = receiptQrBaseUrl == null ? "" : receiptQrBaseUrl.strip();
        this.receiptQrBaseUrl = base.isEmpty() ? "https://fps.example.test/receipt" : base;
    }

    public List<FeeDocument> list(DocumentKind kind, LocalDate generatedOn, LocalDate bankInDate) {
        var sql = new StringBuilder("SELECT ").append(COLUMNS).append(" FROM fee_documents");
        var conditions = new ArrayList<String>();
        if (kind != null) {
            conditions.add("kind = :kind");
        }
        if (generatedOn != null) {
            conditions.add("generated_on = :generatedOn");
        }
        if (bankInDate != null) {
            conditions.add("bank_in_date = :bankInDate");
        }
        if (!conditions.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", conditions));
        }
        sql.append(" ORDER BY generated_at DESC, id DESC");

        JdbcClient.StatementSpec statement = jdbc.sql(sql.toString());
        if (kind != null) {
            statement = statement.param("kind", kind.name());
        }
        if (generatedOn != null) {
            statement = statement.param("generatedOn", generatedOn);
        }
        if (bankInDate != null) {
            statement = statement.param("bankInDate", bankInDate);
        }
        return statement.query(this::map).list();
    }

    @Transactional
    public FeeDocument generate(DocumentRequest request) {
        Fee fee = fees.get(request.feeId());
        if (request.kind() == DocumentKind.RECEIPT && fee.status() == FeeStatus.PAID) {
            jdbc.sql("SELECT id FROM fees WHERE id = :id FOR UPDATE")
                .param("id", fee.id()).query(Long.class).single();
            fee = fees.get(request.feeId());
        }
        LocalDate today = LocalDate.now(clock);
        validate(fee, request.kind(), today);

        long sequence = jdbc.sql("SELECT nextval('fee_document_number_seq')").query(Long.class).single();
        String number = "%s-%d-%06d".formatted(prefix(request.kind()), today.getYear(), sequence);
        String storageKey = "%d/%s.pdf".formatted(today.getYear(), number);

        byte[] pdf = renderer.render(request.kind(), parameters(fee, request.kind(), number, today));
        storage.write(storageKey, pdf);

        OffsetDateTime generatedAt = OffsetDateTime.now(clock);
        try {
            return jdbc.sql("""
                INSERT INTO fee_documents (fee_id, kind, document_number, generated_at, generated_on,
                    delivery_status, recipient_email, bank_in_date, amount, storage_key)
                VALUES (:feeId, :kind, :number, :generatedAt, :generatedOn, 'NOT_SENT',
                    :recipientEmail, :bankInDate, :amount, :storageKey)
                RETURNING
                """ + COLUMNS)
                .param("feeId", fee.id())
                .param("kind", request.kind().name())
                .param("number", number)
                .param("generatedAt", generatedAt)
                .param("generatedOn", today)
                .param("recipientEmail", blankToNull(fee.payerEmail()), Types.VARCHAR)
                .param("bankInDate", request.kind() == DocumentKind.RECEIPT ? fee.bankInDate() : null, Types.DATE)
                .param("amount", fee.amount())
                .param("storageKey", storageKey)
                .query(this::map)
                .single();
        } catch (DuplicateKeyException error) {
            throw new AppException(HttpStatus.CONFLICT, "该费用已出具收据，请核对文档列表");
        }
    }

    public DownloadedDocument download(long id) {
        FeeDocument document = jdbc.sql("SELECT " + COLUMNS + " FROM fee_documents WHERE id = :id")
            .param("id", id)
            .query(this::map)
            .optional()
            .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "未找到文档记录"));
        return new DownloadedDocument(document, storage.read(document.storageKey()));
    }

    private void validate(Fee fee, DocumentKind kind, LocalDate today) {
        switch (kind) {
            case NOTICE -> {
                if (fee.status() != FeeStatus.UNPAID) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "只有未结算的费用可以生成缴费通知");
                }
            }
            case REMINDER -> {
                if (fee.status() != FeeStatus.UNPAID) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "只有未结算的费用可以生成催缴提醒");
                }
                if (!fee.dueDate().isBefore(today)) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "费用尚未逾期，暂不需要发送催缴提醒");
                }
            }
            case RECEIPT -> {
                if (fee.status() != FeeStatus.PAID) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "只有已结算的费用可以生成收据");
                }
                if (jdbc.sql("""
                    SELECT EXISTS (SELECT 1 FROM fee_documents WHERE fee_id = :feeId AND kind = 'RECEIPT' AND valid)
                    """).param("feeId", fee.id()).query(Boolean.class).single()) {
                    throw new AppException(HttpStatus.CONFLICT, "该费用已出具收据，请核对文档列表");
                }
            }
        }
    }

    private Map<String, Object> parameters(Fee fee, DocumentKind kind, String number, LocalDate today) {
        Locale locale = ApiMessages.isEnglish() ? Locale.ENGLISH : Locale.ROOT;
        ResourceBundle labels = ResourceBundle.getBundle("reports.messages", locale);
        var parameters = new HashMap<String, Object>();
        parameters.put(JRParameter.REPORT_LOCALE, locale);
        parameters.put(JRParameter.REPORT_RESOURCE_BUNDLE, labels);
        parameters.put("docNumber", number);
        parameters.put("docDate", today.toString());
        parameters.put("feeReference", "FEE-%04d".formatted(fee.id()));
        parameters.put("payer", fee.payer());
        parameters.put("payerEmail", fee.payerEmail() == null ? "" : fee.payerEmail());
        parameters.put("scheme", fee.scheme());
        parameters.put("applicationNumber", orDash(fee.applicationNumber()));
        parameters.put("description", fee.description());
        parameters.put("feeTypeLabel", labels.getString("feeType." + fee.type().name()));
        parameters.put("amount", money(fee.amount()));
        parameters.put("dueDate", fee.dueDate().toString());
        parameters.put("overdueDays", Math.max(0, ChronoUnit.DAYS.between(fee.dueDate(), today)));
        parameters.put("settlementDate", orDash(fee.settlementDate()));
        parameters.put("paymentMethod", paymentMethodLabel(fee.paymentMethod(), labels));
        parameters.put("bankInDate", orDash(fee.bankInDate()));
        parameters.put("referenceNumber", orDash(fee.referenceNumber()));
        parameters.put("receivedFrom", orDash(fee.receivedFrom() == null ? fee.payer() : fee.receivedFrom()));

        if (kind == DocumentKind.RECEIPT) {
            parameters.put("qrLabel", labels.getString("qr.receipt"));
            parameters.put("qrDetail", labels.getString("qr.receiptDetail").formatted(number));
            parameters.put("qrImage", new ByteArrayInputStream(qrCodes.png(receiptQrContent(fee, number))));
        } else {
            FpsRequest request = fpsRequests.activeOrCreate(fee.id());
            parameters.put("qrLabel", labels.getString("qr.fps"));
            parameters.put("qrDetail", labels.getString("qr.fpsDetail").formatted(request.reference(), request.expiresOn()));
            parameters.put("qrImage", new ByteArrayInputStream(qrCodes.png(request.qrText())));
        }
        return parameters;
    }

    private String receiptQrContent(Fee fee, String number) {
        String separator = receiptQrBaseUrl.contains("?") ? "&" : "?";
        return receiptQrBaseUrl + separator
                + "no=" + number
                + "&fee=FEE-%04d".formatted(fee.id())
                + "&amount=" + fee.amount().toPlainString()
                + "&paid=" + orDash(fee.bankInDate());
    }

    private String prefix(DocumentKind kind) {
        String configured = switch (kind) {
            case NOTICE -> noticePrefix;
            case REMINDER -> reminderPrefix;
            case RECEIPT -> receiptPrefix;
        };
        String normalised = configured == null ? "" : configured.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (!normalised.isEmpty()) {
            return normalised;
        }
        return switch (kind) {
            case NOTICE -> "NOT";
            case REMINDER -> "REM";
            case RECEIPT -> "RCP";
        };
    }

    private String paymentMethodLabel(String paymentMethod, ResourceBundle labels) {
        if (paymentMethod == null || paymentMethod.isBlank()) {
            return "—";
        }
        String key = "payment." + paymentMethod.strip().toUpperCase(Locale.ROOT);
        return labels.containsKey(key) ? labels.getString(key) : paymentMethod;
    }

    private String money(BigDecimal amount) {
        var format = new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.UK));
        return "HKD " + format.format(amount);
    }

    private String orDash(String value) {
        return value == null || value.isBlank() ? "—" : value;
    }

    private String orDash(LocalDate value) {
        return value == null ? "—" : value.toString();
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private FeeDocument map(ResultSet row, int rowNumber) throws SQLException {
        return new FeeDocument(
            row.getLong("id"),
            row.getLong("fee_id"),
            DocumentKind.valueOf(row.getString("kind")),
            row.getString("document_number"),
            row.getObject("generated_at", OffsetDateTime.class),
            row.getObject("generated_on", LocalDate.class),
            DeliveryStatus.valueOf(row.getString("delivery_status")),
            row.getString("recipient_email"),
            row.getObject("bank_in_date", LocalDate.class),
            row.getBigDecimal("amount"),
            row.getString("storage_key"),
            row.getBoolean("valid"),
            row.getObject("voided_at", OffsetDateTime.class),
            row.getString("void_reason"),
            row.getObject("reversal_id", Long.class)
        );
    }

    public record DownloadedDocument(FeeDocument document, byte[] content) {}
}
