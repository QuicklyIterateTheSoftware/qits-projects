package eu.wohlben.qits.projects.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.projects.api.ProjectChangeHint.Topic;
import io.smallrye.mutiny.subscription.Cancellable;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Plain-JUnit test of the broadcaster's routing, debounce and channel lifecycle — no Quarkus needed
 * (the debounce window is set directly and {@link ProjectEventBroadcaster#onHint} driven by hand).
 * {@code subscribe} is used rather than {@code subscribeToProject} for the same reason: the
 * existence check is the only thing here that needs a database, and it is covered over HTTP by
 * {@link ProjectEventsSseTest}.
 */
class ProjectEventBroadcasterTest {

  private ProjectEventBroadcaster broadcaster;

  @BeforeEach
  void setUp() {
    broadcaster = new ProjectEventBroadcaster();
    broadcaster.debounceMillis = 100;
  }

  /** A live subscription that queues what it receives. */
  private record Watcher(BlockingQueue<String> items, Cancellable cancellable) {

    /** Wait for {@code count} items, then return everything seen. */
    List<String> await(int count, long timeoutMs) throws InterruptedException {
      List<String> seen = new ArrayList<>();
      long deadline = System.currentTimeMillis() + timeoutMs;
      while (seen.size() < count) {
        long remaining = deadline - System.currentTimeMillis();
        if (remaining <= 0) {
          break;
        }
        String item = items.poll(remaining, TimeUnit.MILLISECONDS);
        if (item != null) {
          seen.add(item);
        }
      }
      return seen;
    }
  }

  private Watcher watch(String projectId) {
    BlockingQueue<String> items = new LinkedBlockingQueue<>();
    return new Watcher(items, broadcaster.subscribe(projectId).subscribe().with(items::add));
  }

  @Test
  void deliversTheHyphenatedTopicNameToTheProjectChannel() throws InterruptedException {
    Watcher watcher = watch("p-1");

    broadcaster.onHint(new ProjectChangeHint("p-1", Topic.AGENT_ACTIVITY));

    assertEquals(List.of("agent-activity"), watcher.await(1, 2000));
  }

  @Test
  void aHintForOneProjectDoesNotReachAnother() throws InterruptedException {
    Watcher a = watch("p-a");
    Watcher b = watch("p-b");

    broadcaster.onHint(new ProjectChangeHint("p-a", Topic.EPICS));

    assertEquals(List.of("epics"), a.await(1, 2000));
    assertNull(b.items().poll(200, TimeUnit.MILLISECONDS));
  }

  @Test
  void debounceCollapsesABurstToAtMostLeadingPlusTrailing() throws InterruptedException {
    // An agent writing a whole feature/task tree fires one hint per row; a watching browser must
    // not get one re-fetch per row.
    Watcher watcher = watch("p-burst");

    for (int i = 0; i < 8; i++) {
      broadcaster.onHint(new ProjectChangeHint("p-burst", Topic.EPICS));
    }

    List<String> seen = watcher.await(2, 2000);
    Thread.sleep(300); // well past two debounce windows — nothing further should arrive
    assertEquals(2, seen.size());
    assertEquals(0, watcher.items().size());
    assertTrue(seen.stream().allMatch("epics"::equals));
  }

  @Test
  void distinctTopicsForTheSameProjectEachEmitTheirLeadingHint() throws InterruptedException {
    Watcher watcher = watch("p-topics");

    broadcaster.onHint(new ProjectChangeHint("p-topics", Topic.EPICS));
    broadcaster.onHint(new ProjectChangeHint("p-topics", Topic.AGENT_ACTIVITY));

    List<String> seen = watcher.await(2, 2000);
    assertTrue(seen.contains("epics"), () -> "missing epics: " + seen);
    assertTrue(seen.contains("agent-activity"), () -> "missing agent-activity: " + seen);
  }

  @Test
  void theChannelIsDroppedWhenItsLastSubscriberCancels() throws InterruptedException {
    Watcher watcher = watch("p-life");
    broadcaster.onHint(new ProjectChangeHint("p-life", Topic.EPICS));
    watcher.await(1, 2000);
    assertEquals(1, broadcaster.openChannelCount());

    watcher.cancellable().cancel();

    assertEquals(0, broadcaster.openChannelCount());
  }

  @Test
  void hintsForAProjectWithNoSubscribersAreSafelyDropped() {
    broadcaster.onHint(new ProjectChangeHint("p-nobody", Topic.EPICS));
    assertEquals(0, broadcaster.openChannelCount());
  }
}
