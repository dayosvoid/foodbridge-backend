ALTER TABLE listings
DROP CONSTRAINT listings_status_check;

ALTER TABLE listings
    ADD CONSTRAINT listings_status_check
        CHECK (
            status IN (
                       'AVAILABLE',
                       'RESERVED',
                       'COLLECTED',
                       'REVERSED',
                       'EXPIRED',
                       'DELETED'
                )
            );