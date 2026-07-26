# `services/` — deployable components

Archetype: **`SERVICE`**

One directory per deployable component: the things that actually run in production. A backend API,
a worker, a scheduled job.

Put the code directly here to start with (`services/checkout/`). When a component earns its own
repository, extracting this directory produces a repository with archetype `SERVICE`, which is
re-attached as a submodule at this same path.
