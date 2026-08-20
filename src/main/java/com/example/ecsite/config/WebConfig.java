package com.example.ecsite.config;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String productImageDir;

    public WebConfig(
            @Value("${app.upload.product-image-dir}") String productImageDir) {

        this.productImageDir = productImageDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {

        String location = Path.of(productImageDir)
                .toAbsolutePath()
                .normalize()
                .toUri()
                .toString();

        registry.addResourceHandler("/product-images/**")
                .addResourceLocations(location);
    }
}