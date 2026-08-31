-- Whether a red verdict should stand in the way of releasing the commit — qits-ci's `gating` flag
-- (V7 of ITS lineage), carried on both build events since ci 2026-08-31 as null-means-gating and
-- recorded here as the column the release request's build gate filters on. Every existing row
-- fills as gating: it predates the distinction and gating is the conservative reading. The default
-- is then dropped so a writer that forgets the value fails loudly (the entity initializes true).
alter table commit_build_status add column gating boolean not null default true;
alter table commit_build_status alter column gating drop default;
