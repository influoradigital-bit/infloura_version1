package com.influora.service.payout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.influora.common.ApiException;
import com.influora.config.PayoutProperties;
import com.influora.service.PayoutReconciliationService;
import com.influora.service.PayoutService;
import com.influora.service.WalletService;
import com.influora.service.admin.AdminFinanceService;
import java.lang.reflect.Constructor;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * [EV-014 / payoutswitch] Pins the SHAPE of the payout kill switch: its fail-closed default, and
 * exactly which services are gated by it versus deliberately left open.
 *
 * <p>The per-method behaviour is proved in {@code WalletServiceTest}, {@code PayoutServiceTest} and
 * {@code PayoutReconciliationServiceTest}. What those cannot catch is a NEW payout rail being added
 * without the switch, or the manual bank-transfer rail being accidentally swept into it — the
 * launch model depends on that one staying reachable, because it is how creators actually get paid
 * while self-serve payouts are off.
 */
class PayoutKillSwitchWiringTest {

    @Test
    @DisplayName("PayoutProperties: a freshly bound instance is DISABLED — the switch fails closed")
    void testDefaultIsDisabled() {
        // This is the whole safety argument: an unset PAYOUTS_ENABLED, an absent yaml key, or a
        // property source that never loads must all land on "payouts off", never "payouts on".
        assertFalse(new PayoutProperties().isEnabled());
    }

    @Test
    @DisplayName("PayoutKillSwitch: requireEnabled refuses with PAYOUTS_DISABLED (403) when off")
    void testRequireEnabledRefusesWhenOff() {
        PayoutKillSwitch killSwitch = new PayoutKillSwitch(new PayoutProperties());

        ApiException ex = assertThrows(ApiException.class, () -> killSwitch.requireEnabled("test.rail"));

        assertEquals(PayoutKillSwitch.CODE, ex.getCode());
        assertEquals("PAYOUTS_DISABLED", ex.getCode());
        assertEquals(403, ex.getStatus().value());
        // The creator is told what actually happens next, not just that something failed.
        assertTrue(
                ex.getMessage().toLowerCase().contains("team"),
                "refusal must point the creator at the team-managed payout process, was: " + ex.getMessage());
    }

    @Test
    @DisplayName("PayoutKillSwitch: requireEnabled is a pass-through once explicitly enabled")
    void testRequireEnabledPassesWhenOn() {
        PayoutProperties properties = new PayoutProperties();
        properties.setEnabled(true);
        PayoutKillSwitch killSwitch = new PayoutKillSwitch(properties);

        killSwitch.requireEnabled("test.rail"); // must not throw
        assertTrue(killSwitch.isEnabled());
    }

    @Test
    @DisplayName("Every service that can START an outbound payout depends on the kill switch")
    void testGatedServicesDependOnTheSwitch() {
        assertTrue(dependsOnKillSwitch(WalletService.class), "WalletService (POST /wallet/withdraw)");
        assertTrue(dependsOnKillSwitch(PayoutService.class), "PayoutService (POST /escrow/payout)");
        assertTrue(
                dependsOnKillSwitch(PayoutReconciliationService.class),
                "PayoutReconciliationService (admin retry + both sweep resumption paths)");
    }

    @Test
    @DisplayName(
            "AdminFinanceService is DELIBERATELY not gated — recording a manual bank transfer is the"
                    + " beta payout rail and must keep working while payouts are off")
    void testManualPayoutRailIsDeliberatelyUngated() {
        // POST /admin/finance/payouts/manual records a NEFT/IMPS transfer a human already sent; it
        // calls nothing outbound. If someone wires the kill switch into this service, creators stop
        // being payable at all — that is the regression this assertion exists to catch.
        assertFalse(dependsOnKillSwitch(AdminFinanceService.class));
    }

    private static boolean dependsOnKillSwitch(Class<?> type) {
        return Arrays.stream(type.getDeclaredConstructors())
                .map(Constructor::getParameterTypes)
                .flatMap(Arrays::stream)
                .anyMatch(PayoutKillSwitch.class::equals);
    }
}
