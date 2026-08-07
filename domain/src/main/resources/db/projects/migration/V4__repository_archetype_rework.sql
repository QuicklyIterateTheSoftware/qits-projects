-- The other half of the archetype rework, once release A is deployed and the wrapper reconciled.
--
-- Forward-only, and safe in the one direction that matters: if THIS deploy fails, the still-running
-- A.1 image never writes a legacy archetype (its every write path normalizes) and no longer reads
-- repository_submodule (the entity, its repository, DTO, mapper, REST controller and MCP tools are
-- all gone), so both the tightened constraint and the dropped table are already true of it.

-- The row updates are a BACKSTOP. On the deployment this was written for they are no-ops — the
-- wrapper reconcile flipped every live row on the first boot of release A, and the tightened
-- constraint below would refuse to build if one had been missed. They are here so a straggler
-- deployment that never ran a reconcile migrates itself rather than failing at the constraint with
-- nothing to tell its operator.
update Repository set archetype = 'LIBRARY' where archetype = 'INTEGRATION';
update Repository set archetype = 'FRONTEND' where archetype = 'APPLICATION';

-- No backstop for qits-backend. The pre-split monorepo is not a component of qits and is out of the
-- platform entirely as of this release, so the row should not survive in any shape — a straggler
-- deployment that still has one needs no migration for it: it is a placeable row the wrapper does
-- not name, so the next reconcile deregisters it and leaves its history on the git host untouched.

-- The final nine. INTEGRATION and APPLICATION are gone from the enum in this same release, so a
-- value the constraint still allowed would be one Hibernate could no longer read back.
alter table Repository drop constraint CK_repository_archetype;

alter table Repository
  add constraint CK_repository_archetype check (archetype in
    ('PROJECT', 'SERVICE', 'DAEMON', 'LIBRARY', 'FRONTEND', 'CLI', 'IMAGE', 'SERVICE_TEMPLATE',
     'FORK'));

-- The submodule edge table, written by an import that no longer exists. The wrapper's .gitmodules
-- is the submodule graph now: the pull walk reads it per repository and the reconcile reads the
-- wrapper's, so nothing has queried these rows since release A. Its two foreign keys go with it.
drop table repository_submodule;
