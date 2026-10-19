package com.example.ecsite.controller;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.example.ecsite.handler.GlobalExceptionHandler;
import com.example.ecsite.exception.OrderNotFoundException;
import com.example.ecsite.payment.PaymentGatewayException;
import com.example.ecsite.service.payment.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AdminCaptureRequeryControllerTest {
 final AdminCaptureRequeryService service=mock(AdminCaptureRequeryService.class);
 MockMvc mvc;
 static final String URL="/admin/orders/42/capture/requery";
 @BeforeEach void setup(){mvc=MockMvcBuilders.standaloneSetup(new AdminCaptureRequeryController(service)).setControllerAdvice(new GlobalExceptionHandler()).build();}
 @ParameterizedTest @ValueSource(strings={"CONFIRMED","WAITING"})
 void resultUsesPrgAndOnlyPassesOrderId(String result)throws Exception{when(service.requery(42L)).thenReturn(AdminCaptureRequeryService.Result.valueOf(result));mvc.perform(post(URL).param("returnUrl","/admin/orders?page=2&size=20&sort=id,desc").param("paymentId","99").param("transactionId","88").param("actorId","77"))
 .andExpect(status().isFound()).andExpect(redirectedUrl("/admin/orders/42?returnUrl=%2Fadmin%2Forders%3Fpage%3D2%26size%3D20%26sort%3Did%2Cdesc"))
 .andExpect(flash().attribute(result.equals("CONFIRMED")?"successMessage":"errorMessage",result.equals("CONFIRMED")?"売上確定済みであることを確認しました。":"売上確定結果を引き続き確認中です。"));verify(service).requery(42L);verifyNoMoreInteractions(service);}
 @ParameterizedTest @ValueSource(strings={"https://evil.example/","//evil.example/","/admin/orders/42"}) void invalidReturnUrlUsesExistingFallback(String url)throws Exception{when(service.requery(42L)).thenReturn(AdminCaptureRequeryService.Result.CONFIRMED);mvc.perform(post(URL).param("returnUrl",url)).andExpect(redirectedUrl("/admin/orders/42?returnUrl=%2Fadmin%2Forders"));}
 @ParameterizedTest @ValueSource(strings={"rejected","gateway"}) void failuresUseFixedMessagesWithoutInternalData(String kind)throws Exception{when(service.requery(42L)).thenThrow(kind.equals("gateway")?new PaymentGatewayException("private provider"):new CaptureRequeryRejectedException());String expected=kind.equals("gateway")?"決済結果を再照会できませんでした。決済失敗が確定したわけではありません。":"決済状態を安全に確認できないため、自動処理を停止しています。管理者による確認が必要です。";mvc.perform(post(URL)).andExpect(status().isFound()).andExpect(flash().attribute("errorMessage",expected)).andExpect(flash().attributeCount(1));}
 @Test void accessDeniedRemains403WithoutSuccessFlash()throws Exception{when(service.requery(42L)).thenThrow(new AccessDeniedException("private"));mvc.perform(post(URL)).andExpect(status().isForbidden()).andExpect(flash().attributeCount(0));}
 @Test void unexpectedFailureUsesSafe500()throws Exception{when(service.requery(42L)).thenThrow(new NullPointerException("private"));var response=mvc.perform(post(URL)).andExpect(status().isInternalServerError()).andExpect(view().name("error/internal-server-error")).andExpect(flash().attributeCount(0)).andReturn();assertThat(response.getModelAndView().getModel().toString()).doesNotContain("private");}
 @ParameterizedTest @ValueSource(strings={"0","-1","abc"}) void invalidIdsAre400WithoutService(String id)throws Exception{mvc.perform(post("/admin/orders/"+id+"/capture/requery")).andExpect(status().isBadRequest());verifyNoInteractions(service);}
 @Test void missingOrderRemains404()throws Exception{when(service.requery(42L)).thenThrow(new OrderNotFoundException(42L));mvc.perform(post(URL)).andExpect(status().isNotFound());}
 @Test void getCannotPerformRequery()throws Exception{mvc.perform(get(URL)).andExpect(status().isMethodNotAllowed());verifyNoInteractions(service);}
}
