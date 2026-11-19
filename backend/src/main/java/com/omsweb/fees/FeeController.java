package com.omsweb.fees;

import jakarta.validation.Valid;
import java.util.List;
import java.time.Clock;
import java.time.LocalDate;
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
@RequestMapping("/api/fees")
public class FeeController {
    private final FeeService fees;
    private final BillingService billing;
    private final Clock clock;

    public FeeController(FeeService fees, BillingService billing, Clock clock) {
        this.fees = fees;
        this.billing = billing;
        this.clock = clock;
    }

    @GetMapping
    List<Fee> list() {
        return fees.list();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    Fee create(@Valid @RequestBody FeeInput input) {
        return fees.create(input);
    }

    @PutMapping("/{id}")
    Fee update(@PathVariable long id, @Valid @RequestBody FeeInput input) {
        return fees.update(id, input);
    }

    @PostMapping("/periodic-run")
    FeeService.PeriodicRun runPeriodicFees() {
        return billing.runPeriodic(LocalDate.now(clock));
    }
}
