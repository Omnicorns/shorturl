package com.app.shorturl.repository;


import com.app.shorturl.model.PdfDocs;
import com.app.shorturl.projection.PdfDocSummary;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PdfDocRepository extends JpaRepository<PdfDocs,Long> {
    @Query("SELECT p.id AS id, p.filename AS filename, p.contentType AS contentType " +
            "FROM PdfDocs p WHERE LOWER(p.filename) LIKE LOWER(CONCAT('%', :keyword, '%'))")
    Page<PdfDocSummary> searchByFilename(@Param("keyword") String keyword, Pageable pageable);

    @Query("SELECT p.id AS id, p.filename AS filename, p.contentType AS contentType FROM PdfDocs p")
    Page<PdfDocSummary> findAllSummary(Pageable pageable);

    Page<PdfDocs> findByFilenameContainingIgnoreCase(String q, Pageable pageable);

    @Query(
            value = """
                    select distinct p
                    from PdfDocs p
                    left join p.coManagers cm
                    where lower(p.ownerUsername) = lower(:username)
                       or lower(cm) = lower(:username)
                    """,
            countQuery = """
                    select count(distinct p)
                    from PdfDocs p
                    left join p.coManagers cm
                    where lower(p.ownerUsername) = lower(:username)
                       or lower(cm) = lower(:username)
                    """
    )
    Page<PdfDocs> findVisibleForUser(@Param("username") String username, Pageable pageable);

    @Query(
            value = """
                    select distinct p
                    from PdfDocs p
                    left join p.coManagers cm
                    where (
                        lower(p.ownerUsername) = lower(:username)
                        or lower(cm) = lower(:username)
                    )
                    and lower(p.filename) like lower(concat('%', :keyword, '%'))
                    """,
            countQuery = """
                    select count(distinct p)
                    from PdfDocs p
                    left join p.coManagers cm
                    where (
                        lower(p.ownerUsername) = lower(:username)
                        or lower(cm) = lower(:username)
                    )
                    and lower(p.filename) like lower(concat('%', :keyword, '%'))
                    """
    )
    Page<PdfDocs> findVisibleForUserByKeyword(@Param("username") String username,
                                              @Param("keyword") String keyword,
                                              Pageable pageable);

    @org.springframework.data.jpa.repository.Modifying
    @Query("UPDATE PdfDocs p SET p.accessCount = COALESCE(p.accessCount, 0) + 1, " +
            "p.lastAccessedAt = :now WHERE p.id = :id")
    void incrementAccess(@Param("id") Long id,
                         @Param("now") java.time.LocalDateTime now);

    // ─────────────────────────────────────────────────────────────────────
    //  LISTING CEPAT — projection skalar (TANPA LOB `data`, TANPA EAGER coManagers)
    // ─────────────────────────────────────────────────────────────────────

    @Query("select p.id as id, p.filename as filename, p.contentType as contentType, " +
            "p.ownerUsername as ownerUsername, p.accessCount as accessCount, " +
            "p.lastAccessedAt as lastAccessedAt from PdfDocs p")
    Page<com.app.shorturl.projection.CatalogListProjection> findAllProjected(Pageable pageable);

    @Query("select p.id as id, p.filename as filename, p.contentType as contentType, " +
            "p.ownerUsername as ownerUsername, p.accessCount as accessCount, " +
            "p.lastAccessedAt as lastAccessedAt from PdfDocs p " +
            "where lower(p.filename) like lower(concat('%', :keyword, '%'))")
    Page<com.app.shorturl.projection.CatalogListProjection> searchProjected(
            @Param("keyword") String keyword, Pageable pageable);

    @Query(value = """
            select distinct p.id as id, p.filename as filename, p.contentType as contentType,
                   p.ownerUsername as ownerUsername, p.accessCount as accessCount,
                   p.lastAccessedAt as lastAccessedAt
            from PdfDocs p
            left join p.coManagers cm
            where lower(p.ownerUsername) = lower(:username)
               or lower(cm) = lower(:username)
            """,
            countQuery = """
            select count(distinct p)
            from PdfDocs p
            left join p.coManagers cm
            where lower(p.ownerUsername) = lower(:username)
               or lower(cm) = lower(:username)
            """)
    Page<com.app.shorturl.projection.CatalogListProjection> findVisibleForUserProjected(
            @Param("username") String username, Pageable pageable);

    @Query(value = """
            select distinct p.id as id, p.filename as filename, p.contentType as contentType,
                   p.ownerUsername as ownerUsername, p.accessCount as accessCount,
                   p.lastAccessedAt as lastAccessedAt
            from PdfDocs p
            left join p.coManagers cm
            where (lower(p.ownerUsername) = lower(:username) or lower(cm) = lower(:username))
              and lower(p.filename) like lower(concat('%', :keyword, '%'))
            """,
            countQuery = """
            select count(distinct p)
            from PdfDocs p
            left join p.coManagers cm
            where (lower(p.ownerUsername) = lower(:username) or lower(cm) = lower(:username))
              and lower(p.filename) like lower(concat('%', :keyword, '%'))
            """)
    Page<com.app.shorturl.projection.CatalogListProjection> findVisibleForUserByKeywordProjected(
            @Param("username") String username,
            @Param("keyword") String keyword,
            Pageable pageable);

    /** Ambil co-manager untuk banyak catalog sekaligus (1 query, hindari N+1). */
    @Query("select p.id as docId, cm as username from PdfDocs p join p.coManagers cm where p.id in :ids")
    java.util.List<com.app.shorturl.projection.CoManagerRow> findCoManagersByDocIds(
            @Param("ids") java.util.Collection<Long> ids);

    // ─────────────────────────────────────────────────────────────────────
    //  POLLING LIVE — hanya id + accessCount + lastAccessedAt (super ringan)
    // ─────────────────────────────────────────────────────────────────────

    @Query("select p.id as id, p.accessCount as accessCount, p.lastAccessedAt as lastAccessedAt from PdfDocs p")
    java.util.List<com.app.shorturl.projection.CatalogCountRow> findAccessCountsAll();

    @Query("select distinct p.id as id, p.accessCount as accessCount, p.lastAccessedAt as lastAccessedAt " +
            "from PdfDocs p left join p.coManagers cm " +
            "where lower(p.ownerUsername) = lower(:username) or lower(cm) = lower(:username)")
    java.util.List<com.app.shorturl.projection.CatalogCountRow> findAccessCountsVisibleForUser(
            @Param("username") String username);

    @Query("select p.filename as filename, p.contentType as contentType, p.data as data " +
            "from PdfDocs p where p.id = :id")
    java.util.Optional<com.app.shorturl.projection.PdfDocContent> findContentById(@Param("id") Long id);

}
