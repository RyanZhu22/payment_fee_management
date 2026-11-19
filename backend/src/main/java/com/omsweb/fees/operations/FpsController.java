package com.omsweb.fees.operations;

import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fps-requests")
class FpsController {
    private final FpsService requests;
    private final QrCodeRenderer qrCodes;

    FpsController(FpsService requests, QrCodeRenderer qrCodes) {
        this.requests = requests;
        this.qrCodes = qrCodes;
    }

    @GetMapping
    List<FpsRequest> list() {
        return requests.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    FpsRequest create(@Valid @RequestBody FpsRequestInput input) {
        return requests.create(input.feeId());
    }

    @GetMapping(value = "/{id}/qr", produces = MediaType.IMAGE_PNG_VALUE)
    ResponseEntity<byte[]> qr(@PathVariable long id, @RequestParam(defaultValue = "false") boolean download) {
        FpsRequest request = requests.get(id);
        byte[] png = qrCodes.png(request.qrText());
        ContentDisposition disposition = (download ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(request.reference() + ".png")
                .build();
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(png);
    }
}
