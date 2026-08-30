# This project

> Replace this file with a description of what this application is and does.

This is your project's **wrapper repository** — the root of the project, created by qits when the
project was created. It starts as a plain monorepo with all the code inline, and grows into a
polyrepository one directory at a time: a directory here can be extracted into its own repository
and re-attached in the same place as a submodule, without a big-bang migration.

## The layout

Everything lives under `components/`, grouped by the component it belongs to and never by the role
it plays:

    components/<component>/<repository>

A component is any cohesive unit of the product — it does not need a deployable. One component
directory holds the service, its frontend and its daemon side by side, so the three or four things
you change together are neighbours.

The **role** is carried by the repository's name rather than by its parent directory:
`<component>[-<modifier>]-<role>[-<tech>]`, with `-service`, `-daemon`, `-frontend`, `-cli`, `-oci`
and `-javalib`/`-jslib` as the roles. qits reads that suffix, so a name is enough to say what kind
of thing a repository is.

`components/README.md` is the whole grammar, and it travels with the project.

Start by putting code directly in the component directory that fits. Nothing has to become its own
repository until it earns it — that decision is meant to be deferred, not made on day one.

## Files

- `AGENTS.md` — the contract for coding agents working in this repository. `CLAUDE.md` is a symlink
  to it, so agents that look for either name find the same file.
- `.qits-config.yml` — this repository's qits configuration: its services, actions and bootstrap
  chain. It is read in-container per workspace from your branch's checkout, so editing it is an
  ordinary commit.
