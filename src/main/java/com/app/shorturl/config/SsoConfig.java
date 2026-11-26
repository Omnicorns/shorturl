package com.app.shorturl.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import java.util.List;
import java.util.Map;

/**
 * Login SSO lewat Keycloak (realm sarinah, user dari Active Directory via LDAP federation).
 *
 * <p>Aktif hanya bila {@code app.sso.enabled=true}. Endpoint Keycloak ditulis eksplisit (bukan
 * discovery {@code issuer-uri}) sehingga aplikasi tetap bisa start walau Keycloak sedang tidak
 * terjangkau; issuer tetap divalidasi pada ID token.
 */
@Configuration
@ConditionalOnProperty(name = "app.sso.enabled", havingValue = "true")
public class SsoConfig {

    public static final String REGISTRATION_ID = "keycloak";

    @Bean
    public ClientRegistrationRepository clientRegistrationRepository(
            @Value("${app.sso.issuer-uri}") String issuerUri,
            @Value("${app.sso.client-id}") String clientId,
            @Value("${app.sso.client-secret}") String clientSecret,
            @Value("${app.sso.redirect-uri:{baseUrl}/login/oauth2/code/{registrationId}}") String redirectUri
    ) {
        String issuer = issuerUri.replaceAll("/+$", "");
        String oidc = issuer + "/protocol/openid-connect";
        ClientRegistration registration = ClientRegistration.withRegistrationId(REGISTRATION_ID)
                .clientName("SSO Sarinah")
                .clientId(clientId)
                .clientSecret(clientSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri(redirectUri)
                .scope("openid", "profile", "email")
                .authorizationUri(oidc + "/auth")
                .tokenUri(oidc + "/token")
                .userInfoUri(oidc + "/userinfo")
                .jwkSetUri(oidc + "/certs")
                .issuerUri(issuer)
                .userNameAttributeName("preferred_username")
                .providerConfigurationMetadata(Map.of("end_session_endpoint", oidc + "/logout"))
                .build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    /**
     * Username = {@code preferred_username} Keycloak (sAMAccountName AD, huruf kecil), sama dengan
     * username saat login AD biasa, sehingga kepemilikan short URL tetap cocok.
     * Semua user SSO mendapat ROLE_ADMIN, sama seperti user AD pada login form.
     */
    public static OAuth2UserService<OidcUserRequest, OidcUser> oidcUserService() {
        OidcUserService delegate = new OidcUserService();
        return request -> {
            OidcUser user = delegate.loadUser(request);
            return new DefaultOidcUser(
                    List.of(new SimpleGrantedAuthority("ROLE_ADMIN")),
                    user.getIdToken(),
                    user.getUserInfo(),
                    "preferred_username");
        };
    }
}
