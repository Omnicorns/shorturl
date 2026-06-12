package com.app.shorturl.controller;


import com.app.shorturl.config.AccessControlHelper;
import com.app.shorturl.model.PdfDocs;
import com.app.shorturl.model.ShortUrl;
import com.app.shorturl.projection.PdfDocSummary;
import com.app.shorturl.repository.PdfDocRepository;
import com.app.shorturl.repository.ShortUrlRepository;
import com.app.shorturl.response.CatalogResponse;
import com.app.shorturl.service.CatalogAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.*;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/admin/users")
public class UserImportApi {
    private final AccessControlHelper accessControl;

    private final PdfDocRepository pdfDocRepository;
    private final ShortUrlRepository shortUrlRepository;
    private final CatalogAccessService catalogAccessService;

    // Import CSV (multipart/form-data)

    private static final long MAX_FILE_SIZE = 50L * 1024 * 1024; // 10 MB

    private void validateSize(MultipartFile file) {
        if (file.getSize() > MAX_FILE_SIZE) {
            throw new ResponseStatusException(
                    HttpStatus.PAYLOAD_TOO_LARGE,
                    String.format("File '%s' terlalu besar (%.2f MB). Maksimal 10 MB.",
                            file.getOriginalFilename(),
                            file.getSize() / 1024.0 / 1024.0)
            );
        }
    }


    @PostMapping(value = "/pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> upload(@RequestPart("file") MultipartFile file,
                                      Authentication authentication) throws IOException {
        if (file.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File kosong");
        if (!isPdf(file)) throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Harus PDF");

        validateSize(file);

        PdfDocs doc = new PdfDocs();
        doc.setFilename(file.getOriginalFilename());
        doc.setContentType("application/pdf");
        doc.setData(file.getBytes());

        // INI WAJIB
        doc.setOwnerUsername(authentication.getName().toLowerCase());

        doc = pdfDocRepository.save(doc);

        return Map.of(
                "id", doc.getId(),
                "filename", doc.getFilename(),
                "url", "/api/v1/admin/pdf/" + doc.getId(),
                "open_in_viewer", "/catalogue?id=" + doc.getId(),
                "ownerUsername", doc.getOwnerUsername(),
                "coManagers", doc.getCoManagers()
        );
    }


    @GetMapping(value="/pdf/{id}", produces=MediaType.APPLICATION_PDF_VALUE)
    public ResponseEntity<?> stream(
            @PathVariable Long id,
            @RequestHeader HttpHeaders headers) {

        // 1) Ambil entity TANPA langsung sentuh BLOB
        PdfDocs doc = pdfDocRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

        // 2) ETag ringan: berdasarkan id saja
        //    Asumsi: kalau isi PDF berubah => pakai id baru.
        String etag = "\"pdf-" + id + "\"";

        // 3) Cek If-None-Match dulu => bisa 304 tanpa baca BLOB
        List<String> ifNoneMatch = headers.getIfNoneMatch();
        if (ifNoneMatch != null && ifNoneMatch.contains(etag)) {
            return ResponseEntity
                    .status(HttpStatus.NOT_MODIFIED)
                    .eTag(etag)
                    .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                    .build();
        }

        // 4) Baru sekarang akses data (BLOB) karena memang harus kirim konten
        byte[] data = Objects.requireNonNull(doc.getData(), "PDF kosong");
        long len = data.length;

        String filename = (doc.getFilename() != null && !doc.getFilename().isBlank())
                ? doc.getFilename()
                : (id + ".pdf");

        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_PDF);
        h.setContentDisposition(ContentDisposition.inline()
                .filename(filename, StandardCharsets.UTF_8)
                .build());
        h.setCacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
        h.setETag(etag);
        h.setContentLength(len);
        // Nggak perlu ACCEPT_RANGES kalau kamu selalu kirim full body

        return new ResponseEntity<>(new ByteArrayResource(data), h, HttpStatus.OK);
    }

    // ====== sesuai schema kamu (tanpa ubah tabel) ======
    @PostMapping(value = "/pdf/bulk", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String, Object> uploadBulk(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(value = "filenames", required = false) List<String> filenames,
            Authentication authentication) {

        if (files == null || files.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tidak ada file yang diunggah");
        }

        if (files.size() > 100) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Maksimum 100 file per unggahan");
        }
        // Validasi size satu per satu — fail-fast: berhenti di file pertama yang oversize
        for (MultipartFile f : files) {
            validateSize(f);
        }


        List<Map<String, Object>> items = new ArrayList<>();
        int success = 0, failed = 0;

        for (int i = 0; i < files.size(); i++) {
            MultipartFile file = files.get(i);
            String originalName = file != null ? file.getOriginalFilename() : null;

            // Resolve filename: custom name dari frontend, atau fallback ke nama asli
            String fname;
            if (filenames != null && i < filenames.size()
                    && filenames.get(i) != null && !filenames.get(i).isBlank()) {
                fname = filenames.get(i).trim();
                if (!fname.toLowerCase().endsWith(".pdf")) {
                    fname += ".pdf";
                }
            } else {
                fname = originalName;
            }

            try {
                if (file == null || file.isEmpty()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File kosong: " + fname);
                }
                if (!isPdf(file)) {
                    throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                            "Bukan PDF: " + fname);
                }

                PdfDocs doc = new PdfDocs();
                doc.setFilename(fname);
                doc.setContentType("application/pdf");
                doc.setData(file.getBytes());
                if (authentication != null && authentication.getName() != null) {
                    doc.setOwnerUsername(authentication.getName().toLowerCase());
                }

                doc = pdfDocRepository.save(doc);

                Map<String, Object> ok = new LinkedHashMap<>();
                ok.put("index", i);
                ok.put("filename", doc.getFilename());
                ok.put("id", doc.getId());

                ok.put("url", "/api/admin/users/pdf/" + doc.getId());
                ok.put("ownerUsername", doc.getOwnerUsername());
                ok.put("coManagers", doc.getCoManagers());
                ok.put("open_in_viewer", "/catalogue?id=" + doc.getId());
                ok.put("status", "OK");
                items.add(ok);
                success++;

            } catch (Exception e) {
                Map<String, Object> err = new LinkedHashMap<>();
                err.put("index", i);
                err.put("filename", fname);
                err.put("status", "ERROR");
                err.put("error", e.getMessage());
                items.add(err);
                failed++;
            }
        }

        return Map.of(
                "total", files.size(),
                "success", success,
                "failed", failed,
                "items", items
        );
    }

    @GetMapping("/catalogs")
    public Page<CatalogResponse> listCatalogs(@RequestParam(defaultValue = "") String q,
                                              @RequestParam(defaultValue = "") String keyword,
                                              @RequestParam(defaultValue = "0") int page,
                                              @RequestParam(defaultValue = "10") int size,
                                              @RequestParam(defaultValue = "id") String sortBy,
                                              @RequestParam(defaultValue = "desc") String dir,
                                              Authentication authentication) {

        String search = !q.isBlank() ? q : keyword;

        String safeSortBy = "id";
        if ("filename".equalsIgnoreCase(sortBy)) {
            safeSortBy = "filename";
        } else if ("ownerUsername".equalsIgnoreCase(sortBy) || "owner_username".equalsIgnoreCase(sortBy)) {
            safeSortBy = "ownerUsername";
        }

        Sort.Direction direction = "asc".equalsIgnoreCase(dir)
                ? Sort.Direction.ASC
                : Sort.Direction.DESC;

        Pageable pageable = PageRequest.of(
                Math.max(page, 0),
                Math.max(size, 1),
                Sort.by(direction, safeSortBy)
        );

        return catalogAccessService
                .findVisibleCatalogs(search, pageable, authentication)
                .map(doc -> catalogAccessService.toResponse(doc, authentication));
    }
    /**
     * Deteksi cepat PDF:
     * - Content-Type 'application/pdf' ATAU
     * - Magic header file diawali "%PDF"
     */
    private boolean isPdf(MultipartFile file) throws IOException {
        String ct = file.getContentType();
        if ("application/pdf".equalsIgnoreCase(ct)) return true;

        byte[] head = file.getInputStream().readNBytes(5);
        return head.length >= 4 &&
                head[0] == '%' && head[1] == 'P' && head[2] == 'D' && head[3] == 'F';
    }


    @PutMapping(value = "/pdf/{id}", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> updatePdf(
            @PathVariable Long id,
            @RequestPart("file") MultipartFile file,
            @RequestPart(name = "filename", required = false) String filename
    ) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "File kosong");
        }
        if (!isPdf(file)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Harus PDF");
        }
        validateSize(file);

        PdfDocs doc = pdfDocRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Dokumen tidak ditemukan"));

        // Update konten
        doc.setData(file.getBytes());
        doc.setContentType("application/pdf");
        // Pakai filename baru jika dikirim, kalau tidak pakai original filename dari upload
        String newName = (filename != null && !filename.isBlank())
                ? filename
                : (file.getOriginalFilename() != null ? file.getOriginalFilename() : doc.getFilename());
        doc.setFilename(newName);

        doc = pdfDocRepository.save(doc);

        return Map.of(
                "id", doc.getId(),
                "filename", doc.getFilename(),
                "url", "/pdf/" + doc.getId(),
                "open_in_viewer", "/catalogue?id=" + doc.getId(),
                "status", "UPDATED"
        );
    }


    @GetMapping(value = "/click-counts", produces = MediaType.APPLICATION_JSON_VALUE)
    @ResponseBody
    public List<Map<String, Object>> clickCounts() {

        boolean isSuperAdmin = accessControl.isCurrentUserSuperAdmin();
        String currentUser = accessControl.currentUsername();

        List<ShortUrl> data;

        if (isSuperAdmin) {
            data = shortUrlRepository.findAll();
        } else {
            data = shortUrlRepository.findAccessibleByUsername(currentUser);
        }

        return data.stream()
                .map(u -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("shortCode", u.getShortCode());
                    m.put("clickCount", u.getClickCount());
                    m.put("active", u.getActive());
                    return m;
                })
                .toList();
    }





}
