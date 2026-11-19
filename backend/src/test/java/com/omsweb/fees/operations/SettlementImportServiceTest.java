package com.omsweb.fees.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.omsweb.fees.AppException;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;
import com.omsweb.fees.FeeStatus;
import com.omsweb.fees.FeeType;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class SettlementImportServiceTest {
    private final FeeService fees = mock(FeeService.class);
    private final SettlementImportService service = new SettlementImportService(fees);
    private final Fee application = new Fee(10, FeeType.APPLICATION, "APP-102", "启航企业计划", "启航受托服务",
        "finance@example.test", "计划变更申请费", new BigDecimal("1800.00"), LocalDate.parse("2026-11-23"),
        null, FeeStatus.UNPAID, null, null, null, null, null);

    @BeforeEach
    void records() {
        when(fees.list()).thenReturn(List.of(application));
    }

    @Test
    void previewValidatesAmountAndNeverPostsMoney() throws Exception {
        ImportResult result = service.importFile(file("APPLN", values("1800.00", "FPS-1001")), SettlementImportService.Type.APPLN);

        assertThat(result.success()).isEqualTo(1);
        assertThat(result.rows().getFirst().message()).contains("待独立审批");
        verify(fees, never()).settle(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void mismatchedAmountAndMissingChannelReferenceAreRowErrors() throws Exception {
        ImportResult wrongAmount = service.importFile(file("APPLN", values("1799.99", "FPS-1001")), SettlementImportService.Type.APPLN);
        ImportResult missingReference = service.importFile(file("APPLN", values("1800.00", "")), SettlementImportService.Type.APPLN);

        assertThat(wrongAmount.failed()).isEqualTo(1);
        assertThat(wrongAmount.rows().getFirst().message()).contains("实际付款金额");
        assertThat(missingReference.rows().getFirst().message()).contains("渠道参考编号");
        verify(fees, never()).settle(anyLong(), any(), any(), any(), any(), any());
    }

    @Test
    void unknownApplicationNumberIsReportedPerRow() throws Exception {
        String[] values = values("1800.00", "FPS-1001");
        values[0] = "APP-MISSING";
        ImportResult result = service.importFile(file("APPLN", values), SettlementImportService.Type.APPLN);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.rows().getFirst().message()).contains("未找到对应的申请费");
    }

    @Test
    void repeatedFeeIsRejectedInSameWorkbook() throws Exception {
        byte[] bytes = workbook(new XSSFWorkbook(), "APPLN", values("1800.00", "FPS-1"), values("1800.00", "FPS-2"));
        var rows = service.preview(bytes, SettlementImportService.Type.APPLN);

        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).valid()).isTrue();
        assertThat(rows.get(1).valid()).isFalse();
        assertThat(rows.get(1).messageZh()).contains("重复费用");
    }

    @Test
    void supportsLegacyXlsWithRealNumericAmount() throws Exception {
        String[] values = values("1800.00", "FPS-1001");
        values[0] = "10";
        byte[] bytes = workbook(new HSSFWorkbook(), "FEESU", values);
        try (Workbook workbook = WorkbookFactory.create(new ByteArrayInputStream(bytes))) {
            workbook.getSheet("FEESU").getRow(1).getCell(1).setCellValue(1800.0);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            var rows = service.preview(output.toByteArray(), SettlementImportService.Type.FEESU);
            assertThat(rows.getFirst().valid()).isTrue();
            assertThat(rows.getFirst().amount()).isEqualByComparingTo("1800.00");
        }
    }

    @Test
    void rejectsWorkbookWithoutMatchingSheetOrWrongHeadings() throws Exception {
        byte[] bytes = workbook(new XSSFWorkbook(), "FEESU", values("1800.00", "FPS-1001"));
        assertThatThrownBy(() -> service.preview(bytes, SettlementImportService.Type.APPLN))
            .isInstanceOf(AppException.class).hasMessageContaining("APPLN 工作表");

        try (Workbook workbook = new XSSFWorkbook()) {
            var sheet = workbook.createSheet("APPLN");
            sheet.createRow(0).createCell(0).setCellValue("applicationNumber");
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            workbook.write(output);
            assertThatThrownBy(() -> service.preview(output.toByteArray(), SettlementImportService.Type.APPLN))
                .isInstanceOf(AppException.class).hasMessageContaining("表头不符");
        }
    }

    @Test
    void blankTemplateAndExampleAreDistinct() throws Exception {
        LocalDate today = LocalDate.parse("2026-11-19");
        try (Workbook blank = WorkbookFactory.create(new ByteArrayInputStream(service.template(SettlementImportService.Type.APPLN, today)));
             Workbook example = WorkbookFactory.create(new ByteArrayInputStream(service.template(SettlementImportService.Type.APPLN, today, true)))) {
            assertThat(blank.getSheet("APPLN").getPhysicalNumberOfRows()).isEqualTo(1);
            assertThat(example.getSheet("APPLN").getRow(1).getCell(1).getStringCellValue()).isEqualTo("1800.00");
            assertThat(blank.getSheet("APPLN").getRow(0).getCell(1).getStringCellValue()).isEqualTo("amount");
        }
    }

    @Test
    void immutableSnapshotHashChangesWhenFeeChanges() {
        String original = SettlementImportService.snapshot(application);
        Fee changed = new Fee(application.id(), application.type(), application.applicationNumber(), application.scheme(),
            application.payer(), application.payerEmail(), application.description(), new BigDecimal("1900.00"),
            application.dueDate(), null, application.status(), null, null, null, null, null);
        assertThat(SettlementImportService.checksum(original.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .isNotEqualTo(SettlementImportService.checksum(SettlementImportService.snapshot(changed).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    }

    private String[] values(String amount, String reference) {
        return new String[] {"APP-102", amount, "2026-11-18", "FPS", "2026-11-19", reference, "启航受托服务"};
    }

    private MockMultipartFile file(String sheetName, String[] values) throws Exception {
        return new MockMultipartFile("file", "settlement.xlsx",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            workbook(new XSSFWorkbook(), sheetName, values));
    }

    private byte[] workbook(Workbook workbook, String sheetName, String[]... values) throws Exception {
        try (workbook; ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            var sheet = workbook.createSheet(sheetName);
            Row header = sheet.createRow(0);
            String[] headings = {sheetName.equals("FEESU") ? "feeId" : "applicationNumber", "amount",
                "settlementDate", "paymentMethod", "bankInDate", "referenceNumber", "receivedFrom"};
            for (int column = 0; column < headings.length; column++) {
                header.createCell(column).setCellValue(headings[column]);
            }
            for (int rowNumber = 0; rowNumber < values.length; rowNumber++) {
                Row row = sheet.createRow(rowNumber + 1);
                for (int column = 0; column < values[rowNumber].length; column++) {
                    row.createCell(column).setCellValue(values[rowNumber][column]);
                }
            }
            workbook.write(output);
            return output.toByteArray();
        }
    }
}
