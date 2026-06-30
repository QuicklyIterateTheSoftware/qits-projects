-- Soft-delete worktrees: cleanup/discard now removes the on-disk worktree and branch but keeps the
-- row as a persistent record. The row gains a resolution status, markdown preamble/result, and a
-- resolved-at timestamp; a new worktree_event table holds the history timeline.

alter table worktree add column status varchar(255) default 'ACTIVE' not null
  check ((status in ('ACTIVE','INTEGRATED','ABANDONED')));
alter table worktree add column preamble clob;
alter table worktree add column result clob;
alter table worktree add column resolved_at timestamp(6) with time zone;
alter table worktree add column created_at timestamp(6) with time zone;

-- Resolved rows accumulate and worktree ids may be reused, so the unconditional uniqueness on
-- (repository_id, worktree_id) must go; the service enforces at most one ACTIVE worktree per id.
-- H2 named V1's inline `unique (repository_id, worktree_id)` constraint CONSTRAINT_30 (deterministic
-- from the V1 migration order on the pinned H2 version).
alter table worktree drop constraint if exists CONSTRAINT_30;

create sequence worktree_event_SEQ start with 1 increment by 50;

create table worktree_event (
  id bigint not null,
  worktree_id_fk bigint not null,
  type varchar(255) not null
    check ((type in ('CREATED','MERGED','UPDATED_FROM_PARENT','INTEGRATED','ABANDONED'))),
  branch varchar(255),
  parent varchar(255),
  target varchar(255),
  commit_hash varchar(255),
  note varchar(2000),
  at timestamp(6) with time zone not null,
  primary key (id)
);

alter table if exists worktree_event
  add constraint FK_worktree_event_worktree
  foreign key (worktree_id_fk) references worktree on delete cascade;

-- Deleting a repository cascade-deletes its worktrees (JPA), so the rows hanging off those worktrees
-- — commands, command logs and worktree events — must cascade at the DB level too. (Worktrees
-- themselves are only ever soft-deleted; this cascade fires solely on full repository deletion.)
alter table command drop constraint if exists FK_command_worktree;
alter table command add constraint FK_command_worktree
  foreign key (worktree_id_fk) references worktree on delete cascade;

alter table command_log_line drop constraint if exists FK_command_log_line_command;
alter table command_log_line add constraint FK_command_log_line_command
  foreign key (command_id) references command on delete cascade;
