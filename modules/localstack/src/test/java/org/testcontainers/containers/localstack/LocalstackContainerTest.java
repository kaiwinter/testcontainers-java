package org.testcontainers.containers.localstack;


import com.amazonaws.services.s3.AmazonS3;
import com.amazonaws.services.s3.AmazonS3ClientBuilder;
import com.amazonaws.services.s3.model.Bucket;
import com.amazonaws.services.s3.model.ObjectListing;
import com.amazonaws.services.s3.model.S3Object;
import com.amazonaws.services.sqs.AmazonSQS;
import com.amazonaws.services.sqs.AmazonSQSClientBuilder;
import com.amazonaws.services.sqs.model.CreateQueueResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.junit.ClassRule;
import org.junit.Test;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.List;

import static org.hamcrest.CoreMatchers.containsString;
import static org.rnorth.visibleassertions.VisibleAssertions.assertEquals;
import static org.rnorth.visibleassertions.VisibleAssertions.assertThat;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.S3;
import static org.testcontainers.containers.localstack.LocalStackContainer.Service.SQS;

@Slf4j
public class LocalstackContainerTest {

    @ClassRule
    public static LocalStackContainer localstack = new LocalStackContainer()
            .withServices(S3, SQS);

    private static Network network = Network.newNetwork();

    @ClassRule
    public static LocalStackContainer localstackInDockerNetwork = new LocalStackContainer()
        .withNetwork(network)
        .withNetworkAliases("localstack")
        .withServices(S3, SQS);


    @Test
    public void s3TestBridgeNetwork() throws IOException {
        AmazonS3 s3 = AmazonS3ClientBuilder
                .standard()
                .withEndpointConfiguration(localstack.getEndpointConfiguration(S3))
                .withCredentials(localstack.getDefaultCredentialsProvider())
                .build();

        s3.createBucket("foo");
        s3.putObject("foo", "bar", "baz");

        final List<Bucket> buckets = s3.listBuckets();
        assertEquals("The created bucket is present", 1, buckets.size());
        final Bucket bucket = buckets.get(0);

        assertEquals("The created bucket has the right name", "foo", bucket.getName());
        assertEquals("The created bucket has the right name", "foo", bucket.getName());

        final ObjectListing objectListing = s3.listObjects("foo");
        assertEquals("The created bucket has 1 item in it", 1, objectListing.getObjectSummaries().size());

        final S3Object object = s3.getObject("foo", "bar");
        final String content = IOUtils.toString(object.getObjectContent(), Charset.forName("UTF-8"));
        assertEquals("The object can be retrieved", "baz", content);
    }

    @Test
    public void sqsBridgeNetwork() {
        AmazonSQS sqs = AmazonSQSClientBuilder.standard()
            .withEndpointConfiguration(localstack.getEndpointConfiguration(SQS))
            .withCredentials(localstack.getDefaultCredentialsProvider())
            .build();

        CreateQueueResult queueResult = sqs.createQueue("baz");
        String fooQueueUrl = queueResult.getQueueUrl();
        assertThat("Created queue has external hostname URL", fooQueueUrl,
            containsString("http://" + DockerClientFactory.instance().dockerHostIpAddress() + ":" + localstack.getMappedPort(SQS.getPort())));
    }

    @Test
    public void s3DockerNetwork() {
        runAwsCliAgainstDockerNetworkContainer("s3api create-bucket --bucket foo", S3.getPort());
        runAwsCliAgainstDockerNetworkContainer("s3api list-buckets", S3.getPort());
        runAwsCliAgainstDockerNetworkContainer("s3 ls s3://foo", S3.getPort());
    }

    @Test
    public void sqsDockerNetwork() {
        final String queueCreationResponse = runAwsCliAgainstDockerNetworkContainer("sqs create-queue --queue-name baz", SQS.getPort());

        assertThat("Created queue has external hostname URL", queueCreationResponse,
            containsString("http://localstack:" + SQS.getPort()));
    }

    private String runAwsCliAgainstDockerNetworkContainer(String command, final int port) {
        try (GenericContainer container = new GenericContainer<>("atlassian/pipelines-awscli")
            .withNetwork(network)
            .withCommand("--region eu-west-1 " + command + " --endpoint-url http://localstack:" + port + " --no-verify-ssl")
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy())
            .withLogConsumer(new Slf4jLogConsumer(LoggerFactory.getLogger("aws-cli")))
            .withEnv("AWS_ACCESS_KEY_ID", "accesskey")
            .withEnv("AWS_SECRET_ACCESS_KEY", "secretkey")
            .withEnv("AWS_REGION", "eu-west-1")) {

            container.start();
            final String logs = container.getLogs();
            log.info(logs);
            return logs;
        }
    }
}
