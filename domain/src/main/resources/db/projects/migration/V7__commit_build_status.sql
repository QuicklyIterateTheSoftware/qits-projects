-- The per-commit build-status ledger: one row per CI run's terminal verdict about a commit,
-- written from qits-ci's BuildSuccessful/BuildFailed events by a durable bus consumption.
--
-- WHY THIS SERVICE. Build status is repository-lifecycle vocabulary — "what is known about this
-- commit" — and the repository aggregate lives here. It is deliberately NOT in qits-githost, whose
-- tables are git-operational only; and the release-quality-gates work this ledger is the foundation
-- of wants the gate resolver and the ledger write to be one consumer in one service, so a build
-- event resolves a pending gate in the same breath that records it.
--
-- ONE ROW PER RUN, NOT PER COMMIT. A commit can have several runs — a push pipeline and event
-- pipelines, gating and non-gating — and collapsing them here would bake one consumer's policy into
-- the storage. The run id is qits-ci's, a plain key and never a relation (no FK can span a context
-- boundary; the run row lives in qits-ci's own database).
--
-- STATUS HAS NO CHECK CONSTRAINT, the platform's usual reasoning: the vocabulary (SUCCESS, FAILED,
-- TIMED_OUT, CONFIG_ERROR today) is qits-ci's and grows without a migration here; every historical
-- row keeps the word it was written with.
--
-- project_id, repo_name and branch are NULLABLE: an id-addressed push announces no name pair, and
-- the ledger records what the event carried rather than refusing a verdict over a label.
--
-- causation_id is the platform's uniform column (qits-eventstream's CausedRow), set explicitly by
-- the listener from the consumed frame's own id. Nullable, in no constraint, never a foreign key.
create table commit_build_status (
  run_id       varchar(255) not null,
  repo_id      varchar(255) not null,
  project_id   varchar(255),
  repo_name    varchar(255),
  branch       varchar(255),
  commit_sha   varchar(255) not null,
  status       varchar(32) not null,
  finished_at  timestamp with time zone not null,
  causation_id uuid,
  constraint PK_commit_build_status primary key (run_id)
);

-- The question the ledger answers: every verdict for (repository, commit).
create index IX_commit_build_status_commit on commit_build_status (repo_id, commit_sha);
