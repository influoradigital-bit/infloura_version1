package com.influora.service.meera.tool.creator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.influora.common.ApiException;
import com.influora.domain.entity.CreatorProfile;
import com.influora.service.CreatorAgentPreferencesService;
import com.influora.service.rates.RateAddOns;
import com.influora.service.rates.RateQuoteService;
import com.influora.web.dto.creator.CreatorAgentDtos.PreferencesResponse;
import com.influora.web.dto.deal.DealDtos.DeliverableSlot;
import com.influora.web.dto.meera.CreatorToolDtos.EstimateMyRateResult;
import com.influora.web.dto.meera.CreatorToolDtos.PackageQuote;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;

/**
 * T-MEERA-CREATOR-PHASE-B (SPEC.md &sect;3.1/&sect;3.6) — {@code estimate_my_rate}.
 *
 * <p>The pricing itself is {@code RateQuoteServiceTest}'s subject. What is asserted here is the
 * only thing this class actually does: turning raw, model-proposed JSON into the typed arguments
 * that service takes, without ever letting a malformed argument cost the creator her turn.
 */
@ExtendWith(MockitoExtension.class)
class EstimateMyRateExecutorTest {

    private static final String USER_ID = "01HCREATORUSER1234567A";
    private static final String PROFILE_ID = "01HCREATORPROFILE12345";

    @Mock private CreatorAgentPreferencesService preferencesService;
    @Mock private RateQuoteService rateQuoteService;

    private EstimateMyRateExecutor executor;
    private CreatorProfile profile;

    @BeforeEach
    void setUp() {
        executor = new EstimateMyRateExecutor(preferencesService, rateQuoteService);
        profile = CreatorProfile.newForUser(PROFILE_ID, USER_ID, "Priya Shah");
        lenient().when(preferencesService.requireCreatorProfile(USER_ID)).thenReturn(profile);
        lenient().when(preferencesService.getOrCreatePreferences(USER_ID)).thenReturn(prefs());
        lenient()
                .when(rateQuoteService.quote(any(), any(), anyList(), anyList(), any(), any(), any()))
                .thenReturn(quote());
    }

    @Test
    @DisplayName("deliverables, add-ons and the brand budget reach RateQuoteService as typed arguments")
    void passesTypedArgumentsThrough() {
        EstimateMyRateResult result =
                executor.execute(
                        USER_ID,
                        Map.of(
                                "deliverables",
                                List.of(
                                        Map.of("type", "REEL", "qty", 3),
                                        Map.of("type", "STORY_SET", "qty", 2)),
                                "add_ons",
                                List.of("PERPETUITY"),
                                "brand_budget_inr",
                                20000));

        assertThat(result.quote()).isNotNull();
        assertThat(capturedSlots())
                .containsExactly(new DeliverableSlot("REEL", 3), new DeliverableSlot("STORY_SET", 2));
        assertThat(capturedAddOns()).containsExactly(RateAddOns.PERPETUITY);
        verify(rateQuoteService)
                .quote(
                        eq(profile),
                        any(),
                        anyList(),
                        anyList(),
                        eq(new BigDecimal("20000.0")),
                        eq(Locale.forLanguageTag("en-IN")),
                        eq(RateQuoteService.CONTEXT_CHAT));
    }

    @Test
    @DisplayName(
            "a model that emits qty as a string, an unknown add-on code and a lower-case one still"
                    + " gets a quote -- these are shape mistakes, not refusals")
    void tolerantOfTheShapesAModelActuallyEmits() {
        executor.execute(
                USER_ID,
                Map.of(
                        "deliverables",
                        List.of(Map.of("type", "instagram_reel", "qty", "2")),
                        "add_ons",
                        List.of("perpetuity", "FREE_PIZZA", "PERPETUITY")));

        assertThat(capturedSlots()).containsExactly(new DeliverableSlot("instagram_reel", 2));
        assertThat(capturedAddOns())
                .as("unknown codes are dropped and a duplicate must not charge the rider twice")
                .containsExactly(RateAddOns.PERPETUITY);
    }

    @Test
    @DisplayName("no deliverables at all is one reel, not a 400 -- 'what should I charge?' is a real question")
    void noDeliverablesIsOneReel() {
        executor.execute(USER_ID, Map.of());

        assertThat(capturedSlots()).containsExactly(new DeliverableSlot("REEL", 1));
    }

    @Test
    @DisplayName("a zero or unreadable budget is dropped to null, never passed on as an offer of nothing")
    void zeroBudgetIsNotABudget() {
        executor.execute(USER_ID, Map.of("brand_budget_inr", 0));
        executor.execute(USER_ID, Map.of("brand_budget_inr", "about eight thousand"));

        verify(rateQuoteService, org.mockito.Mockito.times(2))
                .quote(any(), any(), anyList(), anyList(), eq(null), any(), any());
    }

    @Test
    @DisplayName("deal_id and brief_id only pick the audit context label, and never reach a lookup")
    void contextLabelComesFromTheIdWithoutResolvingIt() {
        executor.execute(USER_ID, Map.of("deal_id", "01HDEALNOTEVENREAL"));
        verify(rateQuoteService)
                .quote(any(), any(), anyList(), anyList(), any(), any(), eq(RateQuoteService.CONTEXT_DEAL));

        executor.execute(USER_ID, Map.of("brief_id", "01HBRIEFNOTEVENREAL"));
        verify(rateQuoteService)
                .quote(any(), any(), anyList(), anyList(), any(), any(), eq(RateQuoteService.CONTEXT_BRIEF));
    }

    @Test
    @DisplayName("a runaway deliverables list is capped rather than priced line by line")
    void deliverableLinesAreCapped() {
        List<Map<String, Object>> many = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            many.add(Map.of("type", "REEL", "qty", 1));
        }

        executor.execute(USER_ID, Map.of("deliverables", many));

        assertThat(capturedSlots()).hasSize(10);
    }

    @Test
    @DisplayName("a creator with no profile row is CREATOR_PROFILE_NOT_FOUND, and nothing is priced")
    void missingProfileIs404() {
        when(preferencesService.requireCreatorProfile(USER_ID))
                .thenThrow(
                        new ApiException(
                                "CREATOR_PROFILE_NOT_FOUND", "Creator profile not found", HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> executor.execute(USER_ID, Map.of()))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "CREATOR_PROFILE_NOT_FOUND");
        verify(rateQuoteService, org.mockito.Mockito.never())
                .quote(any(), any(), anyList(), anyList(), any(), any(), any());
    }

    @SuppressWarnings("unchecked")
    private List<DeliverableSlot> capturedSlots() {
        ArgumentCaptor<List<DeliverableSlot>> captor = ArgumentCaptor.forClass(List.class);
        verify(rateQuoteService, org.mockito.Mockito.atLeastOnce())
                .quote(any(), any(), captor.capture(), anyList(), any(), any(), any());
        return captor.getValue();
    }

    @SuppressWarnings("unchecked")
    private List<String> capturedAddOns() {
        ArgumentCaptor<List<String>> captor = ArgumentCaptor.forClass(List.class);
        verify(rateQuoteService, org.mockito.Mockito.atLeastOnce())
                .quote(any(), any(), anyList(), captor.capture(), any(), any(), any());
        return captor.getValue();
    }

    private static PackageQuote quote() {
        return new PackageQuote(
                List.of(), List.of(), null, null, "12,000", new BigDecimal("12000"), null, null,
                null, null, null, null, "INR", null, 2, "your floor", 0, null, null, false, null);
    }

    private static PreferencesResponse prefs() {
        return new PreferencesResponse(
                new BigDecimal("12000"),
                new BigDecimal("5000"),
                new BigDecimal("4000"),
                "INR",
                List.of(),
                List.of(),
                0,
                "en-IN",
                null,
                null,
                null,
                null,
                List.of(),
                null,
                false,
                null,
                true,
                null,
                false,
                null,
                false,
                0,
                false);
    }
}
