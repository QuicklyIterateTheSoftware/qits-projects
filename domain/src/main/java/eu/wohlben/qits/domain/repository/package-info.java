/**
 * The repository domain: {@code Repository} rows (with their submodule sibling graph) and the
 * workspaces + containers that iterate on them. Read this before reasoning about "what could go
 * wrong" in the submodule / provisioning code here — the intended model collapses a lot of apparent
 * blast radius. Full narrative: {@code docs/guides/project-model.md}.
 *
 * <h2>The model in one paragraph</h2>
 *
 * A {@link eu.wohlben.qits.domain.project.entity.Project} is <b>one application, organized as a
 * polyrepository</b> — its repositories are the parts of that single app (microservices, shared
 * libraries, extracted fixtures), curated together by <b>one maintainer/team</b>. They are
 * <b>not</b> an aggregation of arbitrary third-party repos. Repositories are the domain qits exists
 * to manage. A configured git <b>remote is only a backup</b>: git is distributed, so a periodic
 * {@code push}/{@code pull} to {@code origin} is the cheapest disaster-recovery — the <b>local
 * clones on the qits instance are authoritative</b>, and {@code origin} is pulled from only
 * deliberately, to recover if the instance is lost. <b>Project grouping + submodule import</b>
 * ({@link eu.wohlben.qits.domain.repository.entity.RepositorySubmodule}, sibling repos served under
 * {@code /git/<projectId>/<name>}) is the <b>technical necessity that lets a local workspace
 * materialize the whole curated repo graph offline</b> from qits' own git host — nothing more.
 *
 * <h2>Trust / blast-radius calibration</h2>
 *
 * Because a project is one team's curated repo set, when weighing severity here:
 *
 * <ul>
 *   <li><b>Repository names within a project are the maintainer's own choice.</b> A basename
 *       collision between two repos in the same project is a naming decision, not an adversarial
 *       input — "a colliding name resolves a submodule to an unrelated repo" is a fixable mistake
 *       in the maintainer's own project, not an outside threat.
 *   <li><b>A repo's submodules are other components of the same application</b>, not arbitrary URLs
 *       smuggling in unrelated content.
 *   <li><b>{@code origin} is a backup, not an authority</b> — a wrong/stale remote can't corrupt
 *       the working set; the local clones are the source of truth.
 * </ul>
 *
 * This calibrates severity; it does not hide real divergences from the model — where an
 * implementation genuinely differs (e.g. the workspace-daemon's autonomous self-clone materializing
 * submodules from {@code .gitmodules} rather than the DB imported-edge closure), that is documented
 * at the source. See {@code docs/guides/project-model.md} and {@code
 * docs/epics/qits-project-repository-submodules/}.
 */
package eu.wohlben.qits.domain.repository;
