-- THE RELEASE REQUEST BECOMES AN OCTOPUS MERGE OF N SOURCES.
--
-- V9 modelled a request as "release this sha": one (branch, commit_sha) pair, gated and handed to a
-- door that merged it. The release flow it belongs to has changed shape. A request now names a SET
-- of participating sources, qits-githost folds them into a backing branch of its own, and what the
-- gates evaluate is the MERGE — one sha that is the whole of what would be released, rather than
-- one branch head that is a part of it.
--
-- IN PLACE, NOT ALONGSIDE. The alternative — a second family of tables and a cutover — was weighed
-- and refused: every V9 row is either settled history (whose branch and sha are a record of what
-- was asked, and which the child row below preserves exactly) or one of a handful of open requests
-- on a platform that releases several times a day. There is no third population, no reader of the
-- old shape that survives this change, and a superseding structure would leave two answers to "what
-- is open" for the rest of the table's life.
--
-- WHAT AN OLD ROW BECOMES. Its branch becomes its ONE named source — not branch + main, because a
-- request created under V9 was an ask about that branch alone and widening a live request's scope
-- in a migration would change what somebody asked for. Its commit_sha becomes merged_sha: it is
-- what the gates evaluated and what the door was pinned to, which is exactly the new column's
-- meaning. So an open V9 row stays open, stays gated on the same sha, and re-merges onto its
-- backing branch the next time anything touches it.
--
-- CONFLICTED joins the state vocabulary with no DDL, which is V9's whole reason for leaving `state`
-- a plain varchar with no check constraint. It is mentioned here rather than expressed here.

-- The NAMED sources: what a caller put on the request. Implicit sources — the repository's released
-- tags not yet merged to main — are NOT rows here; they are derived from released_tag_pending_merge
-- below, because they are a fact about the repository and not a choice about this request. A source
-- somebody named survives a tag being merged; an implicit one must not.
create table release_request_source (
  id           varchar(255) not null,
  request_id   varchar(255) not null,
  -- BRANCH today. RELEASED_TAG exists in the vocabulary because a read reports both kinds, but
  -- nothing writes one: an open set spelled as a string, the same reasoning `state` carries.
  kind         varchar(32)  not null,
  -- The branch's own name (`main`, `feature/x`), never a ref. The `refs/heads/` prefix is the git
  -- host's spelling and is applied at the call, so a row stays readable and one place decides it.
  name         varchar(512) not null,
  added_at     timestamp with time zone not null,
  added_by     varchar(255),
  causation_id uuid,
  constraint PK_release_request_source primary key (id),
  -- ON DELETE CASCADE, deliberately: a request's sources have no meaning without it, and the suites
  -- delete request rows in bulk (`ReleaseRequest.delete("projectId in ?1", …)`) where nothing walks
  -- the children first.
  constraint FK_release_request_source_request
    foreign key (request_id) references release_request (id) on delete cascade,
  -- Naming the same branch twice is one source. Adding a source is therefore idempotent, which is
  -- what lets the add-source route be retried.
  constraint UQ_release_request_source unique (request_id, kind, name)
);

-- The question asked of it from the push consumption: which open requests does a branch participate
-- in? That read is per push, so it is the one that earns an index.
create index IX_release_request_source_name on release_request_source (name);

-- The request's own shape. `commit_sha` is RENAMED rather than dropped-and-added: the value in it
-- is already the answer the new column wants, and a rename keeps the index that names it.
alter table release_request rename column commit_sha to merged_sha;
-- Nullable now: a request exists before its first merge lands, and a CONFLICTED one has no merge at
-- all. Null means "nothing is gated yet", which the gate reads as "not ready" rather than guessing.
alter table release_request alter column merged_sha drop not null;

-- Why a CONFLICTED request is conflicted: qits-githost's own 409 body (target + the conflicting
-- paths, each with the head that introduced it), stored as the JSON document it arrived as so the
-- API and the UI can show a person what to resolve. Text and not jsonb: nothing queries inside it,
-- and the shape is the git host's to change.
alter table release_request add column conflict_detail text;

-- Every V9 row's branch becomes its one named source, before the column goes.
insert into release_request_source (id, request_id, kind, name, added_at, added_by, causation_id)
select gen_random_uuid()::text, id, 'BRANCH', branch, created_at, requester, causation_id
from release_request;

alter table release_request drop column branch;

-- THE RELEASED TAGS STILL PENDING A MERGE TO MAIN.
--
-- A release is a tag now; main is finalized after the deployment succeeds. Between those two
-- moments the released commit exists on no branch, so a release request opened in that window would
-- be a step BACKWARDS from what is already shipping unless it folds that tag in too. This table is
-- what makes "every release is a superset of the releases still in flight" a derivable fact rather
-- than a hope: one row per released tag, written when the release lands, `merged_at` stamped when
-- the post-deployment merge puts it on main. The set of implicit sources for a repository is
-- exactly the rows with merged_at null.
--
-- ROWS ARE KEPT, NOT DELETED, once merged. The record of which release reached main and when is the
-- only place the flow's own history lives, and a delete would make "no row" mean both "never
-- released" and "long since merged".
--
-- repo_id is a plain string and no foreign key, the reasoning V9 states for release_request: the
-- record of what was released must not vanish with the row that named the repository.
create table released_tag_pending_merge (
  id                 varchar(255) not null,
  repo_id            varchar(255) not null,
  tag_name           varchar(255) not null,
  released_sha       varchar(255) not null,
  -- Which request produced it, where one did. Null for a tag recorded by any other path.
  release_request_id varchar(255),
  released_at        timestamp with time zone not null,
  -- Null while the tag is still in flight. Stamped by the post-deployment merge to main.
  merged_at          timestamp with time zone,
  constraint PK_released_tag_pending_merge primary key (id),
  -- One row per tag of a repository: recording a release twice is recording it once.
  constraint UQ_released_tag_pending_merge unique (repo_id, tag_name)
);

-- The one question: what is still in flight for this repository? Asked on every re-merge of every
-- open request, so it is the read that earns the index.
create index IX_released_tag_pending_merge_open
  on released_tag_pending_merge (repo_id, merged_at);
