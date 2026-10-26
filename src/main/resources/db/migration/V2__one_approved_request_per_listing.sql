CREATE UNIQUE INDEX one_approved_request_per_listing
ON requests (listing_id)
WHERE status = 'APPROVED';
