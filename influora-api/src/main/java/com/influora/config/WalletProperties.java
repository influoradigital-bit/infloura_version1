package com.influora.config;

import java.math.BigDecimal;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Brand wallet business-limit config — currently just the top-up ceiling.
 *
 * <p>[SEC: Kabir Option-1 audit P1] {@code /wallet/topup} lets the caller specify the amount
 * directly (unlike escrow-fund, which is always server-derived — see {@code
 * WalletTopUpService} class javadoc). Without a ceiling, a client-controlled number becomes a
 * real Razorpay card charge with no server backstop. The DTO-level {@code @DecimalMax} on {@code
 * WalletTopUpRequest#amount} (see {@code MoneyDtos}) mirrors this same value for fast 400
 * feedback, but THIS config-driven check in {@code WalletTopUpService#initiateTopUp} is the
 * authoritative guard — bean validation can't read Spring config, so the DTO annotation hardcodes
 * the value with a comment pointing back here.
 */
@ConfigurationProperties(prefix = "influora.wallet")
public class WalletProperties {

    /** Maximum amount (INR) a single {@code POST /wallet/topup} call may request. */
    private BigDecimal maxTopupAmount = new BigDecimal("1000000.00");

    public BigDecimal getMaxTopupAmount() {
        return maxTopupAmount;
    }

    public void setMaxTopupAmount(BigDecimal maxTopupAmount) {
        this.maxTopupAmount = maxTopupAmount;
    }
}
