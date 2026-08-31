-- The release request: the asynchronous half of "release this branch", created where the release
-- door used to merge at once and settled by quality gates before anything merges. One row per
-- request; the first gate is the build gate, read off commit_build_status.
--
-- A REQUEST IS ABOUT A SHA. The caller names (branch, commit_sha) and the gates evaluate that sha;
-- a branch whose head moves past a pending request does not silently widen what was gated — the
-- caller re-requests. That is also why there is no unique constraint on (repo_id, branch): an open
-- request is superseded by marking it WITHDRAWN and creating the next one, and history stays.
--
-- STATE HAS NO CHECK CONSTRAINT, the usual reasoning: the vocabulary (PENDING, READY, RELEASED,
-- REJECTED, FAILED, WITHDRAWN today) grows without a migration and every historical row keeps its
-- word.
--
-- repo_id is a plain string, never a foreign key: a request outlives its repository the same way a
-- deployment row outlives the topology that described it, and the record of what was asked must
-- not vanish with the row it named.
create table release_request (
  id           varchar(255) not null,
  repo_id      varchar(255) not null,
  project_id   varchar(255),
  repo_name    varchar(255),
  branch       varchar(255) not null,
  commit_sha   varchar(255) not null,
  summary      text not null,
  requester    varchar(255),
  state        varchar(32) not null,
  detail       text,
  version      varchar(64),
  created_at   timestamp with time zone not null,
  updated_at   timestamp with time zone not null,
  causation_id uuid,
  constraint PK_release_request primary key (id)
);

-- The two questions asked of it: a repository's requests, and the open ones a verdict resolves.
create index IX_release_request_repo on release_request (repo_id, created_at);
create index IX_release_request_open on release_request (repo_id, commit_sha, state);
