package com.omsweb.fees.operations;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/settlements")
class SettlementController {
    private static final MediaType XLSX =
        MediaType.parseMediaType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");

    private final SettlementImportService importer;
    private final SettlementBatchService batches;
    private final boolean demoEnabled;

    SettlementController(SettlementImportService importer, SettlementBatchService batches,
                         @Value("${oms.demo.enabled:true}") boolean demoEnabled) {
        this.importer = importer;
        this.batches = batches;
        this.demoEnabled = demoEnabled;
    }

    @PostMapping(path = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    Preflight upload(@RequestParam MultipartFile file, @RequestParam SettlementImportService.Type type) {
        SettlementBatch batch = batches.submit(file, type);
        List<ImportResult.Row> rows = batch.rows().stream()
            .map(row -> new ImportResult.Row(row.row(), row.reference(), row.success(), row.message())).toList();
        return new Preflight(batch.total(), batch.success(), batch.failed(), rows, batch.id(), batch.status());
    }

    @GetMapping("/template")
    ResponseEntity<byte[]> template(@RequestParam SettlementImportService.Type type,
                                    @RequestParam(defaultValue = "false") boolean example) {
        boolean includeExample = example && demoEnabled;
        String filename = "OMSWeb-" + type + (includeExample ? "-example" : "-template") + ".xlsx";
        return ResponseEntity.ok().contentType(XLSX)
            .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(filename).build().toString())
            .body(importer.template(type, LocalDate.now(DocumentService.HONG_KONG), includeExample));
    }

    @PostMapping(path = "/batches", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    SettlementBatch submit(@RequestParam MultipartFile file, @RequestParam SettlementImportService.Type type) {
        return batches.submit(file, type);
    }

    @GetMapping("/batches")
    List<SettlementBatch> list() {
        return batches.list();
    }

    @GetMapping("/batches/{id}")
    SettlementBatch detail(@PathVariable long id) {
        return batches.get(id);
    }

    @GetMapping("/batches/{id}/file")
    ResponseEntity<byte[]> file(@PathVariable long id) {
        SettlementBatchService.SourceFile file = batches.file(id);
        MediaType media = file.filename().toLowerCase(java.util.Locale.ROOT).endsWith(".xls")
            ? MediaType.parseMediaType("application/vnd.ms-excel") : XLSX;
        return ResponseEntity.ok().contentType(media)
            .header(HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(file.filename(), StandardCharsets.UTF_8).build().toString())
            .body(file.content());
    }

    @PostMapping("/batches/{id}/approve")
    SettlementBatch approve(@PathVariable long id, @RequestBody(required = false) SettlementReview review) {
        return batches.approve(id, review == null ? null : review.reason());
    }

    @PostMapping("/batches/{id}/reject")
    SettlementBatch reject(@PathVariable long id, @RequestBody SettlementReview review) {
        return batches.reject(id, review.reason());
    }

    @GetMapping("/reversals")
    List<SettlementReversal> reversals() {
        return batches.reversals();
    }

    @PostMapping("/reversals")
    SettlementReversal requestReversal(@Valid @RequestBody SettlementReversalRequest request) {
        return batches.requestReversal(request);
    }

    @PostMapping("/reversals/{id}/approve")
    SettlementReversal approveReversal(@PathVariable long id, @RequestBody(required = false) SettlementReview review) {
        return batches.approveReversal(id, review == null ? null : review.reason());
    }

    @PostMapping("/reversals/{id}/reject")
    SettlementReversal rejectReversal(@PathVariable long id, @RequestBody SettlementReview review) {
        return batches.rejectReversal(id, review.reason());
    }

    record Preflight(int total, int success, int failed, List<ImportResult.Row> rows, long batchId, String status) {}
}
