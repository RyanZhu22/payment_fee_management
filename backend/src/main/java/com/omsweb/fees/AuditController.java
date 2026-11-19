package com.omsweb.fees;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
class AuditController {
    private final AuditTrail audit;

    AuditController(AuditTrail audit) {
        this.audit = audit;
    }

    @GetMapping("/api/audit")
    List<AuditTrail.Event> events() {
        return audit.recent();
    }
}
