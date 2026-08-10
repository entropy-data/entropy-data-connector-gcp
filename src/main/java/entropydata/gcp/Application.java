package entropydata.gcp;

import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.BigQueryOptions;
import entropydata.sdk.EntropyDataAssetsSynchronizer;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.EntropyDataEventListener;
import entropydata.sdk.EntropyDataStateRepositoryInMemory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.info.BuildProperties;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@SpringBootApplication(scanBasePackages = "entropydata")
@ConfigurationPropertiesScan("entropydata")
public class Application {

  public static void main(String[] args) {
    SpringApplication.run(Application.class, args);
  }

  @Bean
  public BigQuery bigQuery() {
    return BigQueryOptions.getDefaultInstance().getService();
  }

  @Bean
  public EntropyDataClient entropyDataClient(@Value("${entropydata.client.host}") String host,
      @Value("${entropydata.client.apikey}") String apiKey) {
    return new EntropyDataClient(host, apiKey);
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "entropydata.client.gcp.accessmanagement.enabled", havingValue = "true")
  public EntropyDataEventListener entropyDataEventListener(EntropyDataClient client, GcpProperties gcpProperties,
      BigQuery bigQuery, TaskExecutor taskExecutor, ObjectProvider<BuildProperties> buildProperties) {
    var connectorid = gcpProperties.accessmanagement().connectorid();
    var stateRepository = new EntropyDataStateRepositoryInMemory(connectorid);
    var eventHandler = new GcpAccessManagement(client, bigQuery, gcpProperties.accessmanagement().role(),
        gcpProperties.accessmanagement().mapping().team().customfield(),
        gcpProperties.accessmanagement().mapping().dataproduct().customfield());
    var listener = new EntropyDataEventListener(connectorid, "accessmanagement", client, eventHandler, stateRepository,
        connectorVersion(buildProperties));
    taskExecutor.execute(listener::start);
    return listener;
  }

  @Bean
  @ConditionalOnProperty(value = "entropydata.client.gcp.assets.enabled", havingValue = "true")
  public AssetsSynchronizationHealth assetsSynchronizationHealth() {
    // This connector does not configure a poll interval, so the synchronizer runs with its own default
    return new AssetsSynchronizationHealth(null);
  }

  @Bean(destroyMethod = "stop")
  @ConditionalOnProperty(value = "entropydata.client.gcp.assets.enabled", havingValue = "true")
  public EntropyDataAssetsSynchronizer entropyDataAssetsSynchronizer(EntropyDataClient client, GcpProperties gcpProperties,
      BigQuery bigQuery, AssetsSynchronizationHealth assetsSynchronizationHealth, TaskExecutor taskExecutor,
      ObjectProvider<BuildProperties> buildProperties) {
    var connectorid = gcpProperties.assets().connectorid();
    var stateRepository = new EntropyDataStateRepositoryInMemory(connectorid);
    var assetsProvider = new GcpAssetsProvider(bigQuery, gcpProperties.assets().projects(), stateRepository);
    var assetsSynchronizer = new EntropyDataAssetsSynchronizer(connectorid, client,
        assetsSynchronizationHealth.wrap(assetsProvider), connectorVersion(buildProperties));
    taskExecutor.execute(assetsSynchronizer::start);
    return assetsSynchronizer;
  }

  @Bean
  public TaskExecutor taskExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(5);
    executor.setMaxPoolSize(10);
    executor.setQueueCapacity(25);
    executor.setThreadNamePrefix("entropydata-connector-");
    executor.initialize();
    return executor;
  }

  /**
   * The version this connector runs with, so that it is visible in Entropy Data. Absent when the build information is not on the
   * classpath, such as when the application is started from an IDE.
   */
  private static String connectorVersion(ObjectProvider<BuildProperties> buildProperties) {
    var properties = buildProperties.getIfAvailable();
    return properties != null ? properties.getVersion() : null;
  }
}
