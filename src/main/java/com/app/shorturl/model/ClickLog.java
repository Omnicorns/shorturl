package com.app.shorturl.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

@Entity
@Table(name = "click_logs", indexes = {
    @Index(name = "idx_log_short_url", columnList = "shortUrlId"),
    @Index(name = "idx_log_created", columnList = "createdAt"),
    @Index(name = "idx_log_ip", columnList = "ipAddress")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ClickLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long shortUrlId;

    @Column(length = 16)
    private String shortCode;

    @Column(length = 45) // IPv6 max 45 chars
    private String ipAddress;

    @Column(length = 500)
    private String userAgent;

    // Parsed dari user-agent
    @Column(length = 50)
    private String browser;

    @Column(length = 50)
    private String browserVersion;

    @Column(length = 50)
    private String operatingSystem;

    @Column(length = 30)
    private String deviceType; // Desktop, Mobile, Tablet, Bot

    @Column(length = 80)
    private String deviceBrand;

    // Lokasi dari IP
    @Column(length = 5)
    private String countryCode;

    @Column(length = 80)
    private String country;

    @Column(length = 80)
    private String region;

    @Column(length = 80)
    private String city;

    @Column(length = 80)
    private String isp;

    @Column(length = 500)
    private String referer;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    public void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }
}
