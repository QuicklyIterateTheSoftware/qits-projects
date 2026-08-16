package eu.wohlben.qits.projects.mcp;

import io.quarkus.test.junit.QuarkusTestProfile;

/** Keeps the MCP 2.x client tests in a separate Quarkus augmentation from REST security tests. */
public final class McpStatelessTestProfile implements QuarkusTestProfile {}
