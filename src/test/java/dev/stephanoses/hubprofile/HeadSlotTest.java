package dev.stephanoses.hubprofile;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class HeadSlotTest {
    @Test void occupiedPreferredSlotIsNeverOverwritten() {
        boolean[] empty = new boolean[36];
        empty[8] = true;
        assertEquals(8, HeadSlot.choose(empty, new boolean[36], 4));
    }
    @Test void aFullInventoryWithoutOurHeadIsLeftAlone() {
        assertEquals(-1, HeadSlot.choose(new boolean[36], new boolean[36], 4));
    }
    @Test void existingHeadCanBeUpdatedEvenWhenInventoryIsFull() {
        boolean[] head = new boolean[36];
        head[6] = true;
        assertEquals(6, HeadSlot.choose(new boolean[36], head, 4));
    }
    @Test void prefersConfiguredSlotWhenAvailable() {
        boolean[] empty = new boolean[36];
        empty[0] = empty[4] = true;
        assertEquals(4, HeadSlot.choose(empty, new boolean[36], 4));
    }
}
