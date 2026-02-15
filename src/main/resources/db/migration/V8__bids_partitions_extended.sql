-- Extend monthly bid partitions well ahead of need. The proper fix - a scheduled job that
-- pre-creates next month's partition automatically - lands in Phase 5 (§8.3); until then we
-- widen coverage by hand so inserts never hit a missing partition.
CREATE TABLE bids_2026_04 PARTITION OF bids FOR VALUES FROM ('2026-04-01') TO ('2026-05-01');
CREATE TABLE bids_2026_05 PARTITION OF bids FOR VALUES FROM ('2026-05-01') TO ('2026-06-01');
CREATE TABLE bids_2026_06 PARTITION OF bids FOR VALUES FROM ('2026-06-01') TO ('2026-07-01');
CREATE TABLE bids_2026_07 PARTITION OF bids FOR VALUES FROM ('2026-07-01') TO ('2026-08-01');
CREATE TABLE bids_2026_08 PARTITION OF bids FOR VALUES FROM ('2026-08-01') TO ('2026-09-01');
CREATE TABLE bids_2026_09 PARTITION OF bids FOR VALUES FROM ('2026-09-01') TO ('2026-10-01');
CREATE TABLE bids_2026_10 PARTITION OF bids FOR VALUES FROM ('2026-10-01') TO ('2026-11-01');
CREATE TABLE bids_2026_11 PARTITION OF bids FOR VALUES FROM ('2026-11-01') TO ('2026-12-01');
CREATE TABLE bids_2026_12 PARTITION OF bids FOR VALUES FROM ('2026-12-01') TO ('2027-01-01');
