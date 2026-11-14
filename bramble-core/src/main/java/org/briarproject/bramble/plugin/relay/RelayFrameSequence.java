package org.briarproject.bramble.plugin.relay;

import java.io.IOException;

/**
 * Validates the sequence numbers received in one direction of a relay session.
 *
 * <p>Each direction has its own instance. Sequence numbers start at zero and
 * increase by one for every frame in that direction. Old sequence numbers are
 * treated as duplicates and can be discarded. A gap is a protocol error.</p>
 *
 * <p>Calls must be serialized by the session's frame reader.</p>
 */
final class RelayFrameSequence {

	private long nextSequence;
	private boolean exhausted;

	RelayFrameSequence() {
		this(0);
	}

	RelayFrameSequence(long firstSequence) {
		if (firstSequence < 0) {
			throw new IllegalArgumentException("Sequence must be non-negative");
		}
		nextSequence = firstSequence;
	}

	/**
	 * Accepts the next frame in this direction.
	 *
	 * @return true if the frame is new and should be processed, false if it is
	 * a duplicate that should be discarded
	 * @throws IOException if the frame skips an expected sequence number or
	 * the sequence space has been exhausted
	 */
	synchronized boolean accept(long sequence) throws IOException {
		if (sequence < 0) throw new IOException("Invalid relay sequence");
		if (exhausted) throw new IOException("Relay sequence exhausted");
		if (sequence < nextSequence) return false;
		if (sequence > nextSequence) {
			throw new IOException("Unexpected relay sequence");
		}
		if (nextSequence == Long.MAX_VALUE) {
			exhausted = true;
		} else {
			nextSequence++;
		}
		return true;
	}
}
