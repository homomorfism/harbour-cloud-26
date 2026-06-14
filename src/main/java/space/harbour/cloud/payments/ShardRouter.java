package space.harbour.cloud.payments;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Decides which shard a key belongs to: {@code shardIndex = hash(key) % shardCount}.
 *
 * <p>The hash is MD5 over the key's bytes rather than {@link String#hashCode()}.
 * {@code hashCode()} is not uniformly distributed for short, structured strings
 * (and Java does not even guarantee it is stable across JVM versions), which
 * would skew the shard distribution. MD5 gives an even spread, and being a pure
 * function of the key it is fully deterministic - the same payment id always
 * routes to the same shard, on every node, forever. (MD5 is used purely as a
 * distribution function here, not for any security purpose.)
 */
public class ShardRouter {

	private final int shardCount;

	public ShardRouter(int shardCount) {
		if (shardCount < 1) {
			throw new IllegalArgumentException("shardCount must be >= 1, was " + shardCount);
		}
		this.shardCount = shardCount;
	}

	/** @return the shard index in {@code [0, shardCount)} that owns this key. */
	public int route(String key) {
		long hash = hash64(key);
		return (int) Long.remainderUnsigned(hash, shardCount);
	}

	public int shardCount() {
		return shardCount;
	}

	private static long hash64(String key) {
		byte[] digest = md5(key.getBytes(StandardCharsets.UTF_8));
		long hash = 0L;
		// Fold the first 8 bytes of the digest into an (unsigned) long.
		for (int i = 0; i < 8; i++) {
			hash = (hash << 8) | (digest[i] & 0xffL);
		}
		return hash;
	}

	private static byte[] md5(byte[] input) {
		try {
			return MessageDigest.getInstance("MD5").digest(input);
		} catch (NoSuchAlgorithmException e) {
			// MD5 is mandated by the JLS to be present on every JVM.
			throw new IllegalStateException("MD5 not available", e);
		}
	}
}
