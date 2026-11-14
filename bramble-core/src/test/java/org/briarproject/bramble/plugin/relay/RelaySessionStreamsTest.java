package org.briarproject.bramble.plugin.relay;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class RelaySessionStreamsTest {

	private static final byte[] SESSION_ID = new byte[16];

	@Test
	public void transfersDataAndClosesCleanly() throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelaySessionStreams sender = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(new byte[0]), wire);
		byte[] message = "Bramble relay stream".getBytes("UTF-8");
		sender.getOutputStream().write(message);
		sender.getOutputStream().close();

		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		byte[] received = new byte[message.length + 1];
		int count = receiver.getInputStream().read(received);
		assertEquals(message.length, count);
		assertArrayEquals(message, Arrays.copyOf(received, count));
		assertEquals(-1, receiver.getInputStream().read());
	}

	@Test
	public void splitsLargeWritesIntoBoundedFramesAndPreservesAllBytes()
			throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelaySessionStreams sender = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(new byte[0]), wire);
		byte[] message = new byte[RelayFrameCodec.MAX_PAYLOAD_BYTES + 17];
		for (int i = 0; i < message.length; i++) {
			message[i] = (byte) (i * 31);
		}
		sender.getOutputStream().write(message);
		sender.getOutputStream().close();

		ByteArrayInputStream encoded = new ByteArrayInputStream(
				wire.toByteArray());
		RelayFrameCodec.Frame first = RelayFrameCodec.readFrame(encoded);
		RelayFrameCodec.Frame second = RelayFrameCodec.readFrame(encoded);
		RelayFrameCodec.Frame close = RelayFrameCodec.readFrame(encoded);

		assertEquals(RelayFrameCodec.Type.DATA, first.getType());
		assertEquals(0, first.getSequence());
		assertEquals(RelayFrameCodec.MAX_PAYLOAD_BYTES,
				first.getPayload().length);
		assertEquals(RelayFrameCodec.Type.DATA, second.getType());
		assertEquals(1, second.getSequence());
		assertEquals(17, second.getPayload().length);
		assertEquals(RelayFrameCodec.Type.CLOSE, close.getType());
		assertEquals(2, close.getSequence());
		assertEquals(0, close.getPayload().length);
		assertNull(RelayFrameCodec.readFrame(encoded));

		byte[] reconstructed = new byte[message.length];
		System.arraycopy(first.getPayload(), 0, reconstructed, 0,
				first.getPayload().length);
		System.arraycopy(second.getPayload(), 0, reconstructed,
				first.getPayload().length, second.getPayload().length);
		assertArrayEquals(message, reconstructed);

		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		byte[] received = new byte[message.length];
		int offset = 0;
		while (offset < received.length) {
			int count = receiver.getInputStream().read(received, offset,
					received.length - offset);
			assertTrue(count > 0);
			offset += count;
		}
		assertArrayEquals(message, received);
		assertEquals(-1, receiver.getInputStream().read());
	}

	@Test
	public void discardsDuplicateFramesWithoutDuplicatingData()
			throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelayFrameCodec.Frame data = new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.DATA, SESSION_ID, 0, new byte[] {7, 8});
		RelayFrameCodec.writeFrame(wire, data);
		RelayFrameCodec.writeFrame(wire, data);
		RelayFrameCodec.writeFrame(wire, new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.CLOSE, SESSION_ID, 1, new byte[0]));

		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		byte[] received = new byte[4];
		assertEquals(2, receiver.getInputStream().read(received));
		assertArrayEquals(new byte[] {7, 8}, Arrays.copyOf(received, 2));
		assertEquals(-1, receiver.getInputStream().read());
	}

	@Test
	public void rejectsFramesForAnotherSession() throws Exception {
		byte[] otherSession = new byte[16];
		otherSession[0] = 1;
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelayFrameCodec.writeFrame(wire, new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.DATA, otherSession, 0, new byte[] {1}));
		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		try {
			receiver.getInputStream().read();
			fail();
		} catch (IOException expected) {
			assertEquals("Unexpected relay session", expected.getMessage());
		}
	}

	@Test
	public void rejectsSequenceGaps() throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelayFrameCodec.writeFrame(wire, new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.DATA, SESSION_ID, 1, new byte[] {1}));
		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		try {
			receiver.getInputStream().read();
			fail();
		} catch (IOException expected) {
			assertEquals("Unexpected relay sequence", expected.getMessage());
		}
	}

	@Test
	public void rejectsCloseFrameWithPayload() throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelayFrameCodec.writeFrame(wire, new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.CLOSE, SESSION_ID, 0, new byte[] {1}));
		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(wire.toByteArray()),
				new ByteArrayOutputStream());
		try {
			receiver.getInputStream().read();
			fail();
		} catch (IOException expected) {
			assertEquals("Invalid relay CLOSE payload", expected.getMessage());
		}
	}

	@Test
	public void rejectsUnexpectedEofWithoutCloseFrame() throws Exception {
		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream());
		try {
			receiver.getInputStream().read();
			fail();
		} catch (IOException expected) {
			assertEquals("Relay stream ended without CLOSE",
					expected.getMessage());
		}
	}

	@Test
	public void closeIsIdempotentAndFurtherWritesAreRejected()
			throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelaySessionStreams sender = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(new byte[0]), wire);
		sender.getOutputStream().close();
		sender.getOutputStream().close();
		try {
			sender.getOutputStream().write(1);
			fail();
		} catch (IOException expected) {
			assertEquals("Stream is closed", expected.getMessage());
		}
		ByteArrayInputStream encoded = new ByteArrayInputStream(
				wire.toByteArray());
		RelayFrameCodec.Frame close = RelayFrameCodec.readFrame(encoded);
		assertEquals(RelayFrameCodec.Type.CLOSE, close.getType());
		assertEquals(0, close.getSequence());
		assertNull(RelayFrameCodec.readFrame(encoded));
	}

	@Test
	public void rejectsReadsAfterInputIsClosed() throws Exception {
		RelaySessionStreams receiver = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(new byte[0]),
				new ByteArrayOutputStream());
		receiver.getInputStream().close();
		try {
			receiver.getInputStream().read();
			fail();
		} catch (IOException expected) {
			assertEquals("Stream is closed", expected.getMessage());
		}
	}
}
