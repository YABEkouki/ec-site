package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.ecsite.entity.*;
import com.example.ecsite.entity.Order;
import com.example.ecsite.repository.PaymentRepository;
import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.Transactional;

class OrderPaymentViewReadServiceTest {
 @Test void twoReadQueriesProvideCompleteHistoryWithoutLocksOrWrites()throws Exception{
  var repo=mock(PaymentRepository.class);var em=mock(EntityManager.class);var factory=mock(OrderPaymentViewFactory.class);
  @SuppressWarnings("unchecked") TypedQuery<PaymentTransaction> query=mock(TypedQuery.class);
  var now=LocalDateTime.of(2026,10,16,10,0);var order=new Order(1L,100,now,now.withHour(14));ReflectionTestUtils.setField(order,"id",42L);
  when(repo.findByOrderIdOrderByCreatedAtAscIdAsc(42L)).thenReturn(List.of());
  String jpql="select t from PaymentTransaction t join fetch t.payment p where p.order.id=:orderId order by t.id";
  when(em.createQuery(jpql,PaymentTransaction.class)).thenReturn(query);when(query.setParameter("orderId",42L)).thenReturn(query);when(query.getResultList()).thenReturn(List.of());
  var expected=OrderPaymentViewFactory.unavailable();when(factory.create(order,List.of(),List.of())).thenReturn(expected);
  assertThat(new OrderPaymentViewReadService(repo,em,factory).read(order)).isSameAs(expected);
  verify(repo).findByOrderIdOrderByCreatedAtAscIdAsc(42L);verify(em).createQuery(jpql,PaymentTransaction.class);verify(query).setParameter("orderId",42L);verify(query).getResultList();verify(factory).create(order,List.of(),List.of());
  verifyNoMoreInteractions(repo,em,query,factory);
  assertThat(OrderPaymentViewReadService.class.getMethod("read",Order.class).getAnnotation(Transactional.class).readOnly()).isTrue();
 }
}
