package com.example.ecsite.handler;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@AutoConfigureMockMvc
@Import(StaticResourceHandlingTest.Errors.class)
class StaticResourceHandlingTest {
    @TempDir static Path images;
    @DynamicPropertySource static void images(DynamicPropertyRegistry properties) {
        properties.add("app.upload.product-image-dir", () -> images.toString()+"/");
    }
    @Autowired MockMvc mvc;

    @Test void missingStaticResourceIs404WithoutInternalDetails() throws Exception {
        var response=mvc.perform(get("/css/private-missing.css")).andExpect(status().isNotFound())
            .andExpect(view().name("error/not_found")).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain("NoResourceFoundException", "private-missing", images.toString(), "org.springframework");
    }
    @Test void missingProductImageIs404() throws Exception {
        mvc.perform(get("/product-images/missing.png")).andExpect(status().isNotFound()).andExpect(view().name("error/not_found"));
    }
    @Test void existingCssIsPublic() throws Exception {
        mvc.perform(get("/css/style.css")).andExpect(status().isOk()).andExpect(content().contentTypeCompatibleWith("text/css"));
    }
    @Test void faviconIsPublicAndAnActualIcon() throws Exception {
        var r=mvc.perform(get("/favicon.ico")).andExpect(status().isOk()).andReturn().getResponse();
        assertThat(r.getContentType()).isIn("image/x-icon", "image/vnd.microsoft.icon");
        assertThat(r.getContentAsByteArray()).startsWith((byte)0,(byte)0,(byte)1,(byte)0);
    }
    @Test void existingProductImageIsStillServed() throws Exception {
        byte[] data={1,2,3,4};Files.write(images.resolve("existing.png"),data);
        mvc.perform(get("/product-images/existing.png")).andExpect(status().isOk()).andExpect(content().bytes(data));
    }
    @Test void internalErrorRemains500WithoutLeakingMessage() throws Exception {
        var r=mvc.perform(get("/resource-test/internal").with(user("customer").roles("USER")))
            .andExpect(status().isInternalServerError()).andExpect(view().name("error/internal-server-error")).andReturn().getResponse();
        assertThat(r.getContentAsString()).doesNotContain("private-server-detail");
    }
    @Test void businessNotFoundKeepsItsOwnHandler() throws Exception {
        mvc.perform(get("/resource-test/business").with(user("customer").roles("USER")))
            .andExpect(status().isNotFound()).andExpect(view().name("error/product_not_found"));
    }
    @Test void authenticationAndAuthorizationAreUnchanged() throws Exception {
        mvc.perform(get("/orders")).andExpect(status().is3xxRedirection()).andExpect(redirectedUrl("/login"));
        mvc.perform(get("/admin").with(user("customer").roles("USER"))).andExpect(status().isForbidden());
    }
    @RestController static class Errors {
        @GetMapping("/resource-test/internal") String internal(){throw new IllegalStateException("private-server-detail");}
        @GetMapping("/resource-test/business") String business(){throw new com.example.ecsite.exception.ProductNotFoundException(999L);}
    }
}
