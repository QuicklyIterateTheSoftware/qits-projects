package eu.wohlben.qits.epics.api;

import io.quarkus.security.identity.SecurityIdentity;

/** Resolves the audit "changed-by" value from the request identity (null when anonymous). */
final class EpicsPrincipal {

  private EpicsPrincipal() {}

  static String changedBy(SecurityIdentity identity) {
    if (identity == null || identity.isAnonymous() || identity.getPrincipal() == null) {
      return null;
    }
    return identity.getPrincipal().getName();
  }
}
