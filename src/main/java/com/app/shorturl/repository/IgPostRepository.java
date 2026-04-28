package com.app.shorturl.repository;

import com.app.shorturl.model.IgPost;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface IgPostRepository extends JpaRepository<IgPost, Long> {

    List<IgPost> findByIsActiveTrueOrderByDisplayOrderAscCreatedAtDesc(Pageable pageable);

    List<IgPost> findAllByOrderByDisplayOrderAscCreatedAtDesc();
}
