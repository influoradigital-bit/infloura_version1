-- Optimistic-locking column for platform_fee_config (Kabir security review: the singleton
-- fee-config row had no @Version and no repository-level locking, so two concurrent SUPER_ADMIN
-- PUT requests -- or a client-side retry racing the original request -- could silently
-- lost-update each other's change with no error and no audit trail of the collision, on the
-- platform's highest-stakes revenue config. Owner: Vikram (Backend).
--
-- Forward-only: V41__platform_fee_config.sql and V42__platform_fee_config_brand_fee_razorpay.sql
-- are already applied to the dev DB (Meera's V43 confirmation), so this extends the table rather
-- than editing either migration in place.
ALTER TABLE platform_fee_config
  ADD COLUMN version BIGINT NOT NULL DEFAULT 0 AFTER updated_by;
