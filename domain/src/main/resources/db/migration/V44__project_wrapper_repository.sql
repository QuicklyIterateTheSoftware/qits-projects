-- The project wrapper repository: every project owns exactly one PROJECT-archetype repository named
-- <slug>-<slug>, seeded with the project template skeleton
-- (docs/epics/qits-projects/features/2026-07-26_project-wrapper-repository.md).
--
-- Two changes, and deliberately NO project.wrapper_repository_id FK: "at most one wrapper per
-- project" is carried by a query (RepositoryRepository.findWrapperByProject) plus a guard in
-- ProjectService.adoptWrapperRepository. The FK would enforce it structurally but duplicates what
-- `archetype` already says, and the wrapper is never looked up by url.

-- 1. Extend Repository.archetype with PROJECT, LIBRARY, INTEGRATION and APPLICATION. The four
--    placeable archetypes (SERVICE/LIBRARY/INTEGRATION/APPLICATION) map one-to-one onto the wrapper
--    skeleton's directories (services/ libs/ integrations/ apps/) via RepositoryArchetype.directory();
--    PROJECT is the wrapper itself and SERVICE_TEMPLATE/FORK stay unplaceable.
--
--    V1__init.sql declared the check INLINE and unnamed, so H2 auto-named it CONSTRAINT_n and it
--    cannot be dropped by name portably. Recreate the column instead — deterministic, and it leaves
--    behind a NAMED constraint a future migration can drop directly. `archetype` is nullable in V1,
--    so no `not null default` is needed here.
alter table Repository add column archetype_new varchar(255);
update Repository set archetype_new = archetype;
alter table Repository drop column archetype;
alter table Repository rename column archetype_new to archetype;
alter table Repository
  add constraint CK_repository_archetype check (archetype in
    ('PROJECT', 'SERVICE', 'LIBRARY', 'INTEGRATION', 'APPLICATION', 'SERVICE_TEMPLATE', 'FORK'));

-- 2. Project.slug — the git-safe, IMMUTABLE identity a project's wrapper repository is named after
--    (<slug>-<slug>), deliberately detached from the free-form, editable `name`. The wrapper's local
--    alias must equal its remote basename for a committed relative submodule url (../<name>.git) to
--    fold to the same thing locally and at the forge, which a display name cannot guarantee. Keeping
--    the slug unrenameable is what removes the "rename leaves a stale alias" problem entirely.
--
--    Nullable at the DB level: Project rows are also persisted directly by tests, and rows predating
--    this migration are backfilled below. ProjectService is what enforces non-null on every create.
--    Deliberately NOT unique — repository name aliases are project-scoped (UK_repository_name_project_name),
--    so two projects may share a slug without their wrappers colliding.
alter table Project add column slug varchar(255);

-- Backfill slugify(name), matching ProjectService.slugify exactly: lowercase, every run of
-- non-[a-z0-9] becomes '-', strip leading/trailing dashes, truncate to 40, then re-strip a trailing
-- dash the truncation may have exposed.
--
-- H2's three-argument regexp_replace is ALREADY global (verified against H2 2.4.240, the version this
-- runs on): 'Quarkus + Angular Demo' -> 'quarkus-angular-demo', with every run replaced. Do NOT add a
-- 'g' flag — H2 2.4.240 rejects the four-argument form outright with `Invalid value "g" for parameter
-- {1}`. This is H2-specific, like V41's random_uuid(); qits runs on H2 everywhere.
--
-- Load-bearing for the deployed instance: the existing 'qits' project must land on slug 'qits', or
-- the self-seed's retro-fit check basename('.../qits-qits.git') == '<slug>-<slug>' fails on every
-- boot inside SelfSeedService's per-item try/catch — a silent log line, not an error.
update Project
set slug = regexp_replace(
             substring(
               regexp_replace(
                 regexp_replace(lower(name), '[^a-z0-9]+', '-'),
                 '(^-+)|(-+$)', ''),
               1, 40),
             '-+$', '');

-- Names that slugify to nothing at all ('***', a pure-unicode name) fall back to a deterministic slug
-- derived from the project id — a UUID prefix, so [a-z0-9] throughout and always valid.
update Project
set slug = 'project-' || lower(substring(id, 1, 8))
where slug is null or slug = '';
