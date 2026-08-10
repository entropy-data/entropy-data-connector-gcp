Entropy Data Connector for GCP
===

The connector for GCP is a Spring Boot application that uses the [entropy-data-sdk](https://github.com/entropy-data/entropy-data-sdk) internally, and is available as a ready-to-use Docker image [entropydata/entropy-data-connector-gcp](https://hub.docker.com/r/entropydata/entropy-data-connector-gcp) to be deployed in your environment.

## Features

- **Asset Synchronization**: Sync tables and datasets of BigQuery projects to Entropy Data as Assets.
- **Access Management**: Listen for AccessActivated and AccessDeactivated events in Entropy Data and grants access on BigQuery datasets to the data consumer.
  The BigQuery server (project and dataset) is resolved from the data contract linked by the provider output port, which must be in ODCS (Open Data Contract Standard) format; the legacy Data Contract Specification (DCS) is not supported.

## Usage

Start the connector using Docker. You must pass the API keys as environment variables.

```
docker run \
  -e ENTROPYDATA_CLIENT_APIKEY='insert-api-key-here' \
  -e GOOGLE_APPLICATION_CREDENTIALS=/tmp/keys/filename.json \
  -v $GOOGLE_APPLICATION_CREDENTIALS:/tmp/keys/filename.json:ro \
  entropydata/entropy-data-connector-gcp:latest
```

## Versions

Every release is published as an immutable image tag. Pin a version rather than following `latest`:

```
entropydata/entropy-data-connector-gcp:0.9.0
```

| Tag | Meaning |
|---|---|
| `X.Y.Z` | A released version. Immutable, and the recommended way to run the connector. |
| `latest` | The most recent release. Moves with every release. |
| `sha-<commit>` | A single commit on `main`, published so that a change can be tried out before it is released. |

Release images are signed with [cosign](https://docs.sigstore.dev/), and carry an SBOM and build provenance:

```
cosign verify entropydata/entropy-data-connector-gcp:0.9.0 \
  --certificate-identity-regexp 'https://github.com/entropy-data/entropy-data-connector-gcp/.*' \
  --certificate-oidc-issuer https://token.actions.githubusercontent.com
```

## Configuration

| Environment Variable                                                         | Default Value                      | Description                                                                            |
|------------------------------------------------------------------------------|------------------------------------|----------------------------------------------------------------------------------------|
| `ENTROPYDATA_CLIENT_HOST`                                                | `https://api.entropy-data.com` | Base URL of the Entropy Data API.                                                 |
| `ENTROPYDATA_CLIENT_APIKEY`                                              |                                    | API key for authenticating requests to Entropy Data.                          |
| `ENTROPYDATA_CLIENT_GCP_ACCESSMANAGEMENT_CONNECTORID`                 | `gcp-access-management`            | Identifier for the GCP access management connector.                                 |
| `ENTROPYDATA_CLIENT_GCP_ACCESSMANAGEMENT_ENABLED`                 | `true`                             | Indicates whether GCP access management is enabled.                             |
| `ENTROPYDATA_CLIENT_GCP_ACCESSMANAGEMENT_MAPPING_DATAPRODUCT_CUSTOMFIELD` | `gcpPrincipal`                     | Custom field mapping for GCP service principals in data products.               |
| `ENTROPYDATA_CLIENT_GCP_ACCESSMANAGEMENT_MAPPING_TEAM_CUSTOMFIELD`       | `gcpPrincipal`                     | Custom field mapping for GCP service principals in teams.                       |
| `ENTROPYDATA_CLIENT_GCP_ASSETS_CONNECTORID`                           | `gcp-assets`                       | Identifier for the GCP assets connector.                                            |
| `ENTROPYDATA_CLIENT_GCP_ASSETS_ENABLED`                           | `true`                             | Indicates whether GCP asset tracking is enabled.                                |
| `ENTROPYDATA_CLIENT_GCP_ASSETS_POLLINTERVAL`                      | `PT5S`                             | Polling interval for GCP asset updates, in ISO 8601 duration format.            |
| `ENTROPYDATA_CLIENT_GCP_ASSETS_TABLES_ALLOWLIST`                  | `*`                                | List of allowed tables for GCP asset tracking (wildcard `*` allows all tables). |

## Resources

The connector needs **at least 1 GB of container memory**. The image sets a heap limit accordingly:

```
JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError
```

Without `MaxRAMPercentage`, the JVM caps the heap at 25% of the container memory. `ExitOnOutOfMemoryError` terminates the
container instead of leaving it running with a dead synchronization thread, so that your orchestrator can restart it.

Setting `JAVA_TOOL_OPTIONS` at runtime **replaces** these flags rather than adding to them. Repeat the flags you want to keep:

```
-e JAVA_TOOL_OPTIONS='-XX:MaxRAMPercentage=60 -XX:+ExitOnOutOfMemoryError -javaagent:/agent.jar'
```

Expect the container to use around 60% of its memory limit under load. Adjust memory alarms accordingly.

### Synchronization Health

The health endpoint reports whether the asset synchronization is still up to date:

```
curl http://localhost:8080/actuator/health
```

The `assetsSynchronizationHealth` component reports `DEGRADED` when the last run failed, or when no run has succeeded for three
poll intervals, and names the failure in `lastFailure`. It is deliberately not reported as `DOWN`, and the endpoint still responds
with 200, because the usual cause is an unavailable data platform, which restarting the container does not fix. Point liveness
probes at `/actuator/health/liveness`, which is unaffected by the synchronization state.
