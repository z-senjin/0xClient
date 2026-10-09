package oxclient;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import oxclient.api.Game;
import oxclient.api.Skills;
import oxclient.ui.SidePanel;

/**
 * The client.
 *
 * <p>C++ injects itself into the game, starts a JVM, and calls {@link #start()} once and {@link
 * #tick(int)} about thirty times a second. Everything from here down is ordinary Java.</p>
 *
 * <h2>Adding a plugin</h2>
 *
 * <p>Write a class extending {@link Plugin}, add it to {@link #PLUGINS}, rebuild. That is the whole
 * plugin system -- the list below IS the registry. No scanning, no annotations, no manifest, nothing
 * that can silently fail to find your class.</p>
 */
public final class OxClient {

    private OxClient() {}

    // Before anything else: under Wine the JDK's own SecureRandom dies in entropy collection
    // (see WineRandomProvider), and the first plugin's static initializer is where that death
    // would happen -- so the substitute provider must be in place before the list below is built.
    static {
        try {
            java.security.Security.insertProviderAt(new WineRandomProvider(), 1);
        } catch (Throwable t) {
            System.out.println("[oxclient] WineRandomProvider not installed: " + t);
        }
    }

    /**
     * Every plugin, in the order they appear in the control panel.
     *
     * <p><b>Add yours here.</b> One line.</p>
     */
    private static final List<Plugin> PLUGINS = new ArrayList<>(List.of(
            // The two 0xClient box drawers were the worked examples that proved the overlay; the RuneLite
            // ports below (NPC Indicators / Player Indicators) are the real visuals now, so these are
            // marked developer -- still here, still switchable, just under the panel's Developer
            // heading instead of at the top of the list. markDeveloper() rather than an override so
            // the plugin classes stay untouched (oxclient.Plugin.developer()).
            new oxclient.plugins.PlayerVisuals().markDeveloper(),
            new oxclient.plugins.NpcVisuals().markDeveloper(),
            new oxclient.plugins.Woodcutter(),
            new oxclient.rl.RlitePlugin("Shortest Path", "Pathfinder over the world map, with auto-walk",
                    shortestpath.ShortestPathPlugin::new),
            // RuneLite ports over the shim's actor surface (net.runelite.client.plugins.*). Opt-in
            // until seen live -- 0xClient's own NpcVisuals/PlayerVisuals stay the default-on box drawers.
            new oxclient.rl.RlitePlugin("NPC Indicators", "Highlight NPCs by name or id: hull box, tile, true tile, name",
                    net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin::new),
            new oxclient.rl.RlitePlugin("Player Indicators", "Names over players, coloured by own/others",
                    net.runelite.client.plugins.playerindicators.PlayerIndicatorsPlugin::new),
            // Kept until Shortest Path has been seen working in-game: if it misbehaves, this
            // minimal shim smoke test isolates whether the fault is the port or the shim.
            new oxclient.rl.RlitePlugin("Test Rlite", "Shim smoke test: config, events, overlay",
                    oxclient.rl.TestRlite::new).markDeveloper(),
            // Appended at the END: panel edit indices are positional (docs/plugin-system.md).
            new oxclient.rl.RlitePlugin("Test Actors",
                    "Shim smoke test: NPC/player actors, hull, name text, spawn events",
                    oxclient.rl.TestActors::new).markDeveloper(),
            // Autologin: types ~/.0xclient/autologin.properties into the title screen. Off by
            // default and NOT in defaultOn() -- a plugin that types a password is switched on by the
            // user once; the profile remembers the switch. Appended LAST: bridge indices are positional.
            new oxclient.plugins.AutoLogin(),
            // Anti-idle: a camera-key tap every few minutes so the server does not log the account
            // out (seen live 2026-09-06). Off by default; appended after AutoLogin (positional indices).
            new oxclient.plugins.AntiIdle(),
            // MntnChopper: nearest tree, chop, drop logs when full, repeat. Off by default; appended
            // last (positional indices).
            new oxclient.plugins.MntnChopper(),
            // Inspector: your location and the ids of NPCs, scenery and items. Off by default; appended
            // last (positional indices).
            new oxclient.plugins.Inspector()
    ));

    // The overlay image, reused between frames. Reallocating eight megabytes thirty times a second
    // would keep the garbage collector permanently busy for no reason.
    private static BufferedImage canvas;
    private static int[] pixels;
    private static int canvasWidth, canvasHeight;

    /**
     * Every plugin, in panel order, read-only. The {@code oxclient.panel.PanelBridge} JNI surface walks
     * this to build the model the launcher process renders; index stability here is load-bearing,
     * because that is how an edit record names its plugin.
     *
     * <p>Once a {@link oxclient.plugin.PluginManager} is installed this is its live list -- the built-ins
     * plus anything the hub registers during the session. Before that (the bare test suite, the
     * instant before start-up) it is the built-in list itself, because something has to answer before
     * start() runs.</p>
     */
    public static List<Plugin> plugins() {
        oxclient.plugin.PluginManager m = oxclient.plugin.PluginManager.instance();
        return m != null ? m.plugins() : Collections.unmodifiableList(PLUGINS);
    }

    /**
     * Where the client keeps its state: profiles, installed hub plugins, the hub's configuration.
     * {@code oxclient.data.dir} overrides it (the tests point it at a temp directory); otherwise it is
     * {@code .0xclient} under the user's home, which under Wine resolves to the Windows profile the
     * JVM starts with, and on Linux to the obvious place.
     */
    public static java.nio.file.Path dataDir() {
        String prop = System.getProperty("oxclient.data.dir");
        if (prop != null && !prop.isBlank()) return java.nio.file.Path.of(prop);
        return java.nio.file.Path.of(System.getProperty("user.home", "."), ".0xclient");
    }

    // Which process draws the control panel. Java (SidePanel) is the default, exactly as it has always
    // been; the launcher sets ImGui mode before the first tick, and from then on this process draws
    // only overlays -- the panel pixels belong to the launcher's software rasterizer. See PanelBridge
    // for how the data crosses the process boundary.
    private static volatile boolean imguiPanel;

    /** Hand the panel to the ImGui launcher (true) or draw it here in Java (false, the default). */
    public static void setPanelMode(boolean imgui) { imguiPanel = imgui; }

    /** Whether the ImGui launcher owns the panel right now. */
    public static boolean isImGuiPanel() { return imguiPanel; }

    /** Called once by the native side after the VM starts. */
    public static void start() {
        System.out.println("0xClient: " + PLUGINS.size() + " plugins");

        // The order here is the order the state flows: the profile store first (it wants to be the
        // Setting persistence sink before any plugin can set anything), then the manager over the
        // built-in registry -- registering each one applies whatever the active profile says about it
        // -- then the hub, which reloads installed external plugins in the background. Only after all
        // of that does the "what is on when I start" list run, and even then only where the profile
        // has no opinion: a stored "off" must beat a code default, or profiles would quietly lose
        // every plugin the user turned off.
        oxclient.profile.ProfileManager.install(dataDir());
        oxclient.profile.ProfileManager profiles = oxclient.profile.ProfileManager.instance();
        oxclient.plugin.PluginManager.install(PLUGINS, profiles);
        oxclient.plugin.hub.Hub.install(oxclient.plugin.PluginManager.instance(), dataDir());

        for (Plugin p : PLUGINS) {
            if (defaultOn(p) && !profiles.hasStoredEnabled(p)) p.setEnabled(true);
        }

        SidePanel.setPlugins(plugins());

        // The process dies when the game does, with no warning and no native shutdown call -- so a
        // JVM hook is the last chance the profile store gets. Daemon threads would not run it; this
        // one is not a daemon, and its whole job is one small file write.
        Runtime.getRuntime().addShutdownHook(new Thread(OxClient::shutdown, "oxc-shutdown"));
    }

    /**
     * Anything a plugin wants on by default, it says so here rather than in its constructor, so "what
     * is on when I start" is one list rather than a hunt through every plugin.
     */
    private static boolean defaultOn(Plugin p) {
        // The RuneLite-style indicators are the default visuals since 2026-09-06 (hull, name and
        // tile at each entity's real height); 0xClient's own NpcVisuals/PlayerVisuals stay in the list
        // as the README's worked examples, off unless switched on.
        return p.name().equals("NPC Indicators") || p.name().equals("Player Indicators");
    }

    /**
     * Last chance for state to reach disk. The plugin manager's shutdown disables every live plugin
     * (their {@code onDisable} may be the thing that stops a timer or closes a file), and the profile
     * store flushes whatever the debounce had not got to yet.
     */
    public static void shutdown() {
        try {
            oxclient.plugin.PluginManager m = oxclient.plugin.PluginManager.instance();
            if (m != null) m.shutdown();
        } catch (Throwable t) {
            System.out.println("[oxclient] shutdown: plugin manager threw: " + t);
        }
        try {
            oxclient.profile.ProfileManager p = oxclient.profile.ProfileManager.instance();
            if (p != null) p.flush();
        } catch (Throwable t) {
            System.out.println("[oxclient] shutdown: profile flush threw: " + t);
        }
    }

    /**
     * One mouse event on the panel's own window, forwarded from the native side. Coordinates are the
     * panel's local space -- the native side positions that window exactly panel-sized, so no
     * conversion happens anywhere. Runs on the overlay thread (the same thread that owns the window),
     * before the next frame's render picks the state up.
     */
    public static void panelMouse(int x, int y, int button, boolean down) {
        try {
            SidePanel.mouse(x, y, button, down);
        } catch (Throwable t) {
            System.out.println("[panel] mouse threw: " + t);
        }
    }

    private static int frames;

    /**
     * How many frames have run: a frame-identity token that cannot repeat, unlike anything read from
     * game memory. Used to tell "this frame's snapshot" from a stale one.
     */
    public static int frame() { return frames; }

    /**
     * One frame: read the world, run the plugins, draw, and hand the result back to be shown.
     *
     * @param keys bitmask of function keys pressed since the last frame; bit 0 is F1, bit 7 is F8
     */
    public static void tick(int keys) {
        Plugin.drainLater();
        frames++;
        Skills.newFrame();
        Game.refresh();

        if (keys != 0) {
            for (Plugin p : plugins()) {
                int k = p.hotkey();
                if (k >= 0 && k < 8 && (keys & (1 << k)) != 0) p.toggle();
            }
        }

        for (Plugin p : plugins()) {
            if (!p.isEnabled()) continue;
            try {
                p.tick();
            } catch (Throwable t) {
                // One broken plugin must not stop the other two, and must never reach the game.
                System.out.println("[" + p.name() + "] tick threw: " + t);
            }
        }

        render();
    }

    /** Draw every enabled plugin's overlay and present it. */
    private static void render() {
        int[] view = Natives.viewport();
        if (view.length != 4) return;
        int w = view[2], h = view[3];
        if (w <= 0 || h <= 0) return;

        if (canvas == null || w != canvasWidth || h != canvasHeight) {
            // TYPE_INT_ARGB_PRE, not TYPE_INT_ARGB: the layered window wants premultiplied alpha, and
            // drawing straight into the right format means nothing has to convert it later.
            canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB_PRE);
            pixels = ((DataBufferInt) canvas.getRaster().getDataBuffer()).getData();
            canvasWidth = w;
            canvasHeight = h;
        }

        Arrays.fill(pixels, 0);                 // fully transparent -- the game shows through

        Graphics2D g = canvas.createGraphics();
        try {
            // Antialiased text everywhere, which is the difference between "styled" and "1998".
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

            for (Plugin p : plugins()) {
                if (!p.isEnabled()) continue;
                try {
                    p.render(g);
                } catch (Throwable t) {
                    System.out.println("[" + p.name() + "] render threw: " + t);
                }
            }
        } finally {
            g.dispose();
        }

        Natives.present(pixels, w, h);

        // The panel draws itself into its own small image and presents it through presentPanel; it is
        // a second layered window, so plugins' pixels and panel pixels never fight over a frame --
        // unless the launcher owns the panel, in which case drawing it here would put a Java panel and
        // an ImGui one on the same pixels. In that mode the SidePanel window does not exist at all and
        // the DLL never calls panelMouse; the data crosses to the launcher through PanelBridge.
        if (imguiPanel) return;
        try {
            SidePanel.frame(h);
        } catch (Throwable t) {
            System.out.println("[panel] render threw: " + t);
        }
    }

    /** One line per enabled plugin. The native side shows this if Java is up but nothing has drawn. */
    public static String status() {
        StringBuilder sb = new StringBuilder();
        for (Plugin p : plugins()) {
            if (!p.isEnabled()) continue;
            sb.append(p.name());
            String s = p.status();
            if (s != null && !s.isEmpty()) sb.append(": ").append(s);
            sb.append('\n');
        }
        return sb.toString();
    }
}
