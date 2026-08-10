-- The platform's generic causation column (qits-eventstream's CausedRow): the id of the event a row
-- was written because of, stamped from the ambient CausationScope at persist.
--
-- Two of this context's three tables take it. `project` and `repository` are rows a request asks
-- for, and every insert path runs on the request thread, where the X-Qits-Causation-Id filter has
-- restored the scope — so the stamp fills the column with no call site remembering to. The
-- machine-driven case is the wrapper reconcile, which mints a row per adopted or cloned member.
--
-- `repository_name` deliberately does NOT take it: an alias is derived, idempotent state whose row
-- the repository beside it already accounts for, and its lazy self-name registration runs off any
-- request context, where a stamp would write null forever. It carries @Uncaused instead, and the
-- arch rules make that a reviewed line rather than an omission.
--
-- Nullable, because a row nothing caused is an ordinary row: a person creating a project in the
-- browser sends no header, and the boot-time self-seed has no cause at all. Never a foreign key --
-- the event lives in qits-events' store, the same reason every cross-context reference here is a
-- bare string. No backfill: the column starts recording from here, and no existing row has an
-- answer to invent.
alter table Project add column causation_id uuid;
alter table Repository add column causation_id uuid;
