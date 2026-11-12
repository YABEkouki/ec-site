package com.example.ecsite.service.order.history;

import java.util.List;
import java.util.Objects;

/** Counts describe confirmed displayable changes, not hidden/unverified evidence. Page numbers start at one. */
public record CustomerOrderChangeHistoryPage(long orderNumber, State state,
        List<Warning> warnings, List<CustomerOrderChangeHistoryEntry> entries,
        int page, int pageSize, int totalEntries, int totalPages) {
    public enum State { ALL, PARTIAL, NONE, LIMIT_EXCEEDED, UNAVAILABLE }
    public enum Warning { SOME_HISTORY_UNAVAILABLE, HISTORY_UNAVAILABLE, HISTORY_LIMIT_EXCEEDED, TRY_AGAIN_LATER }
    public CustomerOrderChangeHistoryPage {
        Objects.requireNonNull(state); warnings = List.copyOf(warnings); entries = List.copyOf(entries);
        if (pageSize != 20 || page < 1 || totalPages < 1 || page > totalPages || totalEntries < 0
                || entries.size() > pageSize) throw new IllegalArgumentException("Invalid display page");
        if (state != State.ALL && state != State.PARTIAL && (!entries.isEmpty() || totalEntries != 0))
            throw new IllegalArgumentException("Unavailable history cannot carry entries");
    }
}
