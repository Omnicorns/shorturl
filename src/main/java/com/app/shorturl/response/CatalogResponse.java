package com.app.shorturl.response;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

@Data
@Builder
public class CatalogResponse {
    private Long id;
    private String filename;
    private String contentType;
    private String url;
    private String open_in_viewer;

    private String ownerUsername;
    private Set<String> coManagers;
    private boolean canManageAccess;
}
