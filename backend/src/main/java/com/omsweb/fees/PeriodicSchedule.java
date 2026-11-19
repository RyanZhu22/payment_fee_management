package com.omsweb.fees;

import java.time.Clock;
import java.util.TimeZone;
import org.quartz.CronScheduleBuilder;
import org.quartz.JobBuilder;
import org.quartz.JobDetail;
import org.quartz.Trigger;
import org.quartz.TriggerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class PeriodicSchedule {
    @Bean
    JobDetail periodicFeeJobDetail() {
        return JobBuilder.newJob(PeriodicFeeJob.class).withIdentity("periodic-fees").storeDurably().build();
    }

    @Bean
    Trigger periodicFeeTrigger(JobDetail periodicFeeJobDetail, Clock clock) {
        return TriggerBuilder.newTrigger().forJob(periodicFeeJobDetail).withIdentity("monthly-periodic-fees")
            .withSchedule(CronScheduleBuilder.cronSchedule("0 0 8 1 * ?").inTimeZone(TimeZone.getTimeZone(clock.getZone())))
            .build();
    }
}
