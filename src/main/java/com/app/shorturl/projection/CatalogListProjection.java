package com.app.shorturl.projection;

import java.time.LocalDateTime;

/**
 * Projection ringan untuk LISTING catalog.
 *
 * Sengaja TIDAK menyentuh kolom {@code data} (LOB / isi PDF) maupun koleksi
 * {@code coManagers}, sehingga query listing hanya membaca kolom skalar yang
 * dibutuhkan tabel dashboard. Ini menghindari pemuatan byte PDF untuk setiap
 * baris (penyebab utama lambatnya fetch catalog).
 */
public interface CatalogListProjection {
    Long getId();
    String getFilename();
    String getContentType();
    String getOwnerUsername();
    Long getAccessCount();
    LocalDateTime getLastAccessedAt();
}
