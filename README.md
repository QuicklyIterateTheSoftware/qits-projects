# qits-projects-service

The **project, repository and planning** context of [qits](https://github.com/QuicklyIterateTheSoftware),
extracted from the monorepo with its history (see `migration-plan.md` §3.1 there).

## What it owns

A **project is its wrapper repository.** It starts as a single repository and grows into a
polyrepository, and the wrapper's `.gitmodules` is the project's configuration: every component is a
submodule under `components/<component>/`, its own name saying the role it plays, and a repository
the wrapper does not name is not part of the project — the reconcile reports such a row `UNDECLARED` and the listing marks it
`declared: false`, but nothing deletes it on its own, because deleting a repository now deletes it on
the git host too. Importing a wrapper url restores the whole project. Submodule urls are
**relative** (`../<name>.git`), so the same wrapper resolves its siblings at a forge and on this
platform's name-addressed git route with nothing to rewrite in between.

Its repositories are the parts of that one app — services, daemons, libraries, frontends — curated
by one maintainer, not an aggregation of arbitrary third-party repos. That framing is load-bearing
wherever this code treats a name collision as the maintainer's own choice, or `origin` as a backup
rather than an authority.

Concretely:

| | |
|---|---|
| `Project` | the aggregate root: name, an immutable and **unique** git-safe `slug`, its repositories |
| `Repository` | a git remote as an entity — a private mirror under `qits.projects.data-dir`, cloned/pulled/pushed/synced host-side against the git host and the row's own backup remote |
| `repository_name` | addressable `(project, name) → repository` aliases, which is what makes a committed relative submodule url (`../<name>.git`) resolve natively |
| the wrapper | every project owns exactly one `PROJECT`-archetype repository named `<slug>-<slug>`, seeded from `project-template/` |
| the project's domain | a `{domain, type, value}` dns record embedded on `Project` — required when a project is created, offered to a nameserver through a port nothing implements today, and a **declared placeholder**: when a service owns domain configuration the embeddable and its three columns go (`ProjectDnsRecord`, `main-environment-plan.md` §1) |
| `.qits-config.yml` | ingestion of the repository's own committed configuration, degrading loudly and never blocking |
| remote-login | an interactive PTY sign-in against a repository's backup remote, so a push can prompt for credentials — a `java.lang.foreign` pseudo-terminal (`ForeignPty`) with git launched onto it by `setsid --ctty`, which is the one thing this service needs from the host besides git itself |
| `epics/` | the planning module — epics → features → tasks + an audit log, on its own datasource, depending on nothing else here |

## What it deliberately does NOT own

Anything workspace-shaped: the `Workspace` entity, containers, in-container file access, framework
detection, prompt drafts, workspace history. That is
[qits-workspaces-service](https://github.com/QuicklyIterateTheSoftware/qits-workspaces-service).
Anything that runs *inside* a workspace container — commands, terminals, agents — is
[qits-workspace-daemon](https://github.com/QuicklyIterateTheSoftware/qits-workspace-daemon). The git
smart-HTTP host that serves these bare origins over the wire is
[qits-githost-service](https://github.com/QuicklyIterateTheSoftware/qits-githost-service).

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

**The client is served at `/` on this service's own host**, `projects.<env>.<domain>`, and every
machine surface keeps the segment `/projects`:

| | |
|---|---|
| `/` | the Angular SPA, built from `service/src/main/webui` by Quinoa and served by this process (`quarkus.quinoa.ui-root-path=/`); unmatched paths fall back to `index.html`, so the client's own router gets its deep links — except under the prefix below. This is also the platform's landing page: the edge sends `/` on the environment host here |
| `/projects/api/…` | the REST surface (`quarkus.rest.path`) |
| `/projects/api/projects/{projectId}/repositories/by-name/{repoName}` | `(project, name) → repositoryId`, what the git host resolves its name-addressed route `/git/<projectId>/<repoName>` through. Requires `qits:system` — the only route the git host calls, now that the post-receive intake has become a domain event |
| `/projects/api/repositories/{repoId}/remote-login` | the sign-in websocket — a literal `@WebSocket` path, which does **not** follow `quarkus.rest.path` |
| `/projects/mcp` | the MCP server, still *named* `repository` |
| `/projects/q/openapi`, `/projects/q/swagger-ui` | the API document and its UI (`quarkus.http.non-application-root-path`) |

The edge path-routes `/projects/*` here from **every** vhost, verbatim and with no rewriting, so a
same-origin API call works from any application's host. The client's own addresses start at the
first path segment — `/<projectSlug>/`, `/<projectSlug>/<category>/<repoName>/` — which is what
leaves no segment to spend on naming the application.

The SPA now takes the *whole root*, so it is the one that can swallow everything else: the deep-link
fallback answers anything that matched no route with `200 text/html`. That is right for a person and
wrong for a machine, which parses `index.html` as garbage data — so
`quarkus.quinoa.ignored-path-prefixes=/projects` says what the fallback may not reach. **The values
are absolute request paths** now that `ui-root-path` is `/`, and one prefix covers the lot:
`/projects/api`, `/projects/q`, `/projects/mcp` and both daemon harnesses are all under it. Setting
the key replaces Quinoa's derivation rather than extending it, and the derivation was incomplete
here anyway — it reads `quarkus.rest.path` and `quarkus.http.non-application-root-path` and knows
nothing of `/projects/mcp`, which is how `/projects/mcp/typo` once answered `200` HTML while
`/projects/mcp` itself answered `405`.

It was extracted as a library jar, on the reasoning that packaging it would need an auth variant, a
webui and a main class. All three have lapsed: authentication terminates at `qits-gateway` and this
service reads a header, Quarkus supplies the main class, and the webui is now
[qits-projects-frontend](https://github.com/QuicklyIterateTheSoftware/qits-projects-frontend) —
a repository of its own, checked out as a submodule at `service/src/main/webui` and built into this process by
Quinoa.

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
| `ProjectDomainRegistrar` | **nothing, today** — qits-platform-dns implemented it and was removed from the platform | a created project's domain is stored and registered nowhere, which is what a project whose dns lives at a registrar's control panel wants — and is the state every deployment is in |
| `CommandOutputSink` | the service module's websocket | — (an SPI this context calls, not one it looks up) |

`ProjectDomainRegistrar` is the hook where a project's domain would be registered in DNS, and
**nothing implements it right now**: qits-platform-dns was the implementation and the platform no
longer runs it, so dns records are configured by hand at the external provider. The port stays as the
documented place a replacement plugs into, and the two behaviours to expect until one does:

- **Creation registers nothing, silently.** The hook is **fire-and-forget** — `ProjectService.create`
  calls it after its transaction commits and swallows every failure, because a project must never
  fail to exist because a sibling service was down — so with no implementation the loop simply runs
  zero times. The record is stored on the project either way.
- **`POST /projects/api/projects/{projectId}/reconcile` answers `FAILED`**, saying no registrar is
  wired, rather than a cheerful `REGISTERED`. The endpoint stays: it re-asserts the record
  **synchronously** through the same port and reports the outcome
  (`REGISTERED`/`NO_MATCHING_ZONE`/`NOT_CONFIGURED`/`FAILED`), which is what makes it the remedy the
  moment an implementation exists (`ProjectReconcileService`, `main-environment-plan.md` §5). A
  project storing no record at all still answers `NOT_CONFIGURED`.

**A project no longer announces a deployment environment.** It used to `POST /cd/api/environments`
per project, through a `ProjectEnvironmentNotifier` port. qits-cd owns environments now: they are
deliberate tiers (`dev`, and later `preprod`/`prod`) created over qits-cd's own REST surface, one per
tier and not one per project. Nothing here creates, names or reconciles one.

Reached the other way: qits-workspaces-service's `RepositoryLookup` and `RepositoryAddressResolver`,
and qits-githost-service's `githost.RepositoryNameResolver`, are ports **those** repos declare and
this one satisfies. This jar does not implement them — the assembling application does, by adapting
`RepositoryRepository` / `RepositoryNameRepository` (which live here) to their interfaces, or, for
a service that runs apart, by calling the by-name route in the table above. Note the name collision:
qits-githost-service's `githost.RepositoryNameResolver` (a port) and this context's
`control.RepositoryNameResolver` (the alias resolver) are unrelated types.

## The startup self-seed

A packaged run reconciles a `qits` project (slug `qits`) on every boot — `StartupSelfSeed` gates it to `LaunchMode.NORMAL`, `SelfSeedService`
is the reconcile. It is additive and per-item idempotent: nothing is deleted, nothing already there
is modified, and one failing item never denies the rest.

The in-code manifest is **one entry**: the wrapper repository (`qits-qits`), which is the one thing
the wrapper's own `.gitmodules` cannot name. Everything else comes from it — the project is created
from the wrapper url so its `.gitmodules` actually arrives, and `WrapperReconcileService` then
registers one repository per entry. The entry is also re-asserted onto its row on every boot, which
is how a wrapper pointed at the wrong forge namespace healed itself.

It used to carry a second, hand-maintained list naming every platform repository the git host serves
with its archetype spelled out beside it. That list is gone. The superproject's own `.gitmodules` is
that list, it is committed where the repositories live, and a repository joins qits by being added
to the wrapper rather than to a Java file that has to be deployed to take effect.

Adoption is still how the platform's own repositories get their rows, and it takes **two
coordinates**: the storage id the git host already holds the bare under, and the addressable name.
Those repositories reach the git host without passing through this service — the bootstrap creates
the bare and records its id — and then accumulate pushes, ci runs and deployments while no
`Repository` row names them. The row therefore keeps the host's id rather than minting a new one (a
fresh id would be attached to nothing), and registers the name in `repository_name`, which is the
only thing that resolves `/git/<projectId>/<name>`.
`RepositoryService.adoptExistingOrigin` is the seam, and its javadoc carries what it deliberately
does not do — no clone, no `origin` remote (so pull and push are not wired for an adopted
repository), no workspace. A wrapper entry with no origin on this host and no reachable backend is
skipped, with a warning, on every boot until the day that origin appears.

Two keys, both defaulted so a deployed platform sets neither:

    qits.startup-seed.enabled=true                 # the kill switch — off, the seed does not run at all
    qits.startup-seed.reconcile-repositories=true  # off, the seed stops after the project and its wrapper
    qits.startup-seed.wrapper-url=                 # redirect the wrapper clone source (a mirror, a fork, a fixture)
    qits.startup-seed.dns-domain=                  # all three or none — the domain the seeded project resolves through
    qits.startup-seed.dns-type=
    qits.startup-seed.dns-value=

**`reconcile-repositories` is for a first bootstrap and nothing else** (env
`QITS_STARTUP_SEED_RECONCILE_REPOSITORIES`). At seed time the git host is empty and the bares are
keyed by **minted storage ids**, so the adopt arm's `GET /git/<name>` can never match and every
wrapper entry would fall through to the clone arm — mirroring all of qits in from GitHub before the
bootstrap has seeded a single bare. Off, the seed creates the project and its wrapper origin exactly
as always and stops there; the bootstrap then adopts the repositories itself, and the next boot's
reconcile matches every entry by its alias.

## The backup twin

Every repository this platform serves has a **forge twin**: the GitHub repository its project's
wrapper implies. `Repository.url` is that twin's address and has never been anything else — the
platform clones through its own git host, always, so nothing has ever cloned from this column.
`RepositoryDto` says `backupUrl` now; `url` is the same value under its old, misleading name and
goes next release.

The address is **derived, not configured**: the wrapper's own forge url folded with the component's
`../<name>.git`, which is by construction the sibling a clone of the superproject resolves. The
reconcile applies that on every run, so a row with a stale twin or none at all is corrected
(`SYNC_TARGET_UPDATED`), and a derivation that would point back at a qits git host is refused with a
warning rather than written — a repository cannot be its own backup.

Two triggers keep the twin current, and they are not redundant:

| | |
|---|---|
| the git host's SCM events | `SCMPublishCommit`, `SCMPublishTag`, `SCMDeleteBranch` and `SCMDeleteTag` on the platform bus, consumed **durably** by `bus/ScmBackupTriggerListener`, so the twin is current within seconds of real work and a push that landed while this service was restarting is caught up rather than lost. All four map to one backup of the repository they name — a deletion and a tag change the refs a twin should hold exactly as a commit does, which the old HTTP hook never told us. `suppressCi` is ignored: it says whether a *build* should run, and an imported history is precisely the push that must not build and must be backed up. Debounced per repository, because one `git push` is several events |
| the hourly sweep | `ScheduledBackupSweep`, packaged runs only. The safety net for what no event can announce: a forge that was down for a minute, a credential that expired between pushes, a backup that failed for its own reasons |
| `POST /projects/api/repositories/{repoId}/backup-sync` | ask for one now — the button beside a red status. 202, debounced like the rest |
| `POST /projects/api/projects/{projectId}/repositories/backup-sync` | ask for all of them now, `{scheduled: <n>}` — what you press after a sign-in |

Every attempt, whichever trigger made it, is recorded on the row and surfaced as `RepositoryDto`'s
`lastBackup: {outcome, at, detail}` — absent meaning never attempted, which is deliberately not the
same as failing. The outcome is one of `SUCCEEDED`, `AUTH_REQUIRED`, `UNREACHABLE`, `FAILED`,
because those four ask different things of a person: a credential wall is a sign-in away, an
unreachable forge usually fixes itself, and the rest is worth reading `detail` for. A success clears
`detail`.

**`AUTH_REQUIRED` is fixed in the browser, once.** The sign-in terminal
(`/projects/api/repositories/{repoId}/remote-login`) runs an interactive `git push` in a host-side
PTY; git prompts, and on success `git credential-store` writes the shared file at
`qits.repositories.credentials-file`. That store is keyed by host, not by repository, and every
remote git verb here goes through it — so one sign-in against a forge restores the backups of every
repository on it.

Both go through `BackupPushService`, which swallows every failure — there is no caller left to hand
one to — and logs an auth wall by name through the same classifier the sign-in terminal uses. The
drift a failed backup leaves is what `syncStatus` already reports on the repository's own screen.

    qits.projects.backup.enabled=true     # the kill switch, honoured by BOTH triggers
    qits.projects.backup.interval=1h      # how often the sweep runs
    qits.projects.backup.debounce-ms=2000 # how long a push-triggered backup waits to collect its siblings

## Persistence

Its own named datasource `projects`, its own persistence unit, its own Flyway lineage at
`classpath:db/projects/migration` — a **PostgreSQL** database of its own. `V1__init.sql` is the whole
schema as it stood: the H2 lineage's V1–V6 arrived at and translated, not replayed, because the move
onto postgres was an unwrap and a re-bootstrap rather than a data migration. V2 adds the platform's
causation column, V3 the agent-container credential table.

Those three tables live in **one** database and keep **real foreign keys** between them; that is
where the split was cut, and it is why `Repository.project` is still a JPA relation. Everything
outside them is another context's database and is referenced by string id through a port — never a
join, because a foreign key cannot span two databases.

`agent_credential` (V3) is the one table in this database with **no** relation to the other three,
deliberately: one row per project holding the idp client commissioned for that project's agent
container. A container outlives its project, so a foreign key would drop the row while the container
still held the credential. See AGENTS.md, "The commissioned credential".

`epics/` keeps its own separate `epics` datasource, its own `db/epics/migration` lineage and its own
physical database — which is what makes it liftable without moving anybody else's tables.

**Both databases are asked for, not configured.** `.config/qits/deployments.yml` carries

    resources: postgresql:db, postgresql:epics:qits_epics

so qits-platform-deployments creates a login role and a database for each before this container
starts (`qits_projects` — the default derived from the application name — and `qits_epics`) and
injects `QITS_RESOURCE_DB_URL`/`_USERNAME`/`_PASSWORD` and the `EPICS` triple beside it. The two
library jars map those onto `quarkus.datasource.*` themselves; a deployment names no JDBC url. There
is no fallback: an unset variable stops the process at Flyway with the missing name in the message,
rather than opening a store nobody meant.

## Build

    ./mvnw verify              # JVM: tests + the fast-jar
    ./mvnw verify -Dnative     # the binary, and the ITs against it

Green from a clone of this repo alone — no monorepo, no docker, no credentials, no prior
`mvn install`. Integration tests that need real docker default to skipped (`-DskipITs=false` to
opt in); the `native` profile flips that, so a `-Dnative` build exercises the binary rather than
skipping past it. The git fixtures the suite clones from are built at test time by `GitFixtures`,
not committed as submodules.

The **webui is the one submodule**, and it is what a `package` needs beyond a clone:

    git submodule update --init                        # service/src/main/webui
    git -C service/src/main/webui switch main          # --init leaves it detached

Quinoa runs `npm install && npm run build` in that directory during augmentation, so a **node and an
npm have to be on `PATH`** — the Angular CLI at 21 wants node `^20.19 || ^22.12 || >=24`. Neither the
submodule nor node reaches `./mvnw verify`: Quinoa is disabled in test mode by default, so the suite
stays green from a bare clone on a machine with no node at all. Only building the artifact builds the
UI. Note `docker/Dockerfile`'s Mandrel builder stage ships no node, so the image build needs one
installed there.

The native build needs a `native-image`, which `sdk env` provides from `.sdkmanrc`. Do **not** set
`GRAALVM_HOME` to something else: Quarkus does not fail when it cannot find one, it logs `Cannot
find the native-image … Attempting to fall back to container build` and shells docker. Green either
way, which is why it is worth grepping the log rather than trusting the exit code.

Released through the new release-request flow on 2026-09-04, verifying the deploy path end to end.
