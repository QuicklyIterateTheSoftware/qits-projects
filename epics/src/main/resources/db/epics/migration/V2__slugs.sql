-- Git-safe slugs for Epic/Feature/Task. They are the path segments of the branch names the
-- platform now mints — epic/<epic>, feature/<epic>/<feature>, task/<epic>/<feature>/<task> — so
-- unlike domain's Project.slug (deliberately non-unique) each must be unique within its parent.
--
-- Backfill mirrors Slugs.slugify in SQL: lowercase the title, every run of non-alphanumerics
-- becomes a dash, strip leading/trailing dashes, then cut. The cut is 36 here, not slugify's 40,
-- so the "-<n>" a duplicate gets still fits in 40; a slug is minted once and never changes, so
-- the two lengths never have to agree on an existing row. A title with nothing alphanumeric in it
-- slugifies to nothing and falls back to a prefix plus the id's first 8 characters (UUID hex,
-- always valid).
--
-- Duplicates are resolved by age: within the parent scope the oldest row keeps the clean slug and
-- the rest get "-2", "-3", … The row_number lives in a temporary table because H2 cannot use a
-- window function in an UPDATE's SET clause.

-- Epic: unique per project.
alter table Epic add column slug varchar(255);

update Epic set slug = coalesce(
    nullif(
        regexp_replace(
            left(regexp_replace(regexp_replace(lower(title), '[^a-z0-9]+', '-'),
                                '^-+|-+$', ''),
                 36),
            '-+$', ''),
        ''),
    'epic-' || lower(left(id, 8)));

create local temporary table epic_slug_rank as
    select id, row_number() over (partition by project_id, slug order by created_at, id) as rn
    from Epic;

update Epic e
    set slug = e.slug || '-' || (select r.rn from epic_slug_rank r where r.id = e.id)
    where exists (select 1 from epic_slug_rank r where r.id = e.id and r.rn > 1);

drop table epic_slug_rank;

alter table Epic alter column slug set not null;
alter table Epic add constraint uq_epic_project_slug unique (project_id, slug);

-- Feature: unique per epic.
alter table Feature add column slug varchar(255);

update Feature set slug = coalesce(
    nullif(
        regexp_replace(
            left(regexp_replace(regexp_replace(lower(title), '[^a-z0-9]+', '-'),
                                '^-+|-+$', ''),
                 36),
            '-+$', ''),
        ''),
    'feature-' || lower(left(id, 8)));

create local temporary table feature_slug_rank as
    select id, row_number() over (partition by epic_id, slug order by created_at, id) as rn
    from Feature;

update Feature f
    set slug = f.slug || '-' || (select r.rn from feature_slug_rank r where r.id = f.id)
    where exists (select 1 from feature_slug_rank r where r.id = f.id and r.rn > 1);

drop table feature_slug_rank;

alter table Feature alter column slug set not null;
alter table Feature add constraint uq_feature_epic_slug unique (epic_id, slug);

-- Task: unique per feature.
alter table Task add column slug varchar(255);

update Task set slug = coalesce(
    nullif(
        regexp_replace(
            left(regexp_replace(regexp_replace(lower(title), '[^a-z0-9]+', '-'),
                                '^-+|-+$', ''),
                 36),
            '-+$', ''),
        ''),
    'task-' || lower(left(id, 8)));

create local temporary table task_slug_rank as
    select id, row_number() over (partition by feature_id, slug order by created_at, id) as rn
    from Task;

update Task t
    set slug = t.slug || '-' || (select r.rn from task_slug_rank r where r.id = t.id)
    where exists (select 1 from task_slug_rank r where r.id = t.id and r.rn > 1);

drop table task_slug_rank;

alter table Task alter column slug set not null;
alter table Task add constraint uq_task_feature_slug unique (feature_id, slug);
