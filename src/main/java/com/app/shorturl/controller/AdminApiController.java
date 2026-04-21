package com.app.shorturl.controller;

import com.app.shorturl.dto.ShortUrlDto.ErrorResponse;
import com.app.shorturl.dto.ShortUrlDto.Response;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.service.ShortUrlService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/admin")
@RequiredArgsConstructor
@Tag(name = "Admin API", description = "Endpoint admin untuk mengelola short URL (butuh Basic Auth)")
@SecurityRequirement(name = "basicAuth")
public class AdminApiController {

    private final ShortUrlService service;

    @Value("${app.base-url}")
    private String baseUrl;

    @Operation(summary = "List semua short URL (pagination)",
               description = "Mengembalikan daftar short URL dengan pagination dan optional search.")
    @ApiResponse(responseCode = "200", description = "Success")
    @ApiResponse(responseCode = "401", description = "Unauthorized",
        content = @Content(schema = @Schema(implementation = ErrorResponse.class)))
    @GetMapping("/urls")
    public ResponseEntity<Map<String, Object>> list(
            @RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<ShortUrl> urls = service.list(q,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100)));

        return ResponseEntity.ok(Map.of(
                "content", urls.getContent().stream().map(u -> Response.from(u, baseUrl)).toList(),
                "page", urls.getNumber(),
                "size", urls.getSize(),
                "totalElements", urls.getTotalElements(),
                "totalPages", urls.getTotalPages()
        ));
    }

    @Operation(summary = "Statistik total",
               description = "Total URL dan total klik.")
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Long>> stats() {
        return ResponseEntity.ok(Map.of(
                "totalUrls", service.totalUrls(),
                "totalClicks", service.totalClicks()
        ));
    }

    @Operation(summary = "Toggle aktif/nonaktif short URL")
    @ApiResponses({
        @ApiResponse(responseCode = "200", description = "Status berhasil diubah"),
        @ApiResponse(responseCode = "404", description = "Short URL tidak ditemukan")
    })
    @PatchMapping("/urls/{id}/toggle")
    public ResponseEntity<?> toggle(@PathVariable Long id) {
        try {
            ShortUrl s = service.toggleActive(id);
            return ResponseEntity.ok(Response.from(s, baseUrl));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, e.getMessage()));
        }
    }

    @Operation(summary = "Hapus short URL")
    @ApiResponses({
        @ApiResponse(responseCode = "204", description = "Berhasil dihapus"),
        @ApiResponse(responseCode = "404", description = "Short URL tidak ditemukan")
    })
    @DeleteMapping("/urls/{id}")
    public ResponseEntity<?> delete(@PathVariable Long id) {
        try {
            service.delete(id);
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            return ResponseEntity.status(404)
                    .body(new ErrorResponse(404, "Tidak ditemukan: id=" + id));
        }
    }
}
