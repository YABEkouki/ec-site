package com.example.ecsite.controller;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(ErrorPageController.class)
class ErrorPageControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser
    void accessDeniedAcceptsPostRequest() throws Exception {
        mockMvc.perform(
                post("/403")
                        .with(csrf()))
                .andExpect(status().isForbidden())
                .andExpect(view().name("error/access_denied"));
    }
}
