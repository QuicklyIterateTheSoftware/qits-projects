-- Rename the "worktree" domain concept to "workspace" at the DB level, matching the entity rename
-- (Worktree -> Workspace and friends). Pure, lossless rename — H2 everywhere, so ALTER … RENAME
-- carries the data, FKs and indexes across. Enum-valued columns (status, runtime_status, type) hold
-- values like ACTIVE/RUNNING/CREATED that never contained "worktree", so they are untouched.
-- FK/index *constraint names* (e.g. FK_command_worktree, idx_daemon_event_worktree) are internal and
-- not part of the runtime contract, so they keep their old names — only the tables, columns and
-- sequences Hibernate derives at runtime are renamed here.

-- The aggregate table and its business-id column.
ALTER TABLE worktree ALTER COLUMN worktree_id RENAME TO workspace_id;
ALTER TABLE worktree RENAME TO workspace;

-- The history-timeline table and its FK column.
ALTER TABLE worktree_event ALTER COLUMN worktree_id_fk RENAME TO workspace_id_fk;
ALTER TABLE worktree_event RENAME TO workspace_event;

-- Foreign-key column on command pointing at the (now) workspace table.
ALTER TABLE command ALTER COLUMN worktree_id_fk RENAME TO workspace_id_fk;

-- Daemon-event denormalised workspace id.
ALTER TABLE daemon_event ALTER COLUMN worktree_id RENAME TO workspace_id;

-- Sequences: H2 has no ALTER SEQUENCE … RENAME, so recreate under the new name and carry the current
-- position across (RESTART WITH the old sequence's BASE_VALUE) before dropping the old one. Preserves
-- id continuity so no surrogate key can collide.
CREATE SEQUENCE workspace_SEQ START WITH 1 INCREMENT BY 50;
ALTER SEQUENCE workspace_SEQ RESTART WITH
  (SELECT base_value FROM information_schema.sequences WHERE sequence_name = 'WORKTREE_SEQ');
DROP SEQUENCE worktree_SEQ;

CREATE SEQUENCE workspace_event_SEQ START WITH 1 INCREMENT BY 50;
ALTER SEQUENCE workspace_event_SEQ RESTART WITH
  (SELECT base_value FROM information_schema.sequences WHERE sequence_name = 'WORKTREE_EVENT_SEQ');
DROP SEQUENCE worktree_event_SEQ;
