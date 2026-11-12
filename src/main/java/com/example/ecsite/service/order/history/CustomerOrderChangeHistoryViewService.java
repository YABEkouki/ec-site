package com.example.ecsite.service.order.history;

import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.transaction.TransactionTimedOutException;
import org.springframework.transaction.CannotCreateTransactionException;
import com.example.ecsite.exception.OrderNotFoundException;
import static com.example.ecsite.service.order.history.CustomerOrderChangeHistoryPage.*;

/** Authenticated read boundary owns its transaction. Verification and display mapping run after it returns. */
@Service
public class CustomerOrderChangeHistoryViewService {
    private static final int PAGE_SIZE = 20;
    private final CustomerOrderChangeHistoryReadService readService;
    private final OrderChangeHistoryVerifier verifier = new OrderChangeHistoryVerifier();
    private final CustomerOrderChangeHistoryOrdering ordering = new CustomerOrderChangeHistoryOrdering();
    private final CustomerOrderChangeHistoryMapper mapper = new CustomerOrderChangeHistoryMapper();

    public CustomerOrderChangeHistoryViewService(CustomerOrderChangeHistoryReadService readService) {
        this.readService = Objects.requireNonNull(readService);
    }
    public CustomerOrderChangeHistoryPage load(Long orderId, int page) {
        if (orderId == null) throw new OrderNotFoundException(orderId);
        if (page < 1) throw new IllegalArgumentException("Page must be positive");
        OrderChangeEvidenceBundle evidence;
        try { evidence = readService.readForCustomer(orderId); }
        catch (DataAccessResourceFailureException | QueryTimeoutException | TransactionTimedOutException unavailable) {
            return empty(orderId, State.UNAVAILABLE, Warning.TRY_AGAIN_LATER);
        }
        catch (CannotCreateTransactionException startup) {
            if (!connectionUnavailable(startup)) throw startup;
            return empty(orderId, State.UNAVAILABLE, Warning.TRY_AGAIN_LATER);
        }
        if (evidence.order().orderId() != orderId) throw new IllegalStateException("Read order mismatch");
        var result = verifier.verify(evidence);
        if (result.acquisitionStatus() == OrderChangeEvidenceBundle.Status.LIMIT_EXCEEDED)
            return empty(orderId, State.LIMIT_EXCEEDED, Warning.HISTORY_LIMIT_EXCEEDED);
        if (result.displayability() == OrderChangeHistoryVerificationResult.Displayability.NONE)
            return empty(orderId, State.NONE, Warning.HISTORY_UNAVAILABLE);
        var changes = ordering.order(result.verifiedChanges());
        int total = changes.size(), totalPages = Math.max(1, (int)((total + (long)PAGE_SIZE - 1) / PAGE_SIZE));
        int selected = Math.min(page, totalPages), start = (selected - 1) * PAGE_SIZE;
        var entries = new ArrayList<CustomerOrderChangeHistoryEntry>();
        for (int i = start; i < Math.min(total, start + PAGE_SIZE); i++) entries.add(mapper.map(changes.get(i)));
        boolean partial = result.displayability() == OrderChangeHistoryVerificationResult.Displayability.PARTIAL;
        return new CustomerOrderChangeHistoryPage(orderId, partial ? State.PARTIAL : State.ALL,
                partial ? List.of(Warning.SOME_HISTORY_UNAVAILABLE) : List.of(), entries, selected, PAGE_SIZE, total, totalPages);
    }
    private boolean connectionUnavailable(Throwable failure) {
        var seen = Collections.newSetFromMap(new IdentityHashMap<Throwable,Boolean>());
        for (var cause = failure.getCause(); cause != null && seen.add(cause); cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException || cause instanceof QueryTimeoutException
                    || cause instanceof java.sql.SQLTimeoutException || cause instanceof java.sql.SQLRecoverableException
                    || cause instanceof java.sql.SQLTransientConnectionException || cause instanceof java.sql.SQLNonTransientConnectionException)
                return true;
            if (cause instanceof java.sql.SQLException sql && sql.getSQLState() != null && sql.getSQLState().startsWith("08"))
                return true;
        }
        return false;
    }
    private CustomerOrderChangeHistoryPage empty(long order, State state, Warning warning) {
        return new CustomerOrderChangeHistoryPage(order, state, List.of(warning), List.of(), 1, PAGE_SIZE, 0, 1);
    }
}
