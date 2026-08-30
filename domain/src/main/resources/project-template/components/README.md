# `components/` — one directory per component

Every part of this project lives here, grouped by the component it belongs to and never by the role
it plays:

    components/<component>/<repository>

A **component** is any cohesive unit of the product. It does not need a deployable: a component may
be one service, or a service with its frontend and its daemon beside it, or two libraries and
nothing else.

The component directory name says what the thing *is*, never how it is built — `payments`, not
`payments-postgres`. The implementation may change; the component does not.

## Names say the role

The repository inside a component carries the role in its name:

    <component>[-<modifier>]-<role>[-<tech>]

| Role suffix | What it is |
|---|---|
| `-service` | A deployable component — the things that run in production. |
| `-daemon` | A long-running background agent nobody calls. |
| `-frontend` | Anything served to a user at a URL. |
| `-cli` | A command-line entry point. |
| `-oci` | A build definition consumed through its published OCI image. |
| `-javalib` / `-jslib` | Shared technical code consumed by the components, never deployed on its own. |

So `components/payments/payments-service` sits next to `components/payments/payments-frontend`, and
the two things you change together are two lines apart instead of two directories apart.

The **name** is what says the kind here, and qits reads it: creating a repository called
`payments-daemon` needs no archetype stated, and renaming one to carry a role suffix restamps the
kind. A name with no role suffix is allowed — qits simply has nothing to derive from it, so state
the kind when you create it.

Add the tech suffix only where the role alone is ambiguous, which in practice is the library pair.

## Start inline

Put the code directly under the component (`components/payments/payments-service/`). Nothing has to
become its own repository until it earns it — that decision is meant to be deferred, not made on day
one. When a directory does earn one, extracting it and re-attaching it as a submodule at the same
path changes nothing about where it sits.
