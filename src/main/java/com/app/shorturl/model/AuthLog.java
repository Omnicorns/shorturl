package com.app.shorturl.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Audit log untuk authentication events: login success, login failure, logout.
 */
@Entity
@Table(name = "auth_logs", indexes = {
    @Index(name = "idx_auth_username", columnList = "username"),
    @Index(name = "idx_auth_created", columnList = "createdAt"),
    @Index(name = "idx_auth_event", columnList = "eventType")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AuthLog {

    public enum EventType {
        LOGIN_SUCCESS,
        LOGIN_FAILURE,
        LOGOUT
    }

    public enum AuthSource {
        LOCAL,      // akun in-memory (surl)
        LDAP,       // Active Directory
        UNKNOWN     // gagal login — belum tau provider mana
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String username;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EventType eventType;

    @Enumerated(EnumType.STRING)
    @Column(length = 10)
    private AuthSource authSource;

    @Column(length = 45)        // cukup untuk IPv6
    private String ipAddress;

    @Column(length = 500)
    private String userAgent;

    @Column(length = 255)
    private String failureReason;     // diisi kalau LOGIN_FAILURE

    @Column(length = 100)
    private String sessionId;         // untuk korelasi login ↔ logout

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}