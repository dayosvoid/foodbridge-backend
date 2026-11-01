-- Future soft-deletion auditing only. No data updates or historical backfill.
ALTER TABLE activity DROP CONSTRAINT activity_type_check;
ALTER TABLE activity ADD CONSTRAINT activity_type_check CHECK (type IN (
    'LISTING_CREATED', 'REQUEST_CREATED', 'REQUEST_APPROVED',
    'REQUEST_DECLINED', 'REQUEST_CANCELLED', 'COLLECTED', 'REVERSED',
    'LISTING_ARCHIVED'
));
