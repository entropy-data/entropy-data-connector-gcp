package entropydata.gcp;

import com.google.cloud.bigquery.Acl;
import com.google.cloud.bigquery.Acl.Entity;
import com.google.cloud.bigquery.Acl.Group;
import com.google.cloud.bigquery.Acl.User;
import com.google.cloud.bigquery.BigQuery;
import com.google.cloud.bigquery.DatasetId;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import entropydata.sdk.EntropyDataClient;
import entropydata.sdk.EntropyDataEventHandler;
import entropydata.sdk.client.ApiException;
import entropydata.sdk.client.model.Access;
import entropydata.sdk.client.model.AccessActivatedEvent;
import entropydata.sdk.client.model.AccessDeactivatedEvent;
import entropydata.sdk.client.model.AccessDeprovisioningSucceededReport;
import entropydata.sdk.client.model.AccessProvisioningFailedReport;
import entropydata.sdk.client.model.AccessProvisioningStartedReport;
import entropydata.sdk.client.model.AccessProvisioningSucceededReport;
import entropydata.sdk.client.model.DataProduct;
import entropydata.sdk.client.model.Team;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class GcpAccessManagement implements EntropyDataEventHandler {

  private static final Logger log = LoggerFactory.getLogger(GcpAccessManagement.class);

  // Reported back to Entropy Data on every provisioning transition, so the access page shows which
  // platform holds the grant and which component created it.
  private static final String PLATFORM = "bigquery";
  private static final String EXECUTOR = "Entropy Data GCP Connector";

  private final EntropyDataClient client;
  private final BigQuery bigQuery;
  private final ObjectMapper objectMapper;

  private final String teamCustomField;
  private final String dataProductCustomField;
  private final String role;

  public GcpAccessManagement(EntropyDataClient client, BigQuery bigQuery, String role, String teamCustomField, String dataProductCustomField) {
    this.client = client;
    this.bigQuery = bigQuery;
    this.objectMapper = client.getApiClient().getObjectMapper();
    this.role = role;
    this.teamCustomField = teamCustomField;
    this.dataProductCustomField = dataProductCustomField;
  }

  @Override
  public void onAccessActivatedEvent(AccessActivatedEvent event) {
    String accessId = event.getId();
    log.info("Processing AccessActivatedEvent {}", accessId);
    var access = client.getAccessApi().getAccess(accessId);

    var datasetId = findProviderDatasetId(access, client);
    if (datasetId == null) {
      // not a BigQuery output port: another connector owns this access, so stay silent
      return;
    }

    var entity = findConsumerEntity(access, client);
    if (entity == null) {
      return;
    }

    reportProvisioningStarted(access);
    try {
      authorize(datasetId, entity);
      addTag(accessId, "permission-granted-on-gcp");
    } catch (RuntimeException e) {
      log.error("Failed to authorize access {}", accessId, e);
      reportProvisioningFailed(access, e);
      // Swallow after reporting: the failure is now recorded and mailed to the provider, whereas
      // rethrowing would stall the whole event feed on this one access until it is retried.
      return;
    }
    reportProvisioningSucceeded(access, reference(datasetId));
  }

  @Override
  public void onAccessDeactivatedEvent(AccessDeactivatedEvent event) {
    String accessId = event.getId();
    log.info("Processing AccessDeactivatedEvent {}", accessId);
    var access = client.getAccessApi().getAccess(accessId);

    var datasetId = findProviderDatasetId(access, client);
    if (datasetId == null) {
      // not a BigQuery output port: another connector owns this access, so stay silent
      return;
    }

    var entity = findConsumerEntity(access, client);
    if (entity == null) {
      return;
    }

    reportDeprovisioningStarted(access);
    try {
      deauthorize(datasetId, entity);
      removeTag(accessId, "permission-granted-on-gcp");
    } catch (RuntimeException e) {
      log.error("Failed to deauthorize access {}", accessId, e);
      reportDeprovisioningFailed(access, e);
      return;
    }
    reportDeprovisioningSucceeded(access, reference(datasetId));
  }

  private static String reference(DatasetId datasetId) {
    return datasetId.getProject() + "." + datasetId.getDataset();
  }

  private void reportProvisioningStarted(Access access) {
    safeReport(access.getId(), "provisioning-started", () ->
        client.getAccessApi().reportProvisioningStarted(access.getId(),
            new AccessProvisioningStartedReport().platform(PLATFORM).executor(EXECUTOR)));
  }

  private void reportProvisioningSucceeded(Access access, String reference) {
    safeReport(access.getId(), "provisioning-succeeded", () ->
        client.getAccessApi().reportProvisioningSucceeded(access.getId(),
            new AccessProvisioningSucceededReport().platform(PLATFORM).executor(EXECUTOR).reference(reference)));
  }

  private void reportProvisioningFailed(Access access, Exception cause) {
    safeReport(access.getId(), "provisioning-failed", () ->
        client.getAccessApi().reportProvisioningFailed(access.getId(),
            new AccessProvisioningFailedReport().platform(PLATFORM).executor(EXECUTOR).diagnostics(diagnostics(cause))));
  }

  private void reportDeprovisioningStarted(Access access) {
    safeReport(access.getId(), "deprovisioning-started", () ->
        client.getAccessApi().reportDeprovisioningStarted(access.getId(),
            new AccessProvisioningStartedReport().platform(PLATFORM).executor(EXECUTOR)));
  }

  private void reportDeprovisioningSucceeded(Access access, String reference) {
    safeReport(access.getId(), "deprovisioning-succeeded", () ->
        client.getAccessApi().reportDeprovisioningSucceeded(access.getId(),
            new AccessDeprovisioningSucceededReport().platform(PLATFORM).executor(EXECUTOR).reference(reference)));
  }

  private void reportDeprovisioningFailed(Access access, Exception cause) {
    safeReport(access.getId(), "deprovisioning-failed", () ->
        client.getAccessApi().reportDeprovisioningFailed(access.getId(),
            new AccessProvisioningFailedReport().platform(PLATFORM).executor(EXECUTOR).diagnostics(diagnostics(cause))));
  }

  /**
   * Reports one transition, swallowing any failure. The grant itself is the source of truth; a
   * report that cannot be delivered (e.g. the backend predates provisioning, or the API key lacks
   * the provider's ACCESS_EDIT permission) must not undo or block the grant.
   */
  private void safeReport(String accessId, String transition, ReportCall call) {
    try {
      call.run();
      log.info("Reported {} for access {}", transition, accessId);
    } catch (Exception e) {
      log.warn("Failed to report {} for access {}: {}", transition, accessId, e.getMessage());
    }
  }

  private static String diagnostics(Exception cause) {
    var message = cause.getMessage();
    return message != null ? message : cause.getClass().getSimpleName();
  }

  @FunctionalInterface
  private interface ReportCall {
    void run() throws ApiException;
  }

  public void authorize(DatasetId datasetId, Entity entity) {
    var dataset = bigQuery.getDataset(datasetId);
    if (dataset == null) {
      log.info("Cannot authorize as dataset {} does not exist", datasetId);
      return;
    }

    List<Acl> aclList = dataset.getAcl() == null ? new ArrayList<>() : new ArrayList<>(dataset.getAcl());
    var expectedRole = Acl.Role.valueOf(role);
    for (var acl : aclList) {
      boolean isAlreadyGranted = acl.getRole().equals(expectedRole) && acl.getEntity().equals(entity);
      if (isAlreadyGranted) {
        log.info("Already authorized entity {} with role {} for dataset {}", entity, expectedRole, datasetId);
        return;
      }
    }

    Acl acl = Acl.of(entity, expectedRole);
    aclList.add(acl);
    dataset.toBuilder().setAcl(aclList).build().update();
    log.info("Authorized entity {} with role {} for dataset {} ", entity, expectedRole, datasetId);
  }

  public void deauthorize(DatasetId datasetId, Entity entity) {
    var dataset = bigQuery.getDataset(datasetId);

    List<Acl> aclList = dataset.getAcl() == null ? new ArrayList<>() : new ArrayList<>(dataset.getAcl());
    var expectedRole = Acl.Role.valueOf(role);
    Acl matchedAcl = null;
    for (var acl : aclList) {
      boolean matchingAcl = acl.getRole().equals(expectedRole) && acl.getEntity().equals(entity);
      if (matchingAcl) {
        matchedAcl = acl;
      }
    }
    if (matchedAcl == null) {
      log.info("Already deauthorized entity {} with role {} for dataset {}", entity, expectedRole, datasetId);
      return;
    }

    aclList.remove(matchedAcl);

    dataset.toBuilder().setAcl(aclList).build().update();
    log.info("Deauthorized entity {} with role {} for dataset {} ", entity, expectedRole, datasetId);
  }

  private Entity findConsumerEntity(Access access, EntropyDataClient client) {
    if (access.getConsumer() == null) {
      log.debug("Abort, as no consumer is available");
      return null;
    }

    var userId = access.getConsumer().getUserId();
    if (userId != null) {
      // requires https://cloud.google.com/iam/docs/principal-identifiers#v1
      // user:USER_EMAIL_ADDRESS
      // userId is always the email address at the moment
      return new User(userId);
    }

    var dataProductId = access.getConsumer().getDataProductId();
    // "unknown" is a sentinel value used by the backend when no data product has been assigned yet;
    // see https://github.com/entropy-data/entropy-data-sdk/blob/a2e78049a483c392ea268720efafad87a01a1c1f/src/main/resources/openapi.yaml#L2718
    if (dataProductId != null && !dataProductId.equals("unknown")) {
      var rawDataProduct = client.getDataProductsApi().getDataProduct(dataProductId);
      var custom = extractCustomFields(rawDataProduct);
      return getEntityFromCustom(custom);
    }

    var teamId = access.getConsumer().getTeamId();
    if (teamId != null) {
      var team = client.getTeamsApi().getTeam(teamId);
      return getEntityForTeam(team);
    }

    return null;
  }

  private Entity getEntityFromCustom(Map<String, String> custom) {
    var gcpServiceAccount = getCustom(custom, dataProductCustomField);
    if (gcpServiceAccount != null && gcpServiceAccount.startsWith("serviceAccount:")) {
      // requires https://cloud.google.com/iam/docs/principal-identifiers#v1
      // serviceAccount:SA_EMAIL_ADDRESS
      return new User(gcpServiceAccount.substring("serviceAccount:".length()));
    }

    return null;
  }

  private Group getEntityForTeam(Team team) {
    var gcpGroup = getCustom(team.getCustom(), teamCustomField);
    if (gcpGroup != null && gcpGroup.startsWith("group:")) {
      // requires https://cloud.google.com/iam/docs/principal-identifiers#v1
      // group:GROUP_EMAIL_ADDRESS
      return new Group(gcpGroup.substring("group:".length()));
    }

    return null;
  }

  private static String getCustom(Map<String, String> custom, String key) {
    if (custom != null) {
      var value = custom.get(key);
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private DatasetId findProviderDatasetId(Access access, EntropyDataClient client) {
    var provider = access.getProvider();
    if (provider == null) {
      log.debug("Abort, as no provider is available");
      return null;
    }

    var rawDataProduct = client.getDataProductsApi().getDataProduct(provider.getDataProductId());
    var dataProductMap = objectMapper.convertValue(rawDataProduct, Map.class);

    var outputPorts = (List<Map<String, Object>>) dataProductMap.get("outputPorts");
    if (outputPorts == null) {
      log.debug("Abort, as no output port is available");
      return null;
    }

    Map<String, Object> matchedPort = null;
    for (var port : outputPorts) {
      var portId = (String) port.get("id");
      var portName = (String) port.get("name");
      if (provider.getOutputPortId().equals(portId) || provider.getOutputPortId().equals(portName)) {
        matchedPort = port;
        break;
      }
    }
    if (matchedPort == null) {
      log.debug("Abort, as no output port found for given output port id");
      return null;
    }

    // Resolve server config: first try data contract, then fall back to direct server field
    var serverConfig = resolveServerFromContract(matchedPort);
    if (serverConfig == null) {
      serverConfig = resolveServerFromOutputPort(matchedPort);
    }
    if (serverConfig == null) {
      log.debug("Abort, as no server configuration is available");
      return null;
    }

    var serverProject = serverConfig.get("project");
    var serverDataset = serverConfig.get("dataset");

    if (serverProject == null) {
      log.debug("Abort, as no project is available");
      return null;
    }

    if (serverDataset == null) {
      log.debug("Abort, as no dataset is available");
      return null;
    }

    return DatasetId.of(serverProject, serverDataset);
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> resolveServerFromContract(Map<String, Object> outputPort) {
    // Get the data contract ID - DPS uses "dataContractId", ODPS uses "contractId"
    var dataContractId = (String) outputPort.get("dataContractId");
    if (dataContractId == null) {
      dataContractId = (String) outputPort.get("contractId");
    }
    if (dataContractId == null) {
      // ODPS may store contractId in customProperties
      dataContractId = getCustomPropertyValue(outputPort, "contractId");
    }
    if (dataContractId == null) {
      return null;
    }

    // Get the contractServer name - DPS uses custom field, ODPS uses customProperties
    var contractServerName = getOutputPortCustomField(outputPort, "contractServer");

    // Fetch the data contract untyped to stay independent of the bundled SDK model version
    Map<String, Object> dataContractMap;
    try {
      dataContractMap = fetchDataContractAsMap(dataContractId);
    } catch (Exception e) {
      log.warn("Failed to fetch data contract {}: {}", dataContractId, e.getMessage());
      return null;
    }

    var servers = dataContractMap.get("servers");
    if (servers == null) {
      return null;
    }

    if (servers instanceof Map) {
      log.warn("Data contract {} uses the deprecated DCS format, which is not supported; migrate it to ODCS", dataContractId);
      return null;
    }

    // Open Data Contract Standard (ODCS): servers is a List with "server" field as the name
    if (servers instanceof List) {
      var serversList = (List<Map<String, Object>>) servers;
      Map<String, Object> server;
      if (contractServerName != null) {
        server = serversList.stream()
            .filter(s -> contractServerName.equals(s.get("server")))
            .findFirst().orElse(serversList.isEmpty() ? null : serversList.get(0));
      } else {
        server = serversList.isEmpty() ? null : serversList.get(0);
      }
      if (server != null) {
        return toStringMap(server);
      }
    }

    return null;
  }

  private Map<String, Object> fetchDataContractAsMap(String dataContractId) throws ApiException {
    var apiClient = client.getApiClient();
    var path = "/api/datacontracts/" + apiClient.escapeString(dataContractId);
    return apiClient.invokeAPI(
        path,
        "GET",
        new ArrayList<>(),
        new ArrayList<>(),
        "",
        null,
        new HashMap<>(),
        new HashMap<>(),
        new HashMap<>(),
        "application/json",
        null,
        new String[] {"ApiKeyAuth", "BearerAuth"},
        new TypeReference<Map<String, Object>>() {});
  }

  @SuppressWarnings("unchecked")
  private Map<String, String> resolveServerFromOutputPort(Map<String, Object> outputPort) {
    // DPS format: direct "server" field
    if (outputPort.containsKey("server") && outputPort.get("server") instanceof Map) {
      return toStringMap((Map<String, Object>) outputPort.get("server"));
    }
    // ODPS format: server in customProperties
    if (outputPort.containsKey("customProperties") && outputPort.get("customProperties") instanceof List) {
      for (var prop : (List<Map<String, Object>>) outputPort.get("customProperties")) {
        if ("server".equals(prop.get("property")) && prop.get("value") instanceof Map) {
          return toStringMap((Map<String, Object>) prop.get("value"));
        }
      }
    }
    return null;
  }

  @SuppressWarnings("unchecked")
  private String getOutputPortCustomField(Map<String, Object> outputPort, String fieldName) {
    // DPS format: custom map
    if (outputPort.containsKey("custom") && outputPort.get("custom") instanceof Map) {
      var custom = (Map<String, Object>) outputPort.get("custom");
      var value = custom.get(fieldName);
      if (value != null) return value.toString();
    }
    // ODPS format: customProperties list
    return getCustomPropertyValue(outputPort, fieldName);
  }

  @SuppressWarnings("unchecked")
  private String getCustomPropertyValue(Map<String, Object> map, String propertyName) {
    if (map.containsKey("customProperties") && map.get("customProperties") instanceof List) {
      for (var prop : (List<Map<String, Object>>) map.get("customProperties")) {
        if (propertyName.equals(prop.get("property"))) {
          var value = prop.get("value");
          return value != null ? value.toString() : null;
        }
      }
    }
    return null;
  }

  /**
   * Extract custom fields from a data product, handling both DPS ("custom" map) and ODPS ("customProperties" list) formats.
   */
  @SuppressWarnings("unchecked")
  private Map<String, String> extractCustomFields(Object rawDataProduct) {
    var map = objectMapper.convertValue(rawDataProduct, Map.class);
    // DPS format: "custom" is a Map<String, String>
    if (map.containsKey("custom") && map.get("custom") instanceof Map) {
      return (Map<String, String>) map.get("custom");
    }
    // ODPS format: "customProperties" is a List of {property, value} objects
    if (map.containsKey("customProperties") && map.get("customProperties") instanceof List) {
      var result = new HashMap<String, String>();
      for (var item : (List<Map<String, Object>>) map.get("customProperties")) {
        var property = (String) item.get("property");
        var value = item.get("value");
        if (property != null && value != null) {
          result.put(property, value.toString());
        }
      }
      return result;
    }
    return Map.of();
  }

  private static Map<String, String> toStringMap(Map<String, Object> map) {
    var result = new HashMap<String, String>();
    for (var entry : map.entrySet()) {
      if (entry.getValue() != null) {
        result.put(entry.getKey(), entry.getValue().toString());
      }
    }
    return result;
  }

  private void removeTag(String accessId, String tag) {
    Access access = client.getAccessApi().getAccess(accessId);
    if (access.getTags() != null) {
      access.getTags().remove(tag);
    }
    client.getAccessApi().addAccess(access.getId(), access);
  }

  private void addTag(String accessId, String tag) {
    Access access = client.getAccessApi().getAccess(accessId);
    if (access.getTags() == null) {
      access.setTags(new ArrayList<>());
    }
    access.getTags().add(tag);
    client.getAccessApi().addAccess(access.getId(), access);
  }

}
