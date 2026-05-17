package com.app.shorturl.config;

import com.app.shorturl.service.DatabaseOrFallbackUserDetailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.List;

@Slf4j
@Configuration
public class SecurityConfig {

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.domain}")
    private String ldapDomain;

    @Value("${ldap.base-dn}")
    private String ldapBaseDn;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * Provider lokal:
     * 1. user dari database app_users
     * 2. fallback emergency app.admin.username/app.admin.password
     */
    @Bean
    public DaoAuthenticationProvider localAuthProvider(
            DatabaseOrFallbackUserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder
    ) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        return provider;
    }

    /**
     * Provider Active Directory.
     */
    @Bean
    public ActiveDirectoryLdapAuthenticationProvider activeDirectoryProvider() {
        ActiveDirectoryLdapAuthenticationProvider provider =
                new ActiveDirectoryLdapAuthenticationProvider(ldapDomain, ldapUrl, ldapBaseDn);

        provider.setConvertSubErrorCodesToExceptions(true);
        provider.setUseAuthenticationRequestCredentials(true);

        provider.setSearchFilter(
                "(&(objectClass=user)(|(sAMAccountName={1})(userPrincipalName={0})))"
        );

        // Semua user AD yang sukses bind diberi ROLE_ADMIN.
        // Untuk production, sebaiknya batasi berdasarkan group AD tertentu.
        provider.setAuthoritiesMapper(authorities -> {
            log.info("AD authorities raw: {}", authorities);
            return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        });

        log.info("AD provider configured: domain={}, url={}, baseDn={}",
                ldapDomain, ldapUrl, ldapBaseDn);

        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            DaoAuthenticationProvider localAuthProvider,
            ActiveDirectoryLdapAuthenticationProvider activeDirectoryProvider,
            AuthenticationEventPublisher eventPublisher
    ) {
        ProviderManager providerManager =
                new ProviderManager(localAuthProvider, activeDirectoryProvider);

        providerManager.setAuthenticationEventPublisher(eventPublisher);
        providerManager.setEraseCredentialsAfterAuthentication(true);

        return providerManager;
    }

    /**
     * API chain khusus /api/v1/**
     *
     * Penting:
     * Jangan pakai /api/** di sini, karena /api/admin/users/**
     * dipakai dashboard admin dan harus lewat session login web.
     */
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager
    ) throws Exception {
        http
                .securityMatcher("/api/v1/**")
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/v1/**").permitAll()
                )
                .httpBasic(basic -> {})
                .csrf(csrf -> csrf.disable())
                .sessionManagement(session ->
                        session.sessionCreationPolicy(SessionCreationPolicy.STATELESS)
                );

        return http.build();
    }

    /**
     * Web chain:
     * - /admin/** pakai session login
     * - /api/admin/users/** juga pakai session login
     *
     * Jadi upload catalog dari halaman admin akan punya Authentication.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            LogoutSuccessHandler logoutSuccessHandler
    ) throws Exception {
        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login",
                                "/register",
                                "/forgot-password",
                                "/reset-password",
                                "/css/**",
                                "/js/**",
                                "/error",
                                "/images/**",
                                "/img.png",
                                "/favicon.ico"
                        ).permitAll()

                        .requestMatchers("/admin/**").hasRole("ADMIN")

                        // API catalog dari dashboard admin.
                        // Ini harus masuk web chain supaya Authentication tidak null.
                        .requestMatchers("/api/admin/users/**").hasRole("ADMIN")

                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/shorten").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**",
                                "/swagger-ui.html",
                                "/v3/api-docs/**",
                                "/v3/api-docs.yaml"
                        ).permitAll()

                        .anyRequest().permitAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .usernameParameter("username")
                        .passwordParameter("password")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                        .logoutSuccessHandler(logoutSuccessHandler)
                        .permitAll()
                )
                .csrf(csrf -> csrf
                        .csrfTokenRepository(CookieCsrfTokenRepository.withHttpOnlyFalse())
                        .ignoringRequestMatchers("/h2-console/**")
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        return http.build();
    }
}