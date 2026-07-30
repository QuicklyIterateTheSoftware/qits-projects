package eu.wohlben.qits.projects.api;

import eu.wohlben.qits.projects.entity.ProjectDnsRecordType;

/**
 * The one valid {@code dns} object every creation payload in the suite needs, in one place.
 *
 * <p>{@code POST /projects/api/projects} requires it (main-environment-plan.md §1), so every test
 * that creates a project has to send one whether or not the test is about domains — and a test
 * about repositories, epics, submodules or MCP tools should not have to make a decision about dns
 * names to say so. {@link #DNS} is that decision, made once.
 *
 * <p>{@code example.test.eu} on purpose: a name under a reserved TLD, so a payload that ever
 * escaped a test into a real deployment would register something inert rather than hijack a live
 * hostname. Tests that are <em>about</em> the record — round-tripping it, rejecting a hostile one —
 * spell their own value out inline, because there the value is the assertion.
 */
public final class ProjectRequests {

  public static final ProjectController.CreateProjectRequest.DnsSpec DNS =
      new ProjectController.CreateProjectRequest.DnsSpec(
          "example.test.eu", ProjectDnsRecordType.A, "203.0.113.9");

  private ProjectRequests() {}
}
