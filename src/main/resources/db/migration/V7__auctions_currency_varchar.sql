-- CHAR(3) space-pads and doesn't round-trip cleanly through Hibernate's default VARCHAR
-- mapping for String columns; VARCHAR(3) is equivalent for a 3-letter currency code without
-- the padding surprises.
ALTER TABLE auctions ALTER COLUMN currency TYPE VARCHAR(3);
