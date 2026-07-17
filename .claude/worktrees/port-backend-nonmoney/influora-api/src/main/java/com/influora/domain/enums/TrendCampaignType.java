package com.influora.domain.enums;

/** Controlled vocabulary for {@code trends.campaign_type} / {@code nudge_log.campaign_type} —
 * mirrors {@code trendspark/campaign-rulebook.json} (Nisha/Tejas, T5). Closed vocab; do not add
 * values without updating the rulebook JSON and {@code ThemeMatchService}. */
public enum TrendCampaignType {
    HYPE,
    SEASONAL,
    PRIDE,
    EDUCATIONAL
}
