package com.app.shorturl.controller;

import com.app.shorturl.dto.IgPostDto.CreateRequest;
import com.app.shorturl.dto.IgPostDto.ErrorResponse;
import com.app.shorturl.dto.IgPostDto.PublicResponse;
import com.app.shorturl.dto.IgPostDto.Response;
import com.app.shorturl.model.IgPost;
import com.app.shorturl.repository.IgPostRepository;
import com.app.shorturl.service.IgThumbnailFetcherService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;

/**
 * Endpoint Instagram feed.
 *
 * Public:
 *   GET  /api/v1/instagram/latest
 *
 * Admin (Basic Auth):
 *   GET    /api/v1/admin/instagram
 *   GET    /api/v1/admin/instagram/{id}
 *   POST   /api/v1/admin/instagram                    — auto-fetch thumbnail dari permalink
 *   PUT    /api/v1/admin/instagram/{id}               — update + auto-refetch jika permalink berubah
 *   POST   /api/v1/admin/instagram/{id}/refetch       — paksa refetch thumbnail untuk post tertentu
 *   PATCH  /api/v1/admin/instagram/{id}/toggle
 *   DELETE /api/v1/admin/instagram/{id}
 */
@Slf4j
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Instagram Feed", description = "Kelola post Instagram — thumbnail otomatis di-fetch dari permalink")
public class IgPostApiController {

    private final IgPostRepository repository;
    private final IgThumbnailFetcherService thumbnailFetcher;

    // =========================================================================
    // PUBLIC
    // =========================================================================

    @Operation(
            summary = "List post aktif (publik)",
            description = "Endpoint publik untuk frontend. Hanya return post dengan isActive=true."
    )
    @SecurityRequirements
    @GetMapping("/instagram/latest")
    @Transactional(readOnly = true)
    public List<PublicResponse> getLatest(
            @RequestParam(defaultValue = "6") int limit
    ) {
        int safe = Math.max(1, Math.min(limit, 50));
        return repository.findByIsActiveTrueOrderByDisplayOrderAscCreatedAtDesc(PageRequest.of(0, safe))
                .stream()
                .map(PublicResponse::from)
                .toList();
    }

    // =========================================================================
    // ADMIN
    // =========================================================================

    @Operation(summary = "[Admin] List semua post")
    @GetMapping("/admin/instagram")
    @Transactional(readOnly = true)
    public List<Response> listAll() {
        return repository.findAllByOrderByDisplayOrderAscCreatedAtDesc()
                .stream()
                .map(Response::from)
                .toList();
    }

    @Operation(summary = "[Admin] Detail post berdasarkan ID")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Ditemukan"),
            @ApiResponse(responseCode = "404", description = "Tidak ditemukan",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @GetMapping("/admin/instagram/{id}")
    @Transactional(readOnly = true)
    public ResponseEntity<?> getById(@PathVariable Long id) {
        Optional<IgPost> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Post tidak ditemukan: id=" + id));
        }
        return ResponseEntity.ok(Response.from(opt.get()));
    }

    @Operation(
            summary = "[Admin] Tambah post baru — thumbnail auto-fetch",
            description = "Cukup kirim permalink, sistem akan otomatis ambil thumbnail dari Instagram " +
                    "dan simpan ke server. Field mediaUrl di body diabaikan (selalu di-fetch ulang). " +
                    "Kalau Instagram block fetch, response 502 — kamu bisa retry pakai endpoint /refetch."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "201", description = "Post dibuat + thumbnail berhasil diambil"),
            @ApiResponse(responseCode = "400", description = "Permalink tidak valid",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Gagal fetch thumbnail dari Instagram",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/admin/instagram")
    @Transactional
    public ResponseEntity<?> create(@Valid @RequestBody CreateRequest req) {
        // Auto-fetch thumbnail dari permalink
        String mediaUrl;
        try {
            mediaUrl = thumbnailFetcher.fetchAndSave(req.getPermalink().trim());
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new ErrorResponse(e.getStatusCode().value(), e.getReason()));
        }

        IgPost post = IgPost.builder()
                .permalink(req.getPermalink().trim())
                .caption(trimOrNull(req.getCaption()))
                .mediaUrl(mediaUrl)
                .displayOrder(req.getDisplayOrder() == null ? 0 : req.getDisplayOrder())
                .isActive(req.getIsActive() == null ? Boolean.TRUE : req.getIsActive())
                .build();

        IgPost saved = repository.save(post);
        log.info("Created IG post id={} permalink={} mediaUrl={}",
                saved.getId(), saved.getPermalink(), saved.getMediaUrl());

        return ResponseEntity.status(201).body(Response.from(saved));
    }

    @Operation(
            summary = "[Admin] Update post",
            description = "Kalau permalink berubah, thumbnail otomatis di-refetch. " +
                    "Kalau permalink tetap sama, mediaUrl tidak diubah."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Updated"),
            @ApiResponse(responseCode = "404", description = "Tidak ditemukan",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Gagal fetch thumbnail baru",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PutMapping("/admin/instagram/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable Long id,
                                    @Valid @RequestBody CreateRequest req) {
        Optional<IgPost> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Post tidak ditemukan: id=" + id));
        }

        IgPost post = opt.get();
        String newPermalink = req.getPermalink().trim();
        boolean permalinkChanged = !newPermalink.equals(post.getPermalink());

        // Kalau permalink berubah, refetch thumbnail
        if (permalinkChanged) {
            try {
                String newMediaUrl = thumbnailFetcher.fetchAndSave(newPermalink);
                post.setMediaUrl(newMediaUrl);
            } catch (ResponseStatusException e) {
                return ResponseEntity.status(e.getStatusCode())
                        .body(new ErrorResponse(e.getStatusCode().value(),
                                "Permalink berubah tapi gagal fetch thumbnail baru: " + e.getReason()));
            }
        }

        post.setPermalink(newPermalink);
        post.setCaption(trimOrNull(req.getCaption()));
        if (req.getDisplayOrder() != null) post.setDisplayOrder(req.getDisplayOrder());
        if (req.getIsActive() != null) post.setIsActive(req.getIsActive());

        return ResponseEntity.ok(Response.from(repository.save(post)));
    }

    @Operation(
            summary = "[Admin] Paksa refetch thumbnail",
            description = "Untuk kasus thumbnail gagal di-fetch saat create, atau gambar di Instagram berubah."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Thumbnail berhasil di-refetch"),
            @ApiResponse(responseCode = "404", description = "Post tidak ditemukan",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class))),
            @ApiResponse(responseCode = "502", description = "Gagal fetch thumbnail",
                    content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    })
    @PostMapping("/admin/instagram/{id}/refetch")
    @Transactional
    public ResponseEntity<?> refetch(@PathVariable Long id) {
        Optional<IgPost> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Post tidak ditemukan: id=" + id));
        }
        IgPost post = opt.get();
        try {
            String newMediaUrl = thumbnailFetcher.fetchAndSave(post.getPermalink());
            post.setMediaUrl(newMediaUrl);
            return ResponseEntity.ok(Response.from(repository.save(post)));
        } catch (ResponseStatusException e) {
            return ResponseEntity.status(e.getStatusCode())
                    .body(new ErrorResponse(e.getStatusCode().value(), e.getReason()));
        }
    }

    @Operation(summary = "[Admin] Toggle aktif / nonaktif")
    @PatchMapping("/admin/instagram/{id}/toggle")
    @Transactional
    public ResponseEntity<?> toggle(@PathVariable Long id) {
        Optional<IgPost> opt = repository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Post tidak ditemukan: id=" + id));
        }
        IgPost post = opt.get();
        post.setIsActive(!Boolean.TRUE.equals(post.getIsActive()));
        return ResponseEntity.ok(Response.from(repository.save(post)));
    }

    @Operation(summary = "[Admin] Hapus post permanen")
    @DeleteMapping("/admin/instagram/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable Long id) {
        if (!repository.existsById(id)) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Post tidak ditemukan: id=" + id));
        }
        repository.deleteById(id);
        return ResponseEntity.noContent().build();
    }

    // =========================================================================
    private String trimOrNull(String value) {
        if (value == null) return null;
        String t = value.trim();
        return t.isEmpty() ? null : t;
    }
}
