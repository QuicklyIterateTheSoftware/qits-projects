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
  `startup`, `wiring` (the git host's lifecycle client — `HttpGitHostRepositories`, a
  `java.net.http.HttpClient` as an instance field). There is **no `notify` package any more**: its
  one class implemented `ProjectDomainRegistrar` against qits-platform-dns, that service is gone from
  the platform, and the port is an unimplemented hook now — so this repo implements no creation port
  at all. Bring the package name back (qits-ci's `ci.notify` idiom) if a fire-and-forget notifier
  ever returns; `wiring` is deliberately not it, because a repository create is waited on and can
  fail the caller's request.
- `service/…/containershost/` — the orchestrator client: the `ContainerRuntime` implementation, the
  producer that gives the jar its bean and its bearer, and the native-image registration. It is an
  *adapter* like `wiring` and `bus` are — the seam is `agenthost/ContainerRuntime`, and
  what lives here is only what a deployable owes a plain jar. See "The container orchestrator".
- `service/…/idphost/` — qits-idp's commission API, the same adapter shape one directory over: the
  seam is `agenthost/AgentCredentials` and the whole of what lives here is one `@DefaultBean` HTTP
  client. See "The commissioned credential".
- `epics/` — untouched by the extraction beyond its `<parent>`: its own package, its own error
  types, its own datasource, its own physical database (`qits_epics`) and its own Flyway lineage. It
  depends on neither `domain` nor any auth module, and it should stay that way — it is the module
  most likely to be lifted out next, and lifting it out now moves a database rather than tables out
  of somebody else's.

`control/` is flat. The monorepo split this code across `domain.project.*`, `domain.repository.*`
and `domain.seeding.*` to break cycles that do not exist here.

## Paths

**The client is served at `/`** on this service's own host (`projects.<env>.<domain>`); every
machine surface keeps the segment `/projects` — see the table in the README. Three things about
that are easy to get wrong:

- **`@WebSocket` does not follow `quarkus.rest.path`.** The remote-login socket spells
  `/projects/api/...` out as a literal and has to be kept in step with the key by hand. Anything new
  registered straight onto the Vert.x router is the same.
- **The MCP server's name is not its path.** It is mounted at `/projects/mcp` and is still *named*
  `repository` (`@McpServer("repository")`), because qits-workspace-daemon addresses it by name.
  Renaming it breaks the daemon; an *undeclared* name stops the process booting outright.
- **A machine surface outside `/projects` needs a line in
  `quarkus.quinoa.ignored-path-prefixes`.** Quinoa's SPA fallback is a catch-all at `/*` now, so a
  real route still wins — but a path matching *no* route is rerouted to `index.html` and answers
  `200 text/html`, which a machine client parses as data. The values are **absolute request paths**
  since `ui-root-path` became `/` (they used to be tails relative to it, which is why the old list
  read `/api,/q,/mcp,/daemon,/container`), so the whole list is one prefix: `/projects` covers
  `/projects/api`, `/projects/q`, `/projects/mcp` and both daemon harnesses. This service owns no
  route outside that segment; one that did — `/v2`, `/git` — would list it beside. Setting the key
  **replaces** Quinoa's derivation rather than extending it, and the derivation never named the MCP
  root-path or the harnesses' raw routes anyway.

Path parameters naming a repository are `{repoId}` everywhere. Parameter names are visible in the
generated client, so keep them uniform. Note the two reconciles under a project are deliberately
different routes: `POST /{projectId}/reconcile` re-asserts the dns record, and
`POST /{projectId}/repositories/reconcile` reconciles the project's repositories against its
wrapper.

## The event bus

`service/…/bus/` is the whole of the bus's **SEAMS**, and they are **consume-only**: this service
publishes nothing. The machinery is the published `qits-eventstream` jar; the vocabulary is
`qits-githost-events`, the git host's four records. Its rules live in that library's own repository
and are not restated here.

**The word is SEAMS now, not "the bus", and the narrowing was deliberate (2026-08-10).** The
eventstream jar also carries the platform's causation *persistence vocabulary* — `CausedRow`,
`CausationStamp`, `@Uncaused`, three jakarta-persistence-shaped types with no publish, no subscribe
and no wire in them — and six entities implement it, so the jar sits in `domain`'s and `epics`' poms
now. That is not a crack in `epics`' "depends on `domain` and on `auth/*` not at all": a lift-out of
that module takes three annotations along in one jar and no CDI graph. What the rule still forbids
is control flow — no listener, no publisher, no `EventFrame`, no `QitsEventBus` outside
`service/…/bus/`. What the dependency costs is honest and paid in the suites: the jar's persistence
unit boots in both modules' tests too, so each `testdb/*EmbeddedPgConfigSource` feeds it a database
of its own, the test properties keep the bus dark, and — the surprise — both modules now open an
HTTP server they never wanted, because vertx-http rides in with the jar. That is what
`quarkus.http.test-port=0` is doing in two more properties files; without it the suites die on
`Port already bound: 8081`, which is the platform's own npm registry and not a code failure.

- **`ScmBackupTriggerListener` is a `QitsDurableEventListener`, and durable is the point.** It
  replaced `api/GitHostEventController`, a fire-and-forget `POST /projects/api/events/post-receive`
  the git host made from inside somebody's `git push` — so a push landing while this process was
  restarting cost a backup with nothing to say so. Now the claim and the schedule commit together
  and a disconnect window is caught up from the log.
- **All four events map to one call, and `suppressCi` is ignored.** `SCMPublishCommit`,
  `SCMPublishTag`, `SCMDeleteBranch` and `SCMDeleteTag` each say "the refs in this repository are
  not what the twin holds". Tags and deletions **never used to trigger a backup at all** — the old
  hook fanned out branch updates only — so that is a fix rather than a translation. `suppressCi`
  says whether a *build* should run, which is qits-ci's question; the push that sets it is an
  imported upstream's whole history, which is exactly the push that most needs a twin.
- **`consumerId()` is `projects-backup-push` and it is STORAGE.** It names every `consumed_event`
  row and the watermark. Change it and you mint a brand-new consumer that initializes at the head of
  the log, silently skipping everything in between.
- **Nothing here throws.** `onPush` returns immediately and always and swallows every backup
  failure, so the only reachable failure on this thread is a payload that will not parse or names no
  repository — the same bytes forever, which is the poison case: a WARN and a return.
- **`ScheduledBackupSweep` stays.** Durable delivery narrows what it is for without emptying it: an
  unreachable forge, an expired credential and a backup that failed on its own are none of them
  missing events.
- **Causation is stamped on every push TO the git host, and on no push to a forge.**
  `bus/EventstreamPushCausation` implements `control/PushCausation` over `CausationScope`;
  `GitMirrorRegistry` hands it to `GitMirrors` as a `Supplier<String>`, and `RepoMirror.push` turns
  it into `-c http.extraHeader=X-Qits-Causation-Id: <uuid>`. One place, so `createBranch`,
  `deleteBranch` and every hand-built `PushSpec` carry it without a call site remembering to. The
  header name is a literal in `gitmirror` (that module depends on **nothing**, and stays that way);
  `EventstreamPushCausationTest` asserts it equals `CausationHeader.NAME`. A value that will not
  parse as a UUID is dropped rather than interpolated — a cause is advisory and must never fail a
  push, and a newline in an HTTP header would be injection. Backup pushes go to GitHub through the
  domain's own `git` invocation, never through `RepoMirror`, so they cannot pick it up.
- **The jar brings a MANDATORY deployment resource.** `.config/qits/deployments.yml` declares
  `postgresql:eventstream:qits_projects_eventstream`, and the resource **name** is load-bearing
  because the jar reads `QITS_RESOURCE_EVENTSTREAM_*`. `qits.eventstream.enabled=false` (`%dev`,
  `%test`) stops publishing, sweeping and dialling — never the datasource, which Quarkus opens and
  migrates at boot regardless. That is why the suite hands out a third database
  (`testdb/ServiceEmbeddedPgConfigSource`) and why `PackagedSurfaceIT` supplies a third triple.
- **`bus/EventWireReflection` is the native-image registration**, and `EventWireReflectionTest`
  guards its completeness against the registered listener beans. Read that class's javadoc before
  adding a wire type; the failure it prevents is invisible to every JVM test by construction.

## Adding a dependency on another context

Don't. Declare a port in `domain/…/projects/control/`, inject it as `Instance<T>`, and make absent a
supported configuration with a documented behaviour — see the table in the README. Every port here
is optional; if you ever add a mandatory one, say why in its javadoc, the way qits-workspaces'
`RepositoryLookup` does.

**`qits-containers-client` is the exception that shows where the rule's real boundary is.** It is a
*client*, so it has to be read against this rule rather than waved past it — and the answer is the
one the eventstream jar already gets: what is forbidden is a dependency on another **bounded
context**, and this is **platform infrastructure**. The test is whether the jar is the platform's
single answer to a capability every module needs or one context's model. qits-events is where the
platform's events live; qits-containers is where its containers live. Three properties travel with
that, and a jar missing any of them is a client on another context whatever it is called: no domain
model crosses (images, environment and lifetimes — it cannot name a `Project` and does not want to),
every call is synchronous, bounded and **cannot throw**, and the jar brings no framework at all.

Never add a JPA relation to another context's entity. `Project ↔ Repository ↔ repository_name` are
real relations with real foreign keys because those tables are in **this** database. Anything else
is a string id through a port. (`repository_submodule` was the fourth such table; the wrapper's
`.gitmodules` is the submodule graph now, and V4 dropped it.)

Prefer widening an existing port over adding a synchronous call into another context. Where a port
call is a genuine ordering precondition — `createMainWorkspace` before a clone returns,
`releaseRepository` before a repository row goes — say so at the declaration, as
`WorkspaceLifecycle` does.

## Authentication

User authentication happens at `qits-gateway`. This service resolves the trusted forwarded user
and roles through the shared `qits-auth-core`; machine callers present validated OIDC credentials.

There is no auth variant to select in this service. The shared `qits-auth-core` resolves both
`X-Qits-User` and `X-Qits-Roles`, and the edge strips every client-supplied `X-Qits-*` header from
every inbound request unconditionally, which is the entire reason a header can be trusted as an
identity here.

**Nothing on this surface is open, and the role names a kind of caller rather than a level.**

| role | how a caller holds it | what it opens |
| --- | --- | --- |
| `qits:admin` | the forwarded `X-Qits-Roles` header alone — the edge asserts it for an authenticated admin session | every REST controller here (class-level), the events stream and the remote-login socket |
| `qits:system` | a machine bearer alone — qits-idp copies a client's `roles` into the token's `groups` claim, and quarkus-oidc reads that claim as roles with no configuration at all | `GET /projects/{projectId}/repositories/by-name/{repoName}` (qits-githost), `POST /projects/{projectId}/repositories/adopt` (the bootstrap) and the agent control socket `/projects/daemon/{projectId}` |

**Four routes take both roles**, because a sibling service and a browser read each of them:
`GET /projects` (the bootstrap turning the project's name into its id, and the projects overview),
`GET /projects/{projectId}/repositories` (qits-workspaces creating an aggregate branch, and the
projects overview), `GET /repositories` (qits-ci's trigger catalogue) and `GET /repositories/{repoId}`
(qits-workspaces' `RepositoryLookup`, and the workspaces detail screen, which has only the
repository id to go on).

**`POST /projects/{projectId}/repositories/adopt` is `qits:system` alone**, like the by-name
resolution and for the same reason: its caller supplies a git-host STORAGE id, which only the
machine that created the bare holds. That caller is qits-cli-bootstrap, which creates every platform
repository on the git host before this service exists to be asked. A person has no storage id to
supply and `POST /projects/{projectId}/repositories` is their route.

**A method-level `@RolesAllowed` REPLACES the class-level one; it does not add to it.** That is the
defect class to watch for here, and both of the routes above were live 403s found that way: the
controllers are class-level `qits:admin`, so annotating one method `qits:system` to let a machine in
locks every browser out of exactly that route and nothing else. A route with two kinds of caller
spells both roles.

**Two doors, and which one shuts says what is missing.** No user header at all is anonymous and
answers **401** at the mechanism's challenge; a named caller without the role authenticates and
answers **403**. `EpicsAuditIdentityTest` pins both.

**The suite goes through the mechanism, never around it.** `qits-auth-core` ships a `%test` dev user
granted all four platform roles, so a plain `given()` already is an admin session and the ordinary
test needs no fixture. A test about a *particular* caller sends what the edge sends — `X-Qits-User`
plus `X-Qits-Roles`, as the MCP suites and the two role-pinning tests do — and a test under
`NoDevUserProfile`, which blanks the dev user to reach the deployed posture, has no identity until it
sends them.

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
  (`requireWrapperMembership`), the reconcile reports it `UNDECLARED` and the listing marks it
  `declared: false`. **The reconcile deletes nothing** (2026-08-26): a delete now destroys the
  repository on the git host, and an edit to one file is not consent to that, so a person decides in
  the UI.
- **An empty `.gitmodules` is not a manifest.** A wrapper declaring no submodules enforces nothing
  and reports nothing undeclared. Without that, shipping either rule would have bricked every
  project that had not adopted the model yet.

`WrapperSubmoduleWriter` is the only writer of that file and `WrapperGitmodules` the only editor —
textual, one section at a time, every other byte where it was, because this is a file people review.

## Project slugs

`Project.slug` is **unique** (V6) and immutable (`@Column(updatable = false)`). Each project has its
own upstream backup organisation and the slug names it; it also names the project's wrapper
repository (`<slug>-<slug>`) and its agent container (`qits-proj-<slug>`).

It was deliberately non-unique until 2026-08-08. The correction had to be its own migration then —
V1's column comment said the opposite and an applied file is checksummed — but the move to postgres
restarted both lineages, so the constraint now sits in `V1__init.sql` beside a comment that agrees
with it.

**A set of slugs is RESERVED** (`ProjectService.RESERVED_SLUGS`): the six repository categories,
`api`, `q`, `main-navigation`, and every application segment the platform routes. A slug is the
first path segment of every address on every application host, and those hosts path-route every
application's segment too — so a project called `projects` would be shadowed by this service's own
API with nothing to say so. A supplied reserved slug is a **400**; a derived one suffixes like any
other collision, so "Docs" still creates, as `docs-2`. **A new service segment belongs in that list
on the day it is routed.**

Uniqueness is reached two ways, and the difference is what the caller said:

- **no slug given** — derived from the name, then the next free `-2`, `-3`, …, exactly like an epic
  slug within its project. The caller stated nothing about the value and two projects called
  "Checkout" must both be creatable.
- **a slug given** — a collision is a **409**. It is a statement, not a default: it names the
  upstream the wrapper is backed up to, so a silent rename would create a project whose wrapper does
  not match the upstream the caller meant, and nothing would say so until a push failed.

There is no dedupe backfill in V6, and not only because the live platform holds one project: a slug
is immutable because things are named after it, so rewriting one in SQL would leave that project's
wrapper addressable under a name the project no longer derives. A duplicate is a person's decision.

## Branch naming

Work on an epic, feature or task happens on a branch named after the planning row:

    epic/<epic>
    feature/<epic>/<feature>
    task/<epic>/<feature>/<task>

Every level carries its **own** prefix so no branch is ever a path prefix of another. Git stores
refs as files, so `epic/planning` and `epic/planning/slugs` cannot both exist; the per-level
prefixes are what make the three depths coexist.

The segments are the `slug` columns on `Epic`, `Feature` and `Task` (V2). A slug is minted from the
title at **create** and never changes — `@Column(updatable = false)`, and no `update` path touches
it — because a rename must not orphan the branches already cut. `Slugs.slugify` is the derivation, a
deliberate copy of domain's `ProjectService.slugify` (epics depends on `domain` nowhere, and stays
that way); `Slugs.unique` then adds `-2`, `-3`, … within the scope. The scope is the parent: an
epic's slug is unique per project, a feature's per epic, a task's per feature — two siblings sharing
one would name the same branch. `Project.slug` is unique too (V6), with the whole service as its
scope, so the same suffixing runs there; the difference is what a *supplied* slug means, and it is
in **Project slugs** below.

**Open, in another repo:** qits-workspaces' `CaptureService` mints capture branches named
`feature/<timestamp>`. Directory-wise that collides with `feature/<epic>/<feature>` — a capture
branch is a *file* at `refs/heads/feature/<timestamp>` while a feature branch needs
`refs/heads/feature/<epic>/` to be a directory, so the first of the two to be created blocks the
other. Renaming the capture prefix is a qits-workspaces workstream; do not change it from here.

## Epic lifecycle

An epic is in one of four stored statuses (V3): `REFINING`, `IMPLEMENTATION`, `SUPERSEDED`,
`ABANDONED`. New epics start `REFINING`, and `POST /epics/{id}/transition` is the only thing that
moves the status. Four moves are legal — `REFINING→IMPLEMENTATION` (the scope freeze),
`REFINING→ABANDONED`, `IMPLEMENTATION→SUPERSEDED`, `IMPLEMENTATION→ABANDONED` — and everything else,
including a target that names no status, is a 409.

**"Done" is not stored.** It is derived: an `IMPLEMENTATION` epic with at least one feature and every
feature's `implementedOn` set, which is the derivation the SPA already does. A fifth status would
give the same fact two sources that can disagree.

**The freeze is enforced in the services, per field rather than per endpoint.** `EpicLifecycle` holds
the rules and all three services obey them — a task's phase is the phase of its feature's epic.
Structural changes (the epic's title/description, and any feature/task create, update or delete,
`dependsOn` included) need `REFINING`; the implemented markers (`implementedOn`/`implementedAt`) need
`IMPLEMENTATION`. Those two rules alone reject every write in the terminal statuses, and a call
carrying both kinds always fails. Deleting an *epic* stays allowed in every status: it removes the
row rather than editing a frozen scope, and the audit log outlives it.

**Superseding copies the whole discarded scope** into a successor draft — a new `REFINING` epic with
the old title, description and feature/task tree, fresh ids, implemented markers reset, `dependsOn*`
remapped to the new rows, and `supersededByEpicId` on the old row pointing at it. The old row keeps
its frozen scope as the record of what was discarded, which is why superseded epics stay in the
list. Features and tasks keep their slugs, because the new epic and its features are new scopes; the
successor *epic's* slug cannot, because its scope is the project and the old row still holds it, so
it mints the next free suffix like any other create.

## Project agent harness

One container per project, holding a clone of that project's wrapper repository and running
`qits-projects-daemon` over it, so a refinement agent can read and build the project it is drafting
epics for. The host side is `service/…/agenthost/`; the container's process is its own repository
(`qits-projects-daemon`), and that repo's `AGENTS.md` is the source of truth for every value below.
The whole shape is qits-workspaces' daemon harness — registry, tunnels, proxy — adapted rather than
reinvented, so read that repo before changing anything structural here.

**Two path contracts, and both are append-only.** They are baked into every container as env at
creation, and only a container recreate re-injects them:

    control socket   ws://<host>:<port>/projects/daemon/<projectId>
    proxy prefix     /projects/container/<projectId>/

Neither is a literal in this tree. Both live in the vendored `DaemonProtocol` constants and are
asserted on both sides by `DaemonCodecTest`, which is also why `projects-daemon-protocol/` exists
here at all: it is a **source copy** of the daemon repo's module, same java package, different
artifactId. A protocol change is three edits in order — the record and the constants in the daemon
repo, `DaemonCodecTest` there, then the same files here — and it bumps `CAPABILITY_VERSION`.

**The daemon has no address.** `ProjectsApi` binds `127.0.0.1` from capability 1, so there is no
direct branch anywhere: qits sends an `OpenStream` over the control socket, the daemon dials *out*
to `/projects/daemon/stream/{nonce}`, and `DaemonStreamRoute` marries that WebSocket to the parked
loopback connection `AgentTunnels` accepted. The nonce is the whole authentication — host-minted,
single-use, short-lived — because the control socket names its caller with a path parameter and a
dial-back that named its own project would reproduce that weakness in a second place. That
token-free control socket is inherited from qits-workspaces and closes with it when qits-idp machine
auth lands; do not add an interim token.

**Nothing rewrites a path.** `ContainerProxyRoute` forwards `/projects/container/{id}/commands` byte
for byte, and the daemon is *told* which leading part is its own address
(`QITS_PROJECTS_DAEMON_API_BASE_PATH`, rendered by `ContainerProxyPath.base`). Do not add a
`substring` here: a hop that rewrites leaves the two ends disagreeing about the destination's own
address, and the disagreement surfaces a long way from the rewrite. A **WebSocket upgrade does not
go through `vertx-http-proxy` at all** — `proxyUpgrade` does it by hand, because the library skips
its whole interceptor chain on an upgrade (so the bearer never arrives) and pipes with no
`writeQueueFull`/`pause`/`drainHandler` at all.

**The env contract**, read from the daemon repo and asserted by `AgentContainerFactoryTest`. Getting
one wrong fails silently: no url leaves the daemon idle, no token leaves its API unbound.

    QITS_PROJECTS_DAEMON_URL             the control socket, dialled verbatim
    QITS_PROJECTS_DAEMON_API_BASE_PATH   /projects/container/<projectId>/
    QITS_PROJECTS_DAEMON_PROJECT_ID      the project served
    QITS_PROJECTS_DAEMON_REPO_NAME       the wrapper, <slug>-<slug> — the clone is name-addressed
    QITS_PROJECTS_DAEMON_GIT_BASE        stated, never derived: the git host is qits-githost
    QITS_PROJECTS_DAEMON_API_TOKEN       qits.projects.daemon-api-token
    QITS_PROJECTS_DAEMON_API_PORT        13338, also the authority the proxy pins
    QITS_PROJECTS_DAEMON_HOOKS_PORT      13337
    QITS_PROJECTS_DAEMON_CLAUDE_MOUNT    /claude-home
    QITS_REPOSITORY_MCP_URL              the one MCP server a launch attaches — this service
    QITS_COMMISSIONED_CLIENT_ID          this container's OWN idp client — absent with no idp
    QITS_COMMISSIONED_CLIENT_SECRET      its secret, answered once and stored here
    QITS_PROJECTS_DAEMON_AUTH_TOKEN_URL  the idp token endpoint used before dial-home
    QITS_PROJECTS_DAEMON_AUTH_AUDIENCE   this qits-projects service's environment client id

**The last two are a credential per container, not a shared one.** They are commissioned from
qits-idp's `POST /idp/api/clients` as `{agent-container, <projectId>}` and handed back when the
container is gone, so what a container authenticates its pulls, its maven/npm resolution and its git
reads with has the container's lifetime and no other. Read the section below before touching them —
in particular, they are the one part of this table whose *absence* is a supported configuration.

**The harness gets exactly one MCP server, and it is this one.** `QITS_REPOSITORY_MCP_URL` names
this service's `repository` server at `/projects/mcp`, which carries `EpicMcpTools` beside
`RepositoryMcpTools` — the epic surface is why the container exists. It is composed from
`qits.projects.own-host`/`own-port` (`qits.projects.agent-mcp-url` overrides), so it is *stated*
rather than left to the daemon's derivation; the daemon keeps that derivation as a fallback, so
containers created before this env still work and nothing had to be recreated. The name carries no
`QITS_PROJECTS_DAEMON_` prefix because it is the daemon's existing `qits.repository-mcp.url` key.

The exclusion is the other half of the decision: qits-workspace-daemon wires **three** servers into
a workspace container (`actions`, `repository`, `observability`) and a project agent gets neither of
the other two — its job is the project's plan, not workspace actions or another service's telemetry.
Nothing can add them back at runtime: the daemon addresses `repository` alone and refuses any other
name, and Claude is launched `--strict-mcp-config`, so the shared `/claude-home` volume's own MCP
entries are ignored.

**The token is not a boundary.** `qits.projects.daemon-api-token` is peer authentication behind a
loopback bind — it says "qits is calling", never "this user is calling" — so the proxy *sets* it,
replacing whatever the caller sent, and a forwarded one would be a credential leak. It ships with a
default so a deployment needs no configuration; the other end is still fail-closed, because a daemon
handed no token does not bind its API at all.

### The commissioned credential

**One idp client per container, and its lifetime is the container's.** `AgentCommissions` gets it
from qits-idp's commission API — `POST /idp/api/clients` with `{"contextKind":"agent-container",
"contextId":"<projectId>"}`, HTTP Basic with **this service's own** oidc client id and secret,
because a caller there already holds an idp credential and that is how the API authenticates one.
`idphost/IdpAgentCredentials` is the adapter and `agenthost/AgentCredentials` the seam; the adapter
is `@DefaultBean`, so the suite's `FakeAgentCredentials` wins the injection and no test reaches an
idp. Everything is read from the keys the oidc-client block already ships
(`client-enabled`, `client-id`, `credentials.secret`, `auth-server-url`) — there is no second address
and no second credential to configure.

Four things bite.

- **Absent is the shipped configuration and must stay byte-identical.** With
  `quarkus.oidc-client.client-enabled=false` this process holds no secret, so it can authenticate to
  nothing: nothing is commissioned, the two names are simply not in the env map, and the spec a
  container is started with is the spec it was before any of this existed. Same answer, plus one
  WARN, when the switch is on and the secret is blank.
- **The fresh arm commissions and the wake arm must not.** `AgentContainerFactory.forProject` mints
  a credential; `forRestart` reads back the pair the container already holds and sends it unchanged.
  That is not a cache: qits-containers hashes a workload's whole spec, **environment included**, so
  a wake that minted a fresh secret would be a spec change and would replace the container on every
  wake — the exact defect `ContainerRuntime.restart` records, reintroduced through the one door left
  open. `AgentCommissioningTest` compares the two arms' whole env maps for that reason.
- **The pair is a row in this database (`agent_credential`, V3), secret included**, and that follows
  from the point above rather than from convenience: the wake arm has to reproduce a value idp
  answers exactly once. The row is keyed on the project id with **no foreign key** to `project`,
  because an agent container outlives its project and a cascade would drop the row while the
  container still held the credential.
- **Decommissioning is a sweep here, and that is a fact about this repository.** Nothing in this
  service removes an agent container: stop and the idle sweep both stop, deleting a project leaves
  its container standing, and `ContainerRuntime` has no removal verb at all. So the lifecycle hook
  the model asks for has no call site. The two real paths are `forFreshContainer`, which hands a
  project's previous credential back before minting the replacement container's, and
  `AgentCredentialReconcile`, which at boot and hourly compares idp's own listing of what this
  service commissioned against what the orchestrator says exists. It asks
  `ContainerRuntime.inspect` **per commissioned project** rather than reading the listing, because
  the listing answers an empty list both for "no containers" and for "could not ask" and reaping on
  the second would revoke every live agent's credential at once; `inspect` is empty for a true 404
  and throws otherwise, and a pass that cannot ask reaps nothing. If a removal verb is ever added,
  it decommissions there too and this becomes the belt it should be.

A commission holds through 401, 403 and nothing answering for
`qits.projects.agent-credentials.commission-patience` (PT30S) — the same classifier and the same
measured idp-cutover window as `ContainersAgentRuntime.holdThrough`, shorter because it sits in
front of an image pull somebody is waiting on. Past that it throws an `AgentCredentialException`,
which is a plain `RuntimeException` **on purpose**: `AgentContainers.ensure` rethrows a
`DomainException` with its status and turns everything else into `FAILED` with the reason on
`failureDetail`, and this belongs in the second arm.

**The shared volumes carry qits-workspaces' names on purpose** — `qits_shared_dot_claude`,
`qits_shared_m2`, `qits_shared_pnpm`. They are platform-wide: a divergent credential volume here
would give project agents their own unauthenticated agent home. The per-project checkout volume is
`qits_project_<projectId>`, keyed on the id, while the container is named `qits-proj-<slug>` so
`docker ps` reads well. `Project.slug` is unique (V6), but only among *live* projects, and deleting
a project does not remove its agent container — so a later project taking the freed slug finds the
old container on the name. The name therefore proves nothing, which is why it is not the address:
a place is `owner/project-agent/<projectId>` and a row found under this project's id *is* this
project's. What survives of the old label check is one arm down, in
`ContainersAgentRuntime.run`: before provisioning it asks whether another of this owner's places
already holds the name, and answers 409 rather than letting the registry refuse it as a unique
constraint nobody can act on.

**The stop policy is stop, never remove.** `POST …/agent-container/stop` and the
`qits.projects.agent-idle-timeout` sweep (PT4H) both leave the container in place, so the next ensure
starts *that* container again — same docker id, same writable layer. The checkout would survive a
replacement too (it is a named volume the orchestrator will not remove under `IDLE_STOP`, and the
daemon skips its self-clone on an already-populated `/workspace`), which is what makes a wake safe
even when an image bump turns it into one — see "The container orchestrator" below. Idleness is
measured from the
last thing the daemon said — `Hello`, heartbeat, agent activity — so it means "nobody is using this
project", not "nothing is happening": a long silent build still heartbeats. A container this process
has never heard from is stamped on sight and ages out one window later, rather than being immortal
or reaped immediately.

**A failed provision is reported, not swallowed.** The daemon clones the project into
`/workspace` on boot; when that fails it says `ProvisionFailed`, and docker still calls the
container healthy. So the frame is *recorded* per project and the agent-container read answers
`FAILED` with a `failureDetail` rather than `RUNNING` — otherwise the panel opens a terminal onto
an empty checkout. The record lives in a map beside `lastActivity`, not on the connection, because a
daemon that cannot clone usually drops its socket right after saying so and a failure held on the
socket would vanish exactly when somebody came to read it. A `Provisioned`, a reconnect or a stop
clears it. **There is no re-provision**: `ensure` no-ops on a running container and the daemon
latches its attempt for the life of its process, so recovery is to remove the container and ensure
it again — deliberately not automatic, since the `/workspace` volume a remove orphans is where
uncommitted work lives. The detail is a field and not a sixth `AgentRuntimeStatus`: the SPA switches
on those five strings and they are a published contract.

## Refinement containers

One container per REFINING epic — the refining route's whole backend, which used to be an ordinary
qits-workspaces workspace on a `refining/*` branch (epic refinement-improvements, part 2). The host
side is `service/…/refinementhost/`; the container runs the WORKSPACE image and daemon, unchanged —
`qits-workspace-daemon` dials home to whatever `QITS_WORKSPACE_DAEMON_URL` names, and this service
is that home now. `workspace-daemon-protocol/` is that daemon's wire contract, vendored beside
`projects-daemon-protocol/` (two daemons, two vocabularies, two modules; the codec test travels with
each).

The shape deliberately mirrors the project-agent harness one section up — control socket, registry,
reverse tunnel, verbatim proxy with the hand-rolled websocket upgrade — on refinement's own paths,
all three append-only once a container exists (`RefinementPaths`):

    control socket   ws://<host>:<port>/projects/refinement-daemon/<rowId>
    dial-back        /projects/refinement-daemon/stream/<nonce>
    proxy prefix     /projects/refinement-container/<rowId>/

Where it differs from the agent harness, each difference is the domain line:

- **Keyed by epic, addressed by row id.** `refinement` (V4) holds one row per epic (unique), with
  the branch (`refining/<epicSlug>`), the parent (the wrapper's default branch — a refinement always
  forks it, which is why there is no parent/child tree and no integrate door), the preamble computed
  from the epic tree at create, and the commissioned credential — ON THE ROW, because
  `Recreate.ifChanged` hashes the whole spec and a resume must reproduce the pair byte for byte.
- **A refinement runs no code.** `BOOTSTRAP_AUTORUN=false`, `SERVICES_AUTOSTART=false`, no
  `SERVICE_PROXY_BASE`, no actions MCP server — the tab set this backs has no Services or Actions
  tab, and its web view frames the deployed environment, not a dev server.
- **There is a removal verb.** Discard tears down container → volume → credential → branch → row,
  in that order; the agent harness deliberately has no removal at all. `RefinementCommissions`
  decommissions at the explicit seams; `RefinementCommissionReconcile` reaps `refinement`-kind idp
  clients no row claims (its own CONTEXT_KIND, invisible to the agent reconcile and vice versa).
- **The ensure ladder runs off the request thread** (`RefinementService`): the browser gets a
  technical-process id to watch instead of a request that hangs behind an image pull. One
  `Semaphore` permit per row — a semaphore and not a lock, because the permit is taken on the
  request thread and released on the worker. The daemon's provision output (`CommandChunk`s tagged
  `provision`) streams into the narration via `RefinementDaemonRegistry`, and its terminal
  `Provisioned`/`ProvisionFailed` settles it.
- **`processhost/` is the technical-process port's live implementation** — the piece the domain
  port's javadoc always said an assembling application supplies. Standing it up for refinement also
  lights the repository-scoped narration (pull/push/sync leases) that ran unnarrated before, and
  `api/TechnicalProcessEventsController` is the SSE controller the port was waiting for. Everything
  in it is this process's memory; an evicted id answers the 404 the frontend reads as "expired".
- **The image pin rides qits-workspace-daemon's releases**: that repo's release publishes
  `qits/workspace`, and `.config/qits/ci-event-upstream-workspace-daemon.yml` moves
  `qits.projects.refinement-image-version` the same way the SPA follow moves the webui gitlink.
  Deployments can override it as `QITS_PROJECTS_REFINEMENT_IMAGE_VERSION`.
- **Git reaches the edge** (`qits.projects.refinement-git-url`, default
  `http://qits-platform-edge:8080`): the workspace image's credential helper speaks oauth2 Basic and
  the edge rewrites it to a Bearer, exactly as a workspace's does. The three registry keys
  (`refinement-maven-repository-url` / `-npm-registry-url` / `-npm-proxy-url`) ship blank like
  qits-workspaces' — unset injects nothing.

The REST surface is under `/projects/api`: `POST /refinements` (find-or-create keyed by epic —
adopt-existing is the create's ordinary path, not an error dance), `GET/verbs /refinements/{id}`,
`GET /projects/{projectId}/refinements` (the LIGHT projection — live halves, no git drift, because
the list redraws on every activity hint), the prompt draft and attachments (content URLs are
embedded into epic markdown, so attachment ids are never renumbered), the per-row SSE hint channel,
and the technical-process stream. The suite's seams are `FakeRefinementRuntime` and
`FakeRefinementCredentials`, winning over the `@DefaultBean` adapters exactly as the agent fakes do
— and read through METHODS, never public fields, because a client proxy does not proxy field access.

**Designs are frozen HTML kept with the refinement** (`refinement_design`, V5) — one self-contained
document per page, styles inline, cascading from the row like the draft and attachments do.
`RefinementDesigns` holds the writes and `/refinements/{id}/designs` serves them; a list leaves the
document out and only the single read carries it.

There is deliberately **no content route**. Agent-authored HTML served same-origin would be an XSS
door into the platform's own session, so the bytes only ever travel as a JSON field and the SPA
renders them in a sandboxed iframe with scripts off. Do not add a `text/html` route here.

**A row is ACTIVE or PROPOSED, and only a person crosses that line.** A REST capture is ACTIVE at
once; the three MCP tools on the `repository` server (`list_designs`, `get_design`,
`propose_design`) let a refinement agent read the designs and propose a revision, which lands
PROPOSED with the agent's note on it. `POST …/{designId}/resolve` is the decision: `REPLACE` copies
the proposal onto the design it revises and drops the proposal, `KEEP` makes the proposal a design
of its own, and discarding is a plain `DELETE`. `propose_design` is in
`ReadOnlyRepositoryToolFilter`'s mutating set — an unattended run must not fill the tab with work
nobody asked for.

## The container orchestrator

**This service holds no docker socket and spawns no process.** Every container verb the harness has
— provision, bring back, stop, stamp, list, make a volume — is one HTTP call to **qits-containers**,
which owns the daemon. `agenthost/ContainerRuntime` is still the seam; its sole implementation is
`containershost/ContainersAgentRuntime`, and `DockerAgentRuntime` (a `ProcessBuilder` shelling
`docker`) and the `AgentContainer` argv builder are **deleted**, not retired. If a docker argv ever
reappears in this repository, that is the regression.

Five things bite.

- **A place is `owner/workload/ref`, and this service's ref is the project id.** So the seam takes a
  project id where it used to take a container name, and `qits-proj-<slug>` travels as the spec's
  `explicitName` — a hint for `docker ps`, never an address. `qits.projects.containers.owner`
  **must equal the machine token's `sub`** once the far side's gate is on (its `OwnerGuard` compares
  them), which is why it defaults to reading `quarkus.oidc-client.client-id`. Two instances must not
  share it; two environments sharing one docker daemon are `dev-qits-projects` and
  `prod-qits-projects` and neither one's rows name the other's containers.
- **The client never throws, and its four answers are the whole vocabulary.** A refusal and an
  unreachable service mean opposite things — one is evidence about the request, the other about
  nothing at all — so `inspect` answers empty for a **404 only** and throws for everything else. The
  docker CLI it replaces could not tell those apart (a broken binary and an absent container both
  exited non-zero), and reading "we could not ask" as "there is nothing there" is what would send the
  ladder to provision a second container. Do not add a fifth outcome by catching something.
- **A bring-up holds through 401, 403 and nothing answering, and through nothing else.**
  `ContainersAgentRuntime.holdThrough` is qits-ci's classifier copied verbatim, and the measurement
  behind it is that repository's: across a qits-platform-idp cutover those three are statements about
  the moment rather than about the request, and each attempt asks the `TokenSource` again, which is
  the only way a post-cutover token is ever picked up. Retrying is safe because `ensure` is a PUT per
  place. `SPEC_CONFLICT`, `IMAGE_MISSING` and a 400 on a value are one attempt each — no window fixes
  them. **A 2xx whose observed state is `MISSING`/`GONE` is a failed bring-up**, not a started one.
- **Waking a stopped agent is a start in place, and a replacement only if the spec really changed.**
  One `ensure` does both: qits-containers starts the container the row already names when the spec is
  unchanged (same docker id, everything outside the volumes intact), and replaces it when it differs.
  So `forRestart` sends `Recreate.ifChanged` and nothing else — that permission is what lets an
  agent-image bump landing while the agent slept be applied at wake, which is the one moment it can
  be applied without taking a container away from somebody working in it. The running arm asks for no
  recreate at all.
  <br>**This arm was a forced re-create until 2026-08-13**, and the reason is worth keeping: that
  service had no start verb, its `RESTART` step fell through to a second `docker run` under a name
  docker already held, and a stopped place asked for again settled `MISSING` behind a **200**. The
  workaround here was an env stamp that differed per call, so the spec was never "unchanged" and the
  recreate step ran instead. qits-containers 354fd7f fixed it — a bounded `start` on its driver seam,
  a real-daemon test that stop-then-ensure returns the same docker id — and the stamp is gone with
  it. Do not reintroduce a per-call value into this spec: a request that differs every time is a
  request that can never be started in place.
  <br>A delete-then-ensure was never the alternative: `ct_container`'s `container_name` is unique
  across **every** row including the settled ones, and a deleted row keeps the name for
  `qits.containers.row-prune-horizon` (P7D).
- **The idle sweep stays here, and it resolves identity itself.** Its tunnel teardown and
  `registry.forget` are in-memory state of this process that the orchestrator cannot touch. The spec
  still carries an `IDLE_STOP` policy with the same window as the belt for a qits-projects that died
  holding a container — and the sweep `touch`es what it keeps, because that clock is only ever
  stamped when a row is written and would otherwise stop a container somebody is working in. The
  listing carries no labels and no refs, so a container **name** is matched back against the live
  projects' own `qits-proj-<slug>`; one that matches none is **skipped**, because every action past
  the stop is addressed by a project id there is no longer one of.

**Nothing here reaches an orchestrator under test.** `ContainersAgentRuntime` is `@DefaultBean`, so
`FakeContainerRuntime` simply wins the injection, and the test config points `qits.containers.url` at
`http://127.0.0.1:1` so a call that escaped the fake fails fast instead of reaching a real
orchestrator on the developer's own machine. There is no startup observer left to gate: the network
is the bootstrap's and this service creates none.

**`containershost/ContainersWireReflection` is the native-image registration**, the second member of
`bus/EventWireReflection`'s family and there for the identical reason — the client jar builds its own
`ObjectMapper`, so the wire records are invisible to the build step that scans for what needs
reflecting on, and without it the JVM suite stays green and the binary fails on every call. The list
is the client's README's list; keep them the same.

## Schema changes

`domain/src/main/resources/db/projects/migration/`, hand-written, its own lineage on its own
datasource. Never touch the monorepo's `db/migration` — that is a different database. Epics has its
own lineage under `epics/src/main/resources/db/epics/migration/`; the two never mix.

Entities live in a **named** persistence unit (`projects`), not the default one. An `EntityManager`
injection therefore needs `@PersistenceUnit("projects")`.

**Two PostgreSQL databases, and both lineages restarted at V1 to say so.** This application declares
`resources: postgresql:db, postgresql:epics:qits_epics` in `.config/qits/deployments.yml`;
qits-platform-deployments creates a role and a database for each before the container starts and
injects `QITS_RESOURCE_DB_*` and `QITS_RESOURCE_EPICS_*`. The two library jars map those onto
`quarkus.datasource.projects.*` and `quarkus.datasource.epics.*` in their own shipped defaults —
that mapping is the application's job, never the deployer's — and neither triple has a fallback: an
unset variable leaves the expression unresolvable and the process dies at Flyway naming it.

**Every postgresql datasource carries a three-line resilience block, and `DatasourceBaselineTest`
fails the build if one loses a line.** The lines are `jdbc.driver=eu.wohlben.qits.db.PatientPgDriver`,
`jdbc.validate-on-borrow=true` and `jdbc.acquisition-timeout=15S`, and they only work as a set:
stock Agroal does *not* wait for a database that is gone (a failed connection **creation** goes
straight to the caller, so `acquisition-timeout` alone bounds nothing but a starved pool), the
patient driver is what holds the request while postgres comes back, and validation at borrow is what
turns a dead pooled connection into a fresh creation attempt for it to be patient about. The
measurements are in the superproject's `db-patience-plan.md`.

Three datasources, three places the block is written, and the third is the odd one: `projects` in
domain's jar, `epics` in the sibling's, and `eventstream` in **this application's own**
`application.properties` — the one exception to "nothing the bus needs is spelled here", because the
baseline belongs to the deployed application whoever shipped the datasource, and qits-eventstream's
jar does not carry it yet. Drop those three lines when it does.

The enforcement lives in `service` and **not** one per module, the opposite placement to
`ArchRulesTest` above and for the mirror-image reason: those rules judge classes a module owns, while
this one judges configuration only the deployable has all of. It is a `@QuarkusTest` on purpose —
`application.properties` is a Quarkus config source, so a plain unit test would read the jars'
defaults and none of this module's own lines.

**`DbRetry` (qits-db-core) wraps read seams a cutover would otherwise turn into a wrong answer.** One
is wrapped here: `RepositoryService.findByProjectAndName`, the read behind
`GET …/repositories/by-name/{repoName}` — qits-githost's 404 by proxy, and a git client caches "no
such repository" as an answer rather than as an outage. `RepositoryNameResolver.resolve` is wrapped
too, around its own unique-constraint loop rather than inside it: that loop is the alias race and
retries with no pause, and it used to swallow connection losses for three attempts and then report
them as a race that never happened. Two rules govern every new wrap — **outside**
`QuarkusTransaction.requiringNew()` and never inside an open transaction or a `synchronized` monitor
— and the retried block must be re-runnable, which is why these are reads. `RepositoryNameCutoverTest`
is the proof, and it pins both halves: the read survives a severed connection, and a name that
resolves to nothing still answers 404 on the first attempt.

**The epics board's list reads are wrapped too, and `epics` routes them through one bean.**
`control/ReadPatience` holds the deadline (`qits.epics.read-deadline`, 15S) so the five seams —
`EpicService.listByProject`, `FeatureService.listByEpic`, `TaskService.listByFeature` and
`AuditService`'s two histories — cannot drift apart, and so a suite can shorten it: a give-up test at
fifteen seconds is a fifteen-second test. What they are worth: a severed connection would draw a
project with no epics, a feature with no tasks, or an audit log saying nothing ever happened, and
every one of those reads as an answer.

The identical repository calls **inside** this module's writes are deliberately left unwrapped —
slug uniqueness in `insert`/`create`, the cascade deletes, and `listDependents`, which no read path
reaches at all. They run inside `@Transactional`, where a retry would re-run statements on a
connection already marked rollback-only. That is why the wraps sit in the services and not in the
repositories: a repository-level wrap would catch both callers and there is no way to tell them
apart from down there. `EpicService.get` and its two siblings are unwrapped for the same reason —
the write paths call them.

`EpicListCutoverTest` is the proof, one seam standing for the five since they share the bean: the
list answers after a severed connection, and a database that stays gone still fails at the deadline
rather than never.

**`DbRetry.inNewTx` holds WRITES through the same cutover, and it is a different offer from
`DbRetry.call`.** It owns the transaction — every attempt is `QuarkusTransaction.requiringNew()` —
so it can separate an attempt that *certainly* did not commit (the body threw a connection-class
failure; Quarkus rolls a failed body back and never commits it) from one nobody can place (anything
the transaction manager reports, a `RollbackException` included, because Narayana spells a lost
commit and a real rollback the same way). Only the first is retried. Three rules govern every wrap:

- **The body is database-only.** It re-runs, so an SSE hint, an HTTP call or a git push inside it
  would happen twice. That is the rule that decides which seams are wrapped here and which are not.
- **It replaces `@Transactional`, never joins it.** A method that kept the annotation would run in a
  transaction the retry cannot open again, and a caller already in one is a wrap that must not exist.
- **Flush last.** Hibernate flushes at commit by default, which puts the write on the far side of the
  line `inNewTx` can classify — the whole write would land in the undecidable commit phase and never
  be retried. `WritePatience` does it for the epics seams; the two domain seams do it by hand.

**`epics` routes all ten writes through one bean, `control/WritePatience`** (`qits.epics.write-deadline`,
15S) — `EpicService`'s create/update/transition/delete, `FeatureService`'s and `TaskService`'s
create/update/delete. Every one is rows and nothing else, and no caller is transactional:
`EpicMcpTools` is deliberately transaction-free (two persistence units, non-XA) and the controllers
are too, with their change hints fired *after* the service returns. `AuditService.record` keeps its
`@Transactional` and joins, exactly as it joined the annotation's.

**`domain` wraps the two writes that are only rows, and leaves the rest alone on purpose.**
`ProjectService.update` (a rename) and `RepositoryService.recordBackupOutcome` (the bookkeeping after
the push to the twin has already happened — the case `inNewTx` exists for) call `DbRetry` directly,
the module's existing idiom. Everything else in those two services reaches out of the database inside
its transaction and a retry would do it a second time: `cloneRepository`, `cloneWrapperOrigin`,
`createBlankRepository`, `initWrapperOrigin` and `adoptExistingOrigin` clone, push and call the git
host over HTTP; `setMainBranch` and `deleteBranch` run `ls-remote` and a push; `deleteInternal` and
`ProjectService.delete` tear down workspaces, call the git host's delete and remove mirror
directories;
`attachBackupRemote` is rows only but its sole caller `adoptWrapperRepository` is `@Transactional`,
so a wrap there would nest. `RepositoryNameResolver` keeps its `DbRetry.call` around the race loop.
Honest partial coverage, not a contorted wrap.

`EpicWriteCutoverTest` is the proof, one seam standing for the ten since they share the bean.
`FailingEpicWrites` fails **after** `super.persist`, which is what makes exactly-once a real
question: the create lands one epic row and one audit row, a failure that is not the connection is
reported after exactly one attempt, and a cutover that never ends still gives up at the deadline.

The H2 lineages (V1..V6 and V1..V3) were **deleted rather than continued**, which needed one
precondition: the move is an unwrap and a re-bootstrap, so no database anywhere was on either and no
appended migration would have had a reader. Each fresh V1 is where its lineage arrived, translated,
and both files carry the decisions in their headers — the ones worth knowing without opening them:
`repository_submodule` is simply absent (V4 had dropped it), `last_backup_at` gained the time zone
`Instant` always meant, `clob` became `text`, the archetype check constraint stayed while every other
enum column still refuses one, and both epics backfills went because every database reaching the file
is empty. **A second clean start is not a precedent** — this one cost a re-bootstrap, and the
ordinary rule (keep appending, never edit an applied migration) is back from V1 onward.

**`V2__causation.sql`, once per lineage, is that rule being followed.** Both add the platform's
generic `causation_id uuid` column (qits-eventstream's `CausedRow`): nullable, in no constraint,
never a foreign key — the event it names lives in qits-events' store — and with no backfill, since
no existing row has an answer to invent. Seven of the eight entities take it (the eighth,
`AgentCredential`, arrived later with the column already in its own `create table` — V3). The stamp fills it from
the ambient `CausationScope` at persist, and **nothing here sets it explicitly**, because no insert
crosses a thread hop: the backup executor and the pull executor only ever UPDATE, and the stamp is
insert-only. Where the decisions land, and why:

| entity | | |
| --- | --- | --- |
| `Project` | `CausedRow` | Created on the request thread (`ProjectService.create`, from REST or MCP); the boot-time self-seed is rootless, which is the honest answer. |
| `Repository` | `CausedRow` | The one worth tracing. All four mint paths run on the request thread, and `WrapperReconcileService` is the machine-driven one — a reconcile records, per adopted or cloned member, what asked for it. |
| `RepositoryName` | `@Uncaused` | The only opt-out. An alias is derived, idempotent state; the repository it FKs to is a `CausedRow` one join away and is the row that was actually caused. Decisively, `RepositoryNameResolver` mints the self-name in its own transaction off any request context — the provision worker, or container creation — where no scope stands, so a stamp would record null forever. No event id is ever in reach to set as data. |
| `Epic`, `Feature`, `Task` | `CausedRow` | `EpicMcpTools` reaches the same services the SPA does, on the same thread: an agent minting a task is exactly the flow worth tracing. |
| `AgentCredential` | `CausedRow` | V3, and the column ships in its own `create table`. The only insert is `AgentCommissions.forFreshContainer`, on the request thread that asked for the container; the reconcile only ever deletes. |
| `AuditEntry` | `CausedRow` | Covers what the live rows cannot. The stamp is insert-only, so an epic row records the cause of its own creation and never of an update; and a deleted row is gone while its DELETE entry stays (audit rows are deliberately not FK'd back). |

The decisions are **enforced, not documented**: `ArchRulesTest` (qits-arch-rules) sits in each entity
module — `domain` and `epics`, one per module rather than one in `service`, because a module owns its
entities and `epics` depends on nothing, so a guard downstream of it would neither see its classes
nor survive its lift-out. A new `@Entity` that neither implements `CausedRow` nor declares
`@Uncaused` fails the build naming the class.

**`epics` needed an `archunit.properties` and no longer does.** Every epics entity participates, so
no class there carries `@Uncaused` and the rule set's negative rule ("nothing `@Uncaused` may
implement `CausedRow`") matched zero classes — which ArchUnit fails by default, and the module went
red for being in the best state the rules describe. The module-scoped
`archRule.failOnEmptyShould=false` bought time; the real fix was `allowEmptyShould(true)` on that
rule in qits-arch-rules, it shipped in 2026.811.152803, and the file went with the pin. Do not
reintroduce it: a rule that matches nothing anywhere else is still a typo worth failing on.

## Tests

- App-level config lives in `service/src/main/resources/application.properties` and **the tests
  inherit it** — Quarkus reads main's copy during a test run and merges the test resources over it,
  so `quarkus.rest.path`, the MCP root-path and the rest are already in effect. Never re-declare
  them in `src/test/resources/application.properties`: a test copy is free to drift from the shipped
  one, and then a green suite proves nothing about what actually starts. That file is for genuine
  test-only overrides (the persistence-unit wiring, `clean-at-start`, the test port, `target/`
  paths, the test-jar index entry).
- **No dev services and no containers, ever.** A dev service is a container start, and the first
  rule here is that a clone tests green with no docker. The stores being postgres does not change
  that answer: `testdb/EmbeddedPg` starts **zonky's** postgres — real binaries resolved as Maven
  artifacts, spawned as a child process — and a config source per module hands its url, username and
  password to every `@QuarkusTest` at an ordinal above `application.properties`, because the port is
  chosen at run time and cannot be written down. Testcontainers is not on this classpath and must
  not arrive. Every **(module, datasource) pair names its own database** (`qp_domain_projects`,
  `qp_epics_epics`, `qp_svc_projects`, `qp_svc_epics`, and the IT's two) so no two suites can mean
  one schema. `EmbeddedPg` travels to `service` in `domain`'s test-jar; `epics` keeps a **copy**,
  because that module depends on nothing and a test-scoped edge is still an edge.
- **The service suite runs on port 0.** 8081 is Quarkus' default test port and also the address the
  platform's own npm registry is published on, so on the machine this is most likely built on the
  whole suite dies with `Port already bound: 8081` — which reads like a code failure and is not one.
  `quarkus.http.test-port=0` in the service test properties is the answer; the flake below is the
  same rock from the other side.
- **A deleted class stays in `target/classes` and can still win a bean lookup, so the gate is
  `./mvnw clean verify`.** Measured here on 2026-08-17: half the REST suite answered 403 and no
  forwarded role ever reached an identity, while the same tree had passed minutes earlier. The cause
  was `eu/wohlben/qits/projects/security/ForwardAuth{Mechanism,IdentityProvider}.class` — this
  repo's own pre-`qits-auth-core` mechanism, whose **source is gone** while an incremental build left
  the compiled classes behind. Two `HttpAuthenticationMechanism` beans then compete, and the stale
  one reads `X-Qits-User` and no roles at all; which of them wins varies between builds, so the same
  working tree is green in one run and red in the next. A `clean` is not a ritual here — it is the
  difference between testing this repository and testing what it used to be.
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
  augments in the build JVM, with the whole classpath present and reflection unrestricted — a native
  image has none of those, and three real defects here were invisible to all of them and fatal to
  the binary (H2's `AUTO_SERVER` on a shipped datasource default, the missing `project-template/`
  resources, `RepositoryMetadata` unregistered for reflection). It runs under `-Dnative`, and
  `-DskipITs=false` runs it against the fast-jar. It launches a real process, so it reads main's
  `application.properties` and none of the test overrides: paths reach it through
  `quarkus.test.arg-line`, which is why the test resources carry that key. **The two databases
  cannot** — their urls name a port chosen at run time — so its `@TestProfile` hands the launched
  process both `QITS_RESOURCE_*` triples, the generic contract a deployment supplies, which leaves
  the jars' own `${…}` indirection under test rather than restating the datasource keys. Its
  embedded postgres reaches that profile through a **system property**, because a
  `QuarkusTestProfile` is instantiated in two classloaders and a static field is not shared between
  them.
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
- **Never let this process open a pts.** `ProcessBuilder`'s file redirects are opened by the
  *calling* process, not the child, and they carry no `O_NOCTTY` — so
  `redirectOutput(new File("/dev/pts/N"))` makes that terminal **this service's controlling
  terminal**, because the service is a session leader (PID 1 in its container, exec-form
  entrypoint). Closing the master then hangs *us* up, and Quarkus registers SIGHUP as a stop signal
  alongside TERM and INT: the container shut down gracefully with exit 129, three times, ~20-30s
  into every life somebody opened the sign-in dialog. The open belongs in the child
  (`RemoteLoginSessions.terminalProcess` wraps it in `sh -c 'exec 0<>"$0" …'`), where a
  non-session-leader doing it is harmless and `setsid --ctty` then claims the terminal deliberately
  for the child's own session. `RemoteLoginCttyTest` guards both halves — and note its symptom test
  has to relaunch under `setsid`, because a JVM that is not a session leader passes on the broken
  code too, which is exactly why the suite stayed green while production died. `HangupImmunity` is
  the backstop: SIGHUP is a WARN here, never a shutdown.
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
  **returns a result** must be `@Alternative @Priority` (`RecordingProjectDomainRegistrar`): a
  second implementation of the port would leave the caller reporting whichever the container hands
  it first. `ProjectDomainRegistrar` has no implementation under `src/main` today — the registrar in
  `notify/` went with qits-platform-dns — so the fake stands alone and the annotations are kept as
  the rule rather than repaired away. A recording fake for a `void` port does not need this, which is
  why the older two do not have it.
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
