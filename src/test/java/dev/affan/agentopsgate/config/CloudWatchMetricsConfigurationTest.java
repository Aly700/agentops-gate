package dev.affan.agentopsgate.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class CloudWatchMetricsConfigurationTest {

    @Test
    void publishesToTheAgentOpsGateNamespaceOncePerMinute() {
        var config = new CloudWatchMetricsConfiguration().cloudWatchConfig();

        assertThat(config.namespace()).isEqualTo("AgentOpsGate");
        assertThat(config.step()).isEqualTo(Duration.ofMinutes(1));
    }

    @Test
    void buildsAnAwsSdkCompatibleAsyncClient() {
        AwsProperties properties = new AwsProperties();
        properties.setRegion("us-east-1");

        try (var client = new CloudWatchMetricsConfiguration().cloudWatchAsyncClient(properties)) {
            assertThat(client.serviceName()).isEqualTo("monitoring"); // AWS SDK v2 service id for CloudWatch
        }
    }
}
