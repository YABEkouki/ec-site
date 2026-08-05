package com.example.ecsite.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
public class SecurityConfig {

        @Bean
        SecurityFilterChain securityFilterChain(
                        HttpSecurity http) throws Exception {

                http
                                .authorizeHttpRequests(auth -> auth

                                                .requestMatchers(
                                                                "/login",
                                                                "/signup",
                                                                "/403")
                                                .permitAll()

                                                // 管理画面
                                                .requestMatchers("/admin", "/admin/**")
                                                .hasRole("ADMIN")

                                                // 商品一覧・詳細
                                                .requestMatchers(HttpMethod.GET, "/products", "/products/*")
                                                .authenticated()

                                                .anyRequest().authenticated())

                                .formLogin(form -> form
                                                .loginPage("/login")
                                                .defaultSuccessUrl("/", true)
                                                .permitAll())
                                .exceptionHandling(exception -> exception
                                                .accessDeniedPage("/403"))
                                .logout(logout -> logout
                                                .logoutSuccessUrl("/login?logout")
                                                .permitAll());

                return http.build();
        }

        @Bean
        PasswordEncoder passwordEncoder() {
                return new BCryptPasswordEncoder();
        }
        /*
         * 固定ユーザー認証（学習用）
         * DB認証へ移行したため現在は使用しない。
         *
         * @Bean
         * UserDetailsService userDetailsService(
         * PasswordEncoder passwordEncoder) {
         * 
         * UserDetails admin = User.builder()
         * .username("admin")
         * .password(passwordEncoder.encode("password"))
         * .roles("ADMIN")
         * .build();
         * 
         * return new InMemoryUserDetailsManager(admin);
         * }
         */

}