-- Frozen HTML designs kept per refinement: a self-contained document of one page, styles inline,
-- that the refining route's Design tab shows beside the epic being drafted. A row is either what
-- the person sees (ACTIVE) or an agent's proposed revision of it (PROPOSED).
--
-- Cascades from refinement, like the prompt draft and attachments in V4 and for the same reason: a
-- design has no life of its own once the refinement is discarded.
--
-- The epic is NOT named here. The refinement already keys the epic, and a second copy of that key
-- could disagree with it — a design belongs to the container's row, never to the plan beside it.
--
-- based_on_design_id points at the design a proposal revises. It is a self reference with ON DELETE
-- SET NULL rather than CASCADE: deleting the original must leave the proposal readable, with
-- nothing left to replace, not silently take it away with it.
--
-- There is no content route serving these bytes and there must not be one — agent-authored HTML
-- served same-origin would be an XSS door. The SPA renders it in a sandboxed iframe with no
-- scripts, so the document only ever travels as a JSON field.
create table refinement_design
(
    id                  text        not null primary key,
    refinement_id_fk    bigint      not null references refinement (id) on delete cascade,
    title               text        not null,
    status              text        not null check (status in ('ACTIVE', 'PROPOSED')),
    based_on_design_id  text references refinement_design (id) on delete set null,
    note                text,
    source_route        text,
    html                text        not null,
    html_bytes          integer     not null,
    truncated           boolean     not null default false,
    created_by          text        not null,
    created_at          timestamptz not null,
    updated_at          timestamptz not null,
    causation_id        uuid
);

create index ix_refinement_design_refinement on refinement_design (refinement_id_fk);

comment on table refinement_design is 'Frozen HTML designs of one refinement; ACTIVE is on show, PROPOSED awaits a person''s decision.';
comment on column refinement_design.html is 'A whole self-contained document with inline styles — never served as a page.';
comment on column refinement_design.note is 'The agent''s rationale on a proposal; null once it is ACTIVE.';
comment on column refinement_design.causation_id is 'The platform event that caused this row, if one was in scope at persist.';
