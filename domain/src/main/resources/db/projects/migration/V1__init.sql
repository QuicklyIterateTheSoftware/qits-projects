-- The projects context's own lineage, squashed from the monorepo's shared V1..V45 (migration-plan.md
-- §7: projects takes V1, V3, V10*, V20, V24*, V33, V34, V41, V43*, V44). This is the SCHEMA AS OF
-- V45, not a replay: the worktree→workspace rename (V10/V20/V24) touched only tables this context
-- does not own, and V43's projects-side statements were already folded into the column list below.
--
-- Four tables, one database, REAL foreign keys between them. That is deliberate and is the whole
-- point of cutting the split here: Project→Repository→{repository_name, repository_submodule} is one
-- aggregate. Everything outside it — workspace, workspace_event, command, agent_session_stat — lives
-- in another context's database and is referenced by String id through a control/ port, never a
-- join.

create table Project (
  id varchar(255) not null,
  name varchar(255) not null,
  -- The git-safe, immutable identity the wrapper repository is named after (<slug>-<slug>).
  -- Nullable at the DB level (tests persist `new Project()` directly); ProjectService enforces
  -- non-null and the ProjectSlug pattern on every create. Deliberately NOT unique — name aliases
  -- are project-scoped, so two projects may share a slug without their wrappers colliding.
  slug varchar(255),
  description varchar(255),
  primary key (id)
);

create table Repository (
  id varchar(255) not null,
  project_id varchar(255) not null,
  url varchar(255),
  -- The branch synced with the remote ("main"/"master"), configurable per repository (V3).
  main_branch varchar(255),
  -- V44 recreated this column to widen the archetype set and leave behind a NAMED check.
  archetype varchar(255),
  -- The last .qits-config.yml ingestion problem, or NULL when the file is absent or clean (V34).
  -- Config ingestion degrades loudly and never blocks: the last-good rows survive and the message
  -- lands here.
  config_warning varchar(4000),
  primary key (id),
  constraint CK_repository_archetype check (archetype in
    ('PROJECT', 'SERVICE', 'LIBRARY', 'INTEGRATION', 'APPLICATION', 'SERVICE_TEMPLATE', 'FORK'))
);

alter table Repository
  add constraint FK_repository_project foreign key (project_id) references Project (id);

-- Addressable name aliases within a project: (project, name) → repository (V41). A link table, not
-- a column on Repository, so a repository keeps its opaque id yet carries as many names as there
-- are links to it — which is what makes committed relative submodule urls (../<name>.git) resolve
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

alter table repository_name
  add constraint FK_repository_name_project
  foreign key (project_id) references Project (id) on delete cascade;

alter table repository_name
  add constraint FK_repository_name_repository
  foreign key (repository_id) references Repository (id) on delete cascade;

-- A submodule edge between two repositories under the same project (V33): parent (superproject) →
-- child. Both FKs cascade because ProjectService.delete removes a project's repositories one at a
-- time, so an edge must vanish with whichever endpoint goes first.
create table repository_submodule (
  id varchar(255) not null,
  parent_repo_id varchar(255) not null,
  child_repo_id varchar(255) not null,
  path varchar(255) not null,
  name varchar(255) not null,
  primary key (id)
);

alter table repository_submodule
  add constraint UK_repository_submodule_parent_path unique (parent_repo_id, path);

alter table repository_submodule
  add constraint FK_repository_submodule_parent
  foreign key (parent_repo_id) references Repository (id) on delete cascade;

alter table repository_submodule
  add constraint FK_repository_submodule_child
  foreign key (child_repo_id) references Repository (id) on delete cascade;
