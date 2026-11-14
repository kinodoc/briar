package org.briarproject.bramble.plugin.relay;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.Assert.*;

public class RelayFrameCodecTest {

	@Test
	public void roundTripPreservesFrame() throws Exception {
		byte[] session = new byte[16];
		byte[] payload = new byte[] {1, 2, 3, 4};
		RelayFrameCodec.Frame frame = new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.DATA, session, 42, payload);
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		RelayFrameCodec.writeFrame(out, frame);

		RelayFrameCodec.Frame decoded = RelayFrameCodec.readFrame(
				new ByteArrayInputStream(out.toByteArray()));
		assertNotNull(decoded);
		assertEquals(RelayFrameCodec.Type.DATA, decoded.getType());
		assertEquals(42, decoded.getSequence());
		assertArrayEquals(session, decoded.getSessionId());
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
		out.write(new byte[] {0x42, 0x52, 0x4c, 0x59, 1, 2});
		out.write(new byte[16]);
		out.write(new byte[8]);
		out.write(new byte[] {0, 1, 0, 1}); // 65,537 bytes
		try {
			RelayFrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray()));
			fail();
		} catch (IOException expected) {
			assertEquals("Invalid relay payload length", expected.getMessage());
		}
	}

	@Test
	public void constructorDefensivelyCopiesArrays() {
		byte[] session = new byte[16];
		byte[] payload = new byte[] {7};
		RelayFrameCodec.Frame frame = new RelayFrameCodec.Frame(
				RelayFrameCodec.Type.OPEN, session, 0, payload);
		session[0] = 1;
		payload[0] = 2;
		assertEquals(0, frame.getSessionId()[0]);
		assertEquals(7, frame.getPayload()[0]);
	}
}
