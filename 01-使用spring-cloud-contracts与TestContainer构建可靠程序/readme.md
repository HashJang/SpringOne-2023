> 个人创作公约：本人声明创作的所有文章皆为自己原创，如果有参考任何文章的地方，会标注出来，如果有疏漏，欢迎大家批判。如果大家发现网上有抄袭本文章的，欢迎举报，并且积极向这个 [github 仓库](https://github.com/HashZhang/fxckPlagiarism) 提交 issue，谢谢支持~
> 另外，本文为了避免抄袭，会在不影响阅读的情况下，在文章的随机位置放入对于抄袭和洗稿的人的“亲切”的问候。如果是正常读者看到，笔者在这里说声对不起，。如果被抄袭狗或者洗稿狗看到了，希望你能够好好反思，不要再抄袭了，谢谢。


# 01-使用spring-cloud-contract与TestContainer构建可靠程序

> **笔者了解了 spring-cloud-contract 的用法之后，尝试了下，目前感觉这个还是太不智能，不推荐使用**

 - 原始视频地址：https://www.youtube.com/watch?v=9Mc9Yonj9gs&list=PLgGXSWYM2FpPrAdQor9pi__EV1O69Qbom&index=6
 - 个人翻译地址：https://www.bilibili.com/video/BV1nU421Z7h8/
 - 个人总结代码与介绍地址：https://github.com/HashZhang/SpringOne-2023/tree/main/01-%E4%BD%BF%E7%94%A8spring-cloud-contracts%E4%B8%8ETestContainer%E6%9E%84%E5%BB%BA%E5%8F%AF%E9%9D%A0%E7%A8%8B%E5%BA%8F


我们在协作微服务的时候，可能是不同的人写的，不同的团队写的，不同的语言写的，不同的框架写的。通信方式也千奇百怪，可以通过 http 调用，grpc 调用，或者通过消息队列 kafka 这种异步方式通信。但是，核心其实就是我们之间达成某种约定，约定好数据的格式。这样，我们就需要一种方式，来保证我们的微服务之间的协作即数据格式是可靠的。

这时候，我们就需要使用 spring-cloud-contract 来实现这个功能。spring-cloud-contract 是一个测试框架，它可以帮助我们在开发微服务的时候，通过契约测试来保证微服务之间的协作是可靠的。它的核心思想是，通过契约来定义微服务之间的通信，然后通过测试来保证这个契约是可靠的。

spring-cloud-contract 包含三大块内容：
 - 契约定义：定义微服务之间的通信契约
 - 契约生成：生成契约测试代码
 - 契约测试：通过契约测试来保证微服务之间的通信是可靠的

## spring-cloud-contract 使用

主要步骤是：
1. 编写基类，用于定义测试需要的环境（比如需要 TestContainer 初始化哪些镜像进行使用）
2. 编写上游信息的代码，来触发契约生成，这个一般需要配合 spring-cloud-contract-samples（ https://github.com/spring-cloud-samples/spring-cloud-contract-samples ），复制里面的代码模拟你的环境
3. 编写契约定义
4. 进行契约测试，自动生成契约测试代码

这里以他们的示例，演示下上面的步骤，他们的代码主要是一个咖啡服务，咖啡师通过 kafka 接收订单信息，然后制作咖啡，然后通过 kafka 发送制作好的咖啡信息，或者如果订单中的咖啡没有，就发送错误消息到 kafka。

首先编写测试基类，通过 TestContainer 初始化 kafka 镜像：
```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE, classes = {BaristaApplication.class, BaseTestClass.TestConfig.class})
@Testcontainers
@AutoConfigureMessageVerifier
@ActiveProfiles("contracts")
public abstract class BaseTestClass {

    @Autowired
    KafkaHandler kafkaHandler;

    @Container
    @ServiceConnection
    static KafkaContainer kafka = new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka"));
}
```
然后，参考 spring-cloud-contract-samples（ https://github.com/spring-cloud-samples/spring-cloud-contract-samples ），复制其中的 MessageVerifierReceiver 代码，并且配置我们自己的超时时间：
```java
    static class KafkaMessageVerifier implements MessageVerifierReceiver<Message<?>> {

        private static final Log LOG = LogFactory.getLog(KafkaMessageVerifier.class);

        Map<String, BlockingQueue<Message<?>>> broker = new ConcurrentHashMap<>();


        @Override
        public Message receive(String destination, long timeout, TimeUnit timeUnit, @Nullable YamlContract contract) {
            broker.putIfAbsent(destination, new ArrayBlockingQueue<>(1));
            BlockingQueue<Message<?>> messageQueue = broker.get(destination);
            Message<?> message;
            try {
                message = messageQueue.poll(timeout, timeUnit);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
            if (message != null) {
                LOG.info("Removed a message from a topic [" + destination + "]");
                LOG.info(message.getPayload().toString());
            }
            return message;
        }


        @KafkaListener(id = "baristaContractTestListener", topics = {"errors", "servings"})
        public void listen(ConsumerRecord payload, @Header(KafkaHeaders.RECEIVED_TOPIC) String topic) {
            LOG.info("Got a message from a topic [" + topic + "]");
            Map<String, Object> headers = new HashMap<>();
            new DefaultKafkaHeaderMapper().toHeaders(payload.headers(), headers);
            broker.putIfAbsent(topic, new ArrayBlockingQueue<>(1));
            BlockingQueue<Message<?>> messageQueue = broker.get(topic);
            messageQueue.add(MessageBuilder.createMessage(payload.value(), new MessageHeaders(headers)));
        }

        @Override
        public Message receive(String destination, YamlContract contract) {
            return receive(destination, 15, TimeUnit.SECONDS, contract);
        }

    }
```
然后，编写契约定义（可以用 groovy，也可以用 yaml，不推荐用 java）：
```groovy
package contracts

import org.springframework.cloud.contract.spec.Contract

/**
 * @author Olga Maciaszek-Sharma
 */

Contract.make {
	label("serving")
	input {
		triggeredBy("triggerServing()")
	}
	outputMessage {
		sentTo("servings")
		body([beverages:
					  [[uuid  : $(anyUuid()),
						coffee: [
								name         : "V60",
								coffeeContent: "500",
								device       : "V60"
						]],
					   [uuid  : $(anyUuid()),
						coffee: [
								name              : "Latte",
								coffeeContent     : "60",
								steamedMilkContent: "180",
								milkFoamContent   : "5"
						]]
		]])
		headers {
			messagingContentType(applicationJson())
			header 'testKey1', 'testValue1'
		}
	}

}
```
这里的契约定义，就是定义了一个触发条件，然后定义了输出的消息内容（可以用很多方便的方法，例如 anyUuid()，anyInteger()，anyString() 等等）。触发条件是 triggerServing()，我们需要编写这个触发条件的代码，然后，spring-cloud-contract 会自动生成契约测试代码：
```java
public void triggerServing() {
    Order order = new Order();
    order.add(new OrderEntry("latte", 1));
    order.add(new OrderEntry("v60", 2));
    //kafka 发送 order 消息
    kafkaHandler.process(order);
}
```
之后，运行 mvn clean test，spring-cloud-contract 会自动生成契约测试代码并运行测试，生成的测试代码在 target/generated-test-sources/contracts 目录下，样子是：
```java
    @Test
	public void validate_shouldSendServing() throws Exception {
		// when:
			triggerServing();

		// then:
			ContractVerifierMessage response = contractVerifierMessaging.receive("servings",
					contract(this, "shouldSendServing.yml"));
			assertThat(response).isNotNull();

		// and:
			assertThat(response.getHeader("contentType")).isNotNull();
			assertThat(response.getHeader("contentType").toString()).isEqualTo("application/json");
			assertThat(response.getHeader("testKey1")).isNotNull();
			assertThat(response.getHeader("testKey1").toString()).isEqualTo("testValue1");

		// and:
			DocumentContext parsedJson = JsonPath.parse(contractVerifierObjectMapper.writeValueAsString(response.getPayload()));
			assertThatJson(parsedJson).array("['beverages']").contains("['uuid']").matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['name']").isEqualTo("V60");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['coffeeContent']").isEqualTo("500");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['device']").isEqualTo("V60");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['name']").isEqualTo("Latte");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['coffeeContent']").isEqualTo("60");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['steamedMilkContent']").isEqualTo("180");
			assertThatJson(parsedJson).array("['beverages']").field("['coffee']").field("['milkFoamContent']").isEqualTo("5");
	}
```

通过 mvn clean deploy，我们可以将契约测试代码部署到我们的 CI/CD 环境中，这样，其他人就可以使用我们的契约测试代码来保证他们的微服务是可靠的。

## 笔者为何不推荐使用

但是，笔者不推荐使用 spring-cloud-contract 的原因：
1. spring-cloud-contract 需要配合 spring-cloud-contract-samples（ https://github.com/spring-cloud-samples/spring-cloud-contract-samples ） 这个项目使用，根据自己的场景需要复制对应的代码去模拟对应的场景。
2. 需要手动编写 contracts 约定，同时，如果 contracts 更新，需要手动更新对应的测试代码（即每次都要 mvn clean 重新生成）。
3. 同时，测试代码的可读性大大降低，学习成本很高。

虽然 spring-cloud-contract 有很多的优点，但是笔者认为，它的缺点更多，还需要很长的路要走。可以保持关注。

## Spring Boot 与 TestContainer 的集成改进

可以参考这篇文章：https://spring.io/blog/2023/06/23/improved-testcontainers-support-in-spring-boot-3-1
详细的有关 TestContainer 的介绍可以参考我的系列文章：[深入理解并应用TestContainer系列]()

其实就是我们在开发过程中，可能也需要用到 TestContainer 来本地启动我们的项目，但是把  TestContainer 加入非 test 的依赖（例如maven 的 dependency 的 scope 为 test，这样打包的时候不会打进去需要这个依赖），可能会导致我们的项目打包臃肿。 所以，spring-boot 3.1 提供了一个新的特性，我们可以在单元测试中添加一个新的 Main 类。

```java
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
```
这里是使用了一个新的方法 `SpringApplication.from`， MyApplication 是你原来的 Spring Boot 应用入口类，这里的意思是从原来的入口类启动。有了这个方法，我们可以在启动的时候加入一个包含 MySQLContainer 的 TestContainer 配置的类。

其实，这里的 `@ServiceConnection` 是一个自定义的注解，就是起到了之前下面这段代码的作用（`@Container`自动在合适的时候调用 start 方法启动容器，并且在测试结束时关闭容器，`@DynamicPropertySource` 在容器启动后，将容器的属性塞入 spring 对应属性来兼容测试）：
```java
@Container
static MySQLContainer mySQLContainer = new MySQLContainer("mysql");

@DynamicPropertySource
static void setProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", mySQLContainer::getJdbcUrl);
    registry.add("spring.datasource.username", mySQLContainer::getUsername);
    registry.add("spring.datasource.password", mySQLContainer::getPassword);
}
```
目前，这个特性支持的 TestContainer 的容器类型有限，参考：https://spring.io/blog/2023/06/23/improved-testcontainers-support-in-spring-boot-3-1 ，其实也很好理解，因为要自动填充 spring 的属性，必须是 spring 封装了客户端的容器，所以目前支持的容器有：
 - CassandraContainer
 - CouchbaseContainer
 - ElasticsearchContainer
 - GenericContainer（使用的镜像是：redis 或者 openzipkin/zipkin）
 - JdbcDatabaseContainer
 - KafkaContainer
 - MongoDBContainer
 - MariaDBContainer 
 - MSSQLServerContainer 
 - MySQLContainer
 - Neo4jContainer
 - OracleContainer
 - PostgreSQLContainer 
 - RabbitMQContainer 
 - RedpandaContainer

> **微信搜索“hashcon”关注公众号，加作者微信**：
> ![image](https://zhxhash-blog.oss-cn-beijing.aliyuncs.com/%E5%85%AC%E4%BC%97%E5%8F%B7QR.gif)
> 我会经常发一些很好的各种框架的官方社区的新闻视频资料并加上个人翻译字幕到如下地址（也包括上面的公众号），欢迎关注：
>
> *   知乎：<https://www.zhihu.com/people/zhxhash>
> *   B 站：<https://space.bilibili.com/31359187>