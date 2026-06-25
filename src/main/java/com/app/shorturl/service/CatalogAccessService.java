package com.app.shorturl.service;


import com.app.shorturl.model.PdfDocs;
import com.app.shorturl.repository.PdfDocRepository;
import com.app.shorturl.response.CatalogResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class CatalogAccessService {

    private final PdfDocRepository pdfDocsRepository;


    public boolean isSuperAdmin(Authentication auth) {
        if (auth == null) return false;

        String username = auth.getName();

        boolean roleSuperAdmin = auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));

        boolean emergencyAdmin = username != null && (
                username.equalsIgnoreCase("admin")
                        || username.equalsIgnoreCase("surl")
        );

        return roleSuperAdmin || emergencyAdmin;
    }

    public String currentUsername(Authentication auth) {
        if (auth == null || auth.getName() == null) return "system";
        return auth.getName().trim().toLowerCase();
    }

    public boolean canManage(PdfDocs doc, Authentication auth) {
        if (doc == null || auth == null) return false;
        if (isSuperAdmin(auth)) return true;

        String username = currentUsername(auth);
        return doc.getOwnerUsername() != null
                && doc.getOwnerUsername().equalsIgnoreCase(username);
    }

    public Page<PdfDocs> findVisibleCatalogs(String keyword,
                                             Pageable pageable,
                                             Authentication auth) {
        String k = keyword == null ? "" : keyword.trim();

        if (isSuperAdmin(auth)) {
            if (!k.isBlank()) {
                return pdfDocsRepository.findByFilenameContainingIgnoreCase(k, pageable);
            }
            return pdfDocsRepository.findAll(pageable);
        }

        String username = currentUsername(auth);

        if (!k.isBlank()) {
            return pdfDocsRepository.findVisibleForUserByKeyword(username, k, pageable);
        }

        return pdfDocsRepository.findVisibleForUser(username, pageable);
    }

    /**
     * Jumlah total catalog yang BISA DILIHAT user saat ini.
     * Super-admin: semua. User biasa: yang dia miliki / di-share.
     * Dipakai untuk badge "Catalog PDF (n)" di dashboard.
     */
    public long countVisibleCatalogs(Authentication auth) {
        if (isSuperAdmin(auth)) {
            return pdfDocsRepository.count();
        }
        String username = currentUsername(auth);
        return pdfDocsRepository
                .findVisibleForUser(username, org.springframework.data.domain.PageRequest.of(0, 1))
                .getTotalElements();
    }

    public CatalogResponse toResponse(PdfDocs doc, Authentication auth) {
        return CatalogResponse.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .contentType(doc.getContentType())
                .url("/api/admin/users/pdf/" + doc.getId())
                .open_in_viewer("/catalogue?id=" + doc.getId())
                .ownerUsername(doc.getOwnerUsername())
                .coManagers(doc.getCoManagers())
                .canManageAccess(canManage(doc, auth))
                .accessCount(doc.getAccessCount() == null ? 0L : doc.getAccessCount())
                .lastAccessedAt(doc.getLastAccessedAt())
                .build();
    }

    // ═══════════════════════════════════════════════════════════════════
    //  LISTING CEPAT — pakai projection (tanpa LOB) + co-manager batch
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Versi cepat dari listing catalog: mengembalikan langsung Page<CatalogResponse>.
     * - Query listing hanya membaca kolom skalar (tanpa byte PDF).
     * - Co-manager seluruh halaman diambil dalam SATU query (bukan N+1).
     */
    @Transactional(readOnly = true)
    public Page<CatalogResponse> listCatalogResponses(String keyword,
                                                      Pageable pageable,
                                                      Authentication auth) {
        String k = keyword == null ? "" : keyword.trim();

        Page<com.app.shorturl.projection.CatalogListProjection> page;
        if (isSuperAdmin(auth)) {
            page = k.isBlank()
                    ? pdfDocsRepository.findAllProjected(pageable)
                    : pdfDocsRepository.searchProjected(k, pageable);
        } else {
            String username = currentUsername(auth);
            page = k.isBlank()
                    ? pdfDocsRepository.findVisibleForUserProjected(username, pageable)
                    : pdfDocsRepository.findVisibleForUserByKeywordProjected(username, k, pageable);
        }

        // Ambil co-manager untuk semua id di halaman ini sekaligus.
        java.util.List<Long> ids = page.getContent().stream()
                .map(com.app.shorturl.projection.CatalogListProjection::getId)
                .toList();

        java.util.Map<Long, Set<String>> coManagerMap = new java.util.HashMap<>();
        if (!ids.isEmpty()) {
            for (com.app.shorturl.projection.CoManagerRow row : pdfDocsRepository.findCoManagersByDocIds(ids)) {
                coManagerMap
                        .computeIfAbsent(row.getDocId(), x -> new LinkedHashSet<>())
                        .add(row.getUsername());
            }
        }

        boolean superAdmin = isSuperAdmin(auth);
        String me = currentUsername(auth);

        return page.map(p -> {
            boolean canManage = superAdmin
                    || (p.getOwnerUsername() != null && p.getOwnerUsername().equalsIgnoreCase(me));
            return CatalogResponse.builder()
                    .id(p.getId())
                    .filename(p.getFilename())
                    .contentType(p.getContentType())
                    .url("/api/admin/users/pdf/" + p.getId())
                    .open_in_viewer("/catalogue?id=" + p.getId())
                    .ownerUsername(p.getOwnerUsername())
                    .coManagers(coManagerMap.getOrDefault(p.getId(), new LinkedHashSet<>()))
                    .canManageAccess(canManage)
                    .accessCount(p.getAccessCount() == null ? 0L : p.getAccessCount())
                    .lastAccessedAt(p.getLastAccessedAt())
                    .build();
        });
    }

    @Transactional
    public void updateAccess(Long id,
                             String coManagers,
                             String newOwner,
                             Authentication auth) {
        PdfDocs doc = pdfDocsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Catalog tidak ditemukan."));

        if (!canManage(doc, auth)) {
            throw new AccessDeniedException("Anda bukan owner atau super admin.");
        }

        doc.setCoManagers(parseUsers(coManagers));

        if (newOwner != null && !newOwner.isBlank()) {
            doc.setOwnerUsername(newOwner.trim().toLowerCase());
        }

        pdfDocsRepository.save(doc);
    }

    /**
     * Hapus catalog (PDF). Hanya owner atau super-admin yang boleh.
     *
     * @throws IllegalArgumentException jika catalog tidak ditemukan
     * @throws AccessDeniedException    jika user bukan owner/super-admin
     */
    @Transactional
    public void deleteCatalog(Long id, Authentication auth) {
        PdfDocs doc = pdfDocsRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Catalog tidak ditemukan."));

        if (!canManage(doc, auth)) {
            throw new AccessDeniedException("Anda bukan owner atau super admin.");
        }

        pdfDocsRepository.delete(doc);
    }

    private Set<String> parseUsers(String raw) {
        if (raw == null || raw.isBlank()) {
            return new LinkedHashSet<>();
        }

        return Arrays.stream(raw.split("[,;\\s]+"))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .map(String::toLowerCase)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
