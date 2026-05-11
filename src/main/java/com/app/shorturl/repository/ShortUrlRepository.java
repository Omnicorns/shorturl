package com.app.shorturl.repository;

import com.app.shorturl.model.ShortUrl;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface ShortUrlRepository extends JpaRepository<ShortUrl, Long> {

    Optional<ShortUrl> findByShortCode(String shortCode);

    boolean existsByShortCode(String shortCode);

    // ─── Super-admin / list-semua ───────────────────────────────────
    Page<ShortUrl> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT s FROM ShortUrl s WHERE " +
            "LOWER(s.shortCode) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(s.originalUrl) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
            "LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<ShortUrl> search(@Param("q") String q, Pageable pageable);

    // ─── Filter by akses user (owner ATAU co-manager) ───────────────
    // SELECT DISTINCT karena join ke coManagers bisa nge-duplicate row
    @Query("SELECT DISTINCT s FROM ShortUrl s " +
            "LEFT JOIN s.coManagers cm " +
            "WHERE LOWER(s.ownerUsername) = LOWER(:user) " +
            "   OR LOWER(cm) = LOWER(:user) " +
            "ORDER BY s.createdAt DESC")
    Page<ShortUrl> findAccessibleByUser(@Param("user") String user, Pageable pageable);

    @Query("SELECT DISTINCT s FROM ShortUrl s " +
            "LEFT JOIN s.coManagers cm " +
            "WHERE (LOWER(s.ownerUsername) = LOWER(:user) OR LOWER(cm) = LOWER(:user)) " +
            "  AND ( LOWER(s.shortCode)              LIKE LOWER(CONCAT('%', :q, '%')) " +
            "     OR LOWER(s.originalUrl)            LIKE LOWER(CONCAT('%', :q, '%')) " +
            "     OR LOWER(COALESCE(s.title, ''))    LIKE LOWER(CONCAT('%', :q, '%')) ) " +
            "ORDER BY s.createdAt DESC")
    Page<ShortUrl> searchAccessibleByUser(@Param("q") String q,
                                          @Param("user") String user,
                                          Pageable pageable);

    @Modifying
    @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1, s.lastAccessedAt = :now WHERE s.id = :id")
    void incrementClick(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COALESCE(SUM(s.clickCount), 0) FROM ShortUrl s")
    Long totalClicks();

    // ─── Stats hanya untuk URL yang accessible oleh user ────────────
    @Query("SELECT COUNT(DISTINCT s) FROM ShortUrl s " +
            "LEFT JOIN s.coManagers cm " +
            "WHERE LOWER(s.ownerUsername) = LOWER(:user) OR LOWER(cm) = LOWER(:user)")
    long countAccessibleByUser(@Param("user") String user);

    @Query("SELECT COALESCE(SUM(s.clickCount), 0) FROM ShortUrl s " +
            "WHERE s.id IN (" +
            "  SELECT DISTINCT s2.id FROM ShortUrl s2 LEFT JOIN s2.coManagers cm " +
            "  WHERE LOWER(s2.ownerUsername) = LOWER(:user) OR LOWER(cm) = LOWER(:user)" +
            ")")
    Long totalClicksByUser(@Param("user") String user);
}
