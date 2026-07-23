-- Addressable name aliases for repositories within a project: (project, name) → repository. The git
-- host resolves /git/<projectId>/<name> through this table to the repository's internal id and serves
-- <data-dir>/<id>/origin. A link table (not a column on Repository) so a repository keeps its opaque
-- UUID identity (deduped on url) yet carries as many names as there are links to it — which is what
-- makes committed relative submodule urls (../<name>.git) resolve natively against a project's repos
-- served as siblings, retiring the per-level submodule.<name>.url override.
--
-- Both FKs cascade on delete: ProjectService.delete removes a project's repositories one at a time,
-- so an alias must vanish with whichever endpoint goes first (same care as repository_submodule). The
-- unique (project_id, name) enforces one repository per name within a project and makes registration
-- idempotent.

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

-- Backfill one alias per existing repository from its url basename. The basename derivation MUST
-- match RepositoryNameRepository.basename(): strip trailing slashes, take the segment after the last
-- '/' OR ':' (so scp-style git@host:foo.git → foo, not git@host:foo), then strip a trailing '.git'.
--
-- Only NON-colliding names are backfilled: if two repos in one project derive the same basename from
-- distinct urls, neither is aliased here (a `min(id)` pick could assign the name to the wrong repo,
-- and native ../name.git resolution is inherently ambiguous there). The unaliased repos stay reachable
-- by their id-addressed /git/<repoId> route and get their correct name from registerSelfName on the
-- next clone/provision.
insert into repository_name (id, project_id, repository_id, name)
select cast(random_uuid() as varchar(255)), t.project_id, t.repository_id, t.name
from (
  select r.project_id as project_id,
         min(r.id) as repository_id,
         regexp_replace(
           regexp_replace(regexp_replace(r.url, '/+$', ''), '^.*[/:]', ''),
           '\.git$', '') as name
  from Repository r
  group by r.project_id,
           regexp_replace(
             regexp_replace(regexp_replace(r.url, '/+$', ''), '^.*[/:]', ''),
             '\.git$', '')
  having count(*) = 1
) t;
