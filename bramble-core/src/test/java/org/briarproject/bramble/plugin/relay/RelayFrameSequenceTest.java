package org.briarproject.bramble.plugin.relay;

import org.junit.Test;

import java.io.IOException;

import static org.junit.Assert.*;

public class RelayFrameSequenceTest {

	@Test
	public void acceptsContiguousSequenceStartingAtZero() throws Exception {
		RelayFrameSequence sequence = new RelayFrameSequence();
		assertTrue(sequence.accept(0));
		assertTrue(sequence.accept(1));
		assertTrue(sequence.accept(2));
	}

	@Test
	public void discardsPreviouslyAcceptedFramesAsDuplicates() throws Exception {
		RelayFrameSequence sequence = new RelayFrameSequence();
		assertTrue(sequence.accept(0));
		assertTrue(sequence.accept(1));
		assertFalse(sequence.accept(0));
		assertFalse(sequence.accept(1));
		assertTrue(sequence.accept(2));
	}

	@Test
	public void rejectsSequenceGaps() throws Exception {
		RelayFrameSequence sequence = new RelayFrameSequence();
		assertTrue(sequence.accept(0));
		try {
			sequence.accept(2);
			fail();
		} catch (IOException expected) {
			assertEquals("Unexpected relay sequence", expected.getMessage());
		}
	}

	@Test
	public void rejectsNegativeSequence() throws Exception {
		try {
			new RelayFrameSequence().accept(-1);
			fail();
		} catch (IOException expected) {
			assertEquals("Invalid relay sequence", expected.getMessage());
		}
	}

	@Test
	public void rejectsSequenceSpaceExhaustion() throws Exception {
		RelayFrameSequence sequence =
				new RelayFrameSequence(Long.MAX_VALUE);
		assertTrue(sequence.accept(Long.MAX_VALUE));
		try {
			sequence.accept(Long.MAX_VALUE);
			fail();
		} catch (IOException expected) {
			assertEquals("Relay sequence exhausted", expected.getMessage());
		}
	}

	@Test(expected = IllegalArgumentException.class)
	public void rejectsNegativeInitialSequence() {
		new RelayFrameSequence(-1);
	}
}
