# qits-projects as a GraalVM-native container IMAGE — the same shape as qits-gateway's and
# qits-workspace-daemon's docker/Dockerfile: a Mandrel builder stage compiles the binary, a minimal
# runtime stage carries only it.
#
# This file is not how the binary is built; it is how the binary is SHIPPED. `.sdkmanrc` names
# 25.0.2-graalce, so `sdk env && ./mvnw package -Dnative` produces service/target/qits-projects
# locally with no container involved. What this adds is the image around it — and a way for a
# machine with no GraalVM to get a binary at all, since the builder stage brings its own
# native-image.
#
# Build from the repo root:
#
#     git submodule update --init service/src/main/webui
#     (cd service/src/main/webui && npm ci && npm run build)
#     docker build -t qits/projects:latest -f docker/Dockerfile .
#
# The first two lines are not optional and are not a convenience: this image carries the projects
# client (service/src/main/webui = the qits-spa-projects submodule), and the builder stage below
# packages a bundle that was ALREADY BUILT rather than building one. See the Quinoa flags on the
# mvnw line for why the build cannot happen inside that stage.
#
# --- git and setsid are part of this image, not an optional extra ---------------------------------
# Unlike the gateway (which shells nothing) this service's job IS git on disk: GitExecutor,
# RepositoryService, CommitService and GitRemoteAuth fork `git` for every clone, fetch, push,
# write-tree and log, and the remote-login terminal launches it through `setsid --ctty` so git can
# prompt for credentials on a real TTY. ubi-minimal ships neither. Left out, the image would build
# green, start, serve /projects/q/health — and then fail every repository operation with an
# IOException naming a missing executable, which is the whole service. So the runtime stage installs
# them; see the microdnf line below for which packages and why.
#
# --- `docker run` with no configuration FAILS, and that is the honest behaviour -------------------
# The `domain` and `epics` jars ship their datasource urls as EXPRESSIONS over the platform's generic
# resource contract — `${QITS_RESOURCE_DB_URL}` and `${QITS_RESOURCE_EPICS_URL}`, each with a
# `_USERNAME` and a `_PASSWORD` beside it — and there is no fallback behind them. A bare run leaves
# the expressions unresolvable and the process dies at Flyway's migrate-at-start naming the missing
# variable.
#
# It used to fail for a worse reason and in the same second: the urls were file-backed H2 under
# `${user.home}/.qits/…`, and `USER 1001` has no passwd entry, so `user.home` resolved to the literal
# `?` and H2 refused it as an implicitly relative path. The failure is now a sentence about
# configuration rather than about a path nobody chose, but the stance is unchanged and is
# deliberately NOT patched here with an `ENV HOME=…`: a database inside the container would make the
# bare run "work" by writing state that dies with the container and that no sibling service can
# reach — the failure would move from the first second to the first restart. The same `${user.home}`
# default sits under `qits.projects.data-dir` (this service's own git mirrors and project-template
# scratch, projects-volume-decoupling-plan.md), so it belongs on this list too. What a deployment
# supplies instead:
#
#   QITS_RESOURCE_DB_*  /  QITS_RESOURCE_EPICS_*
#                       two separate lineages on two separate DATABASES. Not hand-set on a real
#                       platform: this repository's .config/qits/deployments.yml declares
#                       `resources: postgresql:db, postgresql:epics:qits_epics`, and
#                       qits-platform-deployments creates the roles and databases and injects both
#                       triples before this container starts. See AGENTS.md.
#   qits.projects.data-dir
#                       this service's own private mirror root — nothing else reads it.
#
#
# What a deployment does NOT supply, because the image names it: `qits.repositories.credentials-file`
# — where `git credential-store` keeps what a remote-login session captures. Settled, and stored on
# disk rather than in SQL, the same reason the git mirrors above are not in the database either.
#
# The on-disk contract that used to put `qits.repositories.data-dir` on this list is two-way now,
# not three: qits-githost serves the bare origins over git smart-HTTP, and qits-workspaces still
# reads pre-move workspace sidecars off the same volume. This service mounts neither — every
# repository it touches is now a private mirror it clones over the wire, through
# `qits.githost.url`.
#
# That split is deliberate, but it is now a three-way one, not two: a database is handed to this
# application by the deployment that provisioned it and can never be guessed, so it refuses to start
# unconfigured; the mirror root has nowhere safe to default to under this user either, so it refuses
# the same way; only the credentials file is genuinely settled, and it is the one that simply works.
#
# There is no dns key to supply any more. A project's domain goes to the `ProjectDomainRegistrar`
# port, nothing implements it since qits-platform-dns left the platform, and dns records are
# configured by hand at the external provider. Creating a project registers nothing and says nothing.
#
# With those seven set — both resource triples and the mirror root — the binary starts in ~65ms,
# migrates both schemas, self-seeds the `qits` project — which forks `git init --bare`, writes a
# tree and clones from a remote — and serves
# /projects/mcp. That run is the check worth doing after a change here, because it is the only one
# that exercises the native image's resource and reflection registrations at all.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
#
# `mirror.dev.localhost:8080/quay/…` is the platform's pull-through mirror of quay.io: the first
# build fetches the image from upstream and caches it, every later build is served from local disk.
# The tag is upstream's, unchanged — the mirror revalidates it, so `jdk-25` still moves when quay
# moves it.
FROM mirror.dev.localhost:8080/quay/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
USER root
WORKDIR /src

# Fetch the Maven distribution first, keyed only on the wrapper files, so a source-only change does
# not re-download it.
COPY mvnw .
COPY .mvn/ .mvn/
RUN ./mvnw --version

# The whole reactor, not a module list. `-am` builds service's dependencies from source — `domain`
# and `epics` are 1.0.0-SNAPSHOTs this repo never publishes (no distributionManagement, no
# <repositories>), so a builder container with an empty ~/.m2 has nowhere else to get them. Copying
# module by module would mean editing this file every time one is added, and forgetting to would
# fail the build with an unresolved dependency rather than anything that names the real cause.
# `.dockerignore` is what keeps this cheap — without it this line drags in every target/ dir, and
# service/target alone is ~237 MB.
COPY . .

# -Dnative flips quarkus.native.enabled (service/pom.xml carries its own `native` profile). Cap
# native-image RAM so the build is deterministic on small machines.
#
# -DskipTests skips execution, not compilation: test sources still compile here, and `service`'s
# testResources copy domain's committed bare git fixtures, so neither is droppable from the context.
# Running them is what is not possible in this stage — the git suites fork real git and
# ForeignPtyTest/RemoteLoginSessionTest open real pseudo-terminals, and PackagedSurfaceIT (which the
# `native` profile un-skips) launches the binary against a datasource. `./mvnw verify` on a clone is
# the gate for all of those.
#
# --- the Quinoa flags, and why this stage no longer builds the client -----------------------------
#
# package-manager-install: the Mandrel builder image ships no node at all, so this stage installs its
# own — from nodejs.org, which a docker build can reach. It lives here rather than in
# application.properties on purpose: a local or CI `mvnw` build must use the node on PATH, so that no
# build silently downloads a toolchain. 22.22.0 is the PLATFORM pin
# (docs/project-setup-quinoa-angular.md in the qits monorepo).
#
# The three command overrides are the load-bearing part, and the reason this file now deviates from
# that recipe (which has the builder run the client's real install and build):
#
#   service/src/main/webui is qits-spa-projects, and it depends on @qits/ui-components. That package
#   exists ONLY on the platform's own npm registry (qits-artifacts). A RUN step in a docker build
#   reaches the public internet — Maven Central, npmjs.org, nodejs.org — and reached the platform
#   registry by no address at all: not the qits-net alias, not registry.dev.localhost:8080, not
#   host.docker.internal. So the install could not happen here, and an install that "worked" here
#   would have resolved the @qits scope against npmjs.org, where that name does not exist and never
#   will. Before the client took that dependency this stage did run the real install; it died on
#   `npm error Exit handler never called!` the first time the webui's own .npmrc pointed at a
#   registry with no route from here.
#
# The bundle is therefore built BEFORE this image, by the pipeline step in
# .config/qits/ci-post-receive.yml, which runs on qits-net where the registry is reachable, and
# arrives in the build context as service/src/main/webui/dist/ (see .dockerignore). `--version` is
# the null command: it proves the package manager is really there and does nothing else.
#
# THE "NO ADDRESS" IS PAST TENSE SINCE THE MAVEN FIX. Both pipelines now build with
# `--network qits-net`, so the platform's aliases do resolve in here — that is what makes the Maven
# repository below reachable. It does not undo the split: the bundle still arrives pre-built, and
# moving that install back into this stage is a change to weigh on its own, not a fix.
#
# THIS DOES NOT FAIL QUIETLY. The `test -f` below stops a context with no bundle before the
# multi-minute native compile, and Quinoa's own build-dir check would stop it after. A forgotten
# `npm run build` in the step is a red build, not a service that ships without its client. (The same
# guard covers the case this comment used to describe on its own: an uninitialised submodule, which
# git leaves as an empty directory and Quinoa rejects with `No package.json found in Web UI
# directory`.)
#
# --- and why the bundle is copied onto itself first -----------------------------------------------
# Quinoa does not copy build-dir into target/quinoa/build, it MOVES it, and overlayfs cannot rename a
# directory that still lives in a LOWER image layer. It answers EXDEV, the JDK falls back to its
# cross-file-store path, and that path refuses a non-empty directory — the build dies seconds in with
# `DirectoryNotEmptyException: …/dist/qits-spa-projects/browser`, which names the source and explains
# nothing. A builder stage that *creates* dist itself is immune by accident; ours arrives by COPY.
#
# One `cp` re-materialises it in the layer that is about to move it. It has to be in THIS `RUN` and
# not a tidier one of its own — a layer boundary is exactly what it is undoing.
#
# --- the platform's Maven repository, which this stage has to be TOLD ------------------------------
# `domain` and `epics` depend on qits-eventstream, `service` on qits-githost-events, and the test
# tree on qits-arch-rules — three jars published nowhere but the platform's own Maven repository.
# The pom defaults its address to registry.dev.localhost:8080, right for a developer on the
# deployment host and dead inside this container, where that vhost resolves to nothing. That is how
# the first CI image build after the causation work died: `Could not transfer artifact …
# qits-eventstream … Connection refused`. So the address arrives as a build-arg — defaulted to the
# developer-host one, so a plain host-side `docker build` still works — and the pipeline passes the
# network address along with `--network qits-net`, which is what makes the repository reachable at
# all.
#
# ENV as well as ARG, and a settings file, because Maven refuses plain-HTTP repositories that are
# not on localhost. `.qits-maven-settings.xml` mirrors the `qits-maven` id by exact match, which
# beats that blocker, and points it at `${env.QITS_MAVEN_REPOSITORY_URL}` — so it permits this one
# supplied URL rather than reopening HTTP for every repository.
ARG QITS_MAVEN_REPOSITORY_URL=http://registry.dev.localhost:8080/artifacts/maven/maven
ENV QITS_MAVEN_REPOSITORY_URL=$QITS_MAVEN_REPOSITORY_URL
#
# --- and where Maven Central is mirrored, when it is ----------------------------------------------
# The pipeline passes the platform's pull-through (qits-platform-mirror's central proxy, the same
# authority the FROM lines above pull through); EMPTY — the default — means direct Maven Central.
# `.qits-maven-settings.xml` activates its central-proxy profile only on a non-empty value, so a
# bootstrap or hand build that passes nothing resolves exactly as it always did.
ARG QITS_MAVEN_CENTRAL_URL=
ENV QITS_MAVEN_CENTRAL_URL=$QITS_MAVEN_CENTRAL_URL
#
# --- and how Maven authenticates, once that repository stops answering anonymously ----------------
# The two secret mounts put the CI run's commissioned client into THIS `RUN`'s environment and
# nowhere else — not a layer, not the image history. That is why they are not build args: this image
# is pullable, and a build arg is recorded where anyone who pulls it can read it.
# `.qits-maven-settings.xml` reads the two variables as the `qits-maven-network` server.
#
# Absent secrets leave both variables unset, which is the state every build is in today and the
# state a host-side `docker build` is in permanently: Maven sends credentials only when a repository
# answers 401, and the registry does not yet.
RUN --mount=type=secret,id=qits-client-id,env=QITS_MAVEN_AUTH_USR \
    --mount=type=secret,id=qits-client-secret,env=QITS_MAVEN_AUTH_PSW \
    d=service/src/main/webui/dist/qits-spa-projects/browser; \
    test -f "$d/index.html" || { \
      echo "No built client at $d." >&2; \
      echo "The build context must carry one: run 'npm ci && npm run build'" >&2; \
      echo "in service/src/main/webui first — this stage cannot, see the Quinoa flags above." >&2; \
      exit 1; }; \
    cp -a "$d" "$d.layer" && rm -rf "$d" && mv "$d.layer" "$d" && \
    ./mvnw -B -ntp -s .qits-maven-settings.xml -pl service -am package -Dnative -DskipTests \
      -Dqits.maven.repository.url="$QITS_MAVEN_REPOSITORY_URL" \
      -Dquarkus.native.native-image-xmx=4g \
      -Dquarkus.quinoa.package-manager-install=true \
      -Dquarkus.quinoa.package-manager-install.node-version=22.22.0 \
      -Dquarkus.quinoa.package-manager-command.install=--version \
      -Dquarkus.quinoa.package-manager-command.ci=--version \
      -Dquarkus.quinoa.package-manager-command.build=--version

# --- runtime --------------------------------------------------------------------------------------
# `mirror.dev.localhost:8080/redhat/…` is the same mirror, pointed at registry.access.redhat.com.
FROM mirror.dev.localhost:8080/redhat/ubi9/ubi-minimal:9.6
WORKDIR /work

# `git-core` rather than `git`: the porcelain this service actually forks (clone/fetch/push/log/
# write-tree/update-ref, plus `credential.helper=store`) is all in git-core, while the `git` package
# adds the perl subcommands nothing here calls. It pulls openssh-clients, which is not incidental —
# a repository's backup remote may well be an ssh url. `util-linux-core` is for `setsid --ctty`,
# without which the sign-in terminal's git finds no controlling terminal, never prompts, and fails
# non-interactively instead of asking for the credentials the session exists to collect.
# --nodocs and no weak deps because an image is not a workstation.
#
# Those two are the whole list. A project's agent container is started through **qits-containers**
# over HTTP — `qits.containers.url` (`QITS_CONTAINERS_URL`) plus a machine token — so this image
# needs no docker CLI and no socket mount. It used to carry a `docker-ce-cli` from Docker's own
# repository, for a runtime that shelled the binary; that runtime is deleted.
RUN microdnf install -y --nodocs --setopt=install_weak_deps=0 git-core util-linux-core \
 && microdnf clean all

# `quarkus.package.output-name` names the binary `qits-projects` (no -runner suffix), so the process
# is identifiable in `ps`/`docker top`. It is built by `service`, the one module of the three that is
# an application; `domain` and `epics` are library jars with nothing to native-compile.
COPY --from=build --chown=1001:root /src/service/target/qits-projects /work/qits-projects
RUN chmod +x /work/qits-projects

# The mount point for this service's own volume, created here so it is OWNED. Docker seeds a fresh
# named volume from whatever the image has at the mount path, ownership included; with no such
# directory it makes one root-owned 0755 and the boot dies on an AccessDeniedException rather than
# on the config error, which reads like a bug in this image and is not one. Group-writable and
# group-root because a ubi image is meant to survive being assigned an arbitrary uid with gid 0. (A
# bind mount still takes its ownership from the host — nothing in an image can change that.)
RUN mkdir -p /data && chown -R 1001:root /data && chmod -R 775 /data

# The credentials file, named here rather than left to a deployment: nothing in it is stored in SQL,
# and the path has no way of being wrong. The one other thing this service keeps on `/data` — the
# git mirrors (`qits.projects.data-dir`) — is a deployment's to name, per the header comment above.
# The databases are on nobody's volume any more: they live on the tier's postgres, provisioned per
# `resources:` entry.
#
# Note what is NOT set beside it: no QITS_RESOURCE_* and no qits.projects.data-dir. That asymmetry
# is the point — the credentials path works out of the box, the rest is left for a deployment to
# supply.
ENV QITS_REPOSITORIES_CREDENTIALS_FILE=/data/git-credentials

EXPOSE 8080
USER 1001

# Everything this service serves hangs off /projects — /projects/api/*, /projects/mcp, /projects/q/*
# — because qits-gateway routes verbatim by prefix. The paths are in application.properties, not
# here; see README.md.
ENTRYPOINT ["/work/qits-projects", "-Dquarkus.http.host=0.0.0.0"]
