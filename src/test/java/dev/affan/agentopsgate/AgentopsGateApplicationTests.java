package dev.affan.agentopsgate;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {
		"agentops.api-key=test-key",
		"agentops.aws.enabled=false"
})
class AgentopsGateApplicationTests {

	@Test
	void contextLoads() {
	}

}
