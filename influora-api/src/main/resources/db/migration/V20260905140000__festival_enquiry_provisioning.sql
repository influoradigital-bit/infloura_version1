-- T-FESTIVALBOX-0905 phase 2 — link-back columns for "provision as sponsor"
-- (FestivalSponsorProvisioningService, POST /admin/festival-enquiries/{id}/provision).
--
-- A WON/BRAND festival_enquiries row is a lead; provisioning turns it into a real User +
-- Workspace + Campaign. These four columns record that outcome on the enquiry row itself so the
-- admin inbox can show "already provisioned -> workspace X" instead of allowing a second click to
-- create a second workspace.
--
-- Deliberately NOT foreign keys, same reasoning as handled_by on the base
-- V20260905130000__festival_enquiries.sql table: a workspace/user/campaign being deleted later
-- must never cascade away this enquiry's history, and an FK would force exactly that (or force
-- ON DELETE SET NULL, which loses the same history less honestly). provisioned_workspace_id is
-- indexed anyway because "which enquiry produced this workspace" is a real admin lookup direction.
--
-- provisioned_workspace_id (not provisioned_user_id) is the idempotency marker
-- FestivalSponsorProvisioningService checks under its PESSIMISTIC_WRITE row lock — see that
-- class's javadoc.
ALTER TABLE festival_enquiries
    ADD COLUMN provisioned_user_id      VARCHAR(26) NULL AFTER updated_at,
    ADD COLUMN provisioned_workspace_id VARCHAR(26) NULL AFTER provisioned_user_id,
    ADD COLUMN provisioned_campaign_id  VARCHAR(26) NULL AFTER provisioned_workspace_id,
    ADD COLUMN provisioned_at           TIMESTAMP   NULL AFTER provisioned_campaign_id;

CREATE INDEX idx_festival_enquiries_provisioned_workspace
    ON festival_enquiries (provisioned_workspace_id);
