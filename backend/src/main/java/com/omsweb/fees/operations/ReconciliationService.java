package com.omsweb.fees.operations;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PushbackReader;
import java.math.BigDecimal;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import com.omsweb.fees.ApiMessages;
import com.omsweb.fees.AppException;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
class ReconciliationService {
    private static final List<String> HEADERS = List.of("reference", "feeId", "amount", "bankInDate");
    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .get();

    record Converted(String reference, long feeId, BigDecimal amount, LocalDate bankInDate) {
    }

    record Result(int total, int success, int failed, List<ImportResult.Row> rows, List<Converted> converted) {
    }

    private final FeeService fees;

    ReconciliationService(FeeService fees) {
        this.fees = fees;
    }

    Result convert(MultipartFile file) {
        List<CSVRecord> records = readRecords(file);
        Map<Long, Fee> byId = fees.list().stream().collect(Collectors.toMap(Fee::id, Function.identity()));
        List<ImportResult.Row> results = new ArrayList<>();
        List<Converted> converted = new ArrayList<>();
        for (int index = 0; index < records.size(); index++) {
            CSVRecord row = records.get(index);
            String reference = row.size() > 0 ? row.get(0) : "";
            try {
                converted.add(convertRow(row, byId));
                results.add(new ImportResult.Row(index + 2, reference, true, ApiMessages.translate("已转换，供复核。")));
            } catch (AppException error) {
                results.add(new ImportResult.Row(index + 2, reference, false, ApiMessages.translate(error.getMessage())));
            }
        }
        ImportResult summary = ImportResult.from(results);
        return new Result(summary.total(), summary.success(), summary.failed(), summary.rows(), List.copyOf(converted));
    }

    byte[] template(LocalDate today) {
        String header = String.join(",", HEADERS) + "\n";
        String example = fees.list().stream().findFirst()
                .map(fee -> "FPS-DEMO-" + fee.id() + "," + fee.id() + "," + fee.amount().toPlainString() + "," + today + "\n")
                .orElse("");
        return (header + example).getBytes(StandardCharsets.UTF_8);
    }

    private List<CSVRecord> readRecords(MultipartFile file) {
        var decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try (var reader = new PushbackReader(new InputStreamReader(file.getInputStream(), decoder))) {
            int first = reader.read();
            if (first != -1 && first != '\ufeff') {
                reader.unread(first);
            }
            try (CSVParser parser = FORMAT.parse(reader)) {
                if (!parser.getHeaderNames().equals(HEADERS)) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "CSV 表头不符，请下载 FPS 对账演示模板。");
                }
                List<CSVRecord> records = parser.getRecords();
                if (records.isEmpty()) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "CSV 没有可转换的对账记录。");
                }
                return records;
            }
        } catch (IOException | IllegalArgumentException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CSV 无法读取，请使用 UTF-8 编码和演示模板格式。");
        }
    }

    private Converted convertRow(CSVRecord row, Map<Long, Fee> fees) {
        if (row.size() != HEADERS.size() || row.get(0).isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "对账记录缺少参考编号或列数有误。");
        }
        try {
            long feeId = Long.parseLong(row.get(1));
            BigDecimal amount = new BigDecimal(row.get(2));
            LocalDate bankInDate = LocalDate.parse(row.get(3));
            Fee fee = fees.get(feeId);
            if (fee == null) {
                throw new AppException(HttpStatus.BAD_REQUEST, "未找到对应费用。");
            }
            if (amount.compareTo(fee.amount()) != 0) {
                throw new AppException(HttpStatus.BAD_REQUEST, "对账金额与费用金额不同。");
            }
            return new Converted(row.get(0), feeId, fee.amount(), bankInDate);
        } catch (NumberFormatException | DateTimeParseException e) {
            throw new AppException(HttpStatus.BAD_REQUEST, "费用 ID、金额或银行入账日格式无效。");
        }
    }
}
