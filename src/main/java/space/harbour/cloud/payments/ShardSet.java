package space.harbour.cloud.payments;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

/**
 * The set of shard databases plus the router that maps keys onto them.
 *
 * <p>Holds one {@link JdbcTemplate} per shard (index-aligned with the
 * configured shards) and exposes the two routing operations the repository
 * needs: pick the single owning shard for a key, or fan out over all shards.
 */
public class ShardSet {

	private final List<JdbcTemplate> shards;
	private final ShardRouter router;

	public ShardSet(List<JdbcTemplate> shards, ShardRouter router) {
		if (shards.size() != router.shardCount()) {
			throw new IllegalArgumentException(
					"router shardCount " + router.shardCount()
							+ " does not match number of shard templates " + shards.size());
		}
		this.shards = List.copyOf(shards);
		this.router = router;
	}

	/** The shard that owns this key - used for single-row writes and lookups by id. */
	public JdbcTemplate forKey(String key) {
		return shards.get(router.route(key));
	}

	/** The shard index that owns this key. */
	public int indexForKey(String key) {
		return router.route(key);
	}

	/** All shards, in index order - used for scatter-gather queries (e.g. by store). */
	public List<JdbcTemplate> all() {
		return shards;
	}

	public int size() {
		return shards.size();
	}
}
