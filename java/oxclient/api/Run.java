package oxclient.api;

import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Running: turned on while walking once run energy is over {@link #ON_ABOVE}% (the user's choice, 2026-10-10).
 * {@link Walker.Route} calls {@link #keepOn} every step, so every walk in the builder runs.
 *
 * <p>Ids from the bundled RuneLite game values (build/dist/0xclient.jar, net.runelite.api.gameval): the run mode
 * is player variable {@code OPTION_RUN} (173, 1 = running) and the minimap's run orb is {@code Orbs.RUNBUTTON}
 * (160:28), option 1 = "Toggle Run". Both NOT SEEN WORKING YET. A click that does not flip the variable within
 * {@link #CHECK_MS} counts as failed; after {@link #MAX_FAILS} it stops trying for the session and says so, so a
 * wrong id cannot click the orb forever.</p>
 *
 * <p><b>Energy is read off the run orb's own number</b> ({@code Orbs.RUNENERGY_TEXT}, 160:29), not the
 * RUN_ENERGY field: the self-check calls that offset "suspect" on 241-3 (it read 1), so the first version saw
 * 0% and never clicked -- live 2026-10-11, no "turning run on" line in a whole run. The user's own orb clicks in
 * that log were 160:28 child -1 option 1, matching RUNBUTTON. The field is only the fallback.</p>
 */
public final class Run {

    private Run() {}

    /** Turn run on when energy is above this (percent). */
    public static final int ON_ABOVE = 60;
    private static final long CHECK_MS = 2500;
    private static final int MAX_FAILS = 3;

    private static long clickedAt;
    private static int fails;
    private static boolean reported;

    /** Run energy in percent, from the run orb's number (the field as a fallback, see the class comment). */
    public static int energy() {
        for (Interfaces.Line l : Interfaces.text(InterfaceID.Orbs.RUNENERGY_TEXT >>> 16))
            if (l.widgetId() == InterfaceID.Orbs.RUNENERGY_TEXT) {
                String t = l.plain().replaceAll("[^0-9]", "");
                if (!t.isEmpty()) return Integer.parseInt(t);
            }
        return Game.me().runEnergy();
    }

    /** True when the run mode is on. */
    public static boolean isOn() { return Varbits.varp(VarPlayerID.OPTION_RUN) == 1; }

    /** Call while walking: clicks the run orb when run is off and energy is over {@link #ON_ABOVE}%. */
    public static void keepOn() {
        if (!reported) {                                               // once per session: what each source says
            reported = true;
            System.out.println("[walker] run: varp 173 = " + Varbits.varp(VarPlayerID.OPTION_RUN) + ", orb energy " + energy()
                    + "%, RUN_ENERGY field " + Game.me().runEnergy() + "%");
        }
        if (fails >= MAX_FAILS) return;
        long now = System.currentTimeMillis();
        if (isOn()) { if (clickedAt != 0) { fails = 0; clickedAt = 0; } return; }
        if (now - clickedAt < CHECK_MS) return;                        // give the last click time to show
        if (clickedAt != 0 && ++fails >= MAX_FAILS) {
            System.out.println("[walker] run did not turn on after " + MAX_FAILS + " clicks on the run orb (160:28 op 1,"
                    + " varp 173 = " + Varbits.varp(VarPlayerID.OPTION_RUN) + "): not trying again this session");
            return;
        }
        int energy = energy();
        if (energy <= ON_ABOVE) { clickedAt = 0; return; }
        System.out.println("[walker] run energy " + energy + "%: turning run on");
        Interfaces.click(InterfaceID.Orbs.RUNBUTTON, -1, 1);
        clickedAt = now;
    }
}
