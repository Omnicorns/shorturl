package com.app.shorturl.service;

import com.app.shorturl.model.AppUser;
import com.app.shorturl.repository.AppUserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class DatabaseOrFallbackUserDetailsService implements UserDetailsService {

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final String fallbackUsername;
    private final String fallbackPasswordHash;

    public DatabaseOrFallbackUserDetailsService(
            AppUserRepository userRepository,
            PasswordEncoder passwordEncoder,
            @Value("${app.admin.username:surl}") String fallbackUsername,
            @Value("${app.admin.password:surl123}") String fallbackPassword) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.fallbackUsername = normalize(fallbackUsername);
        this.fallbackPasswordHash = looksLikeBCrypt(fallbackPassword)
                ? fallbackPassword
                : passwordEncoder.encode(fallbackPassword);
    }

    @Override
    public UserDetails loadUserByUsername(String identity) throws UsernameNotFoundException {
        String normalizedIdentity = normalize(identity);

        return userRepository.findByUsernameOrEmail(normalizedIdentity)
                .map(this::toUserDetails)
                .orElseGet(() -> loadFallbackAdmin(normalizedIdentity));
    }

    private UserDetails toUserDetails(AppUser appUser) {
        return User.builder()
                .username(appUser.getUsername())
                .password(appUser.getPassword())
                .authorities(appUser.getRole())
                .disabled(!appUser.isEnabled())
                .accountExpired(false)
                .accountLocked(false)
                .credentialsExpired(false)
                .build();
    }

    private UserDetails loadFallbackAdmin(String identity) {
        if (!fallbackUsername.equalsIgnoreCase(identity)) {
            throw new UsernameNotFoundException("User not found");
        }

        return User.builder()
                .username(fallbackUsername)
                .password(fallbackPasswordHash)
                .authorities("ROLE_ADMIN")
                .build();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean looksLikeBCrypt(String value) {
        if (value == null) return false;
        return value.startsWith("$2a$") || value.startsWith("$2b$") || value.startsWith("$2y$");
    }
}
