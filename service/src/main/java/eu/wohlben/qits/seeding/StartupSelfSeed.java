package eu.wohlben.qits.seeding;

import eu.wohlben.qits.domain.seeding.control.SelfSeedService;
import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * Startup gate for the {@link SelfSeedService} reconcile (see {@code
 * docs/features/2026-07-19_startup-qits-self-seed.md}). Runs only on a packaged deployment ({@link
 * LaunchMode#NORMAL} — jar / native / prod image), never under {@code quarkus:dev} or tests, and
 * only when the kill-switch is on. Startup is the deliberate home: reconciliation must run on every
 * release rollout so manifest additions land, and boot is a deployment's only guaranteed "new code
 * is now running" moment.
 *
 * <p>Living in {@code service} (not {@code domain}) means it fires only when the web app boots —
 * the {@code cli} command-mode app, which also boots {@code domain}, never self-seeds. The
 * reconcile needs network reach to GitHub, so it runs on a virtual thread after startup (the {@code
 * TranscriptionService} warmup precedent) and does not block readiness; a failure leaves a usable
 * instance and the next boot's reconcile retries the failed items.
 */
@ApplicationScoped
public class StartupSelfSeed {

  private static final Logger LOG = Logger.getLogger(StartupSelfSeed.class);

  @Inject SelfSeedService selfSeedService;

  @ConfigProperty(name = "qits.startup-seed.enabled", defaultValue = "true")
  boolean enabled;

  void onStart(@Observes StartupEvent event) {
    if (!shouldSeed(LaunchMode.current(), enabled)) {
      return;
    }
    Thread.ofVirtual()
        .name("qits-self-seed")
        .start(
            () -> {
              try {
                selfSeedService.reconcile();
              } catch (RuntimeException e) {
                LOG.error(
                    "Self-seed reconcile failed — instance is usable; retried on next boot.", e);
              }
            });
  }

  /** Packaged runs only, and only when enabled — dev/test launch modes never self-seed. */
  static boolean shouldSeed(LaunchMode mode, boolean enabled) {
    return mode == LaunchMode.NORMAL && enabled;
  }
}
