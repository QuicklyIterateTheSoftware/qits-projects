-- Refinement containers move into this service (epic refinement-improvements, part 2): a refining
-- workspace stops being a qits-workspaces row and becomes this table, keyed by the epic it refines.
--
-- The epic id is a KEY, not a relation — the epic lives in the epics module's own database and
-- Flyway lineage, so no foreign key can name it. project_id and repository_id are plain keys too,
-- deliberately: a refinement is torn down by an explicit discard (container, volume, credential,
-- branch, then this row), never by a cascade racing that teardown.
--
-- The commissioned credential is two nullable columns for the same reason agent_credential stores
-- a secret at all: qits-containers hashes a workload's whole spec, environment included, so a
-- resume must reproduce the pair byte for byte or every resume becomes a spec change that replaces
-- the container.
create table refinement
(
    id                         bigint generated always as identity primary key,
    epic_id                    text        not null unique,
    project_id                 text        not null,
    repository_id              text        not null,
    branch                     text        not null,
    parent                     text        not null,
    label                      text        not null,
    preamble                   text,
    commissioned_client_id     text,
    commissioned_client_secret text,
    created_at                 timestamptz not null,
    causation_id               uuid
);

comment on table refinement is 'One epic''s refinement container, hosted by this service.';
comment on column refinement.epic_id is 'The refined epic (epics module''s database) — a key, not a relation.';
comment on column refinement.causation_id is 'The platform event that caused this row, if one was in scope at persist.';

-- The prompt draft and its attachments, host-owned so they outlive the container. These two DO
-- cascade from the refinement row: a discard hard-deletes the refinement, and the draft and
-- attachments have no life of their own beyond it.
create table refinement_prompt_draft
(
    refinement_id_fk        bigint primary key references refinement (id) on delete cascade,
    content                 text        not null,
    serialized_prompt       text,
    prompt_version          bigint      not null default 0,
    last_run_at             timestamptz,
    last_run_prompt_version bigint,
    last_run_command_id     text,
    updated_at              timestamptz not null
);

create table refinement_prompt_attachment
(
    id               text        not null primary key,
    refinement_id_fk bigint      not null references refinement (id) on delete cascade,
    mime_type        text        not null,
    label            text        not null,
    source           text        not null,
    bytes            bytea       not null,
    created_at       timestamptz not null
);

create index ix_refinement_prompt_attachment_refinement
    on refinement_prompt_attachment (refinement_id_fk);
