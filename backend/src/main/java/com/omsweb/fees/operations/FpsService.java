package com.omsweb.fees.operations;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omsweb.fees.AppException;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;
import com.omsweb.fees.FeeStatus;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records demo FPS QR requests. Request and response payloads are stored separately and the
 * request number is linked to the fee, mirroring the original external hand-off.
 */
@Service
class FpsService {
    private static final Logger LOG = LoggerFactory.getLogger(FpsService.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String COLUMNS = "id, fee_id, request_number, expires_on, status, qr_payload";

    private final JdbcClient jdbc;
    private final FeeService fees;
    private final Clock clock;
    private final int expiryDays;
    private final String prefix;
    private final String qrBaseUrl;

    FpsService(
            JdbcClient jdbc,
            FeeService fees,
            Clock clock,
            @Value("${oms.fps.expiry-days:30}") int expiryDays,
            @Value("${oms.fps.prefix:FPS}") String prefix,
            @Value("${oms.fps.qr-base-url:https://fps.example.test/pay}") String qrBaseUrl
    ) {
        this.jdbc = jdbc;
        this.fees = fees;
        this.clock = clock;
        this.expiryDays = Math.max(1, expiryDays);
        this.prefix = prefix;
        String base = qrBaseUrl == null ? "" : qrBaseUrl.strip();
        this.qrBaseUrl = base.isEmpty() ? "https://fps.example.test/pay" : base;
    }

    List<FpsRequest> list() {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fps_requests ORDER BY id DESC")
                .query(this::map)
                .list();
    }

    FpsRequest get(long id) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM fps_requests WHERE id = :id")
                .param("id", id)
                .query(this::map)
                .optional()
                .orElseThrow(() -> new AppException(HttpStatus.NOT_FOUND, "未找到 FPS 请求"));
    }

    /**
     * Reuses an unexpired demo request so a regenerated notice keeps the same QR, and creates a
     * new request when the fee has no active one.
     */
    @Transactional
    FpsRequest activeOrCreate(long feeId) {
        LocalDate today = LocalDate.now(clock);
        return jdbc.sql("""
                SELECT id, fee_id, request_number, expires_on, status, qr_payload
                FROM fps_requests
                WHERE fee_id = :feeId AND status = 'DEMO' AND expires_on >= :today
                ORDER BY id DESC
                LIMIT 1
                """)
                .param("feeId", feeId)
                .param("today", today)
                .query(this::map)
                .optional()
                .orElseGet(() -> create(feeId));
    }

    @Transactional
    FpsRequest create(long feeId) {
        Fee fee = fees.get(feeId);
        if (fee.status() != FeeStatus.UNPAID) {
            throw new AppException(HttpStatus.BAD_REQUEST, "只有未结算的费用可以生成 FPS 二维码请求");
        }

        LocalDate today = LocalDate.now(clock);
        LocalDate expiresOn = today.plusDays(expiryDays);
        long sequence = jdbc.sql("SELECT nextval('fps_request_number_seq')").query(Long.class).single();
        String number = "%s-%d-%06d".formatted(normalisedPrefix(), today.getYear(), sequence);
        String amount = fee.amount().toPlainString();
        String separator = qrBaseUrl.contains("?") ? "&" : "?";
        String qrText = qrBaseUrl + separator
                + "ref=" + number
                + "&fee=FEE-%04d".formatted(fee.id())
                + "&amount=" + amount
                + "&currency=HKD"
                + "&expires=" + expiresOn;

        var requestData = new LinkedHashMap<String, Object>();
        requestData.put("requestNumber", number);
        requestData.put("feeId", fee.id());
        requestData.put("feeReference", "FEE-%04d".formatted(fee.id()));
        requestData.put("amount", amount);
        requestData.put("currency", "HKD");
        requestData.put("payee", fee.payer());
        requestData.put("generatedOn", today.toString());
        requestData.put("expiresOn", expiresOn.toString());
        requestData.put("qrContent", qrText);
        Map<String, Object> responseData = Map.of(
                "status", "DEMO",
                "message", "演示请求，未调用真实 FPS 接口。");

        return jdbc.sql("""
            INSERT INTO fps_requests (fee_id, request_number, created_at, expires_on, status, qr_payload,
                request_data, response_data)
            VALUES (:feeId, :number, :createdAt, :expiresOn, 'DEMO', :qrText,
                CAST(:requestData AS jsonb), CAST(:responseData AS jsonb))
            RETURNING
            """ + COLUMNS)
            .param("feeId", fee.id())
            .param("number", number)
            .param("createdAt", OffsetDateTime.now(clock))
            .param("expiresOn", expiresOn)
            .param("qrText", qrText)
            .param("requestData", write(requestData))
            .param("responseData", write(responseData))
            .query(this::map)
            .single();
    }

    private String normalisedPrefix() {
        String normalised = prefix == null ? "" : prefix.strip().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        return normalised.isEmpty() ? "FPS" : normalised;
    }

    private String write(Object value) {
        try {
            return JSON.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            LOG.error("Could not serialise the FPS request payload", error);
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR, "无法保存 FPS 请求数据");
        }
    }

    private FpsRequest map(ResultSet row, int rowNumber) throws SQLException {
        return new FpsRequest(
                row.getLong("id"),
                row.getLong("fee_id"),
                row.getString("request_number"),
                row.getObject("expires_on", LocalDate.class),
                row.getString("status"),
                row.getString("qr_payload"));
    }
}
