CREATE TABLE auctions (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    seller_id          UUID NOT NULL REFERENCES users(id),
    category_id        UUID REFERENCES categories(id),
    title              VARCHAR(200) NOT NULL,
    description        TEXT,
    starting_price     NUMERIC(19,4) NOT NULL CHECK (starting_price >= 0),
    reserve_price      NUMERIC(19,4) CHECK (reserve_price >= starting_price),
    min_increment      NUMERIC(19,4) NOT NULL DEFAULT 1.00 CHECK (min_increment > 0),
    current_price      NUMERIC(19,4) NOT NULL,
    current_winner_id  UUID REFERENCES users(id),
    currency           CHAR(3) NOT NULL DEFAULT 'USD',
    status             VARCHAR(20) NOT NULL DEFAULT 'SCHEDULED',
    start_time         TIMESTAMPTZ NOT NULL,
    end_time           TIMESTAMPTZ NOT NULL,
    anti_snipe_seconds INT NOT NULL DEFAULT 30,
    version            BIGINT NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (end_time > start_time)
);

CREATE INDEX idx_auctions_status_end ON auctions(status, end_time);
CREATE INDEX idx_auctions_category   ON auctions(category_id);
CREATE INDEX idx_auctions_seller     ON auctions(seller_id);
