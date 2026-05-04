package com.app.shorturl.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

/**
 * LDAP Context Configuration
 *
 * SECURITY NOTES - Addressing "Single Point of Compromise":
 *
 * 1. JANGAN gunakan akun Administrator sebagai bind user (seperti di osTicket saat ini)
 *    - Buat service account khusus: svc-portal@sarinah.net
 *    - Berikan hanya permission READ pada OU yang dibutuhkan
 *    - Jangan berikan permission write/modify/delete
 *
 * 2. WAJIB gunakan LDAPS (port 636) bukan LDAP (port 389)
 *    - Tanpa TLS, password dikirim PLAINTEXT → bisa di-sniff (NTLM Relay attack)
 *    - Di osTicket saat ini "Use TLS" TIDAK dicentang = VULNERABLE
 *
 * 3. Password bind user JANGAN di-hardcode
 *    - Gunakan environment variable: LDAP_BIND_PASSWORD
 *    - Atau gunakan vault (HashiCorp Vault, Kubernetes secrets)
 *
 * 4. Audit log setiap authentication attempt
 */
@Slf4j
@Configuration
public class LdapContextConfig {

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.base-dn}")
    private String baseDn;

    @Bean
    public LdapContextSource ldapContextSource() {
        LdapContextSource ctx = new LdapContextSource();
        ctx.setUrl(ldapUrl);
        ctx.setBase(baseDn);
        // Hapus manager DN & password — gak dipakai
        ctx.setPooled(true);
        ctx.setReferral("follow");
        ctx.setAnonymousReadOnly(true); // read-only tanpa bind

        log.info("LDAP Context configured: url={}, base={}", ldapUrl, baseDn);
        return ctx;
    }

    @Bean
    public LdapTemplate ldapTemplate() {
        return new LdapTemplate(ldapContextSource());
    }
}