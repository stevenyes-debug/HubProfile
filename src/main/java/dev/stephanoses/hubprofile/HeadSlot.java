package dev.stephanoses.hubprofile;

/** Chooses a safe slot before any inventory mutation occurs. */
final class HeadSlot {
    private HeadSlot() {}
    static int choose(boolean[] empty, boolean[] ownedHead, int preferred) {
        if (empty.length != ownedHead.length || preferred < 0 || preferred >= empty.length) {
            throw new IllegalArgumentException("Invalid inventory layout");
        }
        if (empty[preferred] || ownedHead[preferred]) return preferred;
        for (int i = 0; i < empty.length; i++) if (ownedHead[i]) return i;
        for (int i = 0; i < empty.length; i++) if (empty[i]) return i;
        return -1;
    }
}
