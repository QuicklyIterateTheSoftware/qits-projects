# `frontends/` — anything served to a user at a URL

Archetype: **`FRONTEND`**

One directory per thing a person opens in a browser: a single-page app, a server-rendered site, a
docs site. The role outlives the technology — an entry that stops being a SPA and becomes
server-rendered still belongs here, so nothing has to move.

Shared frontend code — a component library and the like — is a library, and belongs under `libs/`.
That is what keeps this directory from collecting everything merely written in JavaScript.

Put the code directly here to start with (`frontends/web/`). When a frontend earns its own
repository, extracting this directory produces a repository with archetype `FRONTEND`, which is
re-attached as a submodule at this same path.
