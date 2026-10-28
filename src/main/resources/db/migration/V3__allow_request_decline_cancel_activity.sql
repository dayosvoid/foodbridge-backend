ALTER TABLE activity DROP CONSTRAINT IF EXISTS activity_type_check;

ALTER TABLE activity ADD CONSTRAINT activity_type_check
    CHECK (
        type IN (
                 'LISTING_CREATED',
                 'REQUEST_CREATED',
                 'REQUEST_APPROVED',
                 'REQUEST_DECLINED',
                 'REQUEST_CANCELLED',
                 'COLLECTED',
                 'REVERSED'
            )
        );