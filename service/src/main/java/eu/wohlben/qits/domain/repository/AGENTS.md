# Domain: Repository

The Repository domain manages git remotes as first-class entities and provides access to isolated worktrees for concurrent workflow execution.

## Purpose

- Register a git remote under a stable, caller-defined name so that workflow definitions can refer to it predictably.
- Maintain a local bare mirror (`origin`) that acts as the canonical copy.
- Create named worktrees — isolated checkouts derived from the bare mirror — where workflow steps, reviews, or experiments can run without interfering with each other.
- Execute git operations (clone, pull, push, merge, discard) via the local `git` CLI against this managed layout.

## Core Concepts

### Repository

A named reference to a git remote. The ID is supplied by the caller (e.g. `my-service`, `template-go`) rather than auto-generated, so that downstream systems can reference it directly in workflow definitions and configurations.

A repository has an **archetype** that signals its intended role in the broader system:
- `SERVICE` — a deployable service repository.
- `SERVICE_TEMPLATE` — a scaffolding template from which new services are generated.
- `FORK` — a downstream fork, typically used for contribution or patch workflows.

### Worktree

An isolated checkout linked to a repository's bare mirror. Worktrees are created on demand and are also given caller-defined IDs scoped to their parent repository (e.g. `main`, `review`, `step-01`). This allows orchestration logic to refer to them by deterministic names.

A worktree may optionally declare a **parent** worktree, establishing a lineage within the same repository. This hierarchy can later drive merge order, visualisation, or cleanup policies.

### On-Disk Layout

The domain owns a predictable directory structure under a configurable root (`data/repositories/` by default):

```
$repo_id/
  origin/                 ← bare mirror; all fetch/pull/push operations target this
  worktrees/
    $worktree_id/         ← isolated checkout for doing work
```

`origin` is the single source of truth. Worktrees are lightweight because they share object storage with the bare mirror.

## Domain Operations

Operations are modelled as RPC-style actions against a repository or one of its worktrees:

- **Clone** — initialise the local mirror from the remote and register the repository.
- **Pull** — update the bare mirror from the remote.
- **Push** — propagate local refs from the bare mirror to the remote.
- **Create Worktree** — add a new isolated checkout at a given branch.
- **Merge** — integrate one worktree's branch into another branch (or another worktree's branch).
- **Discard** — tear down a worktree, remove its branch from the bare mirror, and delete the entity.

## Design Decisions

1. **Caller-defined names** — Both repository and worktree IDs are supplied by the caller, not generated. This makes workflow definitions stable and readable.
2. **Bare mirror + worktrees** — Rather than multiple full clones, a single bare mirror serves all worktrees. This saves disk space and keeps fetch/pull centralised.
3. **No worktree archetype** — Lifecycle classification (feature, bugfix, epic, etc.) was deliberately omitted from the Worktree entity. It will be introduced differently if needed.
4. **Explicit fetch only** — The bare mirror is only updated on explicit `pull`. Worktree operations do not auto-fetch.
5. **Default branch: `master`** — When a branch is not specified, `master` is assumed.
