package com.omsweb.fees.operations;

import com.omsweb.fees.AuditTrail;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/documents")
class DocumentController {
    private final DocumentService documents;
    private final AuditTrail audit;

    DocumentController(DocumentService documents, AuditTrail audit) {
        this.documents = documents;
        this.audit = audit;
    }

    @GetMapping
    List<FeeDocument> list(
            @RequestParam(required = false) DocumentKind kind,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate generatedOn,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate bankInDate
    ) {
        return documents.list(kind, generatedOn, bankInDate);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    FeeDocument generate(@Valid @RequestBody DocumentRequest request) {
        FeeDocument document = documents.generate(request);
        record("DOCUMENT_GENERATED", document);
        return document;
    }

    @GetMapping("/{id}/download")
    ResponseEntity<byte[]> download(@PathVariable long id) {
        DocumentService.DownloadedDocument document = documents.download(id);
        record("DOCUMENT_DOWNLOADED", document.document());
        String suffix = document.document().valid() ? ".pdf" : "-VOID.pdf";
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_PDF)
                .header("X-Document-Validity", document.document().valid() ? "VALID" : "VOID")
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename(document.document().number() + suffix)
                        .build()
                        .toString())
                .body(document.content());
    }

    private void record(String action, FeeDocument document) {
        audit.record(action, "DOCUMENT", Long.toString(document.id()), null, Map.of(
            "feeId", document.feeId(), "kind", document.kind(), "number", document.number()));
    }
}
