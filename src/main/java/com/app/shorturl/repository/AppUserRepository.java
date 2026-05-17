package com.app.shorturl.repository;


import com.app.shorturl.model.AppUser;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
    Optional<AppUser> findByUsernameIgnoreCase(String username);
    Optional<AppUser> findByEmailIgnoreCase(String email);
    boolean existsByUsernameIgnoreCase(String username);
    boolean existsByEmailIgnoreCase(String email);

    default Optional<AppUser> findByUsernameOrEmail(String identity) {
        if (identity == null || identity.isBlank()) return Optional.empty();
        String value = identity.trim();
        return findByUsernameIgnoreCase(value).or(() -> findByEmailIgnoreCase(value));
    }
}
