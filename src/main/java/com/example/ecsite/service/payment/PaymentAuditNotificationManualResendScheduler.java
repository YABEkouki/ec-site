package com.example.ecsite.service.payment;

import java.time.Instant;
import java.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.SmartLifecycle;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;
import com.example.ecsite.config.PaymentDiscrepancyAuditNotificationProperties;

/** Private scheduler is not a TaskScheduler bean: Boot's existing default scheduler stays intact. */
@Component
public class PaymentAuditNotificationManualResendScheduler implements SmartLifecycle {
    private static final Logger LOG=LoggerFactory.getLogger(PaymentAuditNotificationManualResendScheduler.class);
    private final PaymentAuditNotificationManualResendDeliveryService delivery;
    private final PaymentDiscrepancyAuditNotificationProperties properties;
    private final ObjectProvider<ScheduledAnnotationBeanPostProcessor> scheduling;
    private ThreadPoolTaskScheduler scheduler;
    private ScheduledFuture<?> task;
    private volatile boolean running;
    public PaymentAuditNotificationManualResendScheduler(PaymentAuditNotificationManualResendDeliveryService delivery,PaymentDiscrepancyAuditNotificationProperties properties,ObjectProvider<ScheduledAnnotationBeanPostProcessor> scheduling){this.delivery=delivery;this.properties=properties;this.scheduling=scheduling;}
    public void tick(){
        try{delivery.recoverExpired();if(properties.enabled())delivery.deliverOne();}
        catch(RuntimeException failure){LOG.atError().addKeyValue("failureCode","MANUAL_RESEND_TICK_FAILED").log("Manual resend tick failed; recovery will run on a later tick");}
    }
    @Override public synchronized void start(){
        if(running || scheduling.getIfAvailable()==null)return;
        scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(1);scheduler.setThreadNamePrefix("payment-audit-manual-resend-");scheduler.setAwaitTerminationSeconds(5);scheduler.initialize();
        task=scheduler.scheduleWithFixedDelay(this::tick,Instant.now().plus(properties.fixedDelay()),properties.fixedDelay());running=true;
    }
    @Override public synchronized void stop(){if(task!=null)task.cancel(true);if(scheduler!=null)scheduler.shutdown();running=false;}
    @Override public boolean isRunning(){return running;}
}
