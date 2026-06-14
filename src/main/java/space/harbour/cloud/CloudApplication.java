package space.harbour.cloud;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The payments table is sharded across several databases, so there is no single
 * "primary" {@code DataSource} for Spring Boot to auto-configure. We build one
 * {@code DataSource} per shard ourselves (see {@code ShardingConfig}), so the
 * default datasource auto-configuration is excluded to keep it from failing on
 * the missing {@code spring.datasource.url}.
 */
@SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
@EnableScheduling
public class CloudApplication {

	public static void main(String[] args) {
		SpringApplication.run(CloudApplication.class, args);
	}

}
