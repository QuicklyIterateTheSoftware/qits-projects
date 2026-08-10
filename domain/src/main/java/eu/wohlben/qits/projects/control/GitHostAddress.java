package eu.wohlben.qits.projects.control;

import eu.wohlben.qits.projects.gitmirror.GitRemotes;

/**
 * Where a repository answers as a <em>git remote</em> — the platform's own git host, the url this
 * service mirrors from and pushes to (projects-volume-decoupling-plan.md §3.2).
 *
 * <p>Before this port existed, this context held every repository's bare origin on a volume it
 * shared with qits-githost and qits-workspaces, and advanced refs by writing them there — which
 * is why no branch create, delete or pulled-in commit ever fired {@code post-receive} or produced a
 * CI run.
 * Every one of those becomes a push over HTTP to the ordinary git host now, so receive-pack is the
 * sole writer of every ref and the existing post-receive → qits-ci → build chain happens for the
 * ordinary reason.
 *
 * <p>A port rather than a config lookup for the reason every seam in this repo is one: the address
 * of another service is deployment knowledge, and the suite needs the same flows pointed at a local
 * bare so a real fast-forward compare-and-swap can be asserted with no HTTP git host in the reactor.
 * {@link ConfiguredGitHostAddress} is the shipped implementation and is {@code @DefaultBean}, so a
 * test-scoped bean of this type simply wins.
 *
 * <p>It <b>extends {@link GitRemotes}</b>, which {@code gitmirror} declares, so nothing has to adapt
 * one to the other: that module named the shape it needs, this port names it in this context's own
 * vocabulary, and config decides what it returns. The two methods are one string in every
 * deployment — see {@link GitRemotes} for the one reason a test double distinguishes them.
 */
public interface GitHostAddress extends GitRemotes {

  /**
   * The remote for {@code repoId}. Any string {@code git} accepts as a remote: the platform's is
   * {@code <qits.githost.url>/git/<repoId>}.
   */
  @Override
  String fetchUrl(String repoId);

  /** The same remote, asked once immediately before a push. */
  @Override
  String pushUrl(String repoId);
}
