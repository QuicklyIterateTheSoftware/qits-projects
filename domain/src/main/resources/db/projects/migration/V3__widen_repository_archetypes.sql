-- Widen CK_repository_archetype to the OLD ∪ NEW value set. Nothing else.
--
-- Release A only widens, and that is the whole design of this file. A failed deploy cannot be
-- rolled back across a Flyway migration, so the predecessor image has to stay legal against the
-- schema this leaves behind: it writes INTEGRATION and APPLICATION, and those values must keep
-- passing the constraint. No row updates here for the same reason.
--
-- Release B (V4) does the other half once the new image is deployed and the wrapper reconciled:
-- INTEGRATION → LIBRARY, APPLICATION → FRONTEND, qits-backend → FORK, then the constraint tightens
-- to the final nine values.
alter table Repository drop constraint CK_repository_archetype;

alter table Repository
  add constraint CK_repository_archetype check (archetype in
    ('PROJECT', 'SERVICE', 'DAEMON', 'LIBRARY', 'FRONTEND', 'CLI', 'IMAGE', 'SERVICE_TEMPLATE',
     'FORK', 'INTEGRATION', 'APPLICATION'));
