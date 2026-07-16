package entropydata.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.bigquery.Acl;
import com.google.cloud.bigquery.Acl.Role;
import com.google.cloud.bigquery.Acl.User;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.Dataset;
import com.google.cloud.bigquery.DatasetId;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.client.ApiClient;
import entropydata.sdk.client.ApiException;
import entropydata.sdk.client.api.AccessApi;
import entropydata.sdk.client.api.DataProductsApi;
import entropydata.sdk.client.api.TeamsApi;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessDeactivatedEvent;
import entropydata.sdk.client.model.AccessProvider;
import entropydata.sdk.client.model.DataUsageAgreementConsumer;
import entropydata.sdk.client.model.Team;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.yaml.snakeyaml.Yaml;

class GcpAccessManagementTest {

  private EntropyDataClient client;
  private BigQuery bigQuery;
  private AccessApi accessApi;
  private DataProductsApi dataProductsApi;
  private ApiClient apiClient;
  private TeamsApi teamsApi;
  private ObjectMapper objectMapper;

  private GcpAccessManagement accessManagement;

  @BeforeEach
  void setUp() {
    client = mock(EntropyDataClient.class);
    bigQuery = mock(BigQuery.class);
    accessApi = mock(AccessApi.class);
    dataProductsApi = mock(DataProductsApi.class);
    teamsApi = mock(TeamsApi.class);

    apiClient = mock(ApiClient.class);
    objectMapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    when(client.getApiClient()).thenReturn(apiClient);
    when(apiClient.getObjectMapper()).thenReturn(objectMapper);
    when(client.getAccessApi()).thenReturn(accessApi);
    when(client.getDataProductsApi()).thenReturn(dataProductsApi);
    when(client.getTeamsApi()).thenReturn(teamsApi);

    accessManagement = new GcpAccessManagement(client, bigQuery, "READER", "gcpPrincipal", "gcpPrincipal");
  }

  /** Stub the raw data contract fetch to return a YAML fixture as the untyped API response. */
  private void stubDataContract(String name) throws ApiException {
    when(apiClient.<Map<String, Object>>invokeAPI(any(), any(), any(), any(), any(), any(),
        any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(loadYaml(name));
  }

  /** Load a YAML fixture file as a Map (simulating a raw API response). */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> loadYaml(String name) {
    try (InputStream is = GcpAccessManagementTest.class.getResourceAsStream("/fixtures/" + name)) {
      return new Yaml().load(is);
    } catch (IOException e) {
      throw new RuntimeException("Failed to load fixture: " + name, e);
    }
  }

  private Access buildAccess(String accessId, String providerDpId, String outputPortId,
      DataUsageAgreementConsumer consumer) {
    var access = new Access();
    access.setId(accessId);
    access.setProvider(new AccessProvider().dataProductId(providerDpId).outputPortId(outputPortId));
    access.setConsumer(consumer);
    access.setTags(new ArrayList<>());
    return access;
  }

  private Dataset mockDataset(DatasetId datasetId, List<Acl> acls) {
    var dataset = mock(Dataset.class);
    var builder = mock(Dataset.Builder.class);
    var updatedDataset = mock(Dataset.class);
    when(bigQuery.getDataset(datasetId)).thenReturn(dataset);
    when(dataset.getAcl()).thenReturn(acls);
    when(dataset.toBuilder()).thenReturn(builder);
    when(builder.setAcl(any())).thenReturn(builder);
    when(builder.build()).thenReturn(updatedDataset);
    when(updatedDataset.update()).thenReturn(updatedDataset);
    return dataset;
  }

  // ===== DPS format (direct server on output port) =====

  @Nested
  class AccessActivatedWithDpsFormat {

    @Test
    void grantsReaderRoleOnBigQueryDataset() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-dps.yaml"));

      var datasetId = DatasetId.of("my-project", "my-dataset");
      mockDataset(datasetId, new ArrayList<>());

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
      verify(accessApi).addAccess(eq("access-1"), any(Access.class));
    }

    @Test
    void skipsWhenAlreadyAuthorized() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-dps.yaml"));

      var datasetId = DatasetId.of("my-project", "my-dataset");
      var existingAcl = Acl.of(new User("sa@project.iam.gserviceaccount.com"), Role.READER);
      var dataset = mock(Dataset.class);
      when(bigQuery.getDataset(datasetId)).thenReturn(dataset);
      when(dataset.getAcl()).thenReturn(List.of(existingAcl));

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(dataset, never()).toBuilder();
    }
  }

  // ===== ODPS format (server from ODCS data contract) =====

  @Nested
  class AccessActivatedWithOdpsFormat {

    @Test
    void resolvesServerFromDataContract() throws Exception {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "bq-output", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-odps.yaml"));
      stubDataContract("datacontract.yaml");
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-odps.yaml"));

      var datasetId = DatasetId.of("gcp-project", "gcp-dataset");
      mockDataset(datasetId, new ArrayList<>());

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
      verify(accessApi).addAccess(eq("access-1"), any(Access.class));
    }

    @Test
    void fallsBackToOutputPortServerWhenNoContract() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "bq-output", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-odps-no-contract.yaml"));
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-dps.yaml"));

      var datasetId = DatasetId.of("fallback-project", "fallback-dataset");
      mockDataset(datasetId, new ArrayList<>());

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
    }

    @Test
    void usesFirstServerWhenContractServerNotSpecified() throws Exception {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "bq-output", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-odps-no-contract-server.yaml"));
      stubDataContract("datacontract-multi-server.yaml");
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-dps.yaml"));

      var datasetId = DatasetId.of("first-project", "first-dataset");
      mockDataset(datasetId, new ArrayList<>());

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
    }
  }

  // ===== Access Deactivated =====

  @Nested
  class AccessDeactivated {

    @Test
    void revokesReaderRoleFromBigQueryDataset() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      access.setTags(new ArrayList<>(List.of("permission-granted-on-gcp")));
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-dps.yaml"));

      var userEntity = new User("sa@project.iam.gserviceaccount.com");
      var existingAcl = Acl.of(userEntity, Role.READER);
      var datasetId = DatasetId.of("my-project", "my-dataset");
      mockDataset(datasetId, new ArrayList<>(List.of(existingAcl)));

      var event = new AccessDeactivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessDeactivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
      var captor = ArgumentCaptor.forClass(Access.class);
      verify(accessApi).addAccess(eq("access-1"), captor.capture());
      assertThat(captor.getValue().getTags()).doesNotContain("permission-granted-on-gcp");
    }
  }

  // ===== Consumer entity resolution =====

  @Nested
  class ConsumerEntityResolution {

    @Test
    void resolvesTeamConsumerAsGroup() {
      var consumer = new DataUsageAgreementConsumer().teamId("analytics-team");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));

      var team = new Team();
      team.setCustom(Map.of("gcpPrincipal", "group:analytics@company.com"));
      when(teamsApi.getTeam("analytics-team")).thenReturn(team);

      var datasetId = DatasetId.of("my-project", "my-dataset");
      mockDataset(datasetId, new ArrayList<>());

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
    }

    @Test
    void resolvesUserConsumerDirectly() {
      var consumer = new DataUsageAgreementConsumer().userId("user@company.com");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));

      var datasetId = DatasetId.of("my-project", "my-dataset");
      mockDataset(datasetId, new ArrayList<>());

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
    }

    @Test
    void abortsWhenNoConsumer() {
      var access = buildAccess("access-1", "provider-dp", "op-1", null);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery, never()).getDataset(any(DatasetId.class));
    }

    @Test
    void abortsWhenConsumerDataProductIdIsUnknown() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("unknown");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(dataProductsApi, never()).getDataProduct("unknown");
      verify(bigQuery, never()).getDataset(any(DatasetId.class));
    }
  }

  // ===== Provider resolution edge cases =====

  @Nested
  class ProviderResolutionEdgeCases {

    @Test
    void abortsWhenNoProvider() {
      var access = new Access();
      access.setId("access-1");
      access.setTags(new ArrayList<>());
      when(accessApi.getAccess("access-1")).thenReturn(access);

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery, never()).getDataset(any(DatasetId.class));
    }

    @Test
    void abortsWhenOutputPortNotFound() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "nonexistent-port", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery, never()).getDataset(any(DatasetId.class));
    }

    @Test
    void abortsWhenDatasetDoesNotExist() {
      var consumer = new DataUsageAgreementConsumer().dataProductId("consumer-dp");
      var access = buildAccess("access-1", "provider-dp", "op-1", consumer);
      when(accessApi.getAccess("access-1")).thenReturn(access);

      when(dataProductsApi.getDataProduct("provider-dp")).thenReturn(loadYaml("provider-dp-dps.yaml"));
      when(dataProductsApi.getDataProduct("consumer-dp")).thenReturn(loadYaml("consumer-dp-dps.yaml"));

      var datasetId = DatasetId.of("my-project", "my-dataset");
      when(bigQuery.getDataset(datasetId)).thenReturn(null);

      var event = new AccessActivatedEvent();
      event.setId("access-1");
      accessManagement.onAccessActivatedEvent(event);

      verify(bigQuery).getDataset(datasetId);
    }
  }
}
