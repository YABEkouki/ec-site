package com.example.ecsite.service;

import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.EntityManager;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.repository.*;
import com.example.ecsite.exception.PaymentAuditNotificationNotFoundException;

@Service
public class AdminPaymentAuditNotificationHandlingService {
    private final PaymentAuditNotificationHandlingToken tokens;
    private final PaymentAuditNotificationHandlingRepository handlings;
    private final PaymentAuditNotificationHandlingHistoryRepository histories;
    private final PaymentAuditNotificationHandlingHistoryUnknownRepository unknowns;
    private final EntityManager entities;
    public AdminPaymentAuditNotificationHandlingService(PaymentAuditNotificationHandlingToken tokens,
        PaymentAuditNotificationHandlingRepository handlings,PaymentAuditNotificationHandlingHistoryRepository histories,
        PaymentAuditNotificationHandlingHistoryUnknownRepository unknowns,EntityManager entities) {
        this.tokens=tokens; this.handlings=handlings; this.histories=histories; this.unknowns=unknowns; this.entities=entities;
    }
    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean changeStatus(long id,long actorId,String actorUsername,String token,
        PaymentAuditNotificationHandlingStatus target,String comment) {
        var snapshot=tokens.verify(token,id,actorId);
        var notification=handlings.lockNotification(id).orElseThrow(()->new PaymentAuditNotificationNotFoundException(id));
        if(notification.getStatus()!=PaymentAuditNotificationStatus.EXHAUSTED && !notification.isDeliveryUncertain())
            throw new IllegalArgumentException("この通知は管理者対応の対象ではありません。");
        var existing=handlings.findById(id);
        if(existing.isPresent()!=snapshot.handlingExists() || existing.isPresent() && existing.get().getVersion()!=snapshot.version())
            throw new PaymentAuditNotificationHandlingConflictException();
        var currentUnknowns=handlings.findUnknownAttemptNumbers(id);
        if(!currentUnknowns.equals(snapshot.unknowns())) throw new PaymentAuditNotificationHandlingConflictException(true);
        var current=existing.map(PaymentAuditNotificationHandling::getHandlingStatus).orElse(PaymentAuditNotificationHandlingStatus.UNREVIEWED);
        if(target==null) throw new IllegalArgumentException("対応状態が必要です。");
        if(current==target) return false;
        PaymentAuditNotificationHandlingComments.validate(comment,current==PaymentAuditNotificationHandlingStatus.RESOLVED || target==PaymentAuditNotificationHandlingStatus.RESOLVED);
        if(actorId<=0 || actorUsername==null || actorUsername.isBlank() || actorUsername.codePointCount(0,actorUsername.length())>100)
            throw new IllegalArgumentException("管理者情報が不正です。");
        Instant now=Instant.now();
        var history=new PaymentAuditNotificationHandlingHistory();
        history.setNotificationId(id); history.setFromStatus(current); history.setToStatus(target);
        history.setActorUserId(actorId); history.setActorUsername(actorUsername); history.setComment(comment);
        history.setEventId(UUID.randomUUID()); history.setChangedAt(now);
        history=histories.saveAndFlush(history);
        var handling=existing.orElseGet(PaymentAuditNotificationHandling::new);
        if(existing.isEmpty()) { handling.setNotificationId(id); handling.setCreatedAt(now); }
        if(target==PaymentAuditNotificationHandlingStatus.RESOLVED) {
            for(int number:currentUnknowns) {
                var member=new PaymentAuditNotificationHandlingHistoryUnknown();
                member.setHistoryId(history.getId()); member.setNotificationId(id); member.setAttemptNo(number);
                entities.persist(member);
            }
            handling.setLastResolvedHistoryId(history.getId());
        }
        handling.setHandlingStatus(target); handling.setUpdatedAt(now);
        if(existing.isEmpty()) entities.persist(handling);
        entities.flush();
        return true;
    }
    @Transactional(readOnly=true)
    public boolean needsReconfirmation(long id) { return handlings.needsReconfirmation(id); }
}
