# `apps/` — end-user-facing applications

Archetype: **`APPLICATION`**

What a person actually opens: a single-page app, a mobile client, a CLI. An app is usually a
consumer of the components under `services/`, not a deployable component in its own right.

Put the code directly here to start with (`apps/web/`). When an app earns its own repository,
extracting this directory produces a repository with archetype `APPLICATION`, which is re-attached
as a submodule at this same path.
