-- The whole planning schema, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE. The H2 lineage (V1 + V2's slugs + V3's epic lifecycle) was
-- deleted rather than continued, and it could be because the move onto PostgreSQL is an UNWRAP AND
-- A RE-BOOTSTRAP rather than a data migration: no database anywhere is on it, so no V4 would have
-- had a reader. Those three files are history in this repository's log. The shape below is where
-- they arrived, translated. FROM HERE ON THE ORDINARY RULE IS BACK: keep appending, never edit an
-- applied migration, and a second clean start is not a precedent — this one cost a re-bootstrap.
--
-- Its OWN named datasource, its own physical database (`qits_epics`, named outright in this
-- repository's .config/qits/deployments.yml because the default would have collided with domain's),
-- never mixed with the `projects` schema. Cross-boundary references — epic.project_id → domain's
-- Project, task.repository_id → domain's Repository — are deliberately PLAIN String columns with NO
-- foreign key: that is a different database, so an FK cannot span it. Existence is validated in
-- `service`'s controllers, which see both. Intra-module relationships ARE real FKs.
--
-- WHAT THE TRANSLATION CHANGED, and why each one:
--   * `clob` → `text`. Four columns (three descriptions and the audit snapshot). Postgres has no
--     `clob`, and `text` is the same unbounded string with none of the LOB machinery; nothing maps
--     these with @Lob, so nothing on the Java side notices.
--   * THE SLUG BACKFILL IS GONE, all three copies of it. V2 added each slug nullable, derived it
--     from the title with regexp_replace, broke ties by age through a temporary ranking table, then
--     set the column not null. Every database reaching THIS file is empty, so the columns are born
--     `not null` and Slugs.slugify — the Java the SQL was imitating — is the only minter left. The
--     temporary-table detour existed because H2 cannot use a window function in an UPDATE's SET
--     clause; postgres can, and it no longer matters.
--   * THE STATUS BACKFILL IS GONE for the same reason. V3 added `status` nullable, set every
--     pre-lifecycle row to IMPLEMENTATION and then tightened it. There are no pre-lifecycle rows
--     here. New rows start REFINING, set by EpicService.create rather than by a DB default — the
--     service owns the value, as it does for every other column.
--   * UNQUOTED MIXED-CASE TABLE NAMES FOLD TO LOWER CASE, so `create table Epic` makes `epic` and
--     Hibernate's unquoted `Epic` finds it. The names still match the entity class simple names (no
--     @Table), the convention domain's Project/Repository carries; nothing is quoted, because one
--     quoted identifier would pin a mixed-case name the unquoted references could no longer reach.
--   * `alter table if exists` COLLAPSED INTO PLAIN `alter table`. The guard was noise in a file
--     that creates the table four lines above.
--   * NO IDENTITY COLUMN. Every id here is an application-assigned string, so there is nothing to
--     translate from `auto_increment` and no sequence to own.
--
-- WHAT THE TRANSLATION DELIBERATELY DID NOT CHANGE: the three check constraints (entity_type,
-- operation, status) stay. They are closed vocabularies the audit log and the lifecycle are written
-- against rather than open taxonomies, and they were already carried under H2.

-- The spine, owned by a project (project_id: no cross-DB FK, just an indexed String).
create table Epic (
    id varchar(255) not null,
    project_id varchar(255) not null,
    title varchar(512) not null,
    -- Git-safe path segment, minted from the title at create and never changed: it names the epic's
    -- branch (epic/<slug>) and prefixes every feature and task branch below it.
    slug varchar(255) not null,
    -- The lifecycle: REFINING (draft, scope mutable) → IMPLEMENTATION (scope frozen, only the
    -- implemented markers move) → SUPERSEDED (back to the drawing board, a successor draft carries
    -- the copied scope) or ABANDONED (terminal). "Done" is NOT a stored status: it is derived from
    -- the features' implemented markers, so nothing here spells it.
    status varchar(32) not null,
    -- The successor draft a superseded epic spawned. Null on every other row.
    superseded_by_epic_id varchar(255),
    description text,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id),
    constraint ck_epic_status check (status in ('REFINING','IMPLEMENTATION','SUPERSEDED','ABANDONED'))
);
create index idx_epic_project_id on Epic (project_id);
-- The project epics list filters by status (the overview groups by phase), so the index carries
-- both columns in that order.
create index idx_epic_project_status on Epic (project_id, status);
alter table Epic add constraint uq_epic_project_slug unique (project_id, slug);
-- The same kind of safety net as the self-references below: deleting the successor clears the
-- pointer rather than leaving it dangling.
alter table Epic
    add constraint fk_epic_superseded_by foreign key (superseded_by_epic_id) references Epic (id) on delete set null;

-- A feature under an epic. depends_on_feature_id is a nullable self-reference.
create table Feature (
    id varchar(255) not null,
    epic_id varchar(255) not null,
    title varchar(512) not null,
    -- Names the feature's branch: feature/<epic-slug>/<slug>. Unique within the epic.
    slug varchar(255) not null,
    description text,
    depends_on_feature_id varchar(255),
    implemented_on timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_feature_epic_id on Feature (epic_id);
alter table Feature add constraint uq_feature_epic_slug unique (epic_id, slug);

-- A task glues a feature to a concrete repository (repository_id: no cross-DB FK, indexed String).
create table Task (
    id varchar(255) not null,
    feature_id varchar(255) not null,
    repository_id varchar(255) not null,
    title varchar(512) not null,
    -- Names the task's branch: task/<epic-slug>/<feature-slug>/<slug>. Unique within the feature.
    slug varchar(255) not null,
    description text,
    depends_on_task_id varchar(255),
    implemented_at timestamp(6) with time zone,
    created_at timestamp(6) with time zone not null,
    updated_at timestamp(6) with time zone not null,
    primary key (id)
);
create index idx_task_feature_id on Task (feature_id);
create index idx_task_repository_id on Task (repository_id);
alter table Task add constraint uq_task_feature_slug unique (feature_id, slug);

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
    snapshot text,
    primary key (id)
);
create index idx_audit_entity on AuditEntry (entity_type, entity_id);
create index idx_audit_epic_id on AuditEntry (epic_id);
create index idx_audit_changed_at on AuditEntry (changed_at);

-- Intra-module FKs. The services delete subtrees and clear dependents IN-SERVICE (so each change
-- gets an audit row); these DB rules are a safety net: CASCADE tears down features/tasks if an epic
-- is ever removed outside the service, and SET NULL clears a depended-on row's dependents' pointers.
alter table Feature
    add constraint fk_feature_epic foreign key (epic_id) references Epic (id) on delete cascade;
alter table Feature
    add constraint fk_feature_depends_on foreign key (depends_on_feature_id) references Feature (id) on delete set null;
alter table Task
    add constraint fk_task_feature foreign key (feature_id) references Feature (id) on delete cascade;
alter table Task
    add constraint fk_task_depends_on foreign key (depends_on_task_id) references Task (id) on delete set null;
