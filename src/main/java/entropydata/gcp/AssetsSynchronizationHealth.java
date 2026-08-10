package entropydata.gcp;

import entropydata.sdk.EntropyDataAssetsProvider;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.health.contributor.Status;

/**
 * Reports whether the asset synchronization is still up to date. It wraps the assets provider, so that a failed run, or a
 * synchronization that no longer runs at all, becomes visible instead of the connector reporting itself as healthy.
 */
public class AssetsSynchronizationHealth implements EntropyDataAssetsProvider, HealthIndicator {

  /**
   * Reported instead of DOWN, because the usual cause is an unavailable data platform. Restarting the container, which is what an
   * orchestrator does when a health check reports DOWN, does not resolve that.
   */
  private static final Status DEGRADED = new Status("DEGRADED");

  private static final Duration DEFAULT_POLL_INTERVAL = Duration.ofHours(1);
  private static final int MISSED_RUNS_UNTIL_DEGRADED = 3;

  private final Duration degradedAfter;

  private volatile EntropyDataAssetsProvider delegate;
  private volatile Instant lastSuccessAt;
  private volatile String lastFailure;

  public AssetsSynchronizationHealth(Duration pollInterval) {
    this.degradedAfter = Objects.requireNonNullElse(pollInterval, DEFAULT_POLL_INTERVAL).multipliedBy(MISSED_RUNS_UNTIL_DEGRADED);
  }

  /**
   * Returns a provider that reports the outcome of every run to this indicator.
   */
  public EntropyDataAssetsProvider wrap(EntropyDataAssetsProvider delegate) {
    this.delegate = delegate;
    return this;
  }

  @Override
  public void fetchAssets(AssetCallback callback) {
    try {
      delegate.fetchAssets(callback);
      this.lastSuccessAt = Instant.now();
      this.lastFailure = null;
    } catch (Exception e) {
      this.lastFailure = e.toString();
      throw e;
    }
  }

  @Override
  public Health health() {
    var lastSuccess = this.lastSuccessAt;
    var failure = this.lastFailure;

    Health.Builder health;
    if (lastSuccess == null) {
      health = Health.status(failure == null ? Status.UNKNOWN : DEGRADED);
    } else if (Duration.between(lastSuccess, Instant.now()).compareTo(degradedAfter) > 0) {
      health = Health.status(DEGRADED);
    } else {
      health = Health.up();
    }

    health.withDetail("lastSuccessfulSynchronizationAt", lastSuccess != null ? lastSuccess.toString() : "never");
    health.withDetail("degradedAfter", degradedAfter.toString());
    if (failure != null) {
      health.withDetail("lastFailure", failure);
    }
    return health.build();
  }
}
