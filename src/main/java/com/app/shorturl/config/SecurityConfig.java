package com.app.shorturl.config;

import com.app.shorturl.service.DatabaseOrFallbackUserDetailsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationEventPublisher;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.ldap.authentication.ad.ActiveDirectoryLdapAuthenticationProvider;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.csrf.HttpSessionCsrfTokenRepository;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

@Slf4j
@Configuration
public class SecurityConfig {

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.domain}")
    private String ldapDomain;

    @Value("${ldap.base-dn}")
    private String ldapBaseDn;

    /** CN group AD (CSV) yang boleh masuk dashboard. Kosong = tidak ada user AD yang boleh. */
    @Value("${ldap.admin-groups:}")
    private String ldapAdminGroupsCsv;

    /** CN group AD (CSV) yang otomatis super-admin. */
    @Value("${ldap.superadmin-groups:}")
    private String ldapSuperAdminGroupsCsv;

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
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(userDetailsService);
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
                "(&(objectClass=user)(|" +
                        "(sAMAccountName={1})" +
                        "(userPrincipalName={0})" +
                        "(userPrincipalName={1})" +
                        "(mail={0})" +
                        "(mail={1})" +
                        "))"
        );


        // Sebelumnya: SEMUA user AD yang berhasil bind diberi ROLE_ADMIN.
        // Sekarang: role hanya diberikan kalau user anggota group AD yang dikonfigurasi
        // (ldap.admin-groups / ldap.superadmin-groups). Authority mentah dari provider
        // AD adalah CN dari atribut memberOf.
        Set<String> adminGroups = parseGroups(ldapAdminGroupsCsv);
        Set<String> superAdminGroups = parseGroups(ldapSuperAdminGroupsCsv);

        provider.setAuthoritiesMapper(authorities ->
                mapAdAuthorities(authorities, adminGroups, superAdminGroups));

        if (adminGroups.isEmpty() && superAdminGroups.isEmpty()) {
            log.warn("ldap.admin-groups & ldap.superadmin-groups kosong: login via Active Directory DITOLAK " +
                    "untuk semua user. Set LDAP_ADMIN_GROUPS untuk mengizinkan group tertentu.");
        }
        log.info("AD provider configured: domain={}, url={}, baseDn={}, adminGroups={}, superAdminGroups={}",
                ldapDomain, ldapUrl, ldapBaseDn, adminGroups, superAdminGroups);

        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(
            DaoAuthenticationProvider localAuthProvider,
            ActiveDirectoryLdapAuthenticationProvider activeDirectoryProvider,
            AuthenticationEventPublisher eventPublisher,
            AccessControlHelper accessControlHelper
    ) {
        ProviderManager providerManager =
                new ProviderManager(localAuthProvider,
                        new AdminGroupGuard(activeDirectoryProvider, accessControlHelper));

        providerManager.setAuthenticationEventPublisher(eventPublisher);
        providerManager.setEraseCredentialsAfterAuthentication(true);

        return providerManager;
    }

    /**
     * API chain khusus /api/v1/**
     * <p>
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
                .httpBasic(basic -> {
                })
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
     * <p>
     * Jadi upload catalog dari halaman admin akan punya Authentication.
     */
    @Bean
    @Order(2)
    public SecurityFilterChain webFilterChain(
            HttpSecurity http,
            AuthenticationManager authenticationManager,
            LogoutSuccessHandler logoutSuccessHandler
    ) throws Exception {

        // Handler non-XOR: token di meta tag / hidden input dipakai apa adanya oleh JS.
        CsrfTokenRequestAttributeHandler requestHandler = new CsrfTokenRequestAttributeHandler();

        http
                .authenticationManager(authenticationManager)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(
                                "/login", "/reset-password",
                                "/css/**", "/js/**", "/error", "/images/**", "/img.png", "/favicon.ico"
                        ).permitAll()
                        // Membuat akun & membuat link reset password hanya untuk admin
                        // (dicek lagi super-admin di controller).
                        .requestMatchers("/register", "/forgot-password").hasRole("ADMIN")
                        .requestMatchers("/admin/**").hasRole("ADMIN")
                        // Baca PDF boleh publik, TAPI controller mewajibkan kunci akses (?k=)
                        // atau user login yang berhak. Upload/ubah/hapus wajib login.
                        .requestMatchers(HttpMethod.GET, "/api/admin/users/pdf/*").permitAll()
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")
                        .requestMatchers("/shorten").permitAll()
                        .requestMatchers(
                                "/swagger-ui/**", "/swagger-ui.html",
                                "/v3/api-docs/**", "/v3/api-docs.yaml"
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
                        .logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher("/logout"))
                        .invalidateHttpSession(true)
                        .logoutSuccessUrl("/login?logout")
                        .deleteCookies("JSESSIONID", "XSRF-TOKEN")
                        .permitAll()
                )
                // CSRF AKTIF untuk web session. Token disimpan di session; Thymeleaf
                // otomatis menyisipkan hidden input _csrf di setiap <form th:action method=post>,
                // dan fetch() di dashboard mengirim header X-CSRF-TOKEN (meta _csrf).
                // /api/v1/** (stateless, chain terpisah) tidak terpengaruh.
                .csrf(csrf -> csrf
                        .csrfTokenRepository(new HttpSessionCsrfTokenRepository())
                        .csrfTokenRequestHandler(requestHandler)
                )
                .headers(headers -> headers
                        .frameOptions(frame -> frame.sameOrigin())
                );

        return http.build();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Helper mapping role AD
    // ═══════════════════════════════════════════════════════════════════

    static Set<String> parseGroups(String csv) {
        if (csv == null || csv.isBlank()) return Set.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(g -> !g.isEmpty())
                .map(g -> g.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    static List<GrantedAuthority> mapAdAuthorities(Collection<? extends GrantedAuthority> raw,
                                                   Set<String> adminGroups,
                                                   Set<String> superAdminGroups) {
        Set<String> groups = raw == null ? Set.of() : raw.stream()
                .map(GrantedAuthority::getAuthority)
                .filter(a -> a != null)
                .map(a -> a.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        boolean superAdmin = groups.stream().anyMatch(superAdminGroups::contains);
        boolean admin = superAdmin || groups.stream().anyMatch(adminGroups::contains);

        List<GrantedAuthority> out = new ArrayList<>();
        if (admin) out.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
        if (superAdmin) out.add(new SimpleGrantedAuthority("ROLE_SUPERADMIN"));
        return out;
    }

    /**
     * Membungkus provider AD: kalau bind sukses tapi user BUKAN anggota group
     * yang diizinkan (tidak dapat ROLE_ADMIN), login ditolak — bukan dibiarkan
     * masuk tanpa role.
     */
    static final class AdminGroupGuard implements AuthenticationProvider {
        private final ActiveDirectoryLdapAuthenticationProvider delegate;
        private final AccessControlHelper accessControl;

        AdminGroupGuard(ActiveDirectoryLdapAuthenticationProvider delegate, AccessControlHelper accessControl) {
            this.delegate = delegate;
            this.accessControl = accessControl;
        }

        @Override
        public Authentication authenticate(Authentication authentication) throws AuthenticationException {
            Authentication result = delegate.authenticate(authentication);
            if (result == null) return null;
            boolean isAdmin = result.getAuthorities().stream()
                    .anyMatch(a -> "ROLE_ADMIN".equals(a.getAuthority()));

            // User yang tercantum eksplisit di app.superadmins tetap boleh masuk
            // walau bukan anggota group (kompatibel dengan konfigurasi lama).
            String name = result.getName() == null ? "" : result.getName().toLowerCase(Locale.ROOT);
            if (!isAdmin && accessControl.isListedInSuperAdminProperty(name)) {
                List<GrantedAuthority> auths = new ArrayList<>(result.getAuthorities());
                auths.add(new SimpleGrantedAuthority("ROLE_ADMIN"));
                UsernamePasswordAuthenticationToken upgraded =
                        UsernamePasswordAuthenticationToken.authenticated(
                                result.getPrincipal(), result.getCredentials(), auths);
                upgraded.setDetails(result.getDetails());
                return upgraded;
            }

            if (!isAdmin) {
                log.warn("AD login ditolak untuk '{}': bukan anggota group admin yang diizinkan",
                        authentication.getName());
                throw new DisabledException("Akun tidak memiliki akses ke aplikasi ini.");
            }
            return result;
        }

        @Override
        public boolean supports(Class<?> authentication) {
            return delegate.supports(authentication);
        }
    }
}