package org.briarproject.bramble.plugin.relay;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class RelaySessionFlowControlTest {

	private static final long WINDOW = 1024;
	private static final long MAX_WINDOW = 4096;

	@Test
	public void senderCannotExceedPeerWindowAndAckOpensCredit()
			throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		flow.setInitialPeerLimit(WINDOW);
		assertTrue(flow.tryReserveOutgoing(700));
		assertFalse(flow.tryReserveOutgoing(400));
		flow.updatePeerLimit(1500);
		assertTrue(flow.tryReserveOutgoing(400));
		assertEquals(1100, flow.getSentBytes());
	}

	@Test
	public void receiverAdvancesWindowOnlyAfterApplicationConsumesBytes()
			throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		assertEquals(WINDOW, flow.getInitialReceiveLimit());
		flow.onIncomingData(700);
		assertEquals(WINDOW, flow.getReceiveLimit());
		assertEquals(1724, flow.onBytesConsumed(700));
		flow.onIncomingData(1024);
		assertEquals(1724, flow.getReceivedBytes());
	}

	@Test
	public void rejectsDataBeyondAdvertisedReceiveLimit() throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		flow.onIncomingData(1024);
		try {
			flow.onIncomingData(1);
			fail();
		} catch (IOException expected) {
			assertEquals("Relay receive window exceeded",
					expected.getMessage());
		}
	}

	@Test
	public void rejectsPeerWindowThatMovesBackwards() throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		flow.setInitialPeerLimit(WINDOW);
		try {
			flow.updatePeerLimit(WINDOW - 1);
			fail();
		} catch (IOException expected) {
			assertEquals("Relay peer window moved backwards",
					expected.getMessage());
		}
	}

	@Test
	public void rejectsUnreasonablyLargePeerWindow() throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		try {
			flow.setInitialPeerLimit(MAX_WINDOW + 1);
			fail();
		} catch (IOException expected) {
			assertEquals("Relay peer window exceeds maximum",
					expected.getMessage());
		}
	}

	@Test
	public void rejectsAckBeforeInitialPeerWindow() throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		try {
			flow.updatePeerLimit(WINDOW);
			fail();
		} catch (IOException expected) {
			assertEquals("Relay peer window is not initialized",
					expected.getMessage());
		}
	}

	@Test
	public void rejectsConsumptionOfBytesNotReceived() throws Exception {
		RelaySessionFlowControl flow =
				new RelaySessionFlowControl(WINDOW, MAX_WINDOW);
		try {
			flow.onBytesConsumed(1);
			fail();
		} catch (IOException expected) {
			assertEquals("Consumed more relay bytes than received",
					expected.getMessage());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsReceiveWindowLargerThanMaximum() {
		new RelaySessionFlowControl(MAX_WINDOW + 1, MAX_WINDOW);
	}
}
