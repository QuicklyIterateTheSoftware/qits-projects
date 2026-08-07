# This project

> Replace this file with a description of what this application is and does.

This is your project's **wrapper repository** — the root of the project, created by qits when the
project was created. It starts as a plain monorepo with all the code inline, and grows into a
polyrepository one directory at a time: a directory here can be extracted into its own repository
and re-attached in the same place as a submodule, without a big-bang migration.

## The layout

Each top-level directory corresponds to one repository archetype. A directory's parent tells you
what kind of repository extracting it will produce, and where a repository of that kind is mounted
when it comes back as a submodule.

| Directory | Archetype | What belongs here |
|---|---|---|
| `services/` | `SERVICE` | Deployable components — the things that run in production. |
| `daemons/` | `DAEMON` | Long-running background agents nobody calls. |
| `libs/` | `LIBRARY` | Shared technical code consumed by the components. |
| `frontends/` | `FRONTEND` | Anything served to a user at a URL. |
| `cli/` | `CLI` | Command-line entry points. |
| `images/` | `IMAGE` | Build definitions for published OCI images. |

The directory an entry sits under is **authoritative**: `.gitmodules` is this project's
configuration, so moving a submodule between these directories is how you change what kind of
component it is.

Start by putting code directly in the directory that fits. Nothing has to become its own repository
until it earns it — that decision is meant to be deferred, not made on day one.

## Files

- `AGENTS.md` — the contract for coding agents working in this repository. `CLAUDE.md` is a symlink
  to it, so agents that look for either name find the same file.
- `.qits-config.yml` — this repository's qits configuration: its services, actions and bootstrap
  chain. It is read in-container per workspace from your branch's checkout, so editing it is an
  ordinary commit.
