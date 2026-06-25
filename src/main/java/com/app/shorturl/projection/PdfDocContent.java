package com.app.shorturl.projection;

public interface PdfDocContent {
    String getFilename();
    String getContentType();
    byte[] getData();
}
