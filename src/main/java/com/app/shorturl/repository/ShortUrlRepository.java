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

    Page<ShortUrl> findAllByOrderByCreatedAtDesc(Pageable pageable);

    @Query("SELECT s FROM ShortUrl s WHERE " +
           "LOWER(s.shortCode) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(s.originalUrl) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(s.title, '')) LIKE LOWER(CONCAT('%', :q, '%'))")
    Page<ShortUrl> search(@Param("q") String q, Pageable pageable);

    @Modifying
    @Query("UPDATE ShortUrl s SET s.clickCount = s.clickCount + 1, s.lastAccessedAt = :now WHERE s.id = :id")
    void incrementClick(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Query("SELECT COALESCE(SUM(s.clickCount), 0) FROM ShortUrl s")
    Long totalClicks();
}
