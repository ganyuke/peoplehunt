package io.github.ganyuke.peoplehunt.game;

/**
 * Controls how a hunter's inventory is handled on death and respawn.
 *
 * <ul>
 *   <li>{@link #NONE}    – full vanilla death; everything is dropped.</li>
 *   <li>{@link #KIT}     – everything is dropped, but the active kit is restored on respawn.</li>
 *   <li>{@link #KEEP}    – the hunter keeps their full inventory across death.</li>
 *   <li>{@link #INHERIT} – internal sentinel used only inside deathstreak tier config to
 *                          fall back to the session-level mode; never valid as a
 *                          user-facing setting.</li>
 * </ul>
 */
public enum KeepInventoryMode {
    NONE,
    KIT,
    KEEP,
    INHERIT
}
