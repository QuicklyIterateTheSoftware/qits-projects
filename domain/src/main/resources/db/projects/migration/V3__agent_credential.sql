-- The idp client commissioned for one project's agent container.
--
-- WHY A TABLE AND NOT A COLUMN. Nothing in this database tracks an agent container: the container's
-- own row lives in qits-containers' registry, addressed owner/project-agent/<projectId>, and this
-- service holds only in-memory state about the daemon inside it. So there was no row to add a
-- column to, and this is the first one.
--
-- WHY THE SECRET IS A COLUMN. The credential reaches the container as environment, and
-- qits-containers hashes a workload's whole spec — environment included — to decide whether an
-- ensure starts the container in place or replaces it. A wake that could not reproduce the same two
-- values byte for byte would be a spec change, so every wake would recreate the container. That is
-- the defect ContainerRuntime.restart records and the reason nothing per-call may enter that spec;
-- this row is what lets the wake arm send exactly what the fresh arm sent.
--
-- NO FOREIGN KEY TO project, unlike every other relation here. An agent container outlives its
-- project — deleting a project does not remove one — so a cascade would drop the row while the
-- container is still running and still holding the credential, which is the leak the hourly
-- reconcile exists to prevent. project_id is a key, not a relation.
--
-- causation_id is the platform's uniform column (qits-eventstream's CausedRow): the insert runs on
-- the request thread that asked for the container, so the stamp has a scope to read. Nullable, in
-- no constraint, never a foreign key — the same terms V2 set for project and repository.
create table agent_credential (
  project_id      varchar(255) not null,
  client_id       varchar(255) not null,
  client_secret   varchar(255) not null,
  commissioned_at timestamp with time zone not null,
  causation_id    uuid,
  constraint PK_agent_credential primary key (project_id)
);
