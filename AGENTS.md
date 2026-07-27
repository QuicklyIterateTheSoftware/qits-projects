# qits-projects — working notes

Read `README.md` first: it defines the boundary and lists the ports. This file is the working
conventions on top of it.

## The one rule that shapes everything

This repo must build and test green from a **clone of itself alone** — no monorepo, no docker, no
prior `mvn install` elsewhere, no credentials. `./mvnw verify` is the gate. Anything that would
break that is not a tradeoff to weigh; it is the thing this repo exists to avoid.

That is why the poms duplicate versions instead of inheriting them, why the git fixtures are built
at test time instead of checked out as submodules, and why every reach into another context is an
optional port rather than a dependency.

## Package and module conventions

`eu.wohlben.qits.projects.*` across `domain/` and `service/`, with disjoint sub-packages so there is
no split package, plus `eu.wohlben.qits.epics.*` in `epics/`:

- `domain/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`, `validation`.
  Framework-free in the sense that matters: no JAX-RS, no websockets. Entities are Panache
  active-record with public fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (JAX-RS + the remote-login websocket), `mcp` (the `repository` MCP server),
  `startup`.
- `epics/` — untouched by the extraction beyond its `<parent>`: its own package, its own error
  types, its own datasource and its own Flyway lineage. It depends on neither `domain` nor any
  auth module, and it should stay that way — it is the module most likely to be lifted out next.

`control/` is flat. The monorepo split this code across `domain.project.*`, `domain.repository.*`
and `domain.seeding.*` to break cycles that do not exist here.

## Adding a dependency on another context

Don't. Declare a port in `domain/…/projects/control/`, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README. Every port here
is optional; if you ever add a mandatory one, say why in its javadoc, the way qits-workspaces'
`RepositoryLookup` does.

Never add a JPA relation to another context's entity. `Project ↔ Repository ↔ repository_name ↔
repository_submodule` are real relations with real foreign keys because all four tables are in
**this** database. Anything else is a string id through a port.

Prefer widening an existing port over adding a synchronous call into another context. Where a port
call is a genuine ordering precondition — `createMainWorkspace` before a clone returns,
`releaseRepository` before a repository row goes — say so at the declaration, as
`WorkspaceLifecycle` does.

## Authentication

Authentication happens at `qits-gateway`. This service resolves a principal from a trusted header
(`X-Qits-User`, read by `projects/security/ForwardAuthMechanism`) and authenticates nothing.

**`identity.isAnonymous()` is not a security state** — it means "no name for the audit row". A check
of the form `if (identity.isAnonymous()) deny` would look like a security control and be worth
nothing, because reaching this service at all already implies you are inside the trusted network.

There is no auth variant to select and no authorization policy here, and roles are deliberately not
resolved — the single role check the system has (`qits.auth.required-role`) is the gateway's. The
identity exists to name `changed_by`; `EpicsAuditIdentityTest` is what pins that, and it uses the
real header rather than `@TestSecurity` on purpose. See `migration-auth-plan.md`.

## Schema changes

`domain/src/main/resources/db/projects/migration/`, hand-written, its own lineage on its own
datasource. Never touch the monorepo's `db/migration` — that is a different database. Epics has its
own lineage under `epics/src/main/resources/db/epics/migration/`; the two never mix.

Entities live in a **named** persistence unit (`projects`), not the default one. An `EntityManager`
injection therefore needs `@PersistenceUnit("projects")`.

## Tests

- `service/src/test/resources/application.properties` is **no longer the only copy** of
  `quarkus.rest.path` and the MCP root-path — `src/main/resources/application.properties` carries
  them for the packaged process. Change one and you must change both; a suite green because the
  *test* copy is right proves nothing about what ships.
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit whenever the REST
  surface changes: `./mvnw -pl service test -Dtest=OpenApiSchemaExportTest`. This is the largest
  published surface of the six services and the one a client is generated from, so the diff is the
  review. It runs as a `@QuarkusTest` and indexes the test classpath, so any `@Path` resource under
  `src/test` lands in the document unless it is `@Operation(hidden = true)` — hence the annotation
  on `IdentityEchoResource`.
- **`mvn verify` passing does not mean the app starts.** Augmentation runs per `@QuarkusTest`
  regardless of packaging, so a missing `quarkus-maven-plugin` execution is invisible to the suite —
  it was in fact missed here once and only a boot caught it. After touching `service/pom.xml`, run
  `java -jar service/target/quarkus-app/quarkus-run.jar` and hit a route.
- A `Failed to start quarkus` / `Port already bound: 8081` failure is the known flake
  (`migration-plan.md` §9 item 14) — `@QuarkusTest` restarts racing for the test port. Re-run first.
- `GitFixtures.path("<name>.git")` is how a test gets a git origin to clone. It returns the
  committed bare fixtures (`submodule-*.git`) as they are and builds the derived ones
  (`testing-repo.git`, `demo-demo.git`, `qits-qits.git`) on first use. Never reintroduce the
  monorepo's `derive-fixture-bares` antrun step: it needs submodules a fresh clone has not got.
- `domain/src/test/java/…/testsupport/` holds the port implementations the suite runs against —
  `InMemoryProcessRegistry` (the monorepo's technical-process framework, vendored) and
  `RecordingWorkspaceLifecycle`. They are **test scope only**; nothing under `src/main` references
  them, and the published jars ship no implementation of any port. `service`'s suite reuses them
  through domain's test-jar rather than carrying a second copy.
- Where a monorepo assertion queried another context's table, it is re-expressed against the seam —
  "did this context ask?" rather than "did the other context's row appear". `RecordingWorkspaceLifecycle`
  exists for exactly that.
- `service/src/test/resources/application.properties` has to re-provide `quarkus.rest.path=/api`
  and `quarkus.mcp.server.repository.http.root-path`: both are a consuming application's settings,
  and without them every REST test 404s and the MCP server refuses to start.
- Integration tests (`*IT`) that need real docker default to skipped (`skipITs` in the parent pom).

## What is deliberately absent

Grep for `SEAM (migration-plan.md` to find every place this repo cut something rather than carrying
it. Each one names what was removed and where it belongs. Do not "restore" any of them here — they
are another context's code, and the monorepo still has every line.
