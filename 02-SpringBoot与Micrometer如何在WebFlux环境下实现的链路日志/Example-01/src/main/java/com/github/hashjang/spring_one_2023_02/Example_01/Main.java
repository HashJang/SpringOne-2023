package com.github.hashjang.spring_one_2023_02.Example_01;

import io.micrometer.context.ContextRegistry;
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Hooks;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@Slf4j
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        Hooks.enableAutomaticContextPropagation();
//        ContextRegistry.getInstance().registerThreadLocalAccessor(
//                //key
//                "traceId",
//                //提取什么信息，这里提取 MDC 中的 traceId
//                () -> MDC.get("traceId"),
//                //设置什么信息，这里设置 MDC 中的 traceId
//                traceId -> MDC.put("traceId", traceId),
//                //清理什么信息，这里清理 MDC 中的 traceId
//                () -> MDC.remove("traceId"));
        SpringApplication.run(Main.class);
    }


    /**
     * Returns an instance of the correlation filter.
     * This filter sets the Mapped Diagnostic Context (MDC) with the requested traceId parameter, if available, before executing the filter chain. After the filter chain is executed
     * , it removes the traceId from the MDC.
     *
     * @return the correlation filter
     */
//    @Bean
    public Filter correlationFilter() {
        return (request, response, chain) -> {
            try {
                log.info("adding traceId");
                String name = request.getParameter("traceId");
                if (name != null) {
                    MDC.put("traceId", name);
                }
                log.info("traceId added");
                chain.doFilter(request, response);
            } finally {
                log.info("removing traceId");
                MDC.remove("traceId");
                log.info("traceId removed");
            }
        };
    }

    /**
     * The ExampleController class is a REST controller that handles the "/hello" endpoint.
     * It is responsible for returning a greeting message with the provided traceId parameter,
     * and it logs the message "hello endpoint called" when the endpoint is called.
     */
    @Slf4j
    @RestController
    public static class ExampleController {
        @GetMapping("/hello")
        String hello(@RequestParam String traceId) {
            log.info("hello endpoint called");
            return "Hello, " + traceId + "!";
        }

        @GetMapping("/hello2")
        Mono<String> hello2(@RequestParam String traceId) {
            return Mono.fromSupplier(() -> {
                log.info("hello2 endpoint called");
                return "Hello, " + traceId + "!";
            }).subscribeOn(
                    Schedulers.boundedElastic()
            ).map(s -> {
                log.info("map operator");
                return s + s;
            }).flatMap(s -> {
                log.info("flatMap operator");
                return Mono.just(s + s);
            });
        }
    }
}
