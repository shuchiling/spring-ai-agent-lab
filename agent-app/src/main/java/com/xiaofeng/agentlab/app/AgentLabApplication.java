package com.xiaofeng.agentlab.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = "com.xiaofeng.agentlab")
public class AgentLabApplication {

    public static void main(String[] args) {
        SpringApplication.run(AgentLabApplication.class, args);
    }
}