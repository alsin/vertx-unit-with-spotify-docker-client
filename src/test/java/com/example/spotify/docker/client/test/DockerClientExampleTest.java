package com.example.spotify.docker.client.test;

import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.spotify.docker.client.DefaultDockerClient;
import com.spotify.docker.client.DockerClient;
import com.spotify.docker.client.LogStream;
import com.spotify.docker.client.exceptions.DockerCertificateException;
import com.spotify.docker.client.exceptions.DockerException;
import com.spotify.docker.client.messages.ContainerConfig;
import com.spotify.docker.client.messages.ContainerCreation;
import com.spotify.docker.client.messages.HostConfig;
import com.spotify.docker.client.messages.PortBinding;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.core.logging.Logger;
import io.vertx.core.logging.LoggerFactory;
import io.vertx.ext.unit.Async;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.sql.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;


@RunWith(VertxUnitRunner.class)
public class DockerClientExampleTest {
    private static final Logger LOG = LoggerFactory.getLogger(DockerClientExampleTest.class);

    private static final List<String> CONTAINER_LOG_TO_MATCH = ImmutableList.of(
            "PostgreSQL init process complete; ready for start up.",
            "database system is ready to accept connections"
    );

    private static final List<String> EXPECTED_USERS = ImmutableList.of(
            "postgres", "foo"
    );

    private static final String PG_USER = "foo";
    private static final String PG_PASSWORD = "bar";
    private static final String PG_DB = "testdb";
    private static final int PG_PORT = Integer.getInteger("pg.port", 5434);
    private static final String POSTGRES_DOCKER_IMAGE =
            "postgres:latest";
    private static final int DB_TIMEOUT_SEC = 10;

    private Vertx vertx;
    private DockerClient dockerClient;
    private String containerId;
    private Future<Void> dockerContainerReadFuture;

    @Before
    public void setUp() {
        vertx = Vertx.vertx();
    }

    @After
    public void cleanup(TestContext context) {
        if (dockerClient != null) {
            stopContainer(containerId).setHandler(context.asyncAssertSuccess());
        }
        if (dockerContainerReadFuture != null && !dockerContainerReadFuture.isComplete()) {
            dockerContainerReadFuture.setHandler(context.asyncAssertSuccess());
            dockerContainerReadFuture = null;
        }
        vertx.close(context.asyncAssertSuccess());
    }

    @Test
    public void testStartPostgresContainerWaitingForConnection(TestContext context) {
        Async async = context.async();

        startUpPostgresDockerContainer(true)
                .compose(containerId -> testPostgres(context))
                .setHandler(context.asyncAssertSuccess(voi -> async.complete()));

        async.await(10000L);
    }

    @Test
    public void testStartPostgresContainerWaitingForContainerLogMatch(TestContext context) {
        Async async = context.async();

        startUpPostgresDockerContainer(false)
                .compose(containerId -> testPostgres(context))
                .setHandler(context.asyncAssertSuccess(voi -> async.complete()));

        async.await(10000L);

    }

    private Future<String> startUpPostgresDockerContainer(boolean waitForPgConnection) {
        Future<String> future = Future.future();
        vertx.executeBlocking(fut -> startUpPostgresDockerContainerImpl(waitForPgConnection, fut), future);
        return future;
    }

    private void startUpPostgresDockerContainerImpl(boolean waitForPgConnection, Future<String> resultFuture) {
        try {
            dockerClient = DefaultDockerClient.fromEnv().build();
        } catch (DockerCertificateException e) {
            resultFuture.fail(e);
            return;
        }

        try {
            dockerClient.pull(POSTGRES_DOCKER_IMAGE);
        } catch (DockerException | InterruptedException e) {
            resultFuture.fail(e);
            return;
        }

        final Map<String, List<PortBinding>> portBindings = new HashMap<>();
        List<PortBinding> hostPorts = new ArrayList<>();
        String pgPortStr = String.valueOf(PG_PORT);
        hostPorts.add(PortBinding.of("0.0.0.0", pgPortStr));
        portBindings.put("5432", hostPorts);

        List<String> env = ImmutableList.of("POSTGRES_PASSWORD=" + PG_PASSWORD,
                "POSTGRES_USER=" + PG_USER,
                "POSTGRES_DB=" + PG_DB
        );
        final HostConfig hostConfig = HostConfig.builder().portBindings(portBindings).build();
        final ContainerConfig containerConfig = ContainerConfig.builder()
                .hostConfig(hostConfig)
                .image(POSTGRES_DOCKER_IMAGE)
                .exposedPorts("5432")
                .env(env)
                .build();
        try {
            final ContainerCreation creation = dockerClient.createContainer(containerConfig);
            containerId = creation.id();
            dockerClient.startContainer(containerId);

            if (waitForPgConnection) {
                if (waitForPostgresToStart(resultFuture)) {
                    final String logs;
                    try (LogStream stream = dockerClient.logs(containerId, DockerClient.LogsParam.stdout(),
                            DockerClient.LogsParam.stderr())) {
                        logs = stream.readFully();
                        LOG.info("Docker container output: ");
                        LOG.info(logs);
                    }
                    resultFuture.complete(containerId);
                }
            } else {
                dockerContainerReadFuture = Future.future();
                vertx.executeBlocking(fut -> {
                    List<String> startUpMatchMessages = Lists.newArrayList(CONTAINER_LOG_TO_MATCH);

                    try (LogStream stream = dockerClient.attachContainer(containerId,
                            DockerClient.AttachParameter.LOGS, DockerClient.AttachParameter.STDOUT,
                            DockerClient.AttachParameter.STDERR, DockerClient.AttachParameter.STREAM)) {
                        while (stream.hasNext()) {
                            String logLine = UTF_8.decode(stream.next().content()).toString();
                            LOG.info(logLine);
                            if (logLine.contains(startUpMatchMessages.get(0))) {
                                startUpMatchMessages.remove(0);
                            }

                            if (startUpMatchMessages.isEmpty()) {
                                LOG.info("All log lines matched!");
                                resultFuture.complete(containerId);
                            }
                        }
                    } catch (InterruptedException | DockerException e) {
                        LOG.info("Failing reading docker container logs.");
                        fut.fail(e);
                    }
                    if (!fut.isComplete()) {
                        LOG.info("Finishing reading docker container logs.");
                        fut.complete();
                    }
                }, dockerContainerReadFuture);
            }
        } catch (Exception e) {
            if (!resultFuture.isComplete()) {
                resultFuture.fail(e);
            }
        }
    }

    private boolean waitForPostgresToStart(Future<String> future) {
        Stopwatch timer = Stopwatch.createStarted();
        boolean succeeded = false;
        while (!succeeded && timer.elapsed(TimeUnit.SECONDS) < DB_TIMEOUT_SEC) {
            try {
                Thread.sleep(500);
                succeeded = checkPostgresConnection();
            } catch (Exception e) {
                future.fail(e);
                return false;
            }
        }
        if (!succeeded) {
            future.fail(new RuntimeException("Postgres did not start in " + DB_TIMEOUT_SEC + " seconds."));
        }
        return succeeded;
    }

    private boolean checkPostgresConnection() {
        Properties props = new Properties();
        props.setProperty("user", PG_USER);
        props.setProperty("password", PG_PASSWORD);

        try (Connection ignored = DriverManager.getConnection(getConnectionUrl(), props)) {
            return true;
        } catch (SQLException e) {
            return false;
        }
    }

    private Future<Void> testPostgres(TestContext context) {
        LOG.info("DB URL: {}", getConnectionUrl());

        Properties props = new Properties();
        props.setProperty("user", PG_USER);
        props.setProperty("password", PG_PASSWORD);

        try (Connection connection = DriverManager.getConnection(getConnectionUrl(), props)) {
            Statement statement = connection.createStatement();
            context.assertTrue(statement.execute("select * from pg_user"));
            ResultSet result = statement.getResultSet();
            int count = 0;
            while (result.next()) {
                String user = result.getString(1);
                context.assertTrue(EXPECTED_USERS.contains(user));
                count++;
            }
            context.assertEquals(EXPECTED_USERS.size(), count);
            return Future.succeededFuture();
        } catch (Exception e) {
            return Future.failedFuture(e);
        }
    }

    private String getConnectionUrl() {
        return String.format("jdbc:postgresql://localhost:%d/%s",
                PG_PORT, PG_DB);
    }

    private Future<Void> stopContainer(String id) {
        LOG.info("Stopping docker container");

        Future<Void> future = Future.future();

        try {
            dockerClient.killContainer(id);
            dockerClient.removeContainer(id);
        } catch (Exception e) {
            future.fail(e);
        }
        dockerClient.close();
        dockerClient = null;
        if (!future.isComplete()) {
            future.complete();
        }

        return future;
    }

}
