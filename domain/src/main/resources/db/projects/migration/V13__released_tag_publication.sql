-- THE PUBLISH PHASE: WHAT ASKED FOR A RELEASED TAG TO REACH `main`, AND WHY IT HAS NOT.
--
-- V12 gave a released tag two states — in flight (merged_at null) and landed (merged_at set) — and
-- that was the whole of it while nothing merged. Something does now: qits-deployments'
-- `DeploymentActive` says a version is live, and for a repository that deploys nothing qits-ci's
-- `SoftwareRelease` says the same thing one hop earlier. Both are TERMINAL GATES: past either one,
-- this tag is owed a merge to main and the only thing that can stop it is the git host.
--
-- Two nullable columns, and each says something the other two timestamps cannot:
--
--   merge_requested_at  the gate PASSED — a deployment of this version is active, or the repository
--                       deploys nothing at all. It is the sweep's whole selection: a row with this
--                       set and merged_at null is a merge somebody is owed, and the sweep keeps
--                       asking until the git host applies it. A row with NEITHER set is a release
--                       still waiting on its deployment, which is a different thing entirely and
--                       must never be swept — merging it would put a commit on main before the
--                       deployment that justifies it, which is the whole shape this epic removed.
--   merge_detail        why the last attempt did not apply, in the git host's own words. Cleared on
--                       the attempt that lands. Its presence beside a null merged_at is the loud
--                       state: main only ever advances through these merges and every release folds
--                       the pending tags in, so a conflict here is an anomaly rather than traffic.
--
-- NO STATE ENUM. Three timestamps and a sentence already answer every question that is asked of the
-- row — released, gated, landed, stuck — and a word beside them would be a fourth source of truth
-- for facts the columns already carry.
alter table released_tag_pending_merge add column merge_requested_at timestamp with time zone;
alter table released_tag_pending_merge add column merge_detail varchar(4000);

-- The correlation `DeploymentActive` forces: that event names an APPLICATION and a VERSION and no
-- repository at all (the application `qits-ci` is built from the repository `qits-ci-service`), so
-- the tag name is the key the lookup has. It is unique platform-wide by construction — the calver
-- is stamped to the second and a collision is refused as `tag-exists` — which is what makes a read
-- by tag_name alone sound. One read per deployment of the platform, so it earns its index.
create index IX_released_tag_pending_merge_tag on released_tag_pending_merge (tag_name);

-- The sweep's selection: everything gated and not landed. Small by construction (a stuck merge is
-- an anomaly), and read every 30 seconds, which is what makes the partial index worth having.
create index IX_released_tag_pending_merge_owed
  on released_tag_pending_merge (merge_requested_at)
  where merged_at is null;
