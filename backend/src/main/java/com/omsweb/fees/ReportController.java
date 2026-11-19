package com.omsweb.fees;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReportController {
    private static final MediaType CSV = new MediaType("text", "csv", StandardCharsets.UTF_8);
    private final ReportService reports;
    private final AuditTrail audit;

    ReportController(ReportService reports, AuditTrail audit) {
        this.reports = reports;
        this.audit = audit;
    }

    @GetMapping("/api/reports")
    ResponseEntity<ReportService.Report> report(@RequestParam String type,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ResponseEntity.ok().varyBy(HttpHeaders.ACCEPT_LANGUAGE)
            .body(reports.report(type, from, to));
    }

    @GetMapping("/api/reports/download")
    ResponseEntity<String> download(@RequestParam String type,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var report = reports.report(type, from, to);
        audit.record("REPORT_EXPORTED", "REPORT", type, null, new ExportScope(from, to, report.rows().size()));
        return csv(type + ".csv", "\uFEFF" + report.csv());
    }

    @GetMapping("/api/fis/export")
    ResponseEntity<String> fis(
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var report = reports.fis(from, to);
        audit.record("FIS_EXPORTED", "REPORT", "fis-demo", null, new ExportScope(from, to, report.rows().size()));
        return csv("fis-demo.csv", report.csv());
    }

    private record ExportScope(LocalDate from, LocalDate to, int rowCount) {}

    private ResponseEntity<String> csv(String filename, String body) {
        return ResponseEntity.ok().varyBy(HttpHeaders.ACCEPT_LANGUAGE).contentType(CSV)
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
            .body(body);
    }
}
