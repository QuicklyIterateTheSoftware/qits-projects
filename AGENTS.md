# qits-projects — working notes

Read `README.md` first: it defines the boundary and lists the ports. This file is the working
conventions on top of it.

## The two rules that shape everything

**A clone of this repo alone builds and tests green** — no monorepo, no docker, no prior
`mvn install` elsewhere, no credentials. `./mvnw verify` is the gate. Anything that would break that
is not a tradeoff to weigh; it is the thing this repo exists to avoid.

That is why the poms duplicate versions instead of inheriting them, why the git fixtures are built
at test time instead of checked out as submodules, and why every reach into another context is an
optional port rather than a dependency.

**`service/` compiles to a GraalVM native image**, the same rule qits-workspace-daemon and
qits-gateway carry, and it extends the clone-alone rule rather than qualifying it: `.sdkmanrc` names
`25.0.2-graalce`, so `sdk env` gives you a `native-image` and `./mvnw package -Dnative` produces
`service/target/qits-projects` with no container involved.

Two consequences worth stating before you reach for a dependency:

- **A missing GraalVM does not fail the build.** Quarkus logs `Cannot find the native-image …
  Attempting to fall back to container build` and shells docker with a 1.8 GB Mandrel image. Green
  either way, so the fallback is easy to be in without noticing — recognise it by the image pull.
- **Every dependency is a decision about what the builder has to be told.** Reflection, dynamic
  proxies, `ServiceLoader`, resource loading by computed name and JNI/JNA all need registering, and
  the failure lands at *runtime* in the binary while the JVM suite stays green. Prefer what is
  already in the image — `ProcessBuilder` over a process library, `java.lang.foreign` over JNA.

That second point is not hypothetical here: it is why the sign-in terminal no longer uses pty4j.
pty4j is JNA plus per-platform `.so`s unpacked from its own jar at runtime, none of which the image
builder can see; `ForeignPty` is six libc calls through `java.lang.foreign`, with git put on the
slave device by `setsid --ctty` — so `setsid` (util-linux) is now a host requirement alongside git.

**FFM is not free of registration either, whatever you may have read.** GraalVM 25 registers zero
downcall stubs on its own — hoisting the `FunctionDescriptor`s into `static final` constants does
not help, and neither does building them inline; both were measured and both report `0 downcalls …
registered for foreign access`. The build then *fails*, parsing `ForeignPty.open` with `unexpected
input could not be handled: linkToNative`. What makes it work is two things that must both be
there and that nothing else substitutes for:

- `domain/src/main/resources/META-INF/native-image/eu.wohlben.qits/qits-projects-domain/reachability-metadata.json`
  — one entry per **distinct** descriptor, in canonical layout names, `firstVariadicArg` included
  for `ioctl`. Change a signature in `ForeignPty` and this file changes with it.
- `quarkus.native.additional-build-args=--enable-native-access=ALL-UNNAMED` in the service's
  `application.properties`, which permits the restricted calls. Without it the binary still works
  but warns on every sign-in, and the warning says it will become a refusal.

## Package and module conventions

`eu.wohlben.qits.projects.*` across `domain/` and `service/`, with disjoint sub-packages so there is
no split package, plus `eu.wohlben.qits.epics.*` in `epics/`:

- `domain/` — `entity`, `persistence`, `dto`, `mapper`, `control`, `error`, `validation`.
  Framework-free in the sense that matters: no JAX-RS, no websockets. Entities are Panache
  active-record with public fields; mappers are MapStruct `@Mapper(componentModel = "jakarta")`.
- `service/` — `api` (JAX-RS + the remote-login websocket), `mcp` (the `repository` MCP server),
  `startup`, `notify` (the outbound fire-and-forget notifiers — the sole implementations of the
  creation ports; the same package name and the same idiom as qits-ci's `ci.notify`), `wiring` (the
  git host's lifecycle client — `HttpGitHostRepositories`, a `java.net.http.HttpClient` as an
  instance field like `notify`'s, but named apart from it: `notify` is for fire-and-forget and a
  repository create is waited on and can fail the caller's request).
- `epics/` — untouched by the extraction beyond its `<parent>`: its own package, its own error
  types, its own datasource and its own Flyway lineage. It depends on neither `domain` nor any
  auth module, and it should stay that way — it is the module most likely to be lifted out next.

`control/` is flat. The monorepo split this code across `domain.project.*`, `domain.repository.*`
and `domain.seeding.*` to break cycles that do not exist here.

## Paths

Everything is served under this service's gateway segment — see the table in the README. Three
things about that are easy to get wrong:

- **`@WebSocket` does not follow `quarkus.rest.path`.** The remote-login socket spells
  `/projects/api/...` out as a literal and has to be kept in step with the key by hand. Anything new
  registered straight onto the Vert.x router is the same.
- **The MCP server's name is not its path.** It is mounted at `/projects/mcp` and is still *named*
  `repository` (`@McpServer("repository")`), because qits-workspace-daemon addresses it by name.
  Renaming it breaks the daemon; an *undeclared* name stops the process booting outright.
- **A new machine surface outside `/projects/api` needs a line in
  `quarkus.quinoa.ignored-path-prefixes`.** Quinoa's SPA fallback is a catch-all at `/projects/*`
  registered near-last, so a real route still wins — but a path matching *no* route is rerouted to
  `index.html` and answers `200 text/html`, which a machine client parses as data. The key is set
  explicitly (`/api,/q,/mcp`) rather than derived, because the derivation reads only
  `quarkus.rest.path` and `quarkus.http.non-application-root-path` and nothing names the MCP
  root-path. Setting it **replaces** the derivation, so `/api` and `/q` are repeated by hand, and
  the values are matched **after** `ui-root-path` is stripped — `/projects/mcp` written there
  matches nothing at all and is indistinguishable from leaving the key unset. The remote-login
  websocket needs no entry only because its literal sits under `/projects/api`.

Path parameters naming a repository are `{repoId}` everywhere. Parameter names are visible in the
generated client, so keep them uniform. Note the two reconciles under a project are deliberately
different routes: `POST /{projectId}/reconcile` re-asserts the dns record, and
`POST /{projectId}/repositories/reconcile` reconciles the project's repositories against its
wrapper.

## Adding a dependency on another context

Don't. Declare a port in `domain/…/projects/control/`, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README. Every port here
is optional; if you ever add a mandatory one, say why in its javadoc, the way qits-workspaces'
`RepositoryLookup` does.

Never add a JPA relation to another context's entity. `Project ↔ Repository ↔ repository_name` are
real relations with real foreign keys because those tables are in **this** database. Anything else
is a string id through a port. (`repository_submodule` was the fourth such table; the wrapper's
`.gitmodules` is the submodule graph now, and V4 dropped it.)

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
gateway is the only place a login happens, and it fixes its variant at **build** time
(`-Dqits.variant`), so no env var and no properties file can put an open mechanism back under a
gateway built as `oauth`. `X-Qits-*` is that gateway's reserved namespace: the whole prefix is
stripped from every inbound request unconditionally, which is the entire reason a header can be
trusted as an identity here.

The identity exists to name `changed_by`; `EpicsAuditIdentityTest` is what pins that, and it uses
the real header rather than `@TestSecurity` on purpose. The header **is** the contract under test —
nothing else ever produces a principal in a deployed service — so `@TestSecurity` would install an
identity without going through the mechanism and prove a path the deployment never takes. That is
also how the bug ran unseen: this repo shipped `SecurityIdentity` with no mechanism behind it,
`changed_by` was null on every row, and an annotation that fabricates an identity would have gone on
passing the whole time.

Do not lift `projects/security` into a shared `libs/qits-auth`. Every repo builds from a clone of
itself alone, so ~115 lines duplicated per service is cheaper than a jar that has to travel to all of
them; the duplication is the decision, not an oversight.

## The wrapper is the project

A project's wrapper repository (`PROJECT` archetype, named `<slug>-<slug>`) carries the project's
configuration in its `.gitmodules`: one submodule per component, under the directory its archetype
names, with a relative url. Three rules follow, and every one of them is enforced in code:

- **The directory is the archetype.** `RepositoryArchetype.fromDirectory` is the derivation and the
  reconcile applies it — moving a submodule between directories is how a component changes kind.
- **A repository the wrapper does not name is not part of the project.** Write paths refuse it
  (`requireWrapperMembership`) and the reconcile deregisters its row, keeping its history on the git
  host so re-adding the entry re-adopts it.
- **An empty `.gitmodules` is not a manifest.** A wrapper declaring no submodules enforces nothing
  and deregisters nothing. Without that, shipping either rule would have bricked every project that
  had not adopted the model yet.

`WrapperSubmoduleWriter` is the only writer of that file and `WrapperGitmodules` the only editor —
textual, one section at a time, every other byte where it was, because this is a file people review.

## Schema changes

`domain/src/main/resources/db/projects/migration/`, hand-written, its own lineage on its own
datasource. Never touch the monorepo's `db/migration` — that is a different database. Epics has its
own lineage under `epics/src/main/resources/db/epics/migration/`; the two never mix.

Entities live in a **named** persistence unit (`projects`), not the default one. An `EntityManager`
injection therefore needs `@PersistenceUnit("projects")`.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus reads main's copy during a test run and merges the test resources over it,
  so `quarkus.rest.path`, the MCP root-path and the rest are already in effect. Never re-declare
  them in `src/test/resources/application.properties`: a test copy is free to drift from the shipped
  one, and then a green suite proves nothing about what actually starts. That file is for genuine
  test-only overrides (in-memory H2, `clean-at-start`, `target/` paths, the test-jar index entry).
- `OpenApiSchemaExportTest` writes `docs/openapi.yml`. Regenerate and commit whenever the REST
  surface changes:

      ./mvnw -pl service -am test -Dtest=OpenApiSchemaExportTest -Dsurefire.failIfNoSpecifiedTests=false

  Both extra flags are load-bearing on a fresh clone, which is the only state this repo promises:
  `-am` because `domain` and `epics` are 1.0.0-SNAPSHOTs published nowhere, so `-pl service` alone
  cannot resolve them, and `failIfNoSpecifiedTests=false` because `-am` then walks those two modules,
  which have no test by that name. (A plain `./mvnw verify` regenerates it too — the export is a test.)
  Note also that renaming a class the document names needs a `clean`: a stale nested-record `.class`
  in `target/` fails augmentation with `disagree on InnerClasses attribute`, which reads like a
  dependency conflict and is not one. This is the largest
  published surface of the six services and the one a client is generated from, so the diff is the
  review. It runs as a `@QuarkusTest` and indexes the test classpath, so any `@Path` resource under
  `src/test` lands in the document unless it is `@Operation(hidden = true)` — hence the annotation
  on `IdentityEchoResource`.
- **`mvn verify` passing does not mean the app starts.** Augmentation runs per `@QuarkusTest`
  regardless of packaging, so a missing `quarkus-maven-plugin` goal is invisible to the suite — it
  was in fact missed here once, an `<executions>` block under a `<build>` whose `<testResources>`
  came first, and only a boot caught it. `<packaging>quarkus</packaging>` is what closed that hole:
  it binds the goals to the lifecycle, and removing `<extensions>true</extensions>` now fails with
  "Unknown packaging: quarkus" rather than quietly building nothing. After touching
  `service/pom.xml`, still boot it and hit a route — and boot the **binary**, since a native-only
  failure is invisible to every JVM run:

      java --enable-native-access=ALL-UNNAMED -jar service/target/quarkus-app/quarkus-run.jar
      ./mvnw package -Dnative && ./service/target/qits-projects
- **`PackagedSurfaceIT` is the only test that runs against the artifact.** Every `@QuarkusTest`
  augments in the build JVM, with the whole classpath present, reflection unrestricted and the test
  profile's in-memory H2 — a native image has none of those, and three real defects here were
  invisible to all 168 of them and fatal to the binary (H2's `AUTO_SERVER`, the missing
  `project-template/` resources, `RepositoryMetadata` unregistered for reflection). It runs under
  `-Dnative`, and `-DskipITs=false` runs it against the fast-jar. It launches a real process, so it
  reads main's `application.properties` and none of the test overrides: config reaches it through
  `quarkus.test.arg-line`, which is why the test resources carry that key.
- **Anything read off the classpath by walking is a native-image question.** `ProjectTemplate` is
  the one such reader — it serves both committed skeletons, `project-template/` (a wrapper's first
  commit) and `repository-template/` (a blank component's) — and it handles three URI schemes:
  `file:` (tests), `jar:` (fast-jar) and `resource:` (native). Quarkus' own `ClassPathUtils.consumeAsPaths` handles the first two and
  rejects the third, which is how the binary came to fail every project creation while the suite was
  green. Anything new that enumerates a resource directory needs the same three, plus an entry in
  `quarkus.native.resources.includes` — a native image carries no resource it was not told about.
- **The sign-in terminal is the one thing a native build can break silently.** `ForeignPty`'s
  downcalls are the only native access in the process. `ForeignPtyTest` and `RemoteLoginSessionTest`
  drive real pseudo-terminals — including a prompt on `/dev/tty`, which is what git actually reads
  credentials from — and both are `@EnabledOnOs(OS.LINUX)`. `domain`'s surefire block passes
  `--enable-native-access=ALL-UNNAMED` for them; a JVM-mode `quarkus-run.jar` needs it on the
  command line, and the binary needs nothing.
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
  through domain's test-jar rather than carrying a second copy. A fake for a port whose method
  **returns a result** must be `@Alternative @Priority` (`RecordingProjectDomainRegistrar`): in
  `service`'s suite the real registrar in `notify/` is a bean too, so without it the port has two
  implementations and the caller reports whichever the container hands it first. A recording fake for a `void` port does not need this, which is why the older two
  do not have it.
- Where a monorepo assertion queried another context's table, it is re-expressed against the seam —
  "did this context ask?" rather than "did the other context's row appear". `RecordingWorkspaceLifecycle`
  exists for exactly that.
- Integration tests (`*IT`) that need real docker default to skipped (`skipITs` in the parent pom).

## What is deliberately absent

Grep for `SEAM (migration-plan.md` to find every place this repo cut something rather than carrying
it. Each one names what was removed and where it belongs. Do not "restore" any of them here — they
are another context's code, and the monorepo still has every line.

**The metadata sidecar is gone, not migrated.** `MetadataService`, `RepositoryDiscoveryService` and
`RepositoryMetadata` used to write `<data-dir>/<repoId>/metadata/repository.json` beside every bare
origin and restore a row's `url`/`archetype` from it at every boot. `url` and `archetype` are
columns on `Repository`, in this service's own database, in the same transaction that writes them —
the sidecar could only ever undo a row change, which is why `attachBackupRemote` and
`ProjectService.adoptWrapperRepository`'s promotion arm used to have to rewrite it. Decoupling from
the shared `qits-repositories` volume (projects-volume-decoupling-plan.md §1.4, BQ) removed the one
scenario the sidecar served ("database wiped, volume kept") along with the volume itself. Do not
bring any of the three back.
