package space.harbour.cloud.payments;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the pure routing logic: a payment id maps to the same shard every
 * time (deterministic), and a sample of ids spreads roughly evenly across the
 * shards (uniform). No database required - this tests the hash, not the I/O.
 */
class ShardRouterTest {

	@Test
	void routingIsDeterministic() {
		ShardRouter router = new ShardRouter(3);
		String id = UUID.randomUUID().toString();
		int first = router.route(id);
		for (int i = 0; i < 100; i++) {
			assertEquals(first, router.route(id), "same id must always route to the same shard");
		}
	}

	@Test
	void routeIsAlwaysWithinBounds() {
		ShardRouter router = new ShardRouter(3);
		for (int i = 0; i < 1000; i++) {
			int shard = router.route(UUID.randomUUID().toString());
			assertTrue(shard >= 0 && shard < 3, "shard index out of range: " + shard);
		}
	}

	@Test
	void distributesUniformlyOverASample() {
		int shardCount = 3;
		int sample = 3000;
		ShardRouter router = new ShardRouter(shardCount);

		Map<Integer, Integer> counts = new HashMap<>();
		for (int i = 0; i < sample; i++) {
			// Use ids shaped like the real ones: UUID derived from store + key.
			String paymentId = UUID.nameUUIDFromBytes(("store-" + (i % 50) + "|key-" + i).getBytes()).toString();
			counts.merge(router.route(paymentId), 1, Integer::sum);
		}

		int expected = sample / shardCount;          // 1000 per shard
		int tolerance = (int) (expected * 0.15);     // allow +/-15% skew on a small sample
		for (int shard = 0; shard < shardCount; shard++) {
			int count = counts.getOrDefault(shard, 0);
			assertTrue(Math.abs(count - expected) <= tolerance,
					"shard " + shard + " got " + count + " payments, expected ~" + expected
							+ " (+/-" + tolerance + ")");
		}
	}
}
