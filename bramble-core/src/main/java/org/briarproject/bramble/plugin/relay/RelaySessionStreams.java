package org.briarproject.bramble.plugin.relay;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Adapts framed Relay input and output streams to a pair of byte streams for
 * one Bramble duplex session.
 *
 * <p>The supplied framed streams must be dedicated to this session. A shared
 * TLS connection carrying multiple sessions needs a dispatcher before these
 * adapters can be used.</p>
 *
 * <p>This class provides framing and per-direction sequence validation only.
 * It does not provide TLS, peer authentication, retransmission or flow
 * control.</p>
 */
final class RelaySessionStreams {

	private final byte[] sessionId;
	private final InputStream framedInput;
	private final OutputStream framedOutput;
	private final RelayFrameSequence incomingSequence =
			new RelayFrameSequence();
	private final InputStream inputStream = new SessionInputStream();
	private final OutputStream outputStream = new SessionOutputStream();

	private long nextOutgoingSequence;
	private boolean outgoingExhausted;

	RelaySessionStreams(byte[] sessionId, InputStream framedInput,
			OutputStream framedOutput) {
		if (sessionId == null || framedInput == null || framedOutput == null) {
			throw new NullPointerException();
		}
		if (sessionId.length != 16) {
			throw new IllegalArgumentException("Session ID must be 16 bytes");
		}
		this.sessionId = sessionId.clone();
		this.framedInput = framedInput;
		this.framedOutput = framedOutput;
	}

	InputStream getInputStream() {
		return inputStream;
	}

	OutputStream getOutputStream() {
		return outputStream;
	}

	private synchronized void writeFrame(RelayFrameCodec.Type type,
			byte[] payload) throws IOException {
		if (outgoingExhausted) {
			throw new IOException("Relay sequence exhausted");
		}
		long sequence = nextOutgoingSequence;
		if (sequence == Long.MAX_VALUE) outgoingExhausted = true;
		else nextOutgoingSequence++;
		RelayFrameCodec.writeFrame(framedOutput,
				new RelayFrameCodec.Frame(type, sessionId, sequence, payload));
	}

	private final class SessionInputStream extends InputStream {

		private byte[] currentPayload = new byte[0];
		private int payloadOffset;
		private boolean endOfStream;
		private boolean closed;

		@Override
		public int read() throws IOException {
			byte[] one = new byte[1];
			int read = read(one, 0, 1);
			return read == -1 ? -1 : one[0] & 0xff;
		}

		@Override
		public int read(byte[] bytes, int offset, int length)
				throws IOException {
			if (bytes == null) throw new NullPointerException();
			if (offset < 0 || length < 0 || length > bytes.length - offset) {
				throw new IndexOutOfBoundsException();
			}
			if (length == 0) return 0;
			if (closed) throw new IOException("Stream is closed");
			while (payloadOffset == currentPayload.length && !endOfStream) {
				RelayFrameCodec.Frame frame =
						RelayFrameCodec.readFrame(framedInput);
				if (frame == null) {
					throw new IOException("Relay stream ended without CLOSE");
				}
				if (!Arrays.equals(sessionId, frame.getSessionId())) {
					throw new IOException("Unexpected relay session");
				}
				if (!incomingSequence.accept(frame.getSequence())) continue;
				switch (frame.getType()) {
					case DATA:
						currentPayload = frame.getPayload();
						payloadOffset = 0;
						break;
					case CLOSE:
						if (frame.getPayload().length != 0) {
							throw new IOException("Invalid relay CLOSE payload");
						}
					endOfStream = true;
					break;
					default:
						throw new IOException("Unexpected relay frame type");
				}
			}
			if (payloadOffset == currentPayload.length) return -1;
			int count = Math.min(length,
					currentPayload.length - payloadOffset);
			System.arraycopy(currentPayload, payloadOffset, bytes, offset,
					count);
			payloadOffset += count;
			return count;
		}

		@Override
		public void close() {
			closed = true;
			currentPayload = new byte[0];
			payloadOffset = 0;
		}
	}

	private final class SessionOutputStream extends OutputStream {

		private boolean closed;

		@Override
		public void write(int value) throws IOException {
			byte[] one = {(byte) value};
			write(one, 0, 1);
		}

		@Override
		public void write(byte[] bytes, int offset, int length)
				throws IOException {
			if (bytes == null) throw new NullPointerException();
			if (offset < 0 || length < 0 || length > bytes.length - offset) {
				throw new IndexOutOfBoundsException();
			}
			if (closed) throw new IOException("Stream is closed");
			int remaining = length;
			int position = offset;
			while (remaining > 0) {
				int count = Math.min(remaining,
						RelayFrameCodec.MAX_PAYLOAD_BYTES);
				byte[] payload = Arrays.copyOfRange(bytes, position,
						position + count);
				writeFrame(RelayFrameCodec.Type.DATA, payload);
				position += count;
				remaining -= count;
			}
		}

		@Override
		public void flush() throws IOException {
			if (closed) throw new IOException("Stream is closed");
			framedOutput.flush();
		}

		@Override
		public void close() throws IOException {
			if (closed) return;
			closed = true;
			writeFrame(RelayFrameCodec.Type.CLOSE, new byte[0]);
		}
	}
}
