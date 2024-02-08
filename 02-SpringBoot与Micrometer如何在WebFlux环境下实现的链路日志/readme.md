> 个人创作公约：本人声明创作的所有文章皆为自己原创，如果有参考任何文章的地方，会标注出来，如果有疏漏，欢迎大家批判。如果大家发现网上有抄袭本文章的，欢迎举报，并且积极向这个 [github 仓库](https://github.com/HashZhang/fxckPlagiarism) 提交 issue，谢谢支持~
> 另外，本文为了避免抄袭，会在不影响阅读的情况下，在文章的随机位置放入对于抄袭和洗稿的人的“亲切”的问候。如果是正常读者看到，笔者在这里说声对不起，。如果被抄袭狗或者洗稿狗看到了，希望你能够好好反思，不要再抄袭了，谢谢。

# 02-SpringBoot与Micrometer如何在WebFlux环境下实现的链路日志

- 视频原始地址：https://www.youtube.com/watch?v=xF7aZJlfTSw&list=PLgGXSWYM2FpPrAdQor9pi__EV1O69Qbom&index=25
- 个人翻译地址：https://www.bilibili.com/video/BV12K421y7kF/
- 个人总结代码与介绍地址：https://github.com/HashZhang/SpringOne-2023/blob/main/02-SpringBoot%E4%B8%8EMicrometer%E5%A6%82%E4%BD%95%E5%9C%A8WebFlux%E7%8E%AF%E5%A2%83%E4%B8%8B%E5%AE%9E%E7%8E%B0%E7%9A%84%E9%93%BE%E8%B7%AF%E6%97%A5%E5%BF%97

我们可以在日志中加入链路信息，这样我们可以找到某个请求，某个事务所有的日志，这样就可以方便的进行问题排查。并且，我们还可以通过 traceId 找到不同微服务调用链路相关的日志。 在 Spring Boot 3.x 之前，我们一般用 spring-cloud-sleuth 去实现，但是在 Spring Boot 3.x 之后，已经去掉了对于 sleuth 的原生支持，全面改用了 micrometer。

首先，我们先思考下，这些链路日志是怎么实现的？我们知道，所有的日志框架，都带有 %X 这个日志格式占位符。这个占位符，就是从 MDC（Mapped Diagnostic Context）中取出对应 key 来实现的。MDC 是一个 ThreadLocal 的变量，它是一个 Map，我们可以在任何地方往里面放值，然后在任何地方取出来。这样，我们就可以在任何地方，把 traceId 放到 MDC 中，然后通过类似于下面的日志格式，就可以在日志中打印出来。

```yaml
logging:
  pattern:
    console: "%d{yyyy-MM-dd HH:mm:ss.SSS} [%thread] %-5level %logger{36} %X{traceId} - %msg%n"
```
## 1. 不依赖任何框架，如何实现链路日志
如果我们不依赖任何框架，看看我们如何实现这个功能。首先在 web-mvc 环境下，我们可以通过拦截器，在请求进来的时候，把 traceId 放到 MDC 中，然后在请求结束的时候，把 traceId 从 MDC 中移除。

然后，我们添加一个普通的接口，这个接口里面，我们打印一下日志，看看 traceId 是否能够打印出来。这些都是在 web-mvc 环境下，我们可以很方便的实现。这里为了方便，我们把所有代码放在一个类里面：

```java
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Slf4j
@SpringBootApplication
public class Main {
    public static void main(String[] args) {
        SpringApplication.run(Main.class);
    }


    /**
     * Returns an instance of the correlation filter.
     * This filter sets the Mapped Diagnostic Context (MDC) with the requested traceId parameter, if available, before executing the filter chain. After the filter chain is executed
     * , it removes the traceId from the MDC.
     *
     * @return the correlation filter
     */
    @Bean
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
    }
}
```
启动后，我们调用 hello 接口，传入 traceId 参数（`curl 'http://127.0.0.1:8080/hello?traceId=123456'`），我们可以看到日志中打印出了 traceId。

```shell
2024-02-02 17:58:14.727 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][] - adding traceId
2024-02-02 17:58:14.728 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][123456] - traceId added
2024-02-02 17:58:14.736 [http-nio-8080-exec-1][INFO ][c.g.h.s.E.Main$ExampleController][123456] - hello endpoint called
2024-02-02 17:58:14.740 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][123456] - removing traceId
2024-02-02 17:58:14.740 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][] - traceId removed
```

我们可以看出，随着 traceId 放入 MDC，日志中开始有了 traceId，然后随着 traceId 从 MDC 中移除，日志中的 traceId 也消失了。这就是链路日志的原理。

## 2. 遇到问题，链路信息丢失

由于 MDC 是一个 ThreadLocal 的变量，所以在 WebFlux 的环境下，由于每个操作符都可能会切换线程（在发生 IO 的时候，或者使用 subscribeOn 或者 publishOn 这种操作符），这就导致了我们在 WebFlux 环境下，无法通过 MDC 来实现链路日志。我们举个例子：

```java
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
```

这时候，我们调用 hello2 接口，传入 traceId 参数（`curl 'http://127.0.0.1:8080/hello2?traceId=123456'`），我们可以看到日志中并没有 traceId。

```shell
2024-02-02 18:09:08.398 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][] - adding traceId
2024-02-02 18:09:08.398 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][123456] - traceId added
2024-02-02 18:09:08.421 [boundedElastic-1][INFO ][c.g.h.s.E.Main$ExampleController][] - hello2 endpoint called
2024-02-02 18:09:08.421 [boundedElastic-1][INFO ][c.g.h.s.E.Main$ExampleController][] - map operator
2024-02-02 18:09:08.421 [boundedElastic-1][INFO ][c.g.h.s.E.Main$ExampleController][] - flatMap operator
2024-02-02 18:09:08.423 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][123456] - removing traceId
2024-02-02 18:09:08.424 [http-nio-8080-exec-1][INFO ][c.g.h.s.Example_01.Main][] - traceId removed
```
同时，我们可以看到，我们在 hello2 方法中，使用了 subscribeOn 操作符，这就导致了我们的代码在 boundedElastic 线程中执行，而不是在 http-nio-8080-exec-1 线程中执行。这就导致了我们在 WebFlux 环境下，无法通过 MDC 来实现链路日志。


## 3. 解决方案，以及观察纯 Webflux 下的效果

Micrometer 社区做了很多兼容各种框架的工作，我们首先添加依赖：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>context-propagation</artifactId>
    <version>1.0.4</version>
</dependency>
```

然后，通过以下代码启用 Project Reactor 的 ContextPropagation：

```java
Hooks.enableAutomaticContextPropagation();
```
以上代码的作用是，在 WebFlux 的各种操作符的时候，会自动把当前的 Context 传递到下游中。

然后，添加 context-propagation 中从线程上下文获取信息的功能，同时，在这里将 MDC 中 traceId 信息提取：

```java
ContextRegistry.getInstance().registerThreadLocalAccessor(
    //key
    "traceId",
    //提取什么信息，这里提取 MDC 中的 traceId
    () -> MDC.get("traceId"),
    //设置什么信息，这里设置 MDC 中的 traceId
    traceId -> MDC.put("traceId", traceId),
    //清理什么信息，这里清理 MDC 中的 traceId
    () -> MDC.remove("traceId"));
```
之后，重启我们的应用，我们调用 hello2 接口，传入 traceId 参数（`curl 'http://http://127.0.0.1:8080/hello2?traceId=123456'`）：

```shell
2024-02-02 19:49:47.729 [http-nio-8080-exec-9][INFO ][c.g.h.s.Example_01.Main][] - adding traceId
2024-02-02 19:49:47.730 [http-nio-8080-exec-9][INFO ][c.g.h.s.Example_01.Main][123456] - traceId added
2024-02-02 19:49:47.730 [boundedElastic-3][INFO ][c.g.h.s.E.Main$ExampleController][123456] - hello2 endpoint called
2024-02-02 19:49:47.731 [boundedElastic-3][INFO ][c.g.h.s.E.Main$ExampleController][123456] - map operator
2024-02-02 19:49:47.731 [http-nio-8080-exec-9][INFO ][c.g.h.s.Example_01.Main][123456] - removing traceId
2024-02-02 19:49:47.731 [boundedElastic-3][INFO ][c.g.h.s.E.Main$ExampleController][123456] - flatMap operator
2024-02-02 19:49:47.731 [http-nio-8080-exec-9][INFO ][c.g.h.s.Example_01.Main][] - traceId removed
```
我们可以看到，我们在 hello2 方法中，使用了 subscribeOn 操作符，这就导致了我们的代码在 boundedElastic 线程中执行，而不是在 http-nio-8080-exec-1 线程中执行。但是，我们可以看到，我们的日志中，traceId 依然被打印出来了。这就是我们通过 Micrometer 实现链路日志的原理。

## 4. 框架自动实现链路日志

上面我们演示的工作，其实框架都会帮我们做了。我们只需要添加依赖：

```xml
<dependency>
    <groupId>io.micrometer</groupId>
    <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
```
重新编写代码，依然需要启用 Project Reactor 的 ContextPropagation：

```java
import jakarta.servlet.Filter;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
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
        SpringApplication.run(Main.class);
    }
    
    @Slf4j
    @RestController
    public static class ExampleController {
        @GetMapping("/hello")
        String hello() {
            log.info("hello endpoint called");
            return "Hello!";
        }

        @GetMapping("/hello2")
        Mono<String> hello2() {
            return Mono.fromSupplier(() -> {
                log.info("hello2 endpoint called");
                return "Hello2!";
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
```
之后，重启我们的应用，调用 hello 和 hello2 接口，可以看到日志中都有 traceId。

```shell

2024-02-02 20:32:11.263 [http-nio-8080-exec-5][INFO ][c.g.h.s.E.Main$ExampleController][65bce0cb58b130d852320a114ffa79d0] - hello endpoint called
2024-02-02 20:32:13.262 [boundedElastic-2][INFO ][c.g.h.s.E.Main$ExampleController][65bce0cd3ca3e1a311aaa81e13317436] - hello2 endpoint called
2024-02-02 20:32:13.262 [boundedElastic-2][INFO ][c.g.h.s.E.Main$ExampleController][65bce0cd3ca3e1a311aaa81e13317436] - map operator
2024-02-02 20:32:13.262 [boundedElastic-2][INFO ][c.g.h.s.E.Main$ExampleController][65bce0cd3ca3e1a311aaa81e13317436] - flatMap operator
```
> **微信搜索“hashcon”关注公众号，加作者微信**：
> ![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E5%85%AC%E4%BC%97%E5%8F%B7QR.gif)
> 我会经常发一些很好的各种框架的官方社区的新闻视频资料并加上个人翻译字幕到如下地址（也包括上面的公众号），欢迎关注：
>
> *   知乎：<https://www.zhihu.com/people/zhxhash>
> *   B 站：<https://space.bilibili.com/31359187>


