package com.github.hashjang.spring_one_2023_01.test;

import com.github.hashjang.spring_one_2023_01.Main;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.MySQLContainer;

@SpringBootApplication
public class LocalTestMain {
    public static void main(String[] args) {
        SpringApplication.from(Main::main)
                .with(TestContainerConfig.class)
                .run(args);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestContainerConfig {
        @Bean
        @ServiceConnection
        public MySQLContainer<?> mysqlContainer() {
            return new MySQLContainer<>("mysql");
        }

    }
}
