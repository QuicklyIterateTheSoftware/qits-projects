-- The whole schema of the projects context, in one migration.
--
-- ONE V1 AND NO INHERITED LINEAGE, FOR THE SECOND TIME. The first clean start was the extraction
-- from the monorepo, where V1 was the shared V1..V45 squashed to the tables this context owns; this
-- one is the move off H2. The store is PostgreSQL now — the platform provisions a database per
-- `resources:` entry before the container starts — and the move is an UNWRAP AND A RE-BOOTSTRAP
-- rather than a data migration, so no database anywhere is on the H2 lineage and no V7 would have
-- had a reader. Its V1..V6 are history in this repository's log. The shape below is where those six
-- arrived, translated. FROM HERE ON THE ORDINARY RULE IS BACK: keep appending, never edit an applied
-- migration.
--
-- Three tables, REAL foreign keys between them. That is deliberate and is the whole point of cutting
-- the split here: Project → Repository → repository_name is one aggregate. Everything outside it —
-- workspace, workspace_event, command, agent_session_stat — lives in another context's database and
-- is referenced by String id through a control/ port, never a join. `epics` is such an outside, even
-- though the same process opens it: it is the second database this application is given
-- (QITS_RESOURCE_EPICS_*), so epic.project_id is a plain string there too.
--
-- repository_submodule is NOT here. The H2 lineage's V4 dropped it: the wrapper's .gitmodules is the
-- submodule graph now, the pull walk reads it per repository and the reconcile reads the wrapper's,
-- and the entity, its repository, DTO, mapper, controller and MCP tools all went with it. A table
-- created only to be dropped again is not a translation of anything.
--
-- WHAT THE TRANSLATION CHANGED, and why each one:
--   * UNQUOTED MIXED-CASE TABLE NAMES FOLD TO LOWER CASE. `create table Project` makes a table
--     called `project`, and Hibernate's own unquoted `Project` in every generated statement folds
--     the same way, so the two meet. The names are spelled as the entity classes are — the
--     convention this context and epics both carry — and nothing here is quoted, because one
--     quoted identifier would pin a mixed-case name that the unquoted references could no longer
--     find.
--   * `last_backup_at` GAINS ITS TIME ZONE. H2's V5 wrote a bare `timestamp` for it while every
--     epics column of the same kind carried `with time zone`; postgres keeps the two types strictly
--     apart, and Hibernate maps `Instant` to `timestamp with time zone`. The zone is what the field
--     always meant, so the column now says so.
--   * NOTHING BECAME `text`. Every string column here is a bounded `varchar` with a length the
--     entity states (`config_warning` 4000, `last_backup_detail` 1000); the H2 lineage had no
--     `clob`, so there is nothing to widen. Epics' lineage is where `clob` → `text` happened.
--   * NO IDENTITY COLUMN. Every id in this context is an application-assigned string — a UUID from
--     the service, or `@GeneratedValue(strategy = UUID)` on repository_name — so there is no
--     `auto_increment` to translate and no sequence to own.
--
-- WHAT THE TRANSLATION DELIBERATELY DID NOT CHANGE:
--   * THE ARCHETYPE CHECK CONSTRAINT STAYS. Every other enum column in this repository refuses one
--     (dns_type, last_backup_outcome) because the value set belongs to the enum and a second copy
--     in DDL is the one nobody remembers to widen. `archetype` is the exception and earns it: it is
--     the taxonomy the whole model keys on — it decides the wrapper directory a repository lands in
--     — and it has already been widened and tightened by two releases that were written as
--     migrations precisely because the constraint made them visible.
--   * `dns_value`, NOT `value`. The name was chosen because H2 2.x reserves the bare word.
--     Postgres would accept `value` unquoted, and the column stays `dns_value` anyway: it is what
--     ProjectDnsRecord maps and renaming it here would be a code change wearing a migration's
--     clothes.

-- The aggregate root. `name` is the human label and may repeat; `slug` is the git-safe, immutable
-- identity everything else is named after.
create table Project (
  id varchar(255) not null,
  name varchar(255) not null,
  -- Names the project's wrapper repository (<slug>-<slug>), its agent container (qits-proj-<slug>)
  -- and its upstream backup organisation, which is why it is UNIQUE (the H2 lineage's V6, whose V1
  -- comment said the opposite and could not be corrected in place). Still NULLABLE, and more than
  -- one null is fine — a unique constraint constrains values, and tests persist `new Project()`
  -- directly. ProjectService is what enforces non-null and the ProjectSlug pattern on every create.
  slug varchar(255),
  description varchar(255),
  -- The project's own dns record: {domain, type, value}, embedded on Project (ProjectDnsRecord).
  --
  -- A DECLARED PLACEHOLDER (main-environment-plan.md §1). Domain configuration belongs to a service
  -- that does not exist yet; when it arrives these three columns are DROPPED rather than migrated,
  -- so nothing outside ProjectDnsRecord and the ProjectDomainRegistrar port may read them.
  --
  -- All three nullable and NO DEFAULT, deliberately. A project without a record registers no
  -- domain, and that is a real state: a self-seed run without qits.startup-seed.dns-* configured.
  -- Hibernate reads an @Embedded whose every column is null as a null field, so it arrives at every
  -- reader as "no domain" rather than as an empty record — which only holds while nothing here
  -- defaults a column. The API requires the object (POST /projects/api/projects carries @NotNull
  -- dns); the schema does not, and that asymmetry is the point of the nullability rather than a gap
  -- in it.
  dns_domain varchar(255),
  -- STRING-valued, matching @Enumerated(EnumType.STRING) and qits-dns' own spelling of the same
  -- three types. No check constraint: the authoritative type rules are qits-dns', and a third copy
  -- in DDL would be the one nobody remembers to widen.
  dns_type varchar(255),
  dns_value varchar(255),
  primary key (id)
);

alter table Project add constraint uq_project_slug unique (slug);

create table Repository (
  id varchar(255) not null,
  project_id varchar(255) not null,
  url varchar(255),
  -- The branch synced with the remote ("main"/"master"), configurable per repository.
  main_branch varchar(255),
  archetype varchar(255),
  -- The last .qits-config.yml ingestion problem, or NULL when the file is absent or clean. Config
  -- ingestion degrades loudly and never blocks: the last-good rows survive and the message lands
  -- here.
  config_warning varchar(4000),
  -- What happened the last time this repository was backed up to its forge twin. All three
  -- nullable, and every row starts null — the honest reading of "never attempted".
  last_backup_at timestamp(6) with time zone,
  -- STRING-valued, matching @Enumerated(EnumType.STRING). No check constraint: this is a status
  -- line, not a taxonomy — see the archetype note in the header for where the line is drawn.
  last_backup_outcome varchar(255),
  -- The short human line behind a non-success outcome, truncated. NULL after a success,
  -- deliberately: a stale reason left beside a green outcome is worse than no reason at all.
  last_backup_detail varchar(1000),
  primary key (id),
  -- The final nine. INTEGRATION and APPLICATION were folded into LIBRARY and FRONTEND by the H2
  -- lineage's V4 and are gone from the enum, so a value this still allowed would be one Hibernate
  -- could no longer read back.
  constraint CK_repository_archetype check (archetype in
    ('PROJECT', 'SERVICE', 'DAEMON', 'LIBRARY', 'FRONTEND', 'CLI', 'IMAGE', 'SERVICE_TEMPLATE',
     'FORK'))
);

alter table Repository
  add constraint FK_repository_project foreign key (project_id) references Project (id);

-- Addressable name aliases within a project: (project, name) → repository. A link table, not a
-- column on Repository, so a repository keeps its opaque id yet carries as many names as there are
-- links to it — which is what makes committed relative submodule urls (../<name>.git) resolve
-- natively against a project's repos served as siblings.
create table repository_name (
  id varchar(255) not null,
  project_id varchar(255) not null,
  repository_id varchar(255) not null,
  name varchar(255) not null,
  primary key (id)
);

alter table repository_name
  add constraint UK_repository_name_project_name unique (project_id, name);

-- Both FKs cascade because ProjectService.delete removes a project's repositories one at a time, so
-- an alias must vanish with whichever endpoint goes first.
alter table repository_name
  add constraint FK_repository_name_project
  foreign key (project_id) references Project (id) on delete cascade;

alter table repository_name
  add constraint FK_repository_name_repository
  foreign key (repository_id) references Repository (id) on delete cascade;
