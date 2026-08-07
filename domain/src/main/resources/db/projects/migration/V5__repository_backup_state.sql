-- What happened the last time this repository was backed up to its forge twin.
--
-- Additive and nullable throughout, so this is forward-only-safe in both directions: a release that
-- fails and rolls back leaves an image that simply never writes these columns, and every row starts
-- null — which is the honest reading of "never attempted" rather than a value that has to be
-- backfilled into meaning something.
--
-- The backup itself has been running since release C; it just had nowhere to say so, which meant a
-- repository whose twin had been failing for a week looked exactly like one that was fine. That is
-- the whole reason these three columns exist: the outcome is only useful if somebody can see it.
alter table Repository add column last_backup_at timestamp;

-- STRING-valued, matching @Enumerated(EnumType.STRING). No check constraint, for the same reason
-- dns_type carries none (V2): the value set belongs to the enum, and a second copy in DDL is the one
-- nobody remembers to widen. The archetype constraint is the exception and earns it — that column is
-- a taxonomy the whole model keys on, this one is a status line.
alter table Repository add column last_backup_outcome varchar(255);

-- The short human line behind a non-success outcome, truncated. NULL after a success, deliberately:
-- a stale reason left beside a green outcome is worse than no reason at all.
alter table Repository add column last_backup_detail varchar(1000);
