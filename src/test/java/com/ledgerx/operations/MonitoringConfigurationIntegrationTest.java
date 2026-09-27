package com.ledgerx.operations;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

class MonitoringConfigurationIntegrationTest {

  @Test
  void prometheusAndAlertmanagerAcceptTheShippedRulesAndRouting() {
    try (GenericContainer<?> alertmanager =
            new GenericContainer<>(DockerImageName.parse("prom/alertmanager:v0.34.1"))
                .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("monitoring/alertmanager.yml")),
                    "/etc/alertmanager/alertmanager.yml")
                .withCommand("--config.file=/etc/alertmanager/alertmanager.yml")
                .withExposedPorts(9093)
                .waitingFor(Wait.forHttp("/-/ready").forPort(9093));
        GenericContainer<?> prometheus =
            new GenericContainer<>(DockerImageName.parse("prom/prometheus:v3.15.0"))
                .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("monitoring/prometheus.yml")),
                    "/etc/prometheus/prometheus.yml")
                .withCopyFileToContainer(
                    MountableFile.forHostPath(Path.of("monitoring/alerts.yml")),
                    "/etc/prometheus/alerts.yml")
                .withCommand("--config.file=/etc/prometheus/prometheus.yml")
                .withExposedPorts(9090)
                .waitingFor(Wait.forHttp("/-/ready").forPort(9090))) {
      alertmanager.start();
      prometheus.start();
      assertThat(alertmanager.isRunning()).isTrue();
      assertThat(prometheus.isRunning()).isTrue();
    }
  }

  @Test
  void grafanaProvisionsTheOperationsDashboard() throws Exception {
    Path passwordFile = Files.createTempFile("grafana-admin-", ".txt");
    Files.writeString(passwordFile, "grafana_test_password");
    try (GenericContainer<?> grafana =
        new GenericContainer<>(DockerImageName.parse("grafana/grafana:13.1.6"))
            .withEnv("GF_SECURITY_ADMIN_USER", "operator")
            .withEnv("GF_SECURITY_ADMIN_PASSWORD__FILE", "/run/secrets/grafana_admin_password")
            .withCopyFileToContainer(
                MountableFile.forHostPath(passwordFile, 0444),
                "/run/secrets/grafana_admin_password")
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Path.of("monitoring/grafana/provisioning/datasources/prometheus.yml")),
                "/etc/grafana/provisioning/datasources/prometheus.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(
                    Path.of("monitoring/grafana/provisioning/dashboards/ledgerx.yml")),
                "/etc/grafana/provisioning/dashboards/ledgerx.yml")
            .withCopyFileToContainer(
                MountableFile.forHostPath(Path.of("monitoring/grafana/dashboards/operations.json")),
                "/etc/grafana/dashboards/operations.json")
            .withExposedPorts(3000)
            .waitingFor(Wait.forHttp("/api/health").forPort(3000))) {
      grafana.start();
      var secretRead = grafana.execInContainer("cat", "/run/secrets/grafana_admin_password");
      assertThat(secretRead.getExitCode()).describedAs(secretRead.getStderr()).isZero();
      assertThat(secretRead.getStdout()).isEqualTo("grafana_test_password");
      String credentials =
          Base64.getEncoder()
              .encodeToString("operator:grafana_test_password".getBytes(StandardCharsets.UTF_8));
      HttpRequest request =
          HttpRequest.newBuilder(
                  URI.create(
                      "http://"
                          + grafana.getHost()
                          + ":"
                          + grafana.getMappedPort(3000)
                          + "/api/dashboards/uid/ledgerx-operations"))
              .header("Authorization", "Basic " + credentials)
              .build();
      HttpResponse<String> response =
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
      assertThat(response.statusCode()).describedAs(grafana.getLogs()).isEqualTo(200);
      assertThat(response.body()).contains("LedgerX operations");
    } finally {
      Files.deleteIfExists(passwordFile);
    }
  }
}
