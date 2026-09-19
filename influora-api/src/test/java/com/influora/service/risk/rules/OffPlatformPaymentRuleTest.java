package com.influora.service.risk.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-1776 (kabir, {@code KABIR-K2C-CHECK-0918.md} clause 2's gap): six pieces of {@link
 * OffPlatformPaymentRule}'s sentence-boundary rule that {@code RiskFlagCorpusTest}'s existing
 * rows do not depend on, so each could be deleted from the rule with the whole suite -- corpus
 * included -- staying green. Round 6's own rows only exercise {@code .} and {@code ।} (danda) as
 * terminators and the digit-after-a-dot half of the currency protection; nothing exercised
 * {@code ?}, {@code !}, {@code …}, {@code ॥} (double danda), the U+2029 paragraph separator, or a
 * dot immediately followed by a currency symbol rather than a digit.
 *
 * <p>Each NO_FLAG case below is built the same way as {@code RiskFlagCorpusTest}'s {@code XS-*}
 * rows: a request word ({@code send}) alone on one side of the boundary, a wallet name
 * ({@code UPI}) alone on the other, close enough in token distance that they would pair up --
 * and wrongly flag -- the moment the boundary piece under test stops being recognised. The one
 * SHOULD_FLAG case is the mirror image: a text that must only flag while the boundary piece
 * stays inert (the protected dot does not cut), so deleting the protection turns it into a
 * silent miss.
 *
 * <p>U+2029 is written as a Java escape, not pasted as a raw character, so the source stays
 * identical however the file is viewed or re-encoded (see {@code RiskText.APPROX}'s javadoc for
 * why this codebase does that for non-printing code points).
 */
class OffPlatformPaymentRuleTest {

    private static boolean matches(String text) {
        return OffPlatformPaymentRule.matches(text);
    }

    @Test
    @DisplayName("F-1776: '?', '!', '…' and '॥' (double danda) each alone still stop pairing across a sentence end")
    void eachExtraTerminatorCharacterStopsPairing() {
        List<String> failures = new ArrayList<>();

        String question = "Should we send the invoice today? Your UPI ID is already saved in Influora.";
        if (matches(question)) {
            failures.add("'?' terminator: must not pair the 'send' before it with the UPI after it");
        }

        String exclamation = "Please send the script today! UPI payments run through Influora as always.";
        if (matches(exclamation)) {
            failures.add("'!' terminator: must not pair the 'send' before it with the UPI after it");
        }

        String ellipsis = "We'll send the payout tomorrow… UPI payouts always go through Influora.";
        if (matches(ellipsis)) {
            failures.add("'…' (U+2026, ellipsis) terminator: must not pair across it");
        }

        String doubleDanda = "ड्राफ्ट भेज दीजिए॥ भुगतान UPI से Influora पर होगा।";
        if (matches(doubleDanda)) {
            failures.add("'॥' (U+0965, double danda) terminator: must not pair across it");
        }

        assertThat(failures).as("extra terminator characters that no longer stop cross-sentence pairing").isEmpty();
    }

    @Test
    @DisplayName("F-1776: U+2029 (paragraph separator) alone still stops pairing, the same as a blank line")
    void paragraphSeparatorAloneStopsPairing() {
        String text = "Please send the draft by Friday" + "\u2029" + "UPI payout goes through Influora";
        assertThat(matches(text))
                .as("U+2029 must be treated as a sentence break on its own, with no blank line around it")
                .isFalse();
    }

    @Test
    @DisplayName("F-1776: a single dot immediately before a currency symbol (not a digit) is still a protected, non-cutting dot")
    void dotBeforeCurrencySymbolAloneIsProtected() {
        // Deliberately different from the corpus's AB-rupee-sym ("We'll send ₹. 5000 to your
        // GPay."), where the character right after the dot is the digit "5" -- that exercises
        // isProtectedDot's DECIMAL_DIGIT_NUMBER branch. Here the character right after the dot is
        // the currency symbol itself, exercising the CURRENCY_SYMBOL branch on its own.
        String text = "We'll send it tonight. ₹5000 to your UPI";
        assertThat(matches(text))
                .as("the dot before ₹ must not cut the sentence, or 'send' and 'UPI' stop pairing")
                .isTrue();
    }
}
