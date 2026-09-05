package eu.wohlben.qits.projects.workspacehost;

import java.util.Optional;

/**
 * The machine credential this service presents to qits-workspaces, as a seam.
 *
 * <p>An interface rather than the {@code OidcClient} inline, for {@code control/GitHostBearer}'s
 * reason one package over: it is the one part of {@link HttpReleasedBranchWorkspaces} that cannot be
 * pointed at a local stub server, so a wire test would otherwise have to boot an idp to assert a
 * request shape. It is <b>not</b> a port out of the domain — nothing in {@code domain} knows this
 * hop exists, and a bearer is infrastructure rather than a contract with another context.
 *
 * <p>Empty is a supported answer and means <b>do not send the request</b>. The door at the far side
 * destroys a container, so there is deliberately no forwarded-header fallback the way the two
 * qits-ci hops have one.
 */
public interface WorkspacesBearer {

  /** {@code Bearer <token>}, or empty when this hop has no credential to present. */
  Optional<String> authorization();
}
