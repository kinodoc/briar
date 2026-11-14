package org.briarproject.bramble.plugin.relay;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

public class RelayFrameCodecTest {

	private static final byte[] SESSION = new byte[16];

	@Test
	public void roundTripPreservesFrame() throws Exception {
		byte[] payload = new byte[] {1, 2, 3, 4};
		RelayFrameCodec.Frame frame = new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.DATA, SESSION, 42, payload);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RelayFrameCodec.writeFrame(out, frame);

		RelayFrameCodec.Frame decoded = RelayFrameCodec.readFrame(
				new ByteArrayInputStream(out.toByteArray()));
		assertNotNull(decoded);
		assertEquals(2, RelayFrameCodec.VERSION);
		assertEquals(RelayFrameCodec.Type.DATA, decoded.getType());
		assertEquals(42, decoded.getSequence());
		assertArrayEquals(SESSION, decoded.getSessionId());
		assertArrayEquals(payload, decoded.getPayload());
	}

	@Test
	public void cleanEofReturnsNull() throws Exception {
		assertNull(RelayFrameCodec.readFrame(new ByteArrayInputStream(
				new byte[0])));
	}

	@Test(expected = IOException.class)
	public void truncatedHeaderIsRejected() throws Exception {
		RelayFrameCodec.readFrame(new ByteArrayInputStream(
				new byte[] {0x42, 0x52}));
	}

	@Test
	public void oversizedPayloadIsRejectedBeforeAllocation() throws Exception {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.write(new byte[] {0x42, 0x52, 0x4c, 0x59, 2, 2});
		out.write(new byte[16]);
		out.write(new byte[8]);
		out.write(new byte[] {0, 1, 0, 1});
		try {
			RelayFrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
			fail();
		} catch (IOException expected) {
			assertEquals("Invalid relay payload length", expected.getMessage());
		}
	}

	@Test
	public void constructorDefensivelyCopiesArrays() throws Exception {
		byte[] session = new byte[16];
		byte[] payload = RelayFrameCodec.encodeWindow(7);
		RelayFrameCodec.Frame frame = new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.OPEN, session, 0, payload);
		session[0] = 1;
		payload[7] = 2;
		assertEquals(0, frame.getSessionId()[0]);
		assertEquals(7, RelayFrameCodec.decodeWindow(frame.getPayload()));
	}

	@Test
	public void encodesAndDecodesAbsoluteWindow() throws Exception {
		long limit = 0x0102030405060708L;
		assertEquals(limit, RelayFrameCodec.decodeWindow(
				RelayFrameCodec.encodeWindow(limit)));
	}

	@Test
	public void roundTripsResetCode() throws Exception {
		assertEquals(RelayFrameCodec.ResetCode.RESOURCE_LIMIT,
				RelayFrameCodec.decodeResetCode(
						RelayFrameCodec.encodeResetCode(
								RelayFrameCodec.ResetCode.RESOURCE_LIMIT)));
	}

	@Test
	public void writerCanDeferFlushForSharedWriter() throws Exception {
		final boolean[] flushed = {false};
		ByteArrayOutputStream out = new ByteArrayOutputStream() {
			@Override
			public void flush() {
				flushed[0] = true;
			}
		};
		RelayFrameCodec.writeFrame(out, new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.CLOSE, SESSION, 0, new byte[0]), false);
		assertFalse(flushed[0]);
		out.flush();
		assertTrue(flushed[0]);
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsInvalidOpenPayload() {
		new RelayFrameCodec.Frame(RelayFrameCodec.Type.OPEN, SESSION, 0,
				new byte[] {1});
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsEmptyDataPayload() {
		new RelayFrameCodec.Frame(RelayFrameCodec.Type.DATA, SESSION, 0,
				new byte[0]);
	}

	@Test(expected = IOException.class)
	public void rejectsUnknownResetCode() throws Exception {
		RelayFrameCodec.decodeResetCode(new byte[] {0, 99});
	}

	@Test(expected = IOException.class)
	public void rejectsVersionOneFrames() throws Exception {
		byte[] v1 = new byte[] {0x42, 0x52, 0x4c, 0x59, 1};
		RelayFrameCodec.readFrame(new ByteArrayInputStream(v1));
	}
}
