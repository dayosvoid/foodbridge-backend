-- MANUAL ONLY. DESTRUCTIVE: permanently deletes ALL FoodBridge domain data.
-- Back up and stop writers first. Roll back V2 before V1. Flyway Community does not run this.
DROP TABLE activity;
DROP TABLE handovers;
DROP TABLE requests;
DROP TABLE listings;
DROP TABLE users;
-- These scripts do not repair Flyway history. Restore a backup or recreate a disposable DB;
-- coordinate any production history reconciliation with the deployment owner.
