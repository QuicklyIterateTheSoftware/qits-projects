# `daemons/` — long-running background agents

Archetype: **`DAEMON`**

Processes that run continuously without serving a public URL: a queue worker, a scheduler, an agent
that watches something and acts on it. A daemon is deployed like a service; what separates it is
that nobody calls it.

Put the code directly here to start with (`daemons/mailer/`). When a daemon earns its own
repository, extracting this directory produces a repository with archetype `DAEMON`, which is
re-attached as a submodule at this same path.
