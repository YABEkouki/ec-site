package com.example.ecsite.service.order.history;

import java.util.*;

/** Pure constrained ordering. Legacy placement is presentation order, never evidence of an application sequence. */
public final class CustomerOrderChangeHistoryOrdering {
    public List<VerifiedOrderChange> order(List<VerifiedOrderChange> changes) {
        Objects.requireNonNull(changes);
        var ids = new HashSet<Long>(); var revisions = new HashSet<Integer>();
        var chain = new ArrayList<VerifiedOrderChange>();
        var available = new PriorityQueue<VerifiedOrderChange>(Comparator.comparing(VerifiedOrderChange::sortInstant)
                .thenComparingLong(VerifiedOrderChange::historyId).reversed());
        for (var change : changes) {
            if (!ids.add(change.historyId())) throw new IllegalStateException("Duplicate verified change");
            if (change.legacy() != null) available.add(change);
            else {
                if (change.after().contentRevision() != Math.incrementExact(change.before().contentRevision())
                        || !revisions.add(change.after().contentRevision()))
                    throw new IllegalStateException("Invalid verified revision sequence");
                chain.add(change);
            }
        }
        // Only the newest available Feature125 change enters the queue. No non-transitive mixed comparator.
        chain.sort(Comparator.comparingInt((VerifiedOrderChange c) -> c.after().contentRevision()).reversed());
        int next = 0;
        if (!chain.isEmpty()) available.add(chain.get(next++));
        var result = new ArrayList<VerifiedOrderChange>(changes.size());
        while (!available.isEmpty()) {
            var change = available.remove(); result.add(change);
            if (change.legacy() == null && next < chain.size()) available.add(chain.get(next++));
        }
        return List.copyOf(result);
    }
}
