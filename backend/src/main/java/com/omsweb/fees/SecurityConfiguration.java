package com.omsweb.fees;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.csrf.CsrfException;
import org.springframework.web.servlet.LocaleResolver;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
class SecurityConfiguration {
    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    ApplicationRunner initializeAdministrator(SecurityAccounts accounts,
            @Value("${oms.demo.enabled:true}") boolean demoEnabled,
            @Value("${OMS_ADMIN_USERNAME:}") String username,
            @Value("${OMS_ADMIN_PASSWORD:}") String password) {
        return args -> {
            if (demoEnabled || accounts.hasAdministrator()) {
                return;
            }
            if (username.isBlank() || password.isBlank()) {
                throw new IllegalStateException("Set OMS_ADMIN_USERNAME and OMS_ADMIN_PASSWORD to create the initial administrator.");
            }
            accounts.create(username, password, SecurityAccounts.Role.ADMIN);
        };
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserDetailsService accounts, PasswordEncoder encoder,
            AuditTrail audit, LocaleResolver localeResolver, JsonMapper json) throws Exception {
        var provider = new DaoAuthenticationProvider(accounts);
        provider.setPasswordEncoder(encoder);
        return http
            .authenticationProvider(provider)
            .csrf(Customizer.withDefaults())
            .requestCache(AbstractHttpConfigurer::disable)
            .authorizeHttpRequests(routes -> routes
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/api/auth/session").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()
                .requestMatchers("/api/auth/users").hasRole("ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/audit", "/api/reports/download")
                    .hasAnyRole("FINANCE", "AUDITOR", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/fis/export").hasAnyRole("FINANCE", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/settlements/batches/*/approve", "/api/settlements/batches/*/reject",
                    "/api/settlements/reversals/*/approve", "/api/settlements/reversals/*/reject", "/api/fees/periodic-run")
                    .hasAnyRole("FINANCE", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/fees", "/api/settlements/batches", "/api/settlements/import",
                    "/api/settlements/reversals", "/api/reconciliation/convert", "/api/fps-requests", "/api/billing/issue")
                    .hasAnyRole("OPERATOR", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/fees/*").hasAnyRole("OPERATOR", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/documents", "/api/billing/quote")
                    .hasAnyRole("OPERATOR", "FINANCE", "ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/billing/schemes", "/api/billing/rules")
                    .hasAnyRole("FINANCE", "ADMIN")
                .requestMatchers(HttpMethod.PUT, "/api/billing/schemes/*", "/api/billing/rules/*")
                    .hasAnyRole("FINANCE", "ADMIN")
                .requestMatchers(HttpMethod.GET, "/api/**").authenticated()
                .requestMatchers("/api/**").denyAll()
                .anyRequest().permitAll())
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, error) -> writeError(request, response, localeResolver, json,
                    HttpServletResponse.SC_UNAUTHORIZED, "请先登录。", "Sign in to continue."))
                .accessDeniedHandler((request, response, error) -> {
                    if (error instanceof CsrfException) {
                        writeError(request, response, localeResolver, json, HttpServletResponse.SC_FORBIDDEN,
                            "会话验证已失效，请刷新页面后重试。", "Session verification expired. Reload the page and try again.");
                    } else {
                        writeError(request, response, localeResolver, json, HttpServletResponse.SC_FORBIDDEN,
                            "当前账号无权执行此操作。", "Your account does not have permission to do this.");
                    }
                }))
            .formLogin(form -> form
                .loginPage("/")
                .loginProcessingUrl("/api/auth/login")
                .successHandler((request, response, authentication) -> {
                    String username = authentication.getName();
                    audit.recordAs(username, "LOGIN", "USER", username, null, null);
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                })
                .failureHandler((request, response, error) -> writeError(request, response, localeResolver, json,
                    HttpServletResponse.SC_UNAUTHORIZED, "账号或密码错误。", "The username or password is incorrect.")))
            .logout(logout -> logout.logoutUrl("/api/auth/logout")
                .logoutSuccessHandler((request, response, authentication) -> {
                    if (authentication != null) {
                        String username = authentication.getName();
                        audit.recordAs(username, "LOGOUT", "USER", username, null, null);
                    }
                    response.setStatus(HttpServletResponse.SC_NO_CONTENT);
                }))
            .build();
    }

    private void writeError(HttpServletRequest request, HttpServletResponse response, LocaleResolver localeResolver,
            JsonMapper json, int status, String chinese, String english) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        String message = localeResolver.resolveLocale(request).getLanguage().equals("en") ? english : chinese;
        json.writeValue(response.getOutputStream(), Map.of("message", message));
    }
}
