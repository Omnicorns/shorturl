package com.app.shorturl.config;

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
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

import java.util.List;

/**
 * Security Configuration — Dual Authentication
 *
 * Login URL: POST /login dengan parameter username & password
 *
 * Urutan provider yang dicoba:
 *   1. DaoAuthenticationProvider (akun lokal in-memory: surl/surl123)
 *      → fallback / emergency kalau LDAP server down
 *   2. ActiveDirectoryLdapAuthenticationProvider (domain sarinah.net)
 *      → user AD bind pakai credential sendiri (username@sarinah.net)
 *
 * Kalau user AD sukses, otomatis di-grant ROLE_ADMIN (lihat authoritiesMapper).
 * Kalau mau dibatasi hanya member group AD tertentu, ganti mapper-nya untuk
 * cek nama group dari `authorities` (yang berasal dari attribute memberOf).
 */
@Slf4j
@Configuration
public class SecurityConfig {

    @Value("${app.admin.username}")
    private String adminUsername;

    @Value("${app.admin.password}")
    private String adminPassword;

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

    // ============================================================
    // Provider 1 — User lokal (in-memory)
    // ============================================================
    @Bean
    public UserDetailsService userDetailsService(PasswordEncoder encoder) {
        UserDetails admin = User.builder()
                .username(adminUsername)
                .password(encoder.encode(adminPassword))
                .roles("ADMIN")
                .build();
        return new InMemoryUserDetailsManager(admin);
    }

    @Bean
    public DaoAuthenticationProvider localAuthProvider(
            UserDetailsService userDetailsService,
            PasswordEncoder passwordEncoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder);
        // Biarkan default (hideUserNotFoundExceptions=true).
        // Saat username tidak ada di in-memory, provider lempar
        // BadCredentialsException → ProviderManager swallow & lanjut ke AD,
        // tanpa men-trigger event LOGIN_FAILURE dobel.
        return provider;
    }

    // ============================================================
    // Provider 2 — Active Directory
    // ============================================================
    @Bean
    public ActiveDirectoryLdapAuthenticationProvider activeDirectoryProvider() {
        ActiveDirectoryLdapAuthenticationProvider provider =
                new ActiveDirectoryLdapAuthenticationProvider(ldapDomain, ldapUrl, ldapBaseDn);
        provider.setConvertSubErrorCodesToExceptions(true);
        provider.setUseAuthenticationRequestCredentials(true);
        // Terima input baik sAMAccountName (mis. "budi") maupun
        // userPrincipalName (mis. "budi@sarinah.net")
        provider.setSearchFilter(
                "(&(objectClass=user)(|(sAMAccountName={1})(userPrincipalName={0})))");

        // Mapping role: semua user yang lulus AD bind => ROLE_ADMIN.
        // Kalau mau granular per-group AD, ganti logic di sini:
        //   - cek apakah `authorities` (dari memberOf) mengandung group tertentu,
        //     misal "CN=ShortURL_Admins,OU=Groups,DC=sarinah,DC=net"
        //   - kalau iya → ROLE_ADMIN, kalau tidak → ROLE_USER atau tolak
        provider.setAuthoritiesMapper(authorities -> {
            log.info("AD authorities raw: {}", authorities);
            return List.of(new SimpleGrantedAuthority("ROLE_ADMIN"));
        });

        log.info("AD provider configured: domain={}, url={}, baseDn={}",
                ldapDomain, ldapUrl, ldapBaseDn);
        return provider;
    }

    // ============================================================
    // AuthenticationManager — gabungkan kedua provider
    //   urutan: lokal dulu, baru AD
    //
    //   PENTING: inject AuthenticationEventPublisher supaya event
    //   AuthenticationSuccessEvent / AbstractAuthenticationFailureEvent
    //   ke-publish (default ProviderManager pakai NullEventPublisher
    //   yang nggak fire event apapun → audit listener nggak jalan).
    // ============================================================
    @Bean
    public AuthenticationManager authenticationManager(
            DaoAuthenticationProvider localAuthProvider,
            ActiveDirectoryLdapAuthenticationProvider activeDirectoryProvider,
            AuthenticationEventPublisher eventPublisher) {
        ProviderManager pm = new ProviderManager(localAuthProvider, activeDirectoryProvider);
        pm.setAuthenticationEventPublisher(eventPublisher);
        pm.setEraseCredentialsAfterAuthentication(true);
        return pm;
    }

    // ============================================================
    // Filter Chain — REST API (Basic Auth, stateless)
    // ============================================================
    @Bean
    @Order(1)
    public SecurityFilterChain apiFilterChain(HttpSecurity http,
                                              AuthenticationManager authenticationManager) throws Exception {
        http
                .securityMatcher("/api/**")
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .requestMatchers("/api/**").permitAll()
                )
                .httpBasic(basic -> {})
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS));

        return http.build();
    }

    // ============================================================
    // Filter Chain — Web UI (form login + CSRF)
    // ============================================================
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(HttpSecurity http,
                                              AuthenticationManager authenticationManager,
                                              LogoutSuccessHandler logoutSuccessHandler) throws Exception {
        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        .requestMatchers("/h2-console/**").permitAll()
                        .requestMatchers("/shorten").permitAll()
                        .requestMatchers("/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/v3/api-docs.yaml").permitAll()
                        .anyRequest().permitAll()
                )
                .formLogin(form -> form
                        .loginPage("/login")
                        .loginProcessingUrl("/login")
                        .defaultSuccessUrl("/admin", true)
                        .failureUrl("/login?error")
                        .permitAll()
                )
                .logout(logout -> logout
                        .logoutRequestMatcher(new AntPathRequestMatcher("/logout"))
                        .logoutSuccessHandler(logoutSuccessHandler)   // ⬅ catat audit, lalu redirect
                        .permitAll()
                )
                .csrf(csrf -> csrf.ignoringRequestMatchers("/h2-console/**"))
                .headers(h -> h.frameOptions(f -> f.sameOrigin()));

        return http.build();
    }
}