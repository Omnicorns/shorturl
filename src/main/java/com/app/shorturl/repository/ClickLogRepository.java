package com.app.shorturl.repository;

import com.app.shorturl.model.ClickLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ClickLogRepository extends JpaRepository<ClickLog, Long> {

    Page<ClickLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<ClickLog> findByShortUrlIdOrderByCreatedAtDesc(Long shortUrlId, Pageable pageable);

    @Query("SELECT c.country, COUNT(c) FROM ClickLog c WHERE c.country IS NOT NULL " +
           "GROUP BY c.country ORDER BY COUNT(c) DESC")
    List<Object[]> countByCountry();

    @Query("SELECT c.browser, COUNT(c) FROM ClickLog c WHERE c.browser IS NOT NULL " +
           "GROUP BY c.browser ORDER BY COUNT(c) DESC")
    List<Object[]> countByBrowser();

    @Query("SELECT c.deviceType, COUNT(c) FROM ClickLog c WHERE c.deviceType IS NOT NULL " +
           "GROUP BY c.deviceType ORDER BY COUNT(c) DESC")
    List<Object[]> countByDeviceType();

    @Query("SELECT c FROM ClickLog c WHERE " +
           "LOWER(c.shortCode) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(c.ipAddress, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(c.country, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(c.city, '')) LIKE LOWER(CONCAT('%', :q, '%')) OR " +
           "LOWER(COALESCE(c.browser, '')) LIKE LOWER(CONCAT('%', :q, '%')) " +
           "ORDER BY c.createdAt DESC")
    Page<ClickLog> search(@Param("q") String q, Pageable pageable);
}
