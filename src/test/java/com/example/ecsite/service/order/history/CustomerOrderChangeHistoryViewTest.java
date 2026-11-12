package com.example.ecsite.service.order.history;

import java.time.*;
import java.util.*;
import java.util.stream.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.*;
import org.springframework.security.access.AccessDeniedException;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.service.order.evidence.OrderContentSnapshot;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static com.example.ecsite.service.order.history.OrderChangeEvidenceBundle.*;

class CustomerOrderChangeHistoryViewTest {
    final CustomerOrderChangeHistoryMapper mapper=new CustomerOrderChangeHistoryMapper();
    static VerifiedOrderChange confirmed(Feature125EvidenceFixture f){return new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().getFirst();}
    @ParameterizedTest @ValueSource(booleans={false,true}) void feature125SavedSnapshots(boolean reauthorize){
        var v=confirmed(new Feature125EvidenceFixture(reauthorize));var e=mapper.map(v);
        assertEquals(v.before().totalAmount(),e.before().totalAmount());assertEquals(v.after().chargeTotal(),e.after().shipping());
        assertEquals(v.after().taxAmount(),e.after().taxAmount());assertEquals(v.changedAt().atZone(ZoneId.of("Asia/Tokyo")).toLocalDateTime(),e.changedAt());
        assertFalse(e.items().isEmpty());assertEquals(CustomerOrderChangeTaxCapability.SAVED_TOTAL_ONLY,e.taxCapability());
    }
    @Test void equalAmountReplacementIsNotDropped(){var e=mapper.map(confirmed(new Feature125EvidenceFixture(false,true)));assertEquals(e.before().totalAmount(),e.after().totalAmount());assertEquals(2,e.items().size());assertTrue(e.items().stream().anyMatch(i->i.kind()==CustomerOrderChangeItemDelta.Kind.ADDED));assertTrue(e.items().stream().anyMatch(i->i.kind()==CustomerOrderChangeItemDelta.Kind.REMOVED));}
    @ParameterizedTest @ValueSource(booleans={false,true}) void legacyShippingWithOrWithoutDiff(boolean shipping){var f=new LegacyOrderChangeFixture(false,shipping,true);var v=new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().getFirst();var e=mapper.map(v);assertEquals(v.legacyChangedAt(),e.changedAt());assertEquals(v.legacy().before().chargeTotal(),e.before().shipping());assertEquals(v.legacy().after().taxAmount(),e.after().taxAmount());assertEquals(CustomerOrderChangeTaxCapability.SAVED_TOTAL_ONLY,e.taxCapability());}
    @Test void legacyRemovalDoesNotInventZero(){var f=new LegacyOrderChangeFixture(true,true,false);var e=mapper.map(new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().getFirst());assertEquals(CustomerOrderChangeItemDelta.Kind.REMOVED,e.items().getFirst().kind());assertNull(e.items().getFirst().after());assertNotNull(e.items().getFirst().before());}
    @Test void oldReauthorization(){var e=mapper.map(new OrderChangeHistoryVerifier().verify(new LegacyReauthorizationFixture().bundle()).verifiedChanges().getFirst());assertEquals(CustomerOrderChangeHistoryEntry.Kind.QUANTITY_INCREASE,e.kind());assertTrue(e.items().getFirst().after().quantity()>e.items().getFirst().before().quantity());}
    static VerifiedOrderChange change(long id,int revision,Instant time){var v=confirmed(new Feature125EvidenceFixture(false));return new VerifiedOrderChange(id,v.route(),time,snapshot(v.before(),revision-1),snapshot(v.after(),revision));}
    static OrderContentSnapshot snapshot(OrderContentSnapshot s,int revision){return new OrderContentSnapshot(s.schemaVersion(),s.orderId(),s.customerId(),revision,s.items(),s.charges(),s.itemSubtotal(),s.chargeTotal(),s.taxAmount(),s.totalAmount());}
    @Test void clocksCannotReverseRevisionAcrossEpochs(){var t=Instant.parse("2026-01-01T00:00:00Z");var first=change(1,1,t.plusSeconds(10));var second=change(2,2,t);assertEquals(List.of(second,first),new CustomerOrderChangeHistoryOrdering().order(List.of(first,second)));}
    @Test void sameTimeUsesIdForLegacy(){var f=new LegacyOrderChangeFixture(false,false,false);var v=new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().getFirst();var next=VerifiedOrderChange.legacy(v.historyId()+1,v.legacyChangedAt(),v.legacy());assertEquals(List.of(next,v),new CustomerOrderChangeHistoryOrdering().order(List.of(v,next)));}
    @Test void mixedOrderUsesAvailableTimestampWithoutInventingLegacyRevision(){var t=Instant.parse("2026-01-01T00:00:00Z");var f=new LegacyOrderChangeFixture(false,false,false);var v=new OrderChangeHistoryVerifier().verify(f.bundle()).verifiedChanges().getFirst();var legacy=VerifiedOrderChange.legacy(99,LocalDateTime.ofInstant(t.plusSeconds(5),ZoneId.of("Asia/Tokyo")),v.legacy());var first=change(1,1,t.plusSeconds(10));var second=change(2,2,t);assertEquals(List.of(legacy,second,first),new CustomerOrderChangeHistoryOrdering().order(List.of(first,legacy,second)));}
    @Test void duplicateRevisionIsContractFailure(){var t=Instant.EPOCH;assertThrows(IllegalStateException.class,()->new CustomerOrderChangeHistoryOrdering().order(List.of(change(1,1,t),change(2,1,t))));}
    CustomerOrderChangeHistoryPage load(OrderChangeEvidenceBundle b,int page){var read=mock(CustomerOrderChangeHistoryReadService.class);when(read.readForCustomer(1)).thenReturn(b);return new CustomerOrderChangeHistoryViewService(read).load(1L,page);}
    @Test void emptyIsNormalAll(){var p=load(OrderChangeHistoryVerifierTest.empty(),1);assertEquals(CustomerOrderChangeHistoryPage.State.ALL,p.state());assertTrue(p.entries().isEmpty());assertEquals(1,p.totalPages());}
    @Test void limitHasNoUsableEntries(){var b=OrderChangeHistoryVerifierTest.empty();var p=load(new OrderChangeEvidenceBundle(Status.LIMIT_EXCEEDED,b.order(),Map.of(),b.coverage(),List.of()),1);assertEquals(CustomerOrderChangeHistoryPage.State.LIMIT_EXCEEDED,p.state());assertTrue(p.entries().isEmpty());}
    @Test void completeMissingCollectionsIsNoneNotEmptyAll(){var b=OrderChangeHistoryVerifierTest.empty();var p=load(new OrderChangeEvidenceBundle(Status.COMPLETE,b.order(),Map.of(),b.coverage(),List.of()),1);assertEquals(CustomerOrderChangeHistoryPage.State.NONE,p.state());assertFalse(p.warnings().isEmpty());}
    static OrderChangeEvidenceBundle legacyMany(int count,boolean bad){var f=new LegacyOrderChangeFixture(false,false,false);var sets=new EnumMap<EvidenceSet,List<EvidenceRow>>(f.bundle().evidence());var hs=new ArrayList<EvidenceRow>();var items=new ArrayList<EvidenceRow>();for(int n=1;n<=count;n++){var h=f.bundle().rows(EvidenceSet.HISTORIES).getFirst();var values=new LinkedHashMap<>(h.values());values.put("id",""+n);if(bad&&n==count)values.put("old_total_amount","1");hs.add(new EvidenceRow(values,h.binaries()));for(var i:f.bundle().rows(EvidenceSet.ITEM_CHANGES)){var iv=new LinkedHashMap<>(i.values());iv.put("id",""+n);iv.put("history_id",""+n);items.add(new EvidenceRow(iv,i.binaries()));}}sets.put(EvidenceSet.HISTORIES,hs);sets.put(EvidenceSet.ITEM_CHANGES,items);var b=f.bundle();return new OrderChangeEvidenceBundle(b.status(),b.order(),sets,b.coverage(),b.diagnostics());}
    @ParameterizedTest @ValueSource(ints={19,20,21,40,41}) void pageBoundaries(int count){var p=load(legacyMany(count,false),1);assertEquals(count,p.totalEntries());assertEquals(Math.min(count,20),p.entries().size());assertEquals((count+19)/20,p.totalPages());var end=load(legacyMany(count,false),Integer.MAX_VALUE);assertEquals(end.totalPages(),end.page());assertEquals(count-(end.page()-1)*20,end.entries().size());}
    @Test void partialWarningSurvivesEveryPage(){var b=legacyMany(22,true);for(int n:List.of(1,2,99)){var p=load(b,n);assertEquals(CustomerOrderChangeHistoryPage.State.PARTIAL,p.state());assertFalse(p.warnings().isEmpty());assertEquals(21,p.totalEntries());}}
    @ParameterizedTest @ValueSource(ints={0,-1,Integer.MIN_VALUE}) void invalidPageRejected(int page){assertThrows(IllegalArgumentException.class,()->load(OrderChangeHistoryVerifierTest.empty(),page));}
    @Test void authorizationExceptionsPropagate(){for(RuntimeException ex:List.of(new AccessDeniedException("private"),new OrderNotFoundException(1L))){var r=mock(CustomerOrderChangeHistoryReadService.class);when(r.readForCustomer(1)).thenThrow(ex);assertSame(ex,assertThrows(ex.getClass(),()->new CustomerOrderChangeHistoryViewService(r).load(1L,1)));}}
    @Test void connectionAndTimeoutUnavailable(){for(RuntimeException ex:List.of(new DataAccessResourceFailureException("secret"),new QueryTimeoutException("secret"))){var r=mock(CustomerOrderChangeHistoryReadService.class);when(r.readForCustomer(1)).thenThrow(ex);var p=new CustomerOrderChangeHistoryViewService(r).load(1L,1);assertEquals(CustomerOrderChangeHistoryPage.State.UNAVAILABLE,p.state());assertTrue(p.entries().isEmpty());assertFalse(p.toString().contains("secret"));}}
    @Test void internalContractsAndSqlBugsPropagate(){for(RuntimeException ex:List.of(new IllegalStateException("tx"),new InvalidDataAccessResourceUsageException("SQL"))){var r=mock(CustomerOrderChangeHistoryReadService.class);when(r.readForCustomer(1)).thenThrow(ex);assertSame(ex,assertThrows(ex.getClass(),()->new CustomerOrderChangeHistoryViewService(r).load(1L,1)));}}
    @Test void dtoRecordsHaveOnlyDisplayFields(){for(Class<?> c:List.of(CustomerOrderChangeHistoryPage.class,CustomerOrderChangeHistoryEntry.class,CustomerOrderChangeItemDelta.class,CustomerOrderChangeItemDelta.Side.class,CustomerOrderChangeAmounts.class)){for(var component:c.getRecordComponents()){assertFalse(component.getName().matches("(?i).*(payment|transaction|admission|operation|historyId|revision|snapshot|payload|fingerprint|digest|diagnostic).*"),component.toString());assertFalse(component.getType().getName().contains("Evidence"));}}}
    @Test void pureComponentsHaveNoInfrastructureFields(){for(Class<?> c:List.of(CustomerOrderChangeHistoryMapper.class,CustomerOrderChangeHistoryOrdering.class))for(var field:c.getDeclaredFields())assertFalse(field.getType().getName().matches(".*(Repository|Gateway|DataSource|Jdbc|SecurityContext|Clock).*"));}
    @Test void databaseConnectionFailureDuringTransactionStartupIsUnavailable(){
        for (Throwable cause : List.of(new java.sql.SQLTransientConnectionException("secret"), new java.sql.SQLNonTransientConnectionException("secret"), new java.sql.SQLTimeoutException("secret"), new java.sql.SQLException("unreachable", "08001"))) {
            var r=mock(CustomerOrderChangeHistoryReadService.class);
            when(r.readForCustomer(1)).thenThrow(new org.springframework.transaction.CannotCreateTransactionException("startup",cause));
            assertEquals(CustomerOrderChangeHistoryPage.State.UNAVAILABLE,new CustomerOrderChangeHistoryViewService(r).load(1L,1).state());
        }
    }
    @Test void transactionStartupContractFailurePropagates(){
        var r=mock(CustomerOrderChangeHistoryReadService.class);
        var ex=new org.springframework.transaction.CannotCreateTransactionException("configuration",new IllegalStateException("contract"));
        when(r.readForCustomer(1)).thenThrow(ex);
        assertSame(ex,assertThrows(ex.getClass(),()->new CustomerOrderChangeHistoryViewService(r).load(1L,1)));
    }
    @Test void unchangedSavedItemIsNotIncluded(){
        var v=confirmed(new Feature125EvidenceFixture(false));
        var unchanged=new OrderContentSnapshot.Item(999,999,"unchanged",1,"category",0,1,0,1,"ZERO","zero",0);
        var b=v.before();var a=v.after();var bi=new ArrayList<>(b.items());bi.add(unchanged);var ai=new ArrayList<>(a.items());ai.add(unchanged);
        var before=new OrderContentSnapshot(b.schemaVersion(),b.orderId(),b.customerId(),b.contentRevision(),bi,b.charges(),b.itemSubtotal(),b.chargeTotal(),b.taxAmount(),b.totalAmount());
        var after=new OrderContentSnapshot(a.schemaVersion(),a.orderId(),a.customerId(),a.contentRevision(),ai,a.charges(),a.itemSubtotal(),a.chargeTotal(),a.taxAmount(),a.totalAmount());
        var e=mapper.map(new VerifiedOrderChange(v.historyId(),v.route(),v.changedAt(),before,after));
        assertTrue(e.items().stream().noneMatch(i->(i.before()!=null&&i.before().productName().equals("unchanged"))||(i.after()!=null&&i.after().productName().equals("unchanged"))));
    }

}
