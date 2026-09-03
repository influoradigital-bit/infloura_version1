package com.influora.web;

import com.influora.common.ApiResponse;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.web.dto.creator.CreatorAgentDtos.ConsentResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationListResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdatePreferencesRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * T-MEERA-CREATOR-PHASE-A (SPEC.md 2.2-2.7, A3/A6) — creator's own Meera preferences (rate
 * floors, filters, automation level, language/tone, working hours), DPDP consent, and Meera
 * conversation list/export/delete. Every route resolves the acting creator strictly from {@code
 * principal.getUserId()} (never a path/body id) — see {@code
 * CreatorAgentPreferencesService#requireCreatorProfile}.
 */
@RestController
@RequestMapping("/creator/agent-preferences")
public class CreatorAgentController {

    private final CreatorAgentPreferencesService preferencesService;
    private final CreatorAgentConversationService conversationService;

    public CreatorAgentController(
            CreatorAgentPreferencesService preferencesService, CreatorAgentConversationService conversationService) {
        this.preferencesService = preferencesService;
        this.conversationService = conversationService;
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PreferencesResponse>> getPreferences(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(preferencesService.getOrCreatePreferences(principal.getUserId())));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<PreferencesResponse>> updatePreferences(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdatePreferencesRequest req) {
        return ResponseEntity.ok(
                ApiResponse.ok(preferencesService.updatePreferences(principal.getUserId(), req)));
    }

    @PostMapping("/consent")
    public ResponseEntity<ApiResponse<ConsentResponse>> recordConsent(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(
                ApiResponse.ok(new ConsentResponse(preferencesService.recordConsent(principal.getUserId()))));
    }

    /**
     * A6 (fix round 1, item 4) — DPDP requires withdrawal to be as easy as giving consent. Puts
     * the creator straight back behind the {@code 403 CONSENT_REQUIRED} gate on their next Meera
     * turn.
     */
    @DeleteMapping("/consent")
    public ResponseEntity<Void> withdrawConsent(@AuthenticationPrincipal AuthPrincipal principal) {
        preferencesService.withdrawConsent(principal.getUserId());
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/conversations")
    public ResponseEntity<ApiResponse<ConversationListResponse>> listConversations(
            @AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(ApiResponse.ok(conversationService.listConversations(principal.getUserId())));
    }

    @GetMapping("/conversations/{conversationId}/export")
    public ResponseEntity<ApiResponse<ConversationExportResponse>> exportConversation(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String conversationId) {
        return ResponseEntity.ok(
                ApiResponse.ok(conversationService.exportConversation(principal.getUserId(), conversationId)));
    }

    @DeleteMapping("/conversations/{conversationId}")
    public ResponseEntity<Void> deleteConversation(
            @AuthenticationPrincipal AuthPrincipal principal, @PathVariable String conversationId) {
        conversationService.deleteConversation(principal.getUserId(), conversationId);
        return ResponseEntity.noContent().build();
    }
}
