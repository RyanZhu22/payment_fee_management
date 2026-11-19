package com.omsweb.fees.operations;

import static com.omsweb.fees.ApiMessages.text;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.omsweb.fees.AppException;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;
import com.omsweb.fees.FeeStatus;
import com.omsweb.fees.FeeType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.apache.poi.EncryptedDocumentException;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class SettlementImportService {
    enum Type {
        APPLN("applicationNumber"), FEESU("feeId");

        final List<String> headers;

        Type(String firstHeader) {
            headers = List.of(firstHeader, "amount", "settlementDate", "paymentMethod",
                "bankInDate", "referenceNumber", "receivedFrom");
        }
    }

    record PreviewRow(
        int row, String reference, Long feeId, BigDecimal amount, LocalDate settlementDate,
        String paymentMethod, LocalDate bankInDate, String referenceNumber, String receivedFrom,
        boolean valid, String messageZh, String messageEn, String feeSnapshot, String snapshotHash
    ) {
        PreviewRow invalid(String chinese, String english) {
            return new PreviewRow(row, reference, feeId, amount, settlementDate, paymentMethod,
                bankInDate, referenceNumber, receivedFrom, false, chinese, english, feeSnapshot, snapshotHash);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private final FeeService fees;

    SettlementImportService(FeeService fees) {
        this.fees = fees;
    }

    List<PreviewRow> preview(byte[] file, Type type) {
        var results = new ArrayList<PreviewRow>();
        List<Fee> records = fees.list();
        Map<Long, Fee> byId = records.stream().collect(Collectors.toMap(Fee::id, Function.identity()));
        Map<String, List<Fee>> applications = applicationFees(records);
        Set<Long> usedFees = new HashSet<>();
        Set<String> usedReferences = new HashSet<>();

        try (Workbook workbook = openWorkbook(file)) {
            Sheet sheet = workbook.getSheet(type.name());
            DataFormatter formatter = new DataFormatter(Locale.ROOT);
            if (sheet == null) {
                throw new AppException(HttpStatus.BAD_REQUEST,
                    text("工作簿需包含 %s 工作表。", "The workbook must contain the %s worksheet.").formatted(type));
            }
            validateHeader(sheet.getRow(0), type, formatter);
            for (Row row : sheet) {
                if (row.getRowNum() != 0 && !isEmpty(row, formatter)) {
                    PreviewRow parsed = parseRow(row, type, formatter, byId, applications);
                    if (parsed.valid() && !usedFees.add(parsed.feeId())) {
                        parsed = parsed.invalid("同一批次包含重复费用。", "The batch contains this fee more than once.");
                    }
                    if (parsed.valid() && !usedReferences.add(referenceKey(parsed.paymentMethod(), parsed.referenceNumber()))) {
                        parsed = parsed.invalid("同一批次包含重复渠道参考编号。", "The batch contains a duplicate channel reference.");
                    }
                    results.add(parsed);
                }
            }
        } catch (IOException error) {
            throw new AppException(HttpStatus.BAD_REQUEST, text("读取结算工作簿失败。", "The settlement workbook could not be read."));
        }
        if (results.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                text("工作簿没有可导入的结算记录。", "The workbook has no settlement records to import."));
        }
        return List.copyOf(results);
    }

    // Compatibility preflight for callers that used the former direct importer.
    ImportResult importFile(MultipartFile file, Type type) {
        try {
            return ImportResult.from(preview(file.getBytes(), type).stream()
                .map(row -> new ImportResult.Row(row.row(), row.reference(), row.valid(), text(row.messageZh(), row.messageEn())))
                .toList());
        } catch (IOException error) {
            throw new AppException(HttpStatus.BAD_REQUEST, text("读取结算工作簿失败。", "The settlement workbook could not be read."));
        }
    }

    byte[] template(Type type, LocalDate today) {
        return template(type, today, false);
    }

    byte[] template(Type type, LocalDate today, boolean example) {
        List<Fee> records = example ? fees.list() : List.of();
        Map<String, List<Fee>> applications = applicationFees(records);
        Fee sample = records.stream()
            .filter(fee -> fee.status() == FeeStatus.UNPAID)
            .filter(fee -> type == Type.FEESU || (fee.type() == FeeType.APPLICATION
                && fee.applicationNumber() != null && applications.get(fee.applicationNumber()).size() == 1))
            .findFirst().orElse(null);
        try (Workbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet(type.name());
            Row header = sheet.createRow(0);
            for (int column = 0; column < type.headers.size(); column++) {
                header.createCell(column).setCellValue(type.headers.get(column));
                sheet.setColumnWidth(column, 23 * 256);
            }
            if (sample != null) {
                Row row = sheet.createRow(1);
                List<String> values = List.of(type == Type.APPLN ? sample.applicationNumber() : Long.toString(sample.id()),
                    sample.amount().toPlainString(), today.toString(), "FPS", today.toString(),
                    "EXAMPLE-REPLACE-" + sample.id(), sample.payer());
                for (int column = 0; column < values.size(); column++) {
                    row.createCell(column).setCellValue(values.get(column));
                }
            }
            sheet.createFreezePane(0, 1);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            return output.toByteArray();
        } catch (IOException error) {
            throw new AppException(HttpStatus.INTERNAL_SERVER_ERROR,
                text("无法生成结算模板。", "The settlement template could not be generated."));
        }
    }

    static String snapshot(Fee fee) {
        var values = new LinkedHashMap<String, Object>();
        values.put("id", fee.id());
        values.put("type", fee.type().name());
        values.put("applicationNumber", fee.applicationNumber());
        values.put("scheme", fee.scheme());
        values.put("payer", fee.payer());
        values.put("payerEmail", fee.payerEmail());
        values.put("description", fee.description());
        values.put("amount", fee.amount().toPlainString());
        values.put("dueDate", fee.dueDate().toString());
        values.put("sourceFeeId", fee.sourceFeeId());
        values.put("status", fee.status().name());
        values.put("settlementDate", fee.settlementDate() == null ? null : fee.settlementDate().toString());
        values.put("paymentMethod", fee.paymentMethod());
        values.put("bankInDate", fee.bankInDate() == null ? null : fee.bankInDate().toString());
        values.put("referenceNumber", fee.referenceNumber());
        values.put("receivedFrom", fee.receivedFrom());
        try {
            return JSON.writeValueAsString(values);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Could not serialise a fee snapshot", error);
        }
    }

    static String checksum(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    static String referenceKey(String method, String reference) {
        return method.strip().toUpperCase(Locale.ROOT) + "\u0000" + reference.strip().toUpperCase(Locale.ROOT);
    }

    private Workbook openWorkbook(byte[] file) {
        try {
            return WorkbookFactory.create(new ByteArrayInputStream(file));
        } catch (IOException | EncryptedDocumentException | POIXMLException | IllegalArgumentException error) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                text("无法打开工作簿。请上传未加密的 .xls 或 .xlsx。", "Upload an unencrypted .xls or .xlsx workbook."));
        }
    }

    private void validateHeader(Row header, Type type, DataFormatter formatter) {
        if (header == null || header.getLastCellNum() != type.headers.size()) {
            throw new AppException(HttpStatus.BAD_REQUEST,
                text("结算表表头不符，请下载对应类型的模板。", "The headings do not match. Download the template for this workbook type."));
        }
        for (int column = 0; column < type.headers.size(); column++) {
            if (!formatter.formatCellValue(header.getCell(column)).strip().equals(type.headers.get(column))) {
                throw new AppException(HttpStatus.BAD_REQUEST,
                    text("结算表表头不符，请下载对应类型的模板。", "The headings do not match. Download the template for this workbook type."));
            }
        }
    }

    private boolean isEmpty(Row row, DataFormatter formatter) {
        for (Cell cell : row) {
            if (!formatter.formatCellValue(cell).isBlank()) {
                return false;
            }
        }
        return true;
    }

    private Map<String, List<Fee>> applicationFees(List<Fee> records) {
        return records.stream()
            .filter(fee -> fee.type() == FeeType.APPLICATION && fee.applicationNumber() != null)
            .collect(Collectors.groupingBy(Fee::applicationNumber));
    }

    private PreviewRow parseRow(Row row, Type type, DataFormatter formatter, Map<Long, Fee> byId,
                                Map<String, List<Fee>> applications) {
        String reference = formatter.formatCellValue(row.getCell(0)).strip();
        PreviewRow empty = new PreviewRow(row.getRowNum() + 1, reference, null, null, null,
            null, null, null, null, false, "", "", null, null);
        try {
            for (Cell cell : row) {
                if (cell.getCellType() == CellType.FORMULA) {
                    throw new InvalidRow("请将公式替换为静态值。", "Replace formulas with static values.");
                }
                if (cell.getColumnIndex() >= type.headers.size() && !formatter.formatCellValue(cell).isBlank()) {
                    throw new InvalidRow("数据列超出结算表头定义。", "The row contains extra values beyond the defined headings.");
                }
            }
            if (reference.isEmpty() || reference.length() > 120) {
                throw new InvalidRow("费用 ID 或申请编号缺失或过长。", "The fee ID or application number is missing or too long.");
            }
            BigDecimal amount = readAmount(row.getCell(1), formatter);
            LocalDate settlementDate = readDate(row.getCell(2), "结算日", "Settlement date", formatter);
            String paymentMethod = readText(row.getCell(3), "付款方式", "Payment method", 64, formatter);
            LocalDate bankInDate = readDate(row.getCell(4), "银行入账日", "Bank-in date", formatter);
            String channelReference = readText(row.getCell(5), "渠道参考编号", "Channel reference", 120, formatter);
            String receivedFrom = readText(row.getCell(6), "来款方", "Received from", 120, formatter);
            Fee fee;
            if (type == Type.APPLN) {
                List<Fee> matches = applications.getOrDefault(reference, List.of());
                if (matches.size() != 1) {
                    throw matches.isEmpty()
                        ? new InvalidRow("未找到对应的申请费。", "No matching application fee was found.")
                        : new InvalidRow("同一申请编号对应多笔申请费，请按费用 ID 导入。", "Several application fees match. Import using the fee ID.");
                }
                fee = matches.getFirst();
            } else {
                long id;
                try {
                    id = Long.parseLong(reference);
                } catch (NumberFormatException error) {
                    throw new InvalidRow("费用 ID 须为正整数。", "The fee ID must be a positive integer.");
                }
                fee = byId.get(id);
                if (fee == null) {
                    throw new InvalidRow("未找到对应费用。", "No matching fee was found.");
                }
            }
            String snapshot = snapshot(fee);
            PreviewRow parsed = new PreviewRow(row.getRowNum() + 1, reference, fee.id(), amount, settlementDate,
                paymentMethod, bankInDate, channelReference, receivedFrom, true,
                "校验通过，待独立审批。", "Validated and awaiting independent approval.",
                snapshot, checksum(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            if (fee.status() != FeeStatus.UNPAID) {
                return parsed.invalid("费用已结算，请核对重复记录。", "The fee is already settled. Check for duplicates.");
            }
            if (amount.compareTo(fee.amount()) != 0) {
                return parsed.invalid("实际付款金额与费用金额不符。", "The actual payment amount differs from the fee amount.");
            }
            return parsed;
        } catch (InvalidRow error) {
            return empty.invalid(error.chinese, error.english);
        }
    }

    private BigDecimal readAmount(Cell cell, DataFormatter formatter) {
        try {
            BigDecimal amount = cell != null && cell.getCellType() == CellType.NUMERIC
                ? BigDecimal.valueOf(cell.getNumericCellValue())
                : new BigDecimal(formatter.formatCellValue(cell).strip());
            if (amount.signum() > 0 && amount.stripTrailingZeros().scale() <= 2
                    && amount.compareTo(new BigDecimal("999999999999.99")) <= 0) {
                return amount.setScale(2);
            }
        } catch (NumberFormatException | ArithmeticException ignored) {
            // The row result below reports the amount validation failure.
        }
        throw new InvalidRow("实际付款金额须为正数并最多保留两位小数。", "The actual payment must be positive with at most two decimal places.");
    }

    private LocalDate readDate(Cell cell, String chinese, String english, DataFormatter formatter) {
        try {
            if (cell != null && cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
                return cell.getLocalDateTimeCellValue().toLocalDate();
            }
            return LocalDate.parse(formatter.formatCellValue(cell).strip());
        } catch (DateTimeParseException | IllegalStateException error) {
            throw new InvalidRow(chinese + "须为 Excel 日期或 YYYY-MM-DD。", english + " must be an Excel date or YYYY-MM-DD.");
        }
    }

    private String readText(Cell cell, String chinese, String english, int limit, DataFormatter formatter) {
        String value = formatter.formatCellValue(cell).strip();
        if (value.isEmpty() || value.length() > limit) {
            throw new InvalidRow(chinese + "不能为空且最长 " + limit + " 个字符。",
                english + " is required and must be at most " + limit + " characters.");
        }
        return value;
    }

    private static final class InvalidRow extends RuntimeException {
        final String chinese;
        final String english;

        InvalidRow(String chinese, String english) {
            this.chinese = chinese;
            this.english = english;
        }
    }
}
