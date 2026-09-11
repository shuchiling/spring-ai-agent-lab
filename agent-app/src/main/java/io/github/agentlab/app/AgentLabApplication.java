package io.github.agentlab.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "io.github.agentlab")
public class AgentLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentLabApplication.class, args);
    }
}