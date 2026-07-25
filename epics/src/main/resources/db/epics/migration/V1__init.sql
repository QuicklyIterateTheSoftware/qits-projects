-- Epics' own Flyway lineage (docs/epics/qits-epics/), on its OWN named datasource — a separate
-- physical H2 file, never mixed with qits' domain schema in db/migration. Table names match the
-- entity class simple names (no @Table), mirroring domain's Project/Repository convention.
--
-- Cross-boundary references (epic.project_id → domain Project, task.repository_id → domain
-- Repository) are deliberately PLAIN String columns with NO foreign key: epics is a separate
-- physical DB, so those FKs can't span it (the artifacts precedent). Existence is validated in
-- `service`'s controllers, which depend on `domain`. Intra-module relationships ARE real FKs.

-- The spine, owned by a project (project_id: no cross-DB FK, just an indexed String).
create table Epic (
    id varchar(255) not null,
    project_id varchar(255) not null,
    title varchar(512) not null,
    description clob,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_epic_project_id on Epic (project_id);

-- A feature under an epic. depends_on_feature_id is a nullable self-reference.
create table Feature (
    id varchar(255) not null,
    epic_id varchar(255) not null,
    title varchar(512) not null,
    description clob,
    depends_on_feature_id varchar(255),
    implemented_on timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_feature_epic_id on Feature (epic_id);

-- A task glues a feature to a concrete repository (repository_id: no cross-DB FK, indexed String).
create table Task (
    id varchar(255) not null,
    feature_id varchar(255) not null,
    repository_id varchar(255) not null,
    title varchar(512) not null,
    description clob,
    depends_on_task_id varchar(255),
    implemented_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_task_feature_id on Task (feature_id);
create index idx_task_repository_id on Task (repository_id);

-- Append-only audit log — the git replacement. Rows are NOT FK'd back to the live entities (they
-- must survive the row's deletion). One row per create/update/delete, with the acting principal and
-- a JSON snapshot of the entity's changed/current fields.
create table AuditEntry (
    id varchar(255) not null,
    entity_type varchar(32) not null check (entity_type in ('EPIC','FEATURE','TASK')),
    entity_id varchar(255) not null,
    epic_id varchar(255) not null,
    operation varchar(16) not null check (operation in ('CREATE','UPDATE','DELETE')),
    changed_by varchar(255),
    changed_at timestamp(6) with time zone not null,
    snapshot clob,
    primary key (id)
);
create index idx_audit_entity on AuditEntry (entity_type, entity_id);
create index idx_audit_epic_id on AuditEntry (epic_id);
create index idx_audit_changed_at on AuditEntry (changed_at);

-- Intra-module FKs. The services delete subtrees and clear dependents IN-SERVICE (so each change
-- gets an audit row); these DB rules are a safety net: CASCADE tears down features/tasks if an epic
-- is ever removed outside the service, and SET NULL clears a depended-on row's dependents' pointers.
alter table if exists Feature
    add constraint fk_feature_epic foreign key (epic_id) references Epic (id) on delete cascade;
alter table if exists Feature
    add constraint fk_feature_depends_on foreign key (depends_on_feature_id) references Feature (id) on delete set null;
alter table if exists Task
    add constraint fk_task_feature foreign key (feature_id) references Feature (id) on delete cascade;
alter table if exists Task
    add constraint fk_task_depends_on foreign key (depends_on_task_id) references Task (id) on delete set null;
