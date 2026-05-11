package com.app.shorturl.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.ldap.core.LdapTemplate;
import org.springframework.ldap.core.support.LdapContextSource;

/**
 * LDAP Context Configuration.
 *
 * Mode operasi:
 *   - Kalau {@code ldap.manager-dn} dan {@code ldap.manager-password} di-set,
 *     pakai bind credentials tsb untuk semua operasi search.
 *     Ini WAJIB untuk AD Microsoft karena default-nya menolak anonymous search.
 *   - Kalau kosong, fallback ke anonymous read (jarang berhasil di AD).
 *
 * Service account yang dipakai cukup punya permission READ ke OU users.
 * Contoh DN: CN=svc-portal,OU=Service Accounts,DC=sarinah,DC=net
 *
 * Password JANGAN di-hardcode di properties yang di-commit. Set lewat
 * environment variable LDAP_BIND_PASSWORD lalu di properties tulis:
 *   ldap.manager-password=${LDAP_BIND_PASSWORD:}
 */
@Slf4j
@Configuration
public class LdapContextConfig {

    @Value("${ldap.url}")
    private String ldapUrl;

    @Value("${ldap.base-dn}")
    private String baseDn;

    @Value("${ldap.manager-dn:}")
    private String managerDn;

    @Value("${ldap.manager-password:}")
    private String managerPassword;

    @Bean
    public LdapContextSource ldapContextSource() {
        LdapContextSource ctx = new LdapContextSource();
        ctx.setUrl(ldapUrl);
        ctx.setBase(baseDn);
        ctx.setPooled(true);
        ctx.setReferral("follow");

        boolean useBind = managerDn != null && !managerDn.isBlank()
                && managerPassword != null && !managerPassword.isBlank();

        if (useBind) {
            ctx.setUserDn(managerDn);
            ctx.setPassword(managerPassword);
            ctx.setAnonymousReadOnly(false);
            log.info("LDAP Context configured WITH bind credentials: url={}, base={}, bindDn={}",
                    ldapUrl, baseDn, managerDn);
        } else {
            ctx.setAnonymousReadOnly(true);
            log.warn("LDAP Context configured for ANONYMOUS read: url={}, base={}. " +
                            "Active Directory biasanya menolak anonymous search — " +
                            "set ldap.manager-dn dan ldap.manager-password kalau hasil kosong.",
                    ldapUrl, baseDn);
        }
        return ctx;
    }

    @Bean
    public LdapTemplate ldapTemplate() {
        return new LdapTemplate(ldapContextSource());
    }
}
