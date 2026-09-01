-- Whether a FAILED execution is worth retrying. The sweep used to retry every FAILED request
-- forever, which for a refusal that cannot heal -- ALREADY_INTEGRATED, a branch that is gone, a
-- malformed ask -- was an unbounded loop of doomed door calls (measured 2026-09-01: two proof
-- requests knocking every 30s). The executor classifies now: network failures, 5xx and the door's
-- own retryable 409s (NOT_FAST_FORWARD, VERSION_ALREADY_RELEASED) mark the failure retryable;
-- everything else waits for a re-arm, which is the revival a FAILED request keeps either way.
-- Default false: every existing FAILED row predates the classification and stops looping.
alter table release_request add column retryable boolean not null default false;
alter table release_request alter column retryable drop default;
