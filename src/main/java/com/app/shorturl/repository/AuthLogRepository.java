package com.app.shorturl.repository;

import com.app.shorturl.model.AuthLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface AuthLogRepository extends JpaRepository<AuthLog, Long> {

    Page<AuthLog> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuthLog> findByUsernameOrderByCreatedAtDesc(String username, Pageable pageable);

    Page<AuthLog> findByEventTypeOrderByCreatedAtDesc(AuthLog.EventType eventType, Pageable pageable);
}