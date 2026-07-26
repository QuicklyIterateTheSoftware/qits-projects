# `libs/` — shared technical code

Archetype: **`LIBRARY`**

Code shared across this project's components and consumed by them: a common client, a schema
module, shared utilities. A library is not deployed on its own — it is depended on.

Put the code directly here to start with (`libs/schema/`). When a library earns its own repository,
extracting this directory produces a repository with archetype `LIBRARY`, which is re-attached as a
submodule at this same path.
