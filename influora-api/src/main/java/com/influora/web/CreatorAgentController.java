package com.influora.web;

import com.influora.common.ApiException;
import com.influora.common.ApiResponse;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.web.dto.creator.CreatorAgentDtos.ConsentResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationExportResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.ConversationListResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdateContentGoalRequest;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdatePhoneModelRequest;
import com.influora.web.dto.creator.CreatorAgentDtos.UpdatePreferencesRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
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
    private final MeeraCreatorFeatureProperties featureProperties;

    public CreatorAgentController(
            CreatorAgentPreferencesService preferencesService,
            CreatorAgentConversationService conversationService,
            MeeraCreatorFeatureProperties featureProperties) {
        this.preferencesService = preferencesService;
        this.conversationService = conversationService;
        this.featureProperties = featureProperties;
    }

    /**
     * Priya gate review defect 4 — the Phase A rollback flag. Called first in every gated
     * handler, before any service/repository work, so a disabled feature never touches the
     * database at all.
     */
    private void requireFeatureEnabled() {
        if (!featureProperties.isCreatorEnabled()) {
            throw new ApiException(
                    "FEATURE_DISABLED", "Meera for Creators is currently disabled", HttpStatus.NOT_FOUND);
        }
    }

    @GetMapping
    public ResponseEntity<ApiResponse<PreferencesResponse>> getPreferences(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireFeatureEnabled();
        return ResponseEntity.ok(ApiResponse.ok(preferencesService.getOrCreatePreferences(principal.getUserId())));
    }

    @PutMapping
    public ResponseEntity<ApiResponse<PreferencesResponse>> updatePreferences(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdatePreferencesRequest req) {
        requireFeatureEnabled();
        return ResponseEntity.ok(
                ApiResponse.ok(preferencesService.updatePreferences(principal.getUserId(), req)));
    }

    /**
     * V76 — saves (or, with null/blank, clears) the phone the creator films on, so Meera's camera
     * advice and Shoot Check fit that phone. Its own route, not a field on the full-replace PUT
     * above, so saving the rest of the settings page never wipes it. Behind SecurityConfig's
     * {@code /creator/**} hasRole("CREATOR") matcher like every route here.
     */
    @PutMapping("/phone")
    public ResponseEntity<ApiResponse<PreferencesResponse>> updatePhoneModel(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdatePhoneModelRequest req) {
        requireFeatureEnabled();
        return ResponseEntity.ok(
                ApiResponse.ok(preferencesService.updatePhoneModel(principal.getUserId(), req)));
    }

    /**
     * Goal memory (Meera intelligence v1) — saves the "My goals" chips: content goal, time per
     * week, kit and rather-not, as fixed codes only (an unknown code is 400
     * INVALID_CONTENT_GOAL_CODE). Its own route, not fields on the full-replace PUT above, so
     * saving the rest of the settings page never wipes it — and a creator tap is the ONLY writer:
     * Meera has no tool that reaches this.
     */
    @PutMapping("/content-goal")
    public ResponseEntity<ApiResponse<PreferencesResponse>> updateContentGoal(
            @AuthenticationPrincipal AuthPrincipal principal, @Valid @RequestBody UpdateContentGoalRequest req) {
        requireFeatureEnabled();
        return ResponseEntity.ok(
                ApiResponse.ok(preferencesService.updateContentGoal(principal.getUserId(), req)));
    }

    @PostMapping("/consent")
    public ResponseEntity<ApiResponse<ConsentResponse>> recordConsent(
            @AuthenticationPrincipal AuthPrincipal principal) {
        requireFeatureEnabled();
        return ResponseEntity.ok(ApiResponse.ok(preferencesService.recordConsent(principal.getUserId())));
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
