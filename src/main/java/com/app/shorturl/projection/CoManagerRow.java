package com.app.shorturl.projection;

/**
 * Baris (docId, username) untuk mengambil co-manager banyak catalog sekaligus
 * dalam SATU query (hindari N+1 saat listing).
 */
public interface CoManagerRow {
    Long getDocId();
    String getUsername();
}
