package com.influora.domain.entity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.influora.domain.enums.WorkspaceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * F-0892 (wiki/decisions/2026-09-18-agency-chooser-removed.md): "Onboarding offers BRAND only.
 * The 4 existing AGENCY workspaces convert to BRAND." {@link Workspace#applyCompanyDetails} is the
 * chokepoint every {@code type} write passes through (reached from {@code
 * OnboardingService#createWorkspace} with the client-supplied {@code workspaceType}), so this
 * covers the guard added there: AGENCY is coerced to BRAND rather than stored, null still defaults
 * to BRAND, and an explicit BRAND passes through unchanged.
 */
class WorkspaceTest {

    private static Workspace newWorkspace() {
        return Workspace.newBrand(
                "01HWORKSPACE12345678AB", "Acme", "acme", "Marketing", "1-10");
    }

    @Test
    @DisplayName("F-0892: applyCompanyDetails(AGENCY) stores BRAND, never AGENCY")
    void testApplyCompanyDetailsCoercesAgencyToBrand() {
        Workspace workspace = newWorkspace();

        workspace.applyCompanyDetails(
                "Acme Agency",
                "acme-agency",
                WorkspaceType.AGENCY,
                "Marketing",
                "1-10",
                null,
                null,
                null);

        assertEquals(WorkspaceType.BRAND, workspace.getType());
    }

    @Test
    @DisplayName("applyCompanyDetails(BRAND) stores BRAND unchanged")
    void testApplyCompanyDetailsKeepsBrand() {
        Workspace workspace = newWorkspace();

        workspace.applyCompanyDetails(
                "Acme", "acme", WorkspaceType.BRAND, "Marketing", "1-10", null, null, null);

        assertEquals(WorkspaceType.BRAND, workspace.getType());
    }

    @Test
    @DisplayName("applyCompanyDetails(null) defaults to BRAND")
    void testApplyCompanyDetailsNullDefaultsToBrand() {
        Workspace workspace = newWorkspace();

        workspace.applyCompanyDetails(
                "Acme", "acme", null, "Marketing", "1-10", null, null, null);

        assertEquals(WorkspaceType.BRAND, workspace.getType());
    }
}
