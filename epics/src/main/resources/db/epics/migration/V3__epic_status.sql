-- The epic lifecycle: REFINING (draft, scope mutable) → IMPLEMENTATION (scope frozen, only the
-- implemented markers move) → SUPERSEDED (back to the drawing board, a successor draft carries the
-- copied scope) or ABANDONED (terminal). "Done" is NOT a stored status: it is derived from the
-- features' implemented markers, so nothing here spells it.
--
-- Existing rows predate the lifecycle and were all created under the implementation-centric UI, so
-- they backfill to IMPLEMENTATION. New rows start REFINING (set by EpicService.create, not by a DB
-- default — the service owns the value, the same as every other column here).
alter table Epic add column status varchar(32);

update Epic set status = 'IMPLEMENTATION';

alter table Epic alter column status set not null;
alter table Epic add constraint ck_epic_status
    check (status in ('REFINING','IMPLEMENTATION','SUPERSEDED','ABANDONED'));

-- The successor draft a superseded epic spawned. Null on every other row. The FK is the same kind
-- of safety net as V1's self-references: deleting the successor clears the pointer rather than
-- leaving it dangling.
alter table Epic add column superseded_by_epic_id varchar(255);
alter table if exists Epic
    add constraint fk_epic_superseded_by foreign key (superseded_by_epic_id) references Epic (id) on delete set null;

-- The project epics list filters by status (the overview groups by phase), so the index carries
-- both columns in that order.
create index idx_epic_project_status on Epic (project_id, status);
