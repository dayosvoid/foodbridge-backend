-- Allow reversed handovers to mark their associated request as REVERSED while reopening the listing to AVAILABLE.
ALTER TABLE requests DROP CONSTRAINT IF EXISTS requests_status_check;
ALTER TABLE requests ADD CONSTRAINT requests_status_check CHECK (
    status IN ('PENDING', 'APPROVED', 'DECLINED', 'CANCELLED', 'EXPIRED', 'REVERSED')
);
