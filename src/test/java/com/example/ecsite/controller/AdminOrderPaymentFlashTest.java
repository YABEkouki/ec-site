package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.example.ecsite.form.AdminOrderStatusChangeForm;
import com.example.ecsite.payment.*;
import com.example.ecsite.security.AdminUserDetails;
import com.example.ecsite.service.*;
import com.example.ecsite.service.payment.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.validation.BindingResult;
import org.springframework.web.servlet.mvc.support.RedirectAttributesModelMap;

class AdminOrderPaymentFlashTest {
 final PaymentCaptureService capture=mock(PaymentCaptureService.class);
 final AdminUserDetails actor=mock(AdminUserDetails.class);
 final AdminOrderController controller=new AdminOrderController(mock(OrderService.class),mock(OrderCsvService.class),mock(OrderStatusHistoryService.class),mock(OrderNoteService.class),mock(OrderHandlingStatusHistoryService.class),mock(OrderShippingAddressHistoryService.class),mock(OrderAssigneeHistoryService.class),mock(OrderContentChangeHistoryService.class),mock(AdminAccountService.class),mock(PaymentService.class),capture,mock(AdminPaymentCancellationService.class),mock(OrderShippingPreparationViewFactory.class),mock(OrderPaymentViewReadService.class),mock(com.example.ecsite.service.orderchange.AdminOldCancelRecoveryViewService.class));
 String call(RedirectAttributesModelMap flash){when(actor.getId()).thenReturn(7L);when(actor.getUsername()).thenReturn("admin");return controller.captureAndShip(42L,new AdminOrderStatusChangeForm(),mock(BindingResult.class),"/admin/orders?page=2&size=20&sort=id,desc",actor,flash);}
 @ParameterizedTest @ValueSource(strings={"CAPTURED","PENDING","FAILED"})
 void resultsUseShipmentFirstMessagesAndKeepPrg(String status){when(capture.captureForShipment(42L,7L,"admin",null)).thenReturn(new CaptureResult(CaptureResultStatus.valueOf(status),"pf",null,null));var flash=new RedirectAttributesModelMap();assertThat(call(flash)).isEqualTo("redirect:/admin/orders/42?returnUrl=%2Fadmin%2Forders%3Fpage%3D2%26size%3D20%26sort%3Did%2Cdesc");
  assertThat(flash.getFlashAttributes().get(status.equals("CAPTURED")?"successMessage":"errorMessage")).isEqualTo(switch(status){case "CAPTURED"->"発送処理と売上確定が完了しました。";case "PENDING"->"発送済みです。売上確定結果を確認中です。";default->"発送済みです。売上確定に失敗しました。決済状況を確認してください。";});verify(capture).captureForShipment(42L,7L,"admin",null);verifyNoMoreInteractions(capture);
 }
 @ParameterizedTest @ValueSource(strings={"gateway","state","safety"})
 void exceptionsNeverClaimShipmentOrExposeInternalMessage(String kind){RuntimeException error=switch(kind){case "gateway"->new PaymentGatewayException("private provider response");case "state"->new IllegalStateException("private db message");default->new PaymentSafetyStopException(PaymentSafetyStopException.Reason.RESULT_PERSISTENCE_UNKNOWN,42L);};when(capture.captureForShipment(42L,7L,"admin",null)).thenThrow(error);var flash=new RedirectAttributesModelMap();call(flash);String message=(String)flash.getFlashAttributes().get("errorMessage");assertThat(message).doesNotContain("private","発送済みです","変更していません");if(kind.equals("gateway"))assertThat(message).contains("失敗確定ではありません");if(kind.equals("safety"))assertThat(message).contains("自動処理を停止");}
 @ParameterizedTest @ValueSource(strings={"denied","unexpected"})
 void authorizationAndUnexpectedFailuresPropagate(String kind){RuntimeException error=kind.equals("denied")?new AccessDeniedException("denied"):new NullPointerException("unexpected");when(capture.captureForShipment(42L,7L,"admin",null)).thenThrow(error);var flash=new RedirectAttributesModelMap();assertThatThrownBy(()->call(flash)).isSameAs(error);assertThat(flash.getFlashAttributes()).isEmpty();}
}
