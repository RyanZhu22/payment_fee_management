package com.omsweb.fees.operations;

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

class FpsServiceTest {
    private static final Clock CLOCK =
        Clock.fixed(Instant.parse("2026-11-17T04:00:00Z"), ZoneId.of("Asia/Hong_Kong"));

    private final FeeService fees = mock(FeeService.class);
    private final FpsService requests = new FpsService(mock(JdbcClient.class), fees, CLOCK, 30, "FPS", "https://fps.example.test/pay");

    @Test
    void requestRequiresAnUnsettledFee() {
        when(fees.get(1)).thenReturn(new Fee(1, FeeType.PERIODIC, null, "维港退休计划", "维港受托有限公司", null,
            "计划定期费用", new BigDecimal("7800.00"), LocalDate.parse("2026-11-30"), null, FeeStatus.PAID,
            LocalDate.parse("2026-11-10"), "FPS", LocalDate.parse("2026-11-11"), "REF", "维港受托有限公司"));

        assertThatThrownBy(() -> requests.create(1))
            .isInstanceOf(AppException.class)
            .hasMessageContaining("未结算");
    }
}
