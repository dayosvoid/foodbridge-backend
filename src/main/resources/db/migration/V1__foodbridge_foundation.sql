CREATE TABLE users (
 id uuid PRIMARY KEY,
 email varchar(254) NOT NULL UNIQUE CHECK (email = lower(email) AND position('@' in email) > 1),
 password_hash text NOT NULL CHECK (length(password_hash) > 0),
 display_name varchar(200) NOT NULL CHECK (length(trim(display_name)) > 0),
 role varchar(20) NOT NULL CHECK (role IN ('DONOR','RECIPIENT','ADMIN')),
 created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE TABLE listings (
 id uuid PRIMARY KEY,
 donor_id uuid NOT NULL REFERENCES users(id),
 title varchar(300) NOT NULL CHECK (length(trim(title)) > 0),
 description text NOT NULL CHECK (length(trim(description)) > 0),
 category varchar(30) NOT NULL CHECK (category IN ('PREPARED_MEALS','PRODUCE','BAKERY','DAIRY','PANTRY','OTHER')),
 quantity numeric(12,2) NOT NULL CHECK (quantity > 0),
 unit varchar(30) NOT NULL CHECK (unit IN ('PORTIONS','KG','LITRES','ITEMS','BOXES')),
 image_url text,
 pickup_area varchar(200) NOT NULL CHECK (length(trim(pickup_area)) > 0),
 collection_deadline timestamptz NOT NULL,
 status varchar(30) NOT NULL DEFAULT 'AVAILABLE' CHECK (status IN ('AVAILABLE','RESERVED','COLLECTED','REVERSED','EXPIRED')),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 updated_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX listings_donor_idx ON listings(donor_id);
CREATE INDEX listings_browse_idx ON listings(status, collection_deadline);
CREATE TABLE requests (
 id uuid PRIMARY KEY,
 listing_id uuid NOT NULL REFERENCES listings(id),
 requester_id uuid NOT NULL REFERENCES users(id),
 status varchar(20) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','APPROVED','DECLINED','CANCELLED','EXPIRED')),
 idempotency_key varchar(200) UNIQUE CHECK (idempotency_key IS NULL OR length(trim(idempotency_key)) > 0),
 created_at timestamptz NOT NULL DEFAULT current_timestamp,
 decided_at timestamptz,
 CHECK ((status = 'PENDING' AND decided_at IS NULL) OR (status <> 'PENDING' AND decided_at IS NOT NULL))
);
CREATE INDEX requests_listing_idx ON requests(listing_id);
CREATE INDEX requests_requester_idx ON requests(requester_id);
CREATE TABLE handovers (
 id uuid PRIMARY KEY,
 request_id uuid NOT NULL UNIQUE REFERENCES requests(id),
 status varchar(30) NOT NULL DEFAULT 'AWAITING_COLLECTION' CHECK (status IN ('AWAITING_COLLECTION','COLLECTED','REVERSED','EXPIRED')),
 portions_estimate integer CHECK (portions_estimate > 0),
 completed_at timestamptz,
 reversed_at timestamptz,
 CHECK (status NOT IN ('COLLECTED','REVERSED') OR completed_at IS NOT NULL),
 CHECK ((status = 'REVERSED') = (reversed_at IS NOT NULL))
);
CREATE TABLE activity (
 id uuid PRIMARY KEY,
 actor_id uuid NOT NULL REFERENCES users(id),
 listing_id uuid NOT NULL REFERENCES listings(id),
 type varchar(40) NOT NULL CHECK (type IN ('LISTING_CREATED','REQUEST_CREATED','REQUEST_APPROVED','COLLECTED','REVERSED')),
 details text NOT NULL,
 created_at timestamptz NOT NULL DEFAULT current_timestamp
);
CREATE INDEX activity_actor_idx ON activity(actor_id);
CREATE INDEX activity_listing_created_idx ON activity(listing_id, created_at);
