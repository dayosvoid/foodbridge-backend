-- MANUAL ONLY. Removing this index permits multiple approvals and corrupts the business invariant.
-- Stop all writers first. Never run this on a shared/demo/production DB for the race experiment.
DROP INDEX one_approved_request_per_listing;
