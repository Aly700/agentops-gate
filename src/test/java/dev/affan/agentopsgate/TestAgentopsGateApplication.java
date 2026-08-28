package dev.affan.agentopsgate;

import org.springframework.boot.SpringApplication;

public class TestAgentopsGateApplication {

	public static void main(String[] args) {
		SpringApplication.from(AgentopsGateApplication::main).with(TestcontainersConfiguration.class).run(args);
	}

}
