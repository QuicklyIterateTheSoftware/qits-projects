package eu.wohlben.qits.projects.bus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.wohlben.qits.eventstream.QitsDurableEventListener;
import eu.wohlben.qits.eventstream.control.EventFrame;
import eu.wohlben.qits.githost.events.SCMDeleteBranch;
import eu.wohlben.qits.githost.events.SCMDeleteTag;
import eu.wohlben.qits.githost.events.SCMPublishCommit;
import eu.wohlben.qits.githost.events.SCMPublishTag;
import eu.wohlben.qits.projects.control.BackupPushService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Set;
import org.jboss.logging.Logger;

/**
 * The git host said a repository's refs moved, so its forge twin is out of date: schedule a backup.
 *
 * <p>This replaces {@code api/GitHostEventController}, a {@code POST /projects/api/events/
 * post-receive} the git host called out of its own hook. The route is gone, and so is the shape it
 * had — one fire-and-forget HTTP call per push, in the critical path of somebody's {@code git push},
 * with a service restart or a moment of unreachability costing a backup silently. What replaced it
 * is a durable consumption: the claim and the schedule commit together, and a push that landed while
 * this process was away is read back off the log by the catch-up sweep rather than lost.
 *
 * <h2>All four events, and every one of them owed a backup</h2>
 *
 * <p>The vocabulary is the git host's {@code githost-events} jar and it has four records. Each one
 * says the same thing to <em>this</em> service: refs in {@code repoId} are not what the twin holds.
 * So all four map to the one call, and the mapping is deliberately total rather than clever.
 *
 * <p><b>Deletions and tags now trigger a backup, and they never did before</b> — that is a fix, not
 * a side effect of the migration. The old hook fanned out branch <em>updates</em> only, so deleting a
 * branch or pushing a tag left the twin holding refs the platform no longer had, until the hourly
 * sweep happened to notice. {@code git push --prune} is what the backup runs, so the delete really
 * does propagate once it is asked for.
 *
 * <p><b>{@code suppressCi} is ignored, deliberately.</b> That flag on {@link SCMPublishCommit}
 * carries {@code -o qits.no-ci} — a statement about whether a <em>build</em> should run, which is
 * qits-ci's question. An imported upstream's history is exactly the case: it must not fire a build
 * per branch, and it must absolutely be backed up. Reading the flag here would make the one push
 * that most needs a twin the one push that never gets one.
 *
 * <h2>Debounce and sweep, both kept</h2>
 *
 * <p>{@link BackupPushService#onPush} is unchanged and still absorbs a burst into one run, which
 * matters more here than it did on the hook: one {@code git push} of several branches and a tag is
 * now several <em>events</em>, and a catch-up sweep can deliver a whole disconnect window at once.
 * {@code startup/ScheduledBackupSweep} stays too. Durable delivery narrows what the sweep is for
 * without emptying it — an unreachable forge, an expired credential and a backup that failed for its
 * own reasons are none of them missing events, and the sweep is the only thing that fixes them.
 *
 * <h2>Failure, and why nothing here throws</h2>
 *
 * <p>The seam's rule is that a throw rolls the claim back and offers the event again forever, so a
 * handler must decide per failure whether a later attempt could do better. This one has no such
 * case: {@code onPush} returns immediately and always, swallowing every backup failure by design,
 * and the only thing that can go wrong on this thread is a payload that will not parse or names no
 * repository. The same bytes would fail identically every time, so both are the poison case — a WARN
 * and a return, never a wedged watermark.
 */
@ApplicationScoped
public class ScmBackupTriggerListener implements QitsDurableEventListener {

  private static final Logger LOG = Logger.getLogger(ScmBackupTriggerListener.class);

  /**
   * The storage key, and therefore not a label: it names every {@code consumed_event} row and the
   * {@code consumer_watermark} this consumption keeps. It says what the consumption is <em>for</em>
   * rather than which class does it, so this class can be renamed without minting a brand-new
   * consumer that would initialize at the head of the log and silently skip everything in between.
   */
  static final String CONSUMER_ID = "projects-backup-push";

  /**
   * Its own mapper, and only ever {@code readTree}. Binding the four records would mean registering
   * them for reflection on both the reading and the writing side; reading one field out of a
   * JsonNode needs none of that, and the one field is all this listener wants.
   */
  private static final ObjectMapper MAPPER = new ObjectMapper();

  @Inject BackupPushService backupPushService;

  @Override
  public String consumerId() {
    return CONSUMER_ID;
  }

  /**
   * The four the git host publishes, named rather than {@code "*"}. They are knowable at startup and
   * they are the whole interest, so naming them keeps the claim table proportional to pushes instead
   * of to the platform's entire event log.
   */
  @Override
  public Set<String> signatures() {
    return Set.of(
        SCMPublishCommit.class.getSimpleName(),
        SCMPublishTag.class.getSimpleName(),
        SCMDeleteBranch.class.getSimpleName(),
        SCMDeleteTag.class.getSimpleName());
  }

  @Override
  public void onFrame(EventFrame frame) {
    String repoId = repoIdOf(frame);
    if (repoId == null) {
      // Poison: a backup is addressed by repository and this event names none, so no later offer of
      // the same bytes could be acted on either. Swallowed with a WARN, because throwing would hold
      // this consumer's watermark behind an event that can never work.
      LOG.warnf(
          "%s %s names no repository; there is nothing to back up and it is settled unhandled",
          frame.name(), frame.id());
      return;
    }
    backupPushService.onPush(repoId);
  }

  private static String repoIdOf(EventFrame frame) {
    JsonNode payload;
    try {
      payload = MAPPER.readTree(frame.payload());
    } catch (Exception unreadable) {
      LOG.warnf(
          "%s %s carried an unreadable payload: %s", frame.name(), frame.id(), unreadable.toString());
      return null;
    }
    String repoId = payload.path("repoId").asText(null);
    return repoId == null || repoId.isBlank() ? null : repoId;
  }
}
