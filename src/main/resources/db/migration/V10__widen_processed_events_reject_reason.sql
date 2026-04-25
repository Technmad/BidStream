-- DUPLICATE_IDEMPOTENCY_KEY (25 chars) exceeds the original VARCHAR(24) - discovered by
-- IdempotencyGuardIT when adding BidRejectReason.DUPLICATE_IDEMPOTENCY_KEY.
ALTER TABLE processed_events ALTER COLUMN reject_reason TYPE VARCHAR(32);
