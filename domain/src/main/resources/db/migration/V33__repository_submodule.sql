-- A submodule edge between two repositories under the same project: parent (superproject) → child
-- (the repository imported to satisfy a .gitmodules entry). A link table, not a field on Repository,
-- so one child can be the submodule of several superprojects and be used standalone (per-project
-- dedup). The pinned commit is not stored (it lives in the gitlink); path/name are the .gitmodules
-- mount path and section name.
--
-- BOTH FKs declare `on delete cascade` deliberately. ProjectService.delete removes a project's
-- repositories one at a time, so when either endpoint of an edge is deleted before the other the row
-- must vanish with it — otherwise a referential-integrity violation, the exact bug V32 fixed for
-- command_agent_session. The unique (parent_repo_id, path) makes re-import idempotent.

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
