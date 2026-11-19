package com.omsweb.fees;

import java.time.Clock;
import java.time.LocalDate;
import org.quartz.Job;
import org.quartz.DisallowConcurrentExecution;
import org.quartz.JobExecutionContext;
import org.springframework.beans.factory.annotation.Autowired;

@DisallowConcurrentExecution
public class PeriodicFeeJob implements Job {
    @Autowired
    private BillingService billing;

    @Autowired
    private Clock clock;

    @Override
    public void execute(JobExecutionContext context) {
        billing.runPeriodic(LocalDate.now(clock));
    }
}
