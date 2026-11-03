-- Soft deletion retains any previously recorded expiration metadata.
ALTER TABLE listings DROP CONSTRAINT listings_expiration_metadata;
ALTER TABLE listings ADD CONSTRAINT listings_expiration_metadata CHECK (
 (expired_at IS NULL AND expiration_reason IS NULL) OR
 (status IN ('EXPIRED','DELETED') AND expired_at IS NOT NULL AND expiration_reason IS NOT NULL
  AND expiration_reason IN ('COLLECTION_DEADLINE_PASSED','LEGACY_EXPIRATION_RECONCILIATION')));
