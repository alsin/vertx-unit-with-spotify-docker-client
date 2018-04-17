# Example of using Spotify's Docker Client in Vert.x Unit tests 
Example project demonstrating how to use Spotify's docker client maven plugin along with Vert.x Unit to operate with 
docker API using plane Java. Contains a Unit test with 2 test cases: 
 * one - pulling, creating, starting a Postgres container and trying to establish a connection with Postgres to make 
   sure the container is up and ready.
 * another one - pulling, creating, starting a Postgres container and trying to match the container's log entries to 
   make sure the container is up and ready.
   
Using the Docker Client is an alternative to the Fabric8's Docker Maven plugin. It can be useful in case you'd like to 
start and work with a Docker container directly from a Java code.    

# Creating a Docker Client
Create a Docker Client instance using your local system Docker setup:   

    DockerClient dockerClient = DefaultDockerClient.fromEnv().build();

# Pulling an image 
    
    dockerClient.pull("postgres:latest");
    
# Pulling an image from a custom registry

    dockerClient.pull("host.com/namespace/image-name:tag");


# Bind a container's port to host port(s)

    final Map<String, List<PortBinding>> portBindings = new HashMap<>();
    List<PortBinding> hostPorts = new ArrayList<>();
    hostPorts.add(PortBinding.of("0.0.0.0", "5444"));
    // Bind container's port 5432 to the list of the host ports
    portBindings.put("5432", hostPorts);

# Prepare environment variables for the container

    List<String> env = ImmutableList.of(
        "POSTGRES_USER=foo",
        "POSTGRES_PASSWORD=bar",
        "POSTGRES_DB=testdb",
        ...
    );

# Creating a container 

    final HostConfig hostConfig = HostConfig.builder().portBindings(portBindings).build();
    
    final ContainerConfig containerConfig = ContainerConfig.builder()
            .hostConfig(hostConfig)
            .image("host.com/namespace/image-name:tag")
            .exposedPorts("5432")
            .env(env)
            .build();

    final ContainerCreation creation = dockerClient.createContainer(containerConfig);
    containerId = creation.id();

# Starting a container
            
    dockerClient.startContainer(containerId);

# 1. Polling server connection
At this point the container should have been created and started. Now we need to know when the Postgres is ready to 
accept connections. One approach is to poll the server for a connection. We could try establishing a connection with 
the server every 500 milliseconds for at maximum 30 seconds as the final timeout:

    boolean succeeded = false;
    while (!succeeded && timer.elapsed(TimeUnit.SECONDS) < 30) {
        try {
            Thread.sleep(500);
            succeeded = checkPostgresConnection();
    
            Properties props = new Properties();
            props.setProperty("user", "foo");
            props.setProperty("password", "bar");

            try (Connection ignored = DriverManager.getConnection(getConnectionUrl(), props)) {
                succeeded = true;
            } catch (SQLException e) {
                succeeded = false;
            }
        } catch (Exception e) {
            //handle error
        }
    }

# 2. Matching container's logs
Another solution could be matching the container's logs with expected text. This would require attaching to the 
container until the test end when the container gets stopped. For this purpose we can use Vertx's `executeBlocking()` 
method providing it a future which we can wait for in the `@After` routine:

    Future<Void> logReadFuture = Future.future();
    
    vertx.executeBlocking(fut -> {
        List<String> startUpMatchMessages = Lists.newArrayList(
            "PostgreSQL init process complete; ready for start up.",
            "database system is ready to accept connections"
        );

        try (LogStream stream = dockerClient.attachContainer(containerId,
                DockerClient.AttachParameter.LOGS, DockerClient.AttachParameter.STDOUT,
                DockerClient.AttachParameter.STDERR, DockerClient.AttachParameter.STREAM)) {
            
            while (stream.hasNext()) {// this gets blocked when we come to the end of the logs and ublocked when the container is stopped
                String logLine = UTF_8.decode(stream.next().content()).toString();
                if (logLine.contains(startUpMatchMessages.get(0))) {
                    startUpMatchMessages.remove(0);
                }

                if (startUpMatchMessages.isEmpty()) {
                    LOG.info("All log lines matched!");
                    // Complete the future initiating the next step where we can start communicate with Postgres
                    resultFuture.complete(containerId);
                }
            }
        } catch (InterruptedException | DockerException e) {
            LOG.info("Error when reading docker container logs.");
            fut.fail(e);
        }
     
        if (!fut.isComplete()) {
            LOG.info("Finishing reading docker container logs.");
            fut.complete();
        }
    }, logReadFuture);
 
# Killing & removing the container

    dockerClient.killContainer(id);
    dockerClient.removeContainer(id);
    dockerClient.close();


 