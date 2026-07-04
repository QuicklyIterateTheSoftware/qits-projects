-- Workspace containers: a worktree's checkout now lives inside its per-worktree container, so the
-- branch it owns can no longer be read from a host checkout (`git branch --show-current`). It
-- becomes a stored column. Nullable: pre-existing rows have no reliable branch to backfill (this
-- is a prototype — resolve or reseed), and the main-branch worktree may carry it too.
ALTER TABLE worktree ADD COLUMN branch VARCHAR(255);
