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
