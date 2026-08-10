-- The platform's generic causation column (qits-eventstream's CausedRow): the id of the event a row
-- was written because of, stamped from the ambient CausationScope at persist.
--
-- All four of this lineage's tables take it, because all four are written on a request thread --
-- EpicService/FeatureService/TaskService are reached from the SPA and from EpicMcpTools alike, and
-- AuditService.record joins the mutating method's transaction on that same thread -- so the scope
-- the X-Qits-Causation-Id filter restored is still standing when the stamp reads it. An agent
-- minting an epic, a feature or a task is exactly the machine-driven flow worth tracing.
--
-- The live rows and the audit log answer DIFFERENT questions and neither replaces the other. The
-- stamp is insert-only: epic.causation_id says what caused that epic to exist and never changes
-- again, while auditentry.causation_id says what caused each later update and the delete -- and it
-- is the only one still standing once the live row is gone, since audit rows are deliberately not
-- FK'd back.
--
-- Nullable, because a row nothing caused is an ordinary row: a person editing in the SPA sends no
-- header. Never a foreign key -- the event lives in qits-events' store, the same reason
-- epic.project_id is a bare string. No backfill: the column starts recording from here.
alter table Epic add column causation_id uuid;
alter table Feature add column causation_id uuid;
alter table Task add column causation_id uuid;
alter table AuditEntry add column causation_id uuid;
