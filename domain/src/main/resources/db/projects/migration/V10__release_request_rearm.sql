-- A release request tracks its BRANCH, merge-request-shaped: a new head re-arms the open request
-- onto the new sha (gates invalidated, state back to PENDING) rather than minting a request per
-- push. armed_at is when the current sha was armed -- the settle window's basis, so a re-armed
-- request waits its own window rather than inheriting the original creation's, and a no-ci push
-- (which will never produce a verdict) still passes vacuously after it. Backfilled from
-- created_at: every existing row was armed when it was created.
alter table release_request add column armed_at timestamp with time zone;
update release_request set armed_at = created_at;
alter table release_request alter column armed_at set not null;
