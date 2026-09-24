package com.influora.web;

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
 * V76 -- {@code PUT /creator/agent-preferences/phone} over real HTTP binding: Jackson reads the
 * snake_case {@code phone_model} key, {@code @Valid} enforces {@code @Size(max = 80)} as a 400
 * through {@link GlobalExceptionHandler}, and the REAL {@link CreatorAgentPreferencesService}
 * (repositories mocked around one real entity row) persists it so the GET echoes it back -- and the
 * full-replace {@code PUT /creator/agent-preferences} leaves it alone.
 *
 * <p>Standalone MockMvc rather than the plain-method convention in {@code CreatorAgentControllerTest}
 * because the two things under test here -- the {@code @Valid} on the parameter and the wire key --
 * are exactly what calling the controller method directly would skip.
 */
@ExtendWith(MockitoExtension.class)
class CreatorAgentPhoneModelEndpointTest {

    private static final String USER_ID = "01HWXYZCREATOR000000001";
    private static final String PROFILE_ID = "profile-1";

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
                .setAuthentication(
                        new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

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

    @Test
    @DisplayName("PUT /phone saves the phone and GET returns it as phone_model")
    void putPhoneThenGetReturnsIt() throws Exception {
        mvc.perform(
                        put("/creator/agent-preferences/phone")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone_model\":\"  OPPO Reno 14 Pro \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone_model").value("OPPO Reno 14 Pro"));

        mvc.perform(get("/creator/agent-preferences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone_model").value("OPPO Reno 14 Pro"));
    }

    @Test
    @DisplayName("PUT /phone with 81 characters -> 400 VALIDATION_ERROR, nothing saved")
    void phoneOver80CharsIs400() throws Exception {
        String tooLong = "x".repeat(81);

        mvc.perform(
                        put("/creator/agent-preferences/phone")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone_model\":\"" + tooLong + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        verify(preferencesRepository, never()).save(any());
    }

    @Test
    @DisplayName("PUT /phone with exactly 80 characters is accepted")
    void phoneOf80CharsIsAccepted() throws Exception {
        String atLimit = "y".repeat(80);

        mvc.perform(
                        put("/creator/agent-preferences/phone")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone_model\":\"" + atLimit + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone_model").value(atLimit));
    }

    @Test
    @DisplayName("the full-replace PUT /creator/agent-preferences does NOT clear a saved phone")
    void fullPutDoesNotClearPhone() throws Exception {
        row.updatePhoneModel("Redmi Note 13");

        // Even a client that sends phone_model:null on the full PUT cannot wipe it -- that request
        // type has no such field.
        mvc.perform(
                        put("/creator/agent-preferences")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reel_floor\":1500,\"approval_level\":0,\"represented\":false,"
                                                + "\"excluded_categories\":[\"Alcohol\"],\"blocked_brands\":[],"
                                                + "\"working_days\":[1,2],\"phone_model\":null}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.excluded_categories", hasItem("Alcohol")))
                .andExpect(jsonPath("$.data.phone_model").value("Redmi Note 13"));

        mvc.perform(get("/creator/agent-preferences"))
                .andExpect(jsonPath("$.data.phone_model").value("Redmi Note 13"));
    }

    @Test
    @DisplayName("PUT /phone with a blank value clears the phone; the key is then omitted (NON_NULL)")
    void blankPhoneClears() throws Exception {
        row.updatePhoneModel("Redmi Note 13");

        mvc.perform(
                        put("/creator/agent-preferences/phone")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone_model\":\"   \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.phone_model").doesNotExist());
    }

    @Test
    @DisplayName("PUT /phone with the feature flag off -> 404 FEATURE_DISABLED, nothing read or saved")
    void flagOffIs404() throws Exception {
        when(featureProperties.isCreatorEnabled()).thenReturn(false);

        mvc.perform(
                        put("/creator/agent-preferences/phone")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"phone_model\":\"Pixel 8\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("FEATURE_DISABLED"));

        verify(preferencesRepository, never()).save(any());
    }
}
