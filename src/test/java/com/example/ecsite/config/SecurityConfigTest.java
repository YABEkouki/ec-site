package com.example.ecsite.config;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class SecurityConfigTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void unauthenticatedUserIsRedirectedToAdminLoginForAdminPage()
            throws Exception {

        mockMvc.perform(get("/admin"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/admin/login"));
    }

    @Test
    void unauthenticatedUserIsRedirectedToCustomerLoginForCustomerPage()
            throws Exception {

        mockMvc.perform(get("/orders"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl(
                        "/login"));
    }

    @Test
    void customerCannotAccessAdminPage()
            throws Exception {

        mockMvc.perform(
                get("/admin")
                        .with(user("user1")
                                .roles("USER")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCannotAccessCustomerPage()
            throws Exception {

        mockMvc.perform(
                get("/orders")
                        .with(user("admin")
                                .roles("ADMIN")))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminLoginPageIsAccessibleWithoutAuthentication()
            throws Exception {

        mockMvc.perform(get("/admin/login"))
                .andExpect(status().isOk());
    }

    @Test
    void customerLoginPageIsAccessibleWithoutAuthentication()
            throws Exception {

        mockMvc.perform(get("/login"))
                .andExpect(status().isOk());
    }
}
