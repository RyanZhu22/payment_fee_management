package com.omsweb.fees.operations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.omsweb.fees.AppException;
import com.omsweb.fees.Fee;
import com.omsweb.fees.FeeService;
import com.omsweb.fees.FeeStatus;
import com.omsweb.fees.FeeType;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;

class DocumentServiceTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"));

    private final JdbcClient jdbc = mock(JdbcClient.class);
    private final FeeService fees = mock(FeeService.class);
    private final DocumentService documents = new DocumentService(jdbc, fees, mock(DocumentPdfRenderer.class),
        mock(DocumentStorage.class), mock(FpsService.class), mock(QrCodeRenderer.class), CLOCK, "NOT", "REM", "RCP",
        "https://fps.example.test/receipt");

    @Test
    void noticeRequiresAnUnsettledFee() {
        when(fees.get(1)).thenReturn(fee(FeeStatus.PAID, "2026-11-01"));

        assertThatThrownBy(() -> documents.generate(new DocumentRequest(1, DocumentKind.NOTICE)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("未结算");
    }

    @Test
    void reminderRequiresAnOverdueFee() {
        when(fees.get(2)).thenReturn(fee(FeeStatus.UNPAID, "2026-11-30"));

        assertThatThrownBy(() -> documents.generate(new DocumentRequest(2, DocumentKind.REMINDER)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("尚未逾期");
    }

    @Test
    void receiptRequiresASettledFee() {
        when(fees.get(3)).thenReturn(fee(FeeStatus.UNPAID, "2026-11-01"));

        assertThatThrownBy(() -> documents.generate(new DocumentRequest(3, DocumentKind.RECEIPT)))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("已结算");
    }

    @Test
    void rejectsIdsThatDoNotExist() {
        when(fees.get(4)).thenThrow(new AppException(org.springframework.http.HttpStatus.NOT_FOUND, "未找到费用记录"));

        assertThatThrownBy(() -> documents.generate(new DocumentRequest(4, DocumentKind.NOTICE)))
            .isInstanceOf(AppException.class)
            .satisfies(error -> assertThat(((AppException) error).status().value()).isEqualTo(404));
    }

    private Fee fee(FeeStatus status, String dueDate) {
        return new Fee(1, FeeType.PERIODIC, null, "维港退休计划", "维港受托有限公司", null, "计划定期费用",
            new BigDecimal("7800.00"), LocalDate.parse(dueDate), null, status, null, null, null, null, null);
    }
}
