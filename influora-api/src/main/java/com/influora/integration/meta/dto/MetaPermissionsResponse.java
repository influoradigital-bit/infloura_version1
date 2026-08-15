package com.influora.integration.meta.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/**
 * {@code GET /me/permissions} — the scopes Meta actually granted or declined for this token
 * (CR-104), as opposed to {@link com.influora.integration.meta.oauth.MetaOAuthService#REQUIRED_SCOPES},
 * which is only what the authorization dialog *requested*. A creator can decline individual
 * permissions in that dialog; only this endpoint reflects what was really granted.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MetaPermissionsResponse(List<Permission> data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Permission(String permission, String status) {

        private static final String STATUS_GRANTED = "granted";

        public boolean isGranted() {
            return STATUS_GRANTED.equalsIgnoreCase(status);
        }
    }
}
