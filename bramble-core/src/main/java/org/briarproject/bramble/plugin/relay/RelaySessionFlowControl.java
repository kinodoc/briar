package org.briarproject.bramble.plugin.relay;

import java.io.IOException;

/**
 * Per-session byte-offset flow control for Relay protocol version 2.
 *
 * <p>Window values are absolute byte offsets, not additive credits. The
 * receiver advertises the greatest cumulative byte offset it is prepared to
 * accept. Reading bytes advances that limit. The sender may write only while
 * its cumulative sent offset remains within the peer's advertised limit.</p>
 *
 * <p>This class is a synchronized protocol state primitive. Queueing and
 * blocking/wakeup policy belong to RelaySessionDispatcher.</p>
 */
final class RelaySessionFlowControl {

	private final long receiveWindowBytes;
	private final long maxWindowBytes;

	private long receivedBytes;
	private long consumedBytes;
	private long receiveLimit;

	private long sentBytes;
	private long peerLimit;
	private boolean peerWindowKnown;

	RelaySessionFlowControl(long receiveWindowBytes, long maxWindowBytes) {
		if (receiveWindowBytes <= 0 || maxWindowBytes <= 0
				|| receiveWindowBytes > maxWindowBytes) {
			throw new IllegalArgumentException("Invalid relay window size");
		}
		this.receiveWindowBytes = receiveWindowBytes;
		this.maxWindowBytes = maxWindowBytes;
		receiveLimit = receiveWindowBytes;
	}

	synchronized long getInitialReceiveLimit() {
		return receiveLimit;
	}

	/**
	 * Applies the initial peer window from OPEN or the first ACK.
	 */
	synchronized void setInitialPeerLimit(long limit) throws IOException {
		if (peerWindowKnown) {
			throw new IOException("Relay peer window already initialized");
		}
		validatePeerLimit(limit);
		peerLimit = limit;
		peerWindowKnown = true;
	}

	/**
	 * Applies a subsequent absolute ACK limit. Stale/decreasing ACKs are
	 * rejected rather than being interpreted as new credit.
	 */
	synchronized void updatePeerLimit(long limit) throws IOException {
		if (!peerWindowKnown) {
			throw new IOException("Relay peer window is not initialized");
		}
		if (limit < peerLimit) {
			throw new IOException("Relay peer window moved backwards");
		}
		validatePeerLimit(limit);
		peerLimit = limit;
	}

	private void validatePeerLimit(long limit) throws IOException {
		if (limit <= 0) throw new IOException("Invalid relay peer window");
		long maximum;
		try {
			maximum = Math.addExact(sentBytes, maxWindowBytes);
		} catch (ArithmeticException e) {
			maximum = Long.MAX_VALUE;
		}
		if (limit > maximum) {
			throw new IOException("Relay peer window exceeds maximum");
		}
	}

	/**
	 * Reserves bytes for transmission. Returns false when peer credit is
	 * exhausted; callers must wait for an ACK before retrying.
	 */
	synchronized boolean tryReserveOutgoing(int length) throws IOException {
		if (length <= 0) throw new IllegalArgumentException("Length must be positive");
		if (!peerWindowKnown) {
			throw new IOException("Relay peer window is not initialized");
		}
		long next;
		try {
			next = Math.addExact(sentBytes, length);
		} catch (ArithmeticException e) {
			throw new IOException("Relay byte offset exhausted", e);
		}
		if (next > peerLimit) return false;
		sentBytes = next;
		return true;
	}

	/**
	 * Accounts for a received DATA frame before its bytes are placed in the
	 * bounded session queue.
	 */
	synchronized void onIncomingData(int length) throws IOException {
		if (length <= 0) throw new IOException("Invalid relay DATA length");
		long next;
		try {
			next = Math.addExact(receivedBytes, length);
		} catch (ArithmeticException e) {
			throw new IOException("Relay byte offset exhausted", e);
		}
		if (next > receiveLimit) {
			throw new IOException("Relay receive window exceeded");
		}
		receivedBytes = next;
	}

	/**
	 * Accounts for bytes actually consumed by the Bramble input stream and
	 * returns the new absolute receive limit to advertise in an ACK.
	 */
	synchronized long onBytesConsumed(int length) throws IOException {
		if (length <= 0) throw new IllegalArgumentException("Length must be positive");
		long next;
		try {
			next = Math.addExact(consumedBytes, length);
		} catch (ArithmeticException e) {
			throw new IOException("Relay byte offset exhausted", e);
		}
		if (next > receivedBytes) {
			throw new IOException("Consumed more relay bytes than received");
		}
		consumedBytes = next;
		try {
			receiveLimit = Math.addExact(consumedBytes, receiveWindowBytes);
		} catch (ArithmeticException e) {
			throw new IOException("Relay receive offset exhausted", e);
		}
		return receiveLimit;
	}

	synchronized long getSentBytes() {
		return sentBytes;
	}

	synchronized long getPeerLimit() {
		return peerLimit;
	}

	synchronized long getReceivedBytes() {
		return receivedBytes;
	}

	synchronized long getConsumedBytes() {
		return consumedBytes;
	}

	synchronized long getReceiveLimit() {
		return receiveLimit;
	}
}
