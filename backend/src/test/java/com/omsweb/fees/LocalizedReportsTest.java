package com.omsweb.fees;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LocalizedReportsTest {
    private final MockMvc mvc = MockMvcBuilders
        .standaloneSetup(new ReportController(new ReportService(mock(FeeService.class), Clock.systemUTC()), mock(AuditTrail.class)))
        .setLocaleResolver(new ApiLocaleConfiguration().localeResolver())
        .setControllerAdvice(new ApiErrors())
        .build();

    @Test
    void reportDefaultsToChineseAndUsesTheLanguageHeaderWhenProvided() throws Exception {
        mvc.perform(get("/api/reports").param("type", "fees"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns[0]").value("费用编号"));

        mvc.perform(get("/api/reports").param("type", "fees").header(HttpHeaders.ACCEPT_LANGUAGE, "en-HK"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.columns[0]").value("Fee ID"));
    }

    @Test
    void reportDownloadHonoursExplicitLanguageAndKeepsFisHeadersStable() throws Exception {
        mvc.perform(get("/api/reports/download").param("type", "fees").param("lang", "en")
                .header(HttpHeaders.ACCEPT_LANGUAGE, "zh-HK"))
            .andExpect(status().isOk())
            .andExpect(content().string(containsString("\"Fee ID\",\"Application number\"")));

        mvc.perform(get("/api/fis/export").param("lang", "en"))
            .andExpect(status().isOk())
            .andExpect(content().string(startsWith("\"transaction_reference\",\"amount\"")));
    }

    @Test
    void localizesApiErrors() throws Exception {
        mvc.perform(get("/api/reports").param("type", "unknown").param("lang", "en"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.message").value("Select a valid report type."));
    }
}
