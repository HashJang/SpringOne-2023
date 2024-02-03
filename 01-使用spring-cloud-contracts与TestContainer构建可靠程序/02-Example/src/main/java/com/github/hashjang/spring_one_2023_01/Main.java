package com.github.hashjang.spring_one_2023_01;

import com.github.hashjang.spring_one_2023_01.jpa.UserEntity;
import com.github.hashjang.spring_one_2023_01.jpa.UserRepository;
import lombok.extern.log4j.Log4j2;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.stereotype.Service;

@Log4j2
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class, args);
    }

    @Service
    public static class CRunner implements CommandLineRunner {
        @Autowired
        private UserRepository userRepository;
        public void run(String... args) {
            UserEntity user = new UserEntity();
            user.setName("test");
            userRepository.save(user);
            log.info("User saved: {}", userRepository.findById(user.getId()));
        }
    }
}
