package com.influora.service.portfolio;

import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioCustomLink;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioPinnedPost;
import com.influora.web.dto.portfolio.PortfolioDtos.PortfolioVisibility;
import java.util.ArrayList;
import java.util.List;

/** Editable portfolio overlay stored as JSON on {@code creator_profiles.portfolio_settings_json}. */
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
