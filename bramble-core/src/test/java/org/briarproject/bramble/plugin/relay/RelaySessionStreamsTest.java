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
	public void splitsLargeWritesIntoBoundedFrames() throws Exception {
		ByteArrayOutputStream wire = new ByteArrayOutputStream();
		RelaySessionStreams sender = new RelaySessionStreams(SESSION_ID,
				new ByteArrayInputStream(new byte[0]), wire);
		byte[] message = new byte[RelayFrameCodec.MAX_PAYLOAD_BYTES + 17];
		sender.getOutputStream().write(message);
		RelayFrameCodec.Frame first =
				RelayFrameCodec.readFrame(new ByteArrayInputStream(
						wire.toByteArray()));
		assertEquals(RelayFrameCodec.MAX_PAYLOAD_BYTES,
				first.getPayload().length);
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
}
