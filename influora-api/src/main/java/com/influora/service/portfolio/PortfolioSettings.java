package com.influora.service.portfolio;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCustomLink;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPinnedPost;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioVisibility;
import java.util.ArrayList;
import java.util.List;

/**
 * Editable portfolio overlay stored as JSON on {@code creator_profiles.portfolio_settings_json}.
 *
 * <p>[F-0673, silent-data-loss] {@code ignoreUnknown} is LOAD-BEARING, not defensive tidiness.
 * {@code PortfolioService#writeSettings} deliberately merges extra top-level keys into this same
 * blob that are NOT fields on this class — {@code "rateCard"} (added by F-0498) and
 * {@code "collabDisplayModes"} — because T-RATECARD-0903 forbids new {@code @Column}s on
 * {@code CreatorProfile}. Without this annotation, {@code PortfolioService}'s plain
 * {@code ObjectMapper} (whose {@code FAIL_ON_UNKNOWN_PROPERTIES} defaults to TRUE) throws
 * {@code UnrecognizedPropertyException} on every read of a blob containing either key;
 * {@code loadSettings}'s catch then swallows it and returns a DEFAULT instance — silently
 * reverting the creator's visibility settings, custom links and pinned posts.
 *
 * <p>This shipped live in F-0498's promoted fix and went unnoticed because all 29 portfolio tests
 * only ever re-read the field they had just written; none read a DIFFERENT field back after a
 * write, which is the only shape that exposes it.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PortfolioSettings {

    private PortfolioVisibility visibility = PortfolioVisibility.defaults();
    private List<PortfolioCustomLink> customLinks = new ArrayList<>();
    private List<PortfolioPinnedPost> pinnedPosts = new ArrayList<>();

    public PortfolioVisibility getVisibility() {
        return visibility != null ? visibility : PortfolioVisibility.defaults();
    }

    public void setVisibility(PortfolioVisibility visibility) {
        this.visibility = visibility;
    }

    public List<PortfolioCustomLink> getCustomLinks() {
        return customLinks != null ? customLinks : List.of();
    }

    public void setCustomLinks(List<PortfolioCustomLink> customLinks) {
        this.customLinks = customLinks;
    }

    public List<PortfolioPinnedPost> getPinnedPosts() {
        return pinnedPosts != null ? pinnedPosts : List.of();
    }

    public void setPinnedPosts(List<PortfolioPinnedPost> pinnedPosts) {
        this.pinnedPosts = pinnedPosts;
    }
}
