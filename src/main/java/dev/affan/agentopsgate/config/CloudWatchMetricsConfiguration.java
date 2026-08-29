package dev.affan.agentopsgate.config;

import io.micrometer.cloudwatch2.CloudWatchConfig;
import io.micrometer.cloudwatch2.CloudWatchMeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import java.time.Duration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatch.CloudWatchAsyncClient;

@Configuration(proxyBeanMethods = false)
@Profile("!local & !test")
@ConditionalOnProperty(
        name = {"agentops.aws.enabled", "agentops.metrics.cloudwatch.enabled"},
        havingValue = "true")
public class CloudWatchMetricsConfiguration {

    @Bean
    CloudWatchConfig cloudWatchConfig() {
        return new CloudWatchConfig() {
            @Override
            public String get(String key) {
                return null;
            }

            @Override
            public String namespace() {
                return "AgentOpsGate";
            }

            @Override
            public Duration step() {
                return Duration.ofMinutes(1);
            }
        };
    }

    @Bean
    CloudWatchAsyncClient cloudWatchAsyncClient(AwsProperties properties) {
        return CloudWatchAsyncClient.builder()
                .region(Region.of(properties.getRegion()))
                .credentialsProvider(DefaultCredentialsProvider.create())
                .build();
    }

    @Bean
    CloudWatchMeterRegistry cloudWatchMeterRegistry(
            CloudWatchConfig config,
            CloudWatchAsyncClient cloudWatchAsyncClient) {
        CloudWatchMeterRegistry registry = new CloudWatchMeterRegistry(
                config,
                io.micrometer.core.instrument.Clock.SYSTEM,
                cloudWatchAsyncClient);
        registry.config().meterFilter(MeterFilter.denyUnless(id ->
                id.getName().startsWith("gate.") || id.getName().equals("http.server.requests")));
        return registry;
    }
}
