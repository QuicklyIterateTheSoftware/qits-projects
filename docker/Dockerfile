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
#     docker build -t qits/projects:latest -f docker/Dockerfile .
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
# The `domain` and `epics` jars default their datasources to file-backed H2 under
# `${user.home}/.qits/…`. In this image `USER 1001` has no passwd entry, so `user.home` resolves to
# the literal `?` and the process dies during Flyway's first connect with:
#
#     A file path that is implicitly relative to the current working directory is not allowed in the
#     database URL "jdbc:h2:file:?/.qits/data/epics/h2/epics" [90011-240]
#
# That is a missing-configuration failure, not a packaging one, and it is deliberately NOT patched
# here with an `ENV HOME=…`: a home dir inside the container would make the bare run "work" by
# writing a database and a repositories tree that die with the container and that no sibling service
# can reach — the failure would move from the first second to the first restart. What a deployment
# supplies instead:
#
#   quarkus.datasource.projects.*  /  quarkus.datasource.epics.*
#                       two separate lineages on two separate datasources; see AGENTS.md.
#
#
# What a deployment does NOT supply, because the image names it: `qits.repositories.data-dir` and
# `qits.repositories.credentials-file`. Those are the bare origins — settled, and stored on disk
# rather than in SQL — so they resolve correctly out of the box, at `/data/repositories`, the same
# literal qits-artifacts and qits-workspaces ship. qits-artifacts serves those trees over git
# smart-HTTP and qits-workspaces branches from them, so it is a VOLUME three containers mount at the
# same path, not a scratch dir; naming it once per image is what stops the three drifting apart.
#
# That split is deliberate: the H2 arrangement is a prototype and must not look decided, so it
# refuses to start unconfigured — while the git tree is not the prototype and simply works.
#
# With those four set the binary starts in ~65ms, migrates both schemas, self-seeds the `qits`
# project — which forks `git init --bare`, writes a tree and clones from a remote — and serves
# /projects/mcp. That run is the check worth doing after a change here, because it is the only one
# that exercises the native image's resource and reflection registrations at all.
#
# --- stage `build`: native-compile ----------------------------------------------------------------
# The builder JDK MUST be 25: native-image cannot read bytecode newer than its own JDK, and this
# project targets release 25.
FROM quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25 AS build
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
RUN ./mvnw -B -ntp -pl service -am package -Dnative -DskipTests \
      -Dquarkus.native.native-image-xmx=4g

# --- runtime --------------------------------------------------------------------------------------
FROM registry.access.redhat.com/ubi9/ubi-minimal:9.6
WORKDIR /work

# `git-core` rather than `git`: the porcelain this service actually forks (clone/fetch/push/log/
# write-tree/update-ref, plus `credential.helper=store`) is all in git-core, while the `git` package
# adds the perl subcommands nothing here calls. It pulls openssh-clients, which is not incidental —
# a repository's backup remote may well be an ssh url. `util-linux-core` is for `setsid --ctty`,
# without which the sign-in terminal's git finds no controlling terminal, never prompts, and fails
# non-interactively instead of asking for the credentials the session exists to collect.
# --nodocs and no weak deps because an image is not a workstation.
RUN microdnf install -y --nodocs --setopt=install_weak_deps=0 git-core util-linux-core \
    && microdnf clean all

# `quarkus.package.output-name` names the binary `qits-projects` (no -runner suffix), so the process
# is identifiable in `ps`/`docker top`. It is built by `service`, the one module of the three that is
# an application; `domain` and `epics` are library jars with nothing to native-compile.
COPY --from=build --chown=1001:root /src/service/target/qits-projects /work/qits-projects
RUN chmod +x /work/qits-projects

# The mount point for the git tree, created here so it is OWNED. Docker seeds a fresh named volume
# from whatever the image has at the mount path, ownership included; with no such directory it makes
# one root-owned 0755 and the boot dies on an AccessDeniedException rather than on the config error,
# which reads like a bug in this image and is not one. Group-writable and group-root because a ubi
# image is meant to survive being assigned an arbitrary uid with gid 0. (A bind mount still takes its
# ownership from the host — nothing in an image can change that.)
RUN mkdir -p /data/repositories && chown -R 1001:root /data && chmod -R 775 /data

# The git tree and the credentials beside it, named here rather than left to a deployment: they are
# not the prototype, and nothing in them is stored in SQL — they are bare origins on disk.
# `/data/repositories` is a literal qits-artifacts and qits-workspaces also ship, because the
# three-way contract in the header only holds if all three resolve it to the same volume, and three
# services each told the same string separately is three chances to get it wrong.
#
# Note what is NOT set beside them: no QUARKUS_DATASOURCE_*. That asymmetry is the point — the git
# side works out of the box, the prototype database refuses to.
ENV QITS_REPOSITORIES_DATA_DIR=/data/repositories \
    QITS_REPOSITORIES_CREDENTIALS_FILE=/data/git-credentials

EXPOSE 8080
USER 1001

# Everything this service serves hangs off /projects — /projects/api/*, /projects/mcp, /projects/q/*
# — because qits-gateway routes verbatim by prefix. The paths are in application.properties, not
# here; see README.md.
ENTRYPOINT ["/work/qits-projects", "-Dquarkus.http.host=0.0.0.0"]
