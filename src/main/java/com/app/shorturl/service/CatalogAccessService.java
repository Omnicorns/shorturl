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
        return auth != null && auth.getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_SUPER_ADMIN"));
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
        String username = currentUsername(auth);

        if (isSuperAdmin(auth)) {
            if (keyword != null && !keyword.isBlank()) {
                return pdfDocsRepository.findByFilenameContainingIgnoreCase(keyword.trim(), pageable);
            }
            return pdfDocsRepository.findAll(pageable);
        }

        if (keyword != null && !keyword.isBlank()) {
            return pdfDocsRepository.findVisibleForUserByKeyword(username, keyword.trim(), pageable);
        }

        return pdfDocsRepository.findVisibleForUser(username, pageable);
    }

    public CatalogResponse toResponse(PdfDocs doc, Authentication auth) {
        return CatalogResponse.builder()
                .id(doc.getId())
                .filename(doc.getFilename())
                .contentType(doc.getContentType())
                .url("/api/v1/admin/pdf/" + doc.getId())
                .open_in_viewer("/catalogue?id=" + doc.getId())
                .ownerUsername(doc.getOwnerUsername())
                .coManagers(doc.getCoManagers())
                .canManageAccess(canManage(doc, auth))
                .build();
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
