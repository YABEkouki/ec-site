package com.example.ecsite.controller;

import java.time.Clock;
import java.time.LocalDateTime;
import com.example.ecsite.entity.Order;
import com.example.ecsite.entity.OrderStatus;
import com.example.ecsite.service.OrderModificationPolicy;
import org.springframework.stereotype.Component;

/** Display hints only. The POST service checks payment eligibility and DB time under locks. */
@Component
public class OrderShippingPreparationViewFactory {
    private final OrderModificationPolicy policy;
    private final Clock clock;

    public OrderShippingPreparationViewFactory(OrderModificationPolicy policy, Clock clock) {
        this.policy = policy;
        this.clock = clock;
    }

    public View create(Order order) {
        if (order == null) return new View(State.UNAVAILABLE, null, null, null);
        LocalDateTime startedAt = order.getShippingPreparationStartedAt();
        Long actorId = order.getShippingPreparationStartedByAccountId();
        String username = order.getShippingPreparationStartedByUsername();
        boolean empty = startedAt == null && actorId == null && username == null;
        boolean complete = startedAt != null && actorId != null && actorId > 0
                && username != null && !username.isBlank() && username.length() <= 100;
        State state;
        if (!empty && !complete) state = State.INCONSISTENT;
        else if (order.getStatus() == OrderStatus.SHIPPED) state = State.SHIPPED;
        else if (order.getStatus() == OrderStatus.CANCELLED) state = State.CANCELLED;
        else if (order.getStatus() != OrderStatus.ORDERED && order.getStatus() != OrderStatus.PAID) state = State.UNAVAILABLE;
        else if (complete) state = State.STARTED;
        else {
            LocalDateTime now = LocalDateTime.now(clock);
            if (policy.allowsPreparationStart(order, now)) state = State.CANDIDATE;
            else if (policy.allowsModification(order, now)) state = State.BEFORE_DEADLINE;
            else state = State.UNAVAILABLE;
        }
        return new View(state, complete ? startedAt : null, complete ? actorId : null, complete ? username : null);
    }

    public enum State { CANDIDATE, BEFORE_DEADLINE, STARTED, SHIPPED, CANCELLED, INCONSISTENT, UNAVAILABLE }

    public record View(State state, LocalDateTime startedAt, Long startedByAccountId, String startedByUsername) {
        public boolean canStart() { return state == State.CANDIDATE; }
        public String message() {
            return switch (state) {
                case CANDIDATE -> "発送準備開始の候補です。受付時に変更期限・注文状態・決済状況を確認し、開始可否を最終判定します。";
                case BEFORE_DEADLINE -> "変更期限前のため、発送準備を開始できません。";
                case STARTED -> "発送準備開始済みです。";
                case SHIPPED -> "発送済みのため、発送準備を開始できません。";
                case CANCELLED -> "キャンセル済みのため、発送準備を開始できません。";
                case INCONSISTENT -> "発送準備情報に不整合があります。管理者に確認してください。";
                case UNAVAILABLE -> "注文情報を確認できないため、発送準備を開始できません。";
            };
        }
        public boolean inconsistent() { return state == State.INCONSISTENT; }
    }
}
