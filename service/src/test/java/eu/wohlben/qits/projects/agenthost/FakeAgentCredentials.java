package eu.wohlben.qits.projects.agenthost;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The suite's {@link AgentCredentials}: an in-memory commission table and a log of the verbs the
 * harness called on it. No idp and no network, because {@code ./mvnw verify} has to be green from a
 * clone of this repository alone.
 *
 * <p>It wins over {@code idphost/IdpAgentCredentials} for free — that bean is {@code @DefaultBean} —
 * the same arrangement {@link FakeContainerRuntime} has.
 *
 * <p><b>Off by default, and every test that wants commissioning turns it on.</b> That is not
 * convenience: the shipped configuration commissions nothing, and every other test in this module
 * asserts the spec a deployment with no idp sends. A fake that was enabled by default would quietly
 * change what all of them are about.
 */
@ApplicationScoped
public class FakeAgentCredentials implements AgentCredentials {

  /** Commissioned client id to the project it belongs to, in insertion order. */
  private final Map<String, String> live = new LinkedHashMap<>();

  /** Every verb, in order: {@code commission:<projectId>}, {@code decommission:<clientId>}, … */
  private final List<String> calls = new CopyOnWriteArrayList<>();

  private final AtomicInteger minted = new AtomicInteger();

  private volatile boolean enabled;

  /** When set, the next {@link #commission} throws it. */
  private volatile AgentCredentialException commissionFailure;

  /** How many attempts still fail before one lands — for the patient loop. */
  private final AtomicInteger failuresLeft = new AtomicInteger();

  public void reset() {
    live.clear();
    calls.clear();
    minted.set(0);
    enabled = false;
    commissionFailure = null;
    failuresLeft.set(0);
  }

  /** Turn commissioning on, as a deployment with an idp has it. */
  public void enable() {
    enabled = true;
  }

  public List<String> calls() {
    return List.copyOf(calls);
  }

  /** The commissions idp would list — client id to project id. */
  public Map<String, String> live() {
    return Map.copyOf(live);
  }

  /** Stage a commission this service made and has no row for — the crash window. */
  public void given(String clientId, String projectId) {
    live.put(clientId, projectId);
  }

  /** Fail the next {@code count} commissions with {@code failure}. */
  public void failCommissions(int count, AgentCredentialException failure) {
    failuresLeft.set(count);
    commissionFailure = failure;
  }

  @Override
  public boolean enabled() {
    return enabled;
  }

  @Override
  public Commissioned commission(String projectId) {
    calls.add("commission:" + projectId);
    if (failuresLeft.get() > 0) {
      failuresLeft.decrementAndGet();
      throw commissionFailure;
    }
    String clientId = "commissioned-" + minted.incrementAndGet();
    live.put(clientId, projectId);
    return new Commissioned(clientId, "secret-of-" + clientId);
  }

  @Override
  public void decommission(String clientId) {
    calls.add("decommission:" + clientId);
    live.remove(clientId);
  }

  @Override
  public List<Commission> listAgentContainerCommissions() {
    calls.add("list");
    List<Commission> commissions = new ArrayList<>();
    live.forEach((clientId, projectId) -> commissions.add(new Commission(clientId, projectId)));
    return commissions;
  }
}
