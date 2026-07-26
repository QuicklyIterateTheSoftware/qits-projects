# `integrations/` — adapters toward other systems

Archetype: **`INTEGRATION`**

Code that faces *outward*: a client for a third-party API, an adapter that makes this application
usable from another one, a published SDK.

The distinction from `libs/` is direction, not size — a library is shared *within* the project, an
integration points *at another system*. Put the code directly here to start with
(`integrations/billing-api/`). Extracting this directory produces a repository with archetype
`INTEGRATION`, which is re-attached as a submodule at this same path.
