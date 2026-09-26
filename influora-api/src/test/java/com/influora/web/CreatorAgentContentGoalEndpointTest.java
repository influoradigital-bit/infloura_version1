package com.influora.web;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.influora.common.GlobalExceptionHandler;
import com.influora.config.MeeraCreatorFeatureProperties;
import com.influora.domain.entity.CreatorAgentPreferences;
import com.influora.domain.entity.CreatorProfile;
import com.influora.domain.enums.UserType;
import com.influora.repository.CollaborationRepository;
import com.influora.repository.CreatorAgentPreferencesRepository;
import com.influora.repository.CreatorMetricsRepository;
import com.influora.repository.CreatorProfileRepository;
import com.influora.security.AuthPrincipal;
import com.influora.service.CreatorAgentConversationService;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.ErrorLogService;
import com.influora.service.scoring.RateEstimationService;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Goal memory (Meera intelligence v1, T27) -- {@code PUT /creator/agent-preferences/content-goal}
 * over real HTTP binding, the REAL {@link CreatorAgentPreferencesService} and one real entity row
 * (repositories mocked around it), the same harness as {@code CreatorAgentPhoneModelEndpointTest}.
 * Standalone MockMvc rather than {@code CreatorAgentControllerTest}'s plain-method convention,
 * because the wire keys, the 400 path and "the full PUT leaves it alone" only exist end to end.
 *
 * <p>The goal is saved ONLY by the creator's own tap on this route: the full-replace PUT cannot set
 * or wipe it, and every value must be one of the fixed codes.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAgentContentGoalEndpointTest {

    private static final String USER_ID = "01HWXYZCREATOR000000001";
    private static final String PROFILE_ID = "profile-1";
    private static final String GOAL_URL = "/creator/agent-preferences/content-goal";

    @Mock private CreatorAgentPreferencesRepository preferencesRepository;
    @Mock private CreatorProfileRepository creatorProfileRepository;
    @Mock private CollaborationRepository collaborationRepository;
    @Mock private CreatorMetricsRepository creatorMetricsRepository;
    @Mock private RateEstimationService rateEstimationService;
    @Mock private CreatorAgentConversationService conversationService;
    @Mock private MeeraCreatorFeatureProperties featureProperties;
    @Mock private ErrorLogService errorLogService;
    @Mock private CreatorProfile profile;

    private CreatorAgentPreferences row;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        CreatorAgentPreferencesService service =
                new CreatorAgentPreferencesService(
                        preferencesRepository,
                        creatorProfileRepository,
                        collaborationRepository,
                        creatorMetricsRepository,
                        rateEstimationService);
        CreatorAgentController controller = new CreatorAgentController(service, conversationService, featureProperties);
        mvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .setControllerAdvice(new GlobalExceptionHandler(errorLogService))
                        .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                        .build();

        AuthPrincipal principal = new AuthPrincipal(USER_ID, "creator@example.com", UserType.CREATOR, null);
        SecurityContextHolder.getContext()
                .setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        row = CreatorAgentPreferences.newWithDefaults("prefs-1", PROFILE_ID, null, null, null, "en-IN");
        lenient().when(featureProperties.isCreatorEnabled()).thenReturn(true);
        lenient().when(creatorProfileRepository.findByUserId(USER_ID)).thenReturn(Optional.of(profile));
        lenient().when(profile.getId()).thenReturn(PROFILE_ID);
        lenient().when(preferencesRepository.findByCreatorId(PROFILE_ID)).thenReturn(Optional.of(row));
        lenient().when(preferencesRepository.save(any(CreatorAgentPreferences.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private static final String FULL_PUT_BODY_WITH_GOAL_KEYS =
            "{\"reel_floor\":1500,\"approval_level\":0,\"represented\":false,"
                    + "\"excluded_categories\":[\"Alcohol\"],\"blocked_brands\":[],\"working_days\":[1,2],"
                    // Keys the full-replace request type does not have: ignored, never bound.
                    + "\"content_goal\":\"SELL_PRODUCT\",\"weekly_time_band\":\"OVER_5H\","
                    + "\"equipment\":[\"GIMBAL\"],\"content_dislikes\":[\"NO_VOICE\"]}";

    @Test
    @DisplayName(
            "T27: the goal is saved ONLY through its own route -- the full PUT cannot set it; PUT"
                    + " /content-goal saves it (codes deduped, canonical order) and GET returns it")
    void contentGoalSavesOnlyThroughItsRoute() throws Exception {
        mvc.perform(put("/creator/agent-preferences").contentType(MediaType.APPLICATION_JSON).content(FULL_PUT_BODY_WITH_GOAL_KEYS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excluded_categories", hasItem("Alcohol")))
                .andExpect(jsonPath("$.data.content_goal").doesNotExist())
                .andExpect(jsonPath("$.data.weekly_time_band").doesNotExist())
                .andExpect(jsonPath("$.data.equipment", empty()))
                .andExpect(jsonPath("$.data.content_dislikes", empty()));

        mvc.perform(
                        put(GOAL_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"content_goal\":\"BRAND_DEALS\",\"weekly_time_band\":\"H2_TO_5\","
                                                + "\"equipment\":[\"TRIPOD\",\"PHONE_ONLY\",\"TRIPOD\"],"
                                                + "\"content_dislikes\":[\"NO_FACE\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content_goal").value("BRAND_DEALS"))
                .andExpect(jsonPath("$.data.weekly_time_band").value("H2_TO_5"))
                .andExpect(jsonPath("$.data.equipment", contains("PHONE_ONLY", "TRIPOD")))
                .andExpect(jsonPath("$.data.content_dislikes", contains("NO_FACE")));

        mvc.perform(get("/creator/agent-preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content_goal").value("BRAND_DEALS"))
                .andExpect(jsonPath("$.data.equipment", contains("PHONE_ONLY", "TRIPOD")));
    }

    @Test
    @DisplayName("T27: the full-replace PUT /creator/agent-preferences does NOT wipe a saved goal")
    void fullReplacePutDoesNotWipeGoal() throws Exception {
        row.updateContentGoal("GROW_FOLLOWERS", "UNDER_2H", "[\"PHONE_ONLY\"]", "[\"NO_DANCING\"]");

        mvc.perform(put("/creator/agent-preferences").contentType(MediaType.APPLICATION_JSON).content(FULL_PUT_BODY_WITH_GOAL_KEYS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content_goal").value("GROW_FOLLOWERS"))
                .andExpect(jsonPath("$.data.weekly_time_band").value("UNDER_2H"))
                .andExpect(jsonPath("$.data.equipment", contains("PHONE_ONLY")))
                .andExpect(jsonPath("$.data.content_dislikes", contains("NO_DANCING")));

        mvc.perform(get("/creator/agent-preferences"))
                .andExpect(jsonPath("$.data.content_goal").value("GROW_FOLLOWERS"));
    }

    @Test
    @DisplayName("T27: an unknown content_goal code is 400 INVALID_CONTENT_GOAL_CODE and nothing is saved")
    void unknownGoalCodeIs400() throws Exception {
        mvc.perform(put(GOAL_URL).contentType(MediaType.APPLICATION_JSON).content("{\"content_goal\":\"GO_VIRAL\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_CONTENT_GOAL_CODE"));
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName(
            "T27: codes are exact -- a lowercase code, an unknown time band, or ONE unknown code in a"
                    + " list is a 400, never silently dropped")
    void everyUnknownCodeIs400() throws Exception {
        for (String body :
                new String[] {
                    "{\"content_goal\":\"brand_deals\"}",
                    "{\"weekly_time_band\":\"ALL_WEEK\"}",
                    "{\"equipment\":[\"TRIPOD\",\"DRONE\"]}",
                    "{\"content_dislikes\":[\"NO_FACE\",\" NO_VOICE\"]}"
                }) {
            mvc.perform(put(GOAL_URL).contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("INVALID_CONTENT_GOAL_CODE"));
        }
        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT /content-goal with nulls clears the goal: keys omitted (NON_NULL), lists []")
    void nullsClear() throws Exception {
        row.updateContentGoal("SELL_PRODUCT", "OVER_5H", "[\"GIMBAL\"]", "[\"NO_OUTDOOR\"]");

        mvc.perform(
                        put(GOAL_URL)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"content_goal\":null,\"weekly_time_band\":null,\"equipment\":[],\"content_dislikes\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content_goal").doesNotExist())
                .andExpect(jsonPath("$.data.weekly_time_band").doesNotExist())
                .andExpect(jsonPath("$.data.equipment", empty()))
                .andExpect(jsonPath("$.data.content_dislikes", empty()));
    }

    @Test
    @DisplayName("PUT /content-goal with the feature flag off -> 404 FEATURE_DISABLED, nothing saved")
    void flagOffIs404() throws Exception {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        mvc.perform(put(GOAL_URL).contentType(MediaType.APPLICATION_JSON).content("{\"content_goal\":\"BRAND_DEALS\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FEATURE_DISABLED"));
        verify(preferencesRepository, never()).save(any());
    }
}
