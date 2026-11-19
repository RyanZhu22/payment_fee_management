package com.omsweb.fees.operations;

import java.time.LocalDate;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/reconciliation")
class ReconciliationController {
    private final ReconciliationService service;

    ReconciliationController(ReconciliationService service) {
        this.service = service;
    }

    @GetMapping("/template")
    ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv;charset=utf-8"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("OMSWeb-FPS-demo.csv").build().toString())
                .body(service.template(LocalDate.now(DocumentService.HONG_KONG)));
    }

    @PostMapping(path = "/convert", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ReconciliationService.Result convert(@RequestParam MultipartFile file) {
        return service.convert(file);
    }
}
