package com.example.ecsite.handler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Controller;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;
import com.example.ecsite.config.SecurityConfig;
import com.example.ecsite.exception.ProductNotFoundException;
import com.example.ecsite.security.*;
import com.example.ecsite.service.*;

@WebMvcTest(HttpMethodNotAllowedRegressionTest.ProbeController.class)
@Import({SecurityConfig.class, GlobalExceptionHandler.class, HttpMethodNotAllowedRegressionTest.ProbeController.class})
class HttpMethodNotAllowedRegressionTest {
    @Autowired MockMvc mvc;
    @MockitoBean AdminUserDetailsService admins;
    @MockitoBean CustomUserDetailsService customers;
    @MockitoBean AdminAuthenticationSuccessHandler adminSuccess;
    @MockitoBean CustomerAuthenticationSuccessHandler customerSuccess;

    @Controller static class ProbeController {
        @PostMapping("/admin/method-probe") @ResponseBody String postOnly() { return "ok"; }
        @RequestMapping(value="/admin/multi-method-probe", method={RequestMethod.POST,RequestMethod.PUT})
        @ResponseBody String multiple() { return "ok"; }
        @GetMapping("/admin/method-probe-not-found") String missing() { throw new ProductNotFoundException(999L); }
        @GetMapping("/admin/method-probe-failure") String failure() { throw new IllegalStateException("PRIVATE_INTERNAL_DETAIL"); }
    }
    @Test void otherPostOnlyControllerReturnsSafe405AndDynamicAllow() throws Exception {
        String body=mvc.perform(get("/admin/method-probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isMethodNotAllowed()).andExpect(header().string(HttpHeaders.ALLOW,"POST"))
            .andExpect(view().name("error/method-not-allowed")).andReturn().getResponse().getContentAsString();
        assertThat(body).contains("405","この操作は指定されたHTTPメソッドでは利用できません。");
        assertThat(body).doesNotContain("HttpRequestMethodNotSupportedException","stackTrace");
    }
    @Test void allowReflectsAllSupportedMethodsRatherThanFixedPost() throws Exception {
        var response=mvc.perform(get("/admin/multi-method-probe").with(user("admin").roles("ADMIN")))
            .andExpect(status().isMethodNotAllowed()).andReturn().getResponse();
        assertThat(response.getHeader(HttpHeaders.ALLOW).split(",\\s*")).containsExactlyInAnyOrder("POST","PUT");
    }
    @Test void existingNotFoundRemains404() throws Exception {
        mvc.perform(get("/admin/method-probe-not-found").with(user("admin").roles("ADMIN")))
            .andExpect(status().isNotFound()).andExpect(view().name("error/product_not_found"));
    }
    @Test void existingUserAccessRemains403() throws Exception {
        mvc.perform(get("/admin/method-probe").with(user("customer").roles("USER")))
            .andExpect(status().isForbidden());
    }
    @Test void existingUnexpectedFailureRemainsSafe500() throws Exception {
        String body=mvc.perform(get("/admin/method-probe-failure").with(user("admin").roles("ADMIN")))
            .andExpect(status().isInternalServerError()).andExpect(view().name("error/internal-server-error"))
            .andReturn().getResponse().getContentAsString();
        assertThat(body).contains("システムエラーが発生しました").doesNotContain("PRIVATE_INTERNAL_DETAIL");
    }
}
