package dev.affan.agentopsgate.config;

import dev.affan.agentopsgate.rules.RulesEngine;
import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class ApplicationConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    RulesEngine rulesEngine() {
        return new RulesEngine();
    }
}
