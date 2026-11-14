package org.briarproject.bramble.plugin.relay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Version 2 wire framing for the Relay transport.
 *
 * Frames are carried inside an authenticated TLS connection. This codec does
 * not provide encryption, peer authentication, retransmission or flow control.
 * Sequence numbers are independent in each direction of each session.
 */
public final class RelayFrameCodec {

	private static final int MAGIC = 0x42524c59; // "BRLY"
	public static final int VERSION = 2;
	private static final int HEADER_BYTES = 4 + 1 + 1 + 16 + 8 + 4;
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024;
	public static final int WINDOW_BYTES = 8;
	public static final int RESET_CODE_BYTES = 2;

	private RelayFrameCodec() {
	}

	public enum Type {
		OPEN(1), DATA(2), CLOSE(3), ACK(4), RESET(5);

		private final int wireValue;

		Type(int wireValue) {
			this.wireValue = wireValue;
		}

		private static Type fromWireValue(int value) throws IOException {
			for (Type type : values()) {
				if (type.wireValue == value) return type;
			}
			throw new IOException("Unknown relay frame type");
		}
	}

	public enum ResetCode {
		PROTOCOL_ERROR(1),
		UNKNOWN_SESSION(2),
		RESOURCE_LIMIT(3),
		CANCELLED(4);

		private final int wireValue;

		ResetCode(int wireValue) {
			this.wireValue = wireValue;
		}

		public int getWireValue() {
			return wireValue;
		}

		static ResetCode fromWireValue(int value) throws IOException {
			for (ResetCode code : values()) {
				if (code.wireValue == value) return code;
			}
			throw new IOException("Unknown relay reset code");
		}
	}

	public static final class Frame {
		private final Type type;
		private final byte[] sessionId;
		private final long sequence;
		private final byte[] payload;

		public Frame(Type type, byte[] sessionId, long sequence,
				byte[] payload) {
			if (type == null || sessionId == null || payload == null) {
				throw new NullPointerException();
			}
			if (sessionId.length != 16) {
				throw new IllegalArgumentException("Session ID must be 16 bytes");
			}
			if (sequence < 0) {
				throw new IllegalArgumentException("Sequence must be non-negative");
			}
			if (payload.length > MAX_PAYLOAD_BYTES) {
				throw new IllegalArgumentException("Relay payload is too large");
			}
			validatePayload(type, payload.length);
			this.type = type;
			this.sessionId = sessionId.clone();
			this.sequence = sequence;
			this.payload = payload.clone();
		}

		public Type getType() {
			return type;
		}

		public byte[] getSessionId() {
			return sessionId.clone();
		}

		public long getSequence() {
			return sequence;
		}

		public byte[] getPayload() {
			return payload.clone();
		}
	}

	private static void validatePayload(Type type, int length) {
		int expected;
		switch (type) {
			case OPEN:
			case ACK:
				expected = WINDOW_BYTES;
				break;
			case CLOSE:
				expected = 0;
				break;
			case RESET:
				expected = RESET_CODE_BYTES;
				break;
			case DATA:
				if (length == 0) {
					throw new IllegalArgumentException(
							"Relay DATA payload must not be empty");
				}
				return;
			default:
				throw new IllegalArgumentException("Unsupported relay frame type");
		}
		if (length != expected) {
			throw new IllegalArgumentException("Invalid relay frame payload");
		}
	}

	/** Encodes an absolute byte offset/window limit in network byte order. */
	public static byte[] encodeWindow(long limit) {
		if (limit <= 0) {
			throw new IllegalArgumentException("Relay window must be positive");
		}
		byte[] payload = new byte[WINDOW_BYTES];
		for (int i = WINDOW_BYTES - 1; i >= 0; i--) {
			payload[i] = (byte) limit;
			limit >>>= 8;
		}
		return payload;
	}

	/** Decodes an absolute byte offset/window limit in network byte order. */
	public static long decodeWindow(byte[] payload) throws IOException {
		if (payload == null || payload.length != WINDOW_BYTES) {
			throw new IOException("Invalid relay window payload");
		}
		long value = 0;
		for (byte b : payload) value = (value << 8) | (b & 0xffL);
		if (value <= 0) throw new IOException("Invalid relay window limit");
		return value;
	}

	public static byte[] encodeResetCode(ResetCode code) {
		if (code == null) throw new NullPointerException();
		int value = code.wireValue;
		return new byte[] {(byte) (value >>> 8), (byte) value};
	}

	public static ResetCode decodeResetCode(byte[] payload)
			throws IOException {
		if (payload == null || payload.length != RESET_CODE_BYTES) {
			throw new IOException("Invalid relay reset payload");
		}
		int value = ((payload[0] & 0xff) << 8) | (payload[1] & 0xff);
		return ResetCode.fromWireValue(value);
	}

	public static void writeFrame(OutputStream output, Frame frame)
			throws IOException {
		writeFrame(output, frame, true);
	}

	/**
	 * Writes one frame. A shared connection writer can pass false and flush
	 * once after batching frames; only that writer may access the output stream.
	 */
	public static void writeFrame(OutputStream output, Frame frame,
			boolean flush) throws IOException {
		DataOutputStream out = new DataOutputStream(output);
		out.writeInt(MAGIC);
		out.writeByte(VERSION);
		out.writeByte(frame.type.wireValue);
		out.write(frame.sessionId);
		out.writeLong(frame.sequence);
		out.writeInt(frame.payload.length);
		out.write(frame.payload);
		if (flush) out.flush();
	}

	/**
	 * Reads one complete frame. Returns null only when EOF occurs before any
	 * byte of a new frame; truncated frames are rejected.
	 */
	public static Frame readFrame(InputStream input) throws IOException {
		int first = input.read();
		if (first == -1) return null;
		DataInputStream in = new DataInputStream(input);
		int magic;
		try {
			magic = (first << 24) | (in.readUnsignedByte() << 16)
					| (in.readUnsignedByte() << 8) | in.readUnsignedByte();
		} catch (EOFException e) {
			throw new IOException("Truncated relay frame header", e);
		}
		if (magic != MAGIC) throw new IOException("Invalid relay frame magic");
		int version = in.readUnsignedByte();
		if (version != VERSION) {
			throw new IOException("Unsupported relay version");
		}
		Type type = Type.fromWireValue(in.readUnsignedByte());
		byte[] sessionId = new byte[16];
		in.readFully(sessionId);
		long sequence = in.readLong();
		if (sequence < 0) throw new IOException("Invalid relay sequence");
		int length = in.readInt();
		if (length < 0 || length > MAX_PAYLOAD_BYTES) {
			throw new IOException("Invalid relay payload length");
		}
		byte[] payload = new byte[length];
		in.readFully(payload);
		try {
			return new Frame(type, sessionId, sequence, payload);
		} catch (IllegalArgumentException e) {
			throw new IOException("Invalid relay frame payload", e);
		}
	}

	static int getHeaderBytes() {
		return HEADER_BYTES;
	}
}
