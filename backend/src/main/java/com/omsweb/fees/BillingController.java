package com.omsweb.fees;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/billing")
class BillingController {
    private final BillingService billing;

    BillingController(BillingService billing) {
        this.billing = billing;
    }

    @GetMapping("/schemes")
    List<FeeScheme> schemes() {
        return billing.schemes();
    }

    @GetMapping("/schemes/{id}")
    FeeScheme scheme(@PathVariable long id) {
        return billing.scheme(id);
    }

    @PostMapping("/schemes")
    @ResponseStatus(HttpStatus.CREATED)
    FeeScheme createScheme(@RequestBody SchemeInput input) {
        return billing.createScheme(input);
    }

    @PutMapping("/schemes/{id}")
    FeeScheme updateScheme(@PathVariable long id, @RequestBody SchemeInput input) {
        return billing.updateScheme(id, input);
    }

    @GetMapping("/rules")
    List<BillingRule> rules() {
        return billing.rules();
    }

    @PostMapping("/rules")
    @ResponseStatus(HttpStatus.CREATED)
    BillingRule createRule(@RequestBody RuleInput input) {
        return billing.createRule(input);
    }

    @PostMapping("/quote")
    BillingService.Quote quote(@RequestBody CalculationInput input) {
        return billing.quote(input);
    }

    @PostMapping("/issue")
    @ResponseStatus(HttpStatus.CREATED)
    Fee issue(@RequestBody IssueInput input) {
        return billing.issue(input);
    }

    record SchemeInput(String code, String name, String payer, String payerEmail, Boolean active) {}
    record RuleInput(Long schemeId, FeeType type, BillingRule.Mode mode, BigDecimal rate, LocalDate effectiveFrom,
                     Integer dueDays, Integer cycleMonths, RoundingMode roundingMode) {}
    record CalculationInput(Long schemeId, FeeType type, LocalDate calculationDate, Long sourceFeeId) {}
    record IssueInput(Long schemeId, FeeType type, LocalDate calculationDate, String applicationNumber,
                      Long sourceFeeId, String description) {}
}
