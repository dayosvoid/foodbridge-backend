-- Legacy EXPIRED rows may have no metadata; new writes populate both fields.
ALTER TABLE listings ADD COLUMN expired_at timestamptz, ADD COLUMN expiration_reason varchar(50);
ALTER TABLE requests ADD COLUMN expired_at timestamptz, ADD COLUMN expiration_reason varchar(50);
ALTER TABLE handovers ADD COLUMN expired_at timestamptz, ADD COLUMN expiration_reason varchar(50);
ALTER TABLE listings ADD CONSTRAINT listings_expiration_metadata CHECK (
 (expired_at IS NULL AND expiration_reason IS NULL) OR
 (status='EXPIRED' AND expired_at IS NOT NULL AND expiration_reason IS NOT NULL AND expiration_reason IN ('COLLECTION_DEADLINE_PASSED','LEGACY_EXPIRATION_RECONCILIATION')));
ALTER TABLE requests ADD CONSTRAINT requests_expiration_metadata CHECK (
 (expired_at IS NULL AND expiration_reason IS NULL) OR
 (status='EXPIRED' AND expired_at IS NOT NULL AND expiration_reason IS NOT NULL AND expiration_reason IN ('COLLECTION_DEADLINE_PASSED','LEGACY_EXPIRATION_RECONCILIATION')));
ALTER TABLE handovers ADD CONSTRAINT handovers_expiration_metadata CHECK (
 (expired_at IS NULL AND expiration_reason IS NULL) OR
 (status='EXPIRED' AND expired_at IS NOT NULL AND expiration_reason IS NOT NULL AND expiration_reason IN ('COLLECTION_DEADLINE_PASSED','LEGACY_EXPIRATION_RECONCILIATION')));
-- Eligibility ends at the deadline; expiration is recorded when processed, possibly later.
ALTER TABLE listings ADD CONSTRAINT listings_expiration_after_deadline CHECK (expired_at IS NULL OR expired_at>=collection_deadline);
ALTER TABLE handovers ADD CONSTRAINT handovers_expiration_unfulfilled CHECK (expired_at IS NULL OR (completed_at IS NULL AND reversed_at IS NULL));
ALTER TABLE activity ALTER COLUMN actor_id DROP NOT NULL;
ALTER TABLE activity DROP CONSTRAINT activity_type_check;
ALTER TABLE activity ADD CONSTRAINT activity_type_check CHECK (type IN (
 'LISTING_CREATED','REQUEST_CREATED','REQUEST_APPROVED','REQUEST_DECLINED','REQUEST_CANCELLED','COLLECTED','REVERSED','LISTING_ARCHIVED','LISTING_EXPIRED'));
ALTER TABLE activity ADD CONSTRAINT activity_system_actor CHECK ((actor_id IS NULL) = (type='LISTING_EXPIRED'));
