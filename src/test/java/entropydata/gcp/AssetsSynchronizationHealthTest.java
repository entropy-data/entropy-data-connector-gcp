package entropydata.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import entropydata.sdk.EntropyDataAssetsProvider;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

class AssetsSynchronizationHealthTest {

  private static final EntropyDataAssetsProvider SUCCEEDS = callback -> {
  };

  private static final EntropyDataAssetsProvider FAILS = callback -> {
    throw new IllegalStateException("Data platform is unavailable");
  };

  private static AssetsSynchronizationHealth wrapping(EntropyDataAssetsProvider provider, Duration pollInterval) {
    var indicator = new AssetsSynchronizationHealth(pollInterval);
    indicator.wrap(provider);
    return indicator;
  }

  @Test
  void isUnknownBeforeTheFirstRunHasFinished() {
    var health = wrapping(SUCCEEDS, Duration.ofMinutes(10)).health();

    assertThat(health.getStatus()).isEqualTo(Status.UNKNOWN);
    assertThat(health.getDetails()).containsEntry("lastSuccessfulSynchronizationAt", "never");
  }

  @Test
  void isUpAfterASuccessfulRun() {
    var indicator = wrapping(SUCCEEDS, Duration.ofMinutes(10));

    indicator.fetchAssets(null);

    assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
  }

  @Test
  void isDegradedAfterAFailedRunAndRethrowsTheFailure() {
    var indicator = wrapping(FAILS, Duration.ofMinutes(10));

    assertThatThrownBy(() -> indicator.fetchAssets(null)).isInstanceOf(IllegalStateException.class);

    var health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(new Status("DEGRADED"));
    assertThat(health.getDetails().get("lastFailure").toString()).contains("Data platform is unavailable");
  }

  @Test
  void isDegradedWhenTheLastSuccessfulRunIsTooLongAgo() throws Exception {
    var indicator = wrapping(SUCCEEDS, Duration.ofMillis(1));

    indicator.fetchAssets(null);
    Thread.sleep(50);

    assertThat(indicator.health().getStatus()).isEqualTo(new Status("DEGRADED"));
  }

  @Test
  void doesNotReportDownSoThatAnUnavailableDataPlatformCannotTriggerRestarts() {
    var indicator = wrapping(FAILS, Duration.ofMinutes(10));

    assertThatThrownBy(() -> indicator.fetchAssets(null)).isInstanceOf(IllegalStateException.class);

    assertThat(indicator.health().getStatus()).isNotEqualTo(Status.DOWN);
  }
}
