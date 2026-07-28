# qits-projects

The **project, repository and planning** context of [qits](https://github.com/QuicklyIterateTheSoftware),
extracted from the monorepo with its history (see `migration-plan.md` §3.1 there).

## What it owns

A **project** is one application that starts as a single wrapper repository and grows into a
polyrepository. Its repositories are the parts of that one app — services, libraries, extracted
fixtures — curated by one maintainer, not an aggregation of arbitrary third-party repos. That
framing is load-bearing wherever this code treats a name collision as the maintainer's own choice,
or `origin` as a backup rather than an authority.

Concretely:

| | |
|---|---|
| `Project` | the aggregate root: name, an immutable git-safe `slug`, its repositories |
| `Repository` | a git remote as an entity — a bare origin under `qits.repositories.data-dir`, cloned/pulled/pushed/synced host-side |
| `repository_name` | addressable `(project, name) → repository` aliases, which is what makes a committed relative submodule url (`../<name>.git`) resolve natively |
| `repository_submodule` | the submodule graph between repositories of one project, deduped per project |
| the wrapper | every project owns exactly one `PROJECT`-archetype repository named `<slug>-<slug>`, seeded from `project-template/` |
| `.qits-config.yml` | ingestion of the repository's own committed configuration, degrading loudly and never blocking |
| remote-login | an interactive PTY sign-in against a repository's backup remote, so a push can prompt for credentials — a `java.lang.foreign` pseudo-terminal (`ForeignPty`) with git launched onto it by `setsid --ctty`, which is the one thing this service needs from the host besides git itself |
| `epics/` | the planning module — epics → features → tasks + an audit log, on its own datasource, depending on nothing else here |

## What it deliberately does NOT own

Anything workspace-shaped: the `Workspace` entity, containers, in-container file access, framework
detection, prompt drafts, workspace history. That is
[qits-workspaces](https://github.com/QuicklyIterateTheSoftware/qits-workspaces). Anything that runs
*inside* a workspace container — commands, terminals, agents — is
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon). The git
smart-HTTP host that serves these bare origins over the wire is
[qits-artifacts](https://github.com/QuicklyIterateTheSoftware/qits-artifacts).

## Layout

    domain/   the aggregate, persistence, control, and the ports out (a library jar, no JAX-RS)
    epics/    the planning module, own datasource + own Flyway lineage, no dependency on domain
    service/  the REST + MCP + websocket boundary over both — THE APPLICATION

`service/` carries `<packaging>quarkus</packaging>` and produces a process, as a JVM fast-jar or as
a native binary:

    ./mvnw verify
    java --enable-native-access=ALL-UNNAMED -jar service/target/quarkus-app/quarkus-run.jar   # :8080

    ./mvnw package -Dnative
    ./service/target/qits-projects                        # same routes, ~60ms to listening

**Native is the shipping form.** `.sdkmanrc` names a GraalVM (`25.0.2-graalce`) so `sdk env` alone
is enough toolchain — the build wants a `native-image` on `GRAALVM_HOME`, `JAVA_HOME` or `PATH`, and
if it finds none it does not fail, it quietly falls back to pulling a 1.8 GB Mandrel image and
running the compile under docker. That fallback still works and is what CI without a GraalVM gets;
it is just not the intended path, and it is worth recognising by name when a compile that normally
takes about a minute starts downloading a container image.

The `--enable-native-access` flag on the JVM line is for the sign-in terminal: it allocates a real
PTY through `java.lang.foreign`, and a runner jar cannot add its own JVM flags. The native binary
needs nothing — it resolved native access at build time.

Everything it serves sits under its gateway segment, `/projects`:

| | |
|---|---|
| `/projects/api/…` | the REST surface (`quarkus.rest.path`) |
| `/projects/api/repositories/{repoId}/remote-login` | the sign-in websocket — a literal `@WebSocket` path, which does **not** follow `quarkus.rest.path` |
| `/projects/mcp` | the MCP server, still *named* `repository` |
| `/projects/q/openapi`, `/projects/q/swagger-ui` | the API document and its UI (`quarkus.http.non-application-root-path`) |

qits-gateway routes verbatim by prefix — `/projects/*` → this service, no rewriting — so the segment
is served here or the service is not reachable through it. There is no unprefixed form.

It was extracted as a library jar, on the reasoning that packaging it would need an auth variant, a
webui and a main class. All three have lapsed: authentication terminates at `qits-gateway` and this
service reads a header, the webui stays in the monorepo, and Quarkus supplies the main class.

Coordinates are namespaced (`eu.wohlben.qits:qits-projects-*`) because the directories are the
generic `domain`/`service`/`epics` and installing `eu.wohlben:domain` would clobber the monorepo jar
in the shared `~/.m2` every workspace container mounts.

## The ports out

This jar depends on no other qits context. Everything it needs from one is an interface declared in
`domain/…/projects/control/` and implemented by the application that assembles the jars. All are
injected as `Instance<T>` and **all are optional** — absent is a supported, documented configuration,
because a deployment that serves repositories, the git host and epics without ever provisioning a
container is a real one.

| Port | Implemented by | Absent behaviour |
|---|---|---|
| `WorkspaceLookup` | qits-workspaces | no branch is workspace-backed: commit logs compare against the repository's main branch, the branch list reports nothing cleanupable, the "branch has child workspaces" delete guard stands down |
| `WorkspaceLifecycle` | qits-workspaces | a cloned repository gets no default workspace; deleting one removes its origin, rows and aliases but reaps no containers or volumes — there are none |
| `TechnicalProcessRegistry` (+ `TechnicalProcess`, `RepoProcessLease`, `RepoReservation`, `TechnicalProcessFrame`) | qits-workspace-daemon | pull/push/sync still run, on the same worker thread, against the same origins — unnarrated, returning a null process id, with no single-flight guard |
| `CommandOutputSink` | the service module's websocket | — (an SPI this context calls, not one it looks up) |

Reached the other way: qits-workspaces' `RepositoryLookup` and `RepositoryAddressResolver`, and
qits-artifacts' `githost.RepositoryNameResolver`, are ports **those** repos declare and this one
satisfies. This jar does not implement them — the assembling application does, by adapting
`RepositoryRepository` / `RepositoryNameRepository` (which live here) to their interfaces. Note the
name collision: artifacts' `githost.RepositoryNameResolver` (a port) and this context's
`control.RepositoryNameResolver` (the alias resolver) are unrelated types.

## Persistence

Its own named datasource `projects`, its own persistence unit, its own Flyway lineage at
`classpath:db/projects/migration` — a file H2 under `~/.qits/data/projects` by default. `V1__init.sql`
is the monorepo's shared V1–V45 squashed to the four tables above (schema as of V45, not a replay).

Those four tables live in **one** database and keep **real foreign keys** between them; that is
where the split was cut, and it is why `Repository.project` is still a JPA relation. Everything
outside them is another context's database and is referenced by string id through a port — never a
join, because a foreign key cannot span two databases.

`epics/` keeps its own separate `epics` datasource and `db/epics/migration` lineage, carried over
from the monorepo verbatim.

## Build

    ./mvnw verify              # JVM: tests + the fast-jar
    ./mvnw verify -Dnative     # the binary, and the ITs against it

Green from a clone of this repo alone — no monorepo, no docker, no credentials, no prior
`mvn install`. Integration tests that need real docker default to skipped (`-DskipITs=false` to
opt in); the `native` profile flips that, so a `-Dnative` build exercises the binary rather than
skipping past it. The git fixtures the suite clones from are built at test time by `GitFixtures`,
not committed as submodules.

The native build needs a `native-image`, which `sdk env` provides from `.sdkmanrc`. Do **not** set
`GRAALVM_HOME` to something else: Quarkus does not fail when it cannot find one, it logs `Cannot
find the native-image … Attempting to fall back to container build` and shells docker. Green either
way, which is why it is worth grepping the log rather than trusting the exit code.
