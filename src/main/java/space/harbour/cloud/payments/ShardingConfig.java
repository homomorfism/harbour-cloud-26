package space.harbour.cloud.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds the {@link ShardSet} from {@link ShardingProperties}: one pooled
 * {@link DataSource} / {@link JdbcTemplate} per configured shard, and creates
 * the {@code payments} table on each shard at startup.
 */
@Configuration
@EnableConfigurationProperties(ShardingProperties.class)
public class ShardingConfig {

	private static final Logger log = LoggerFactory.getLogger(ShardingConfig.class);

	/**
	 * Schema for a shard. Written in the common subset of Postgres and H2 SQL so
	 * the identical DDL initialises both production shards and the in-memory test
	 * shards. {@code IF NOT EXISTS} makes startup idempotent across restarts.
	 *
	 * <p>Both tables live on every shard; routing (by payment id / by batch id)
	 * decides which shard a given row lands on.
	 */
	private static final String[] SCHEMA = {
			"""
			CREATE TABLE IF NOT EXISTS payments (
			    payment_id      VARCHAR(64) PRIMARY KEY,
			    batch_id        VARCHAR(64),
			    store_id        VARCHAR(128) NOT NULL,
			    coffee_type     VARCHAR(32)  NOT NULL,
			    price           NUMERIC(12,2) NOT NULL,
			    currency        VARCHAR(3)   NOT NULL,
			    loyalty_card_id VARCHAR(128),
			    idempotency_key VARCHAR(128) NOT NULL,
			    status          VARCHAR(16)  NOT NULL,
			    remote_ref      VARCHAR(128),
			    registered_at   TIMESTAMP    NOT NULL
			)
			""",
			"""
			CREATE TABLE IF NOT EXISTS payment_batches (
			    batch_id        VARCHAR(64) PRIMARY KEY,
			    store_id        VARCHAR(128) NOT NULL,
			    status          VARCHAR(16)  NOT NULL,
			    total_payments  INT          NOT NULL,
			    created_at      TIMESTAMP    NOT NULL,
			    updated_at      TIMESTAMP    NOT NULL
			)
			""",
			"CREATE INDEX IF NOT EXISTS idx_payments_batch ON payments (batch_id)",
			"CREATE INDEX IF NOT EXISTS idx_payments_status ON payments (status)",
			"CREATE INDEX IF NOT EXISTS idx_batches_status ON payment_batches (status)"
	};

	@Bean
	public ShardSet shardSet(ShardingProperties properties) {
		List<ShardingProperties.Shard> configured = properties.getShards();
		if (configured.isEmpty()) {
			throw new IllegalStateException(
					"No shards configured. Define at least one payments.sharding.shards[i].* entry.");
		}

		List<JdbcTemplate> templates = new ArrayList<>(configured.size());
		for (int i = 0; i < configured.size(); i++) {
			ShardingProperties.Shard shard = configured.get(i);
			DataSource dataSource = DataSourceBuilder.create()
					.url(shard.getUrl())
					.username(shard.getUsername())
					.password(shard.getPassword())
					.build();
			JdbcTemplate template = new JdbcTemplate(dataSource);
			for (String ddl : SCHEMA) {
				template.execute(ddl);
			}
			templates.add(template);
			log.info("Initialised shard {} -> {}", i, shard.getUrl());
		}

		log.info("Sharding enabled across {} shard(s)", templates.size());
		return new ShardSet(templates, new ShardRouter(templates.size()));
	}
}
