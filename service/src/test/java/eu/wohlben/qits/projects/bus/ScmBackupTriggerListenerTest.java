package eu.wohlben.qits.projects.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import eu.wohlben.qits.eventstream.control.EventEnvelope;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import eu.wohlben.qits.eventstream.QitsEvent;
import eu.wohlben.qits.projects.control.BackupPushService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The mapping from the git host's four SCM events to one debounced backup, driven directly.
 *
 * <p>Plain JUnit and a recording {@link BackupPushService}, deliberately. What is under test is a
 * decision — <em>which</em> events owe a backup and which repository they name — and the frames are
 * built through the real {@code CanonicalJson}, so the payloads are byte-for-byte what qits-events
 * stores. Booting a container would add a database, a debounce timer and an executor to a question
 * none of them can answer; the debounce itself is {@code BackupPushServiceTest}'s.
 */
class ScmBackupTriggerListenerTest {

  private RecordingBackups backups;
  private ScmBackupTriggerListener listener;

  @BeforeEach
  void setUp() {
    backups = new RecordingBackups();
    listener = new ScmBackupTriggerListener();
    listener.backupPushService = backups;
  }

  /**
   * The four names, spelled as literals. They are the wire contract with qits-githost, so a rename
   * on either side has to be a diff here rather than a subscription that silently matches nothing.
   */
  @Test
  void itSubscribesToTheGitHostsFourEventsAndNothingElse() {
    assertEquals(
        java.util.Set.of(
            "SCMPublishCommit", "SCMPublishTag", "SCMDeleteBranch", "SCMDeleteTag"),
        listener.signatures());
  }

  /**
   * The consumer id is storage: it names every claim row and the watermark. Pinned as a literal
   * because changing it mints a brand-new consumer that initializes at the head of the log and
   * silently skips everything in between.
   */
  @Test
  void theConsumerIdIsStorageAndIsPinned() {
    assertEquals("projects-backup-push", listener.consumerId());
    assertEquals(ScmBackupTriggerListener.CONSUMER_ID, listener.consumerId());
  }

  @Test
  void aCommitOnABranchOwesTheRepositoryABackup() {
    listener.onFrame(frameOf(commit("some-repository", "main", false)));

    assertEquals(List.of("some-repository"), backups.pushed);
  }

  /**
   * The fix this migration carries. The old post-receive hook fanned out branch updates only, so a
   * deleted branch or a pushed tag left the twin holding refs the platform no longer had until the
   * hourly sweep happened to notice.
   */
  @Test
  void tagsAndDeletionsOweABackupToo() {
    listener.onFrame(
        frameOf(
            new SCMPublishTag(
                "tagged-repo", null, null, "v1.2.3", "aaa", "bbb", "qits", "qits@local", "release", true, now())));
    listener.onFrame(frameOf(new SCMDeleteBranch("pruned-repo", null, null, "gone", "ccc", now())));
    listener.onFrame(frameOf(new SCMDeleteTag("untagged-repo", null, null, "v0.9", "ddd", now())));

    assertEquals(List.of("tagged-repo", "pruned-repo", "untagged-repo"), backups.pushed);
  }

  /**
   * {@code suppressCi} is qits-ci's question, not this one's — and the push it is set on is the
   * import of an upstream's whole history, which is exactly the push that most needs a twin.
   */
  @Test
  void suppressCiIsIgnoredBecauseABackupIsOwedRegardless() {
    listener.onFrame(frameOf(commit("imported-repo", "main", true)));

    assertEquals(List.of("imported-repo"), backups.pushed);
  }

  /**
   * Poison, not a hiccup: a backup is addressed by repository, so the same bytes would fail
   * identically every time and a throw would hold this consumer's watermark behind them forever.
   */
  @Test
  void aFrameNamingNoRepositoryIsSettledRatherThanRetriedForever() {
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(), "SCMDeleteTag", now(), "{\"tagName\":\"v1\"}", null, null));
    listener.onFrame(
        new EventFrame(
            UUID.randomUUID().toString(), "SCMPublishCommit", now(), "not json at all", null, null));

    assertTrue(backups.pushed.isEmpty(), "nothing to back up, and nothing thrown");
  }

  // -------------------------------------------------------------------------------------------

  private static Instant now() {
    return Instant.parse("2026-08-10T17:04:05Z");
  }

  private static SCMPublishCommit commit(String repoId, String branch, boolean suppressCi) {
    return new SCMPublishCommit(
        repoId,
        null,
        null,
        branch,
        "1111111111111111111111111111111111111111",
        "2222222222222222222222222222222222222222",
        List.of("1111111111111111111111111111111111111111"),
        "qits",
        "qits@local",
        now(),
        now(),
        "a commit",
        suppressCi,
        now());
  }

  /** The event as it really arrives: canonicalized into an envelope, then read back as a frame. */
  private static EventFrame frameOf(QitsEvent event) {
    EventEnvelope envelope = EventEnvelope.of(event);
    return new EventFrame(
        UUID.randomUUID().toString(),
        envelope.name(),
        envelope.occurredAt(),
        envelope.payload(),
        null,
        envelope.parentId());
  }

  private static final class RecordingBackups extends BackupPushService {

    final List<String> pushed = new ArrayList<>();

    @Override
    public void onPush(String repoId) {
      pushed.add(repoId);
    }
  }
}
