package org.briarproject.bramble.plugin.relay;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Arrays;

/**
 * Wire framing for the Relay transport.
 *
 * Frames are carried inside an authenticated TLS connection. This codec does
 * not provide encryption, peer authentication, ordering, or retransmission.
 */
public final class RelayFrameCodec {

	private static final int MAGIC = 0x42524c59; // "BRLY"
	private static final int VERSION = 1;
	private static final int HEADER_BYTES = 4 + 1 + 1 + 16 + 8 + 4;
	public static final int MAX_PAYLOAD_BYTES = 64 * 1024;

	private RelayFrameCodec() {
	}

	public enum Type {
		OPEN(1), DATA(2), CLOSE(3), ACK(4);

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

	public static void writeFrame(OutputStream output, Frame frame)
			throws IOException {
		DataOutputStream out = new DataOutputStream(output);
		out.writeInt(MAGIC);
		out.writeByte(VERSION);
		out.writeByte(frame.type.wireValue);
		out.write(frame.sessionId);
		out.writeLong(frame.sequence);
		out.writeInt(frame.payload.length);
		out.write(frame.payload);
		out.flush();
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
		if (version != VERSION) throw new IOException("Unsupported relay version");
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
		return new Frame(type, sessionId, sequence, payload);
	}

	static int getHeaderBytes() {
		return HEADER_BYTES;
	}
}
