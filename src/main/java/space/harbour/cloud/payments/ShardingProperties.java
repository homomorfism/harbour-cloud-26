package space.harbour.cloud.payments;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Static sharding configuration, bound from {@code payments.sharding.*}.
 *
 * <p>The number of shards is simply {@code shards.size()} - it is fixed at
 * startup and never changes at runtime (no resharding).
 */
@ConfigurationProperties(prefix = "payments.sharding")
public class ShardingProperties {

	/** One connection per shard, in shard-index order (shard 0 is element 0). */
	private List<Shard> shards = new ArrayList<>();

	public List<Shard> getShards() {
		return shards;
	}

	public void setShards(List<Shard> shards) {
		this.shards = shards;
	}

	/** JDBC coordinates for a single shard's database. */
	public static class Shard {
		private String url;
		private String username;
		private String password;

		public String getUrl() {
			return url;
		}

		public void setUrl(String url) {
			this.url = url;
		}

		public String getUsername() {
			return username;
		}

		public void setUsername(String username) {
			this.username = username;
		}

		public String getPassword() {
			return password;
		}

		public void setPassword(String password) {
			this.password = password;
		}
	}
}
