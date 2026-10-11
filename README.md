<p align="center">
  <img src="resources/0xclient-logo.png" width="200" alt="0xClient">
</p>

<h1 align="center">0xClient</h1>

<p align="center">A small, readable OSRS client with a plugin panel, entity visuals, and a worked example bot.</p>

> **Renamed.** This project used to be called *KewlKlient*. Everything moved with the name: the Java
> package is `oxclient` (a Java name cannot start with a digit), the binaries are `0xClient.exe`,
> `0xclient.dll` and `0xclient.jar`, the ini is `0xclient.ini` with a `[0xclient]` section, the
> environment variables are `OXC_*`, and your settings live under `~/.0xclient`. An old
> `~/.kewlklient` directory is not migrated -- copy it across if you want your profiles back.

It exists to be **learned from and hacked on**. The core — the API, the plugin model, the config
system, the drawing — is still the few thousand lines of `java/oxclient/` you can hold in your head; the
bulk of the repo is the vendored Shortest Path plugin and the RuneLite API shim it runs on, which you
never have to read. There is no auto-updater and no account manager. The plugin hub is a manifest
endpoint you point it at, not a store of ours. What there is:

- **Everything is Java.** Plugins, settings, profiles, and all the drawing. No C++ toolchain needed
  to write a bot or an overlay.
- **Overlays are plain Java2D.** Your plugin gets a `Graphics2D` over the game window and draws whatever
  it likes — shapes, alpha, antialiased text, images. Nothing had to expose a "draw box" primitive.
- **Settings build their own UI.** Declare a setting; the control panel grows the right widget for it.
  No plugin writes a line of UI code.
- **Your state survives a restart.** Enabled states, settings, pins and profiles are persisted under
  `~/.0xclient` — plugins declare nothing, do nothing, and get it for free.
- **No network code at all.** We never build a packet. We call the game's own "do this menu action"
  function and let it build and send the packet. That is the single biggest reason this codebase is
  small, and the reason it survives most game updates. (The one exception is the optional plugin
  hub, which fetches a manifest you point it at - see
  [docs/plugin-system.md](docs/plugin-system.md).)
- **Twenty-five native methods.** That is the entire unsafe surface, all in one file
  (`java/oxclient/Natives.java` — the four input-injection ones, `postChar`/`postKey`/`postMouse`/
  `inputTarget`, are part of that count: they are how autologin types your password).

```
       launcher (ImGui)               injected into the game
    ┌──────────────┐                ┌────────────────────────────────┐
    │  "+ client"  │ ── spawn ───>  │  osclient.exe                  │
    │              │ ── inject ──>  │  └─ 0xclient.dll             │
    │              │                │     reads memory               │
    │  286px ImGui │                │     starts a JVM ──────────────┼──> 0xclient.jar
    │  panel strip │  <== shared == │     25 natives ────────────────┼──>   api + your plugins
    │  (CPU-raster)│     memory     │  <── one finished image/frame ─┼──    overlays (Java2D)
    └──────────────┘                └────────────────────────────────┘      plugin state (Java)
```

**Java draws, C++ shows.** Java renders the whole overlay into an image and hands it back once a frame;
C++ puts it on screen and otherwise stays out of the way. That inversion is why a plugin can draw
anything Java2D can draw, and it costs one memcpy a frame.

**Java owns the state, C++ shows the panel.** The ImGui strip is a view: it renders a snapshot of the
plugin model that Java publishes into shared memory, and every click comes back as an edit record that
Java applies through the same paths its own panel uses. There is no second copy of plugin state in
C++.

---

## Quick start

**Windows only.** The whole thing is Win32 — there is no Linux or Mac build and there is not going to be
one. (On a Linux desktop you can still run the game itself under Wine and test the whole stack there;
[`tools/wine.md`](tools/wine.md) is the recipe.)

You need three things installed. The first two you probably have; the third is the one people miss:

| | why | get it |
|---|---|---|
| **JDK 17+** | the plugins are Java | IntelliJ ships one, or [adoptium.net](https://adoptium.net) |
| **CMake** | the injected part is C++ | [cmake.org/download](https://cmake.org/download/) — tick *Add CMake to the system PATH* |
| **A C++ compiler** | same | [Build Tools for Visual Studio](https://visualstudio.microsoft.com/downloads/) → *Desktop development with C++*. The full IDE is not needed. |

You do **not** need Gradle — the wrapper in this repo fetches it.

### From IntelliJ

1. **File → Open** and pick the folder you cloned. It imports as a Gradle project.
2. Pick the **0xClient** run configuration (it is checked into the repo) and press **Run**.
3. In the ImGui launcher that appeared, press **+ client**. It starts `osclient.exe`, injects the DLL
   and puts the game inside the launcher's own window.
4. **Log in.**

That is it — no config file to edit. The build points `0xclient.ini` at whichever JDK IntelliJ is
using, and puts everything in `build\dist\`.

### From a terminal

```bat
gradlew run
```

Same thing. `gradlew dist` builds without launching, and `build.bat` is a wrapper around it for people
who prefer a double-click.

The launcher needs to know where the game is: set `game=` in `build\dist\0xclient.ini` (relative
paths resolve against the exe). `build\dist\0xClient.exe --launch` presses **+ client** for you, and
`OXC_LOG=<file>` in the environment collects the launcher's, the DLL's and Java's diagnostics in one
file -- the DLL is built for one exact game build (`client/offsets.hpp`, `BUILD_VERSION`) and refuses
any other, saying so on screen and in that log.

A **panel strip** opens down the right-hand side of the game with a switch, a pin and settings for
every plugin — plus tabs for profiles, the plugin hub and bridge debug info. The same plugins
have hotkeys:

| key | does |
|-----|------|
| F1 | player visuals on/off |
| F2 | NPC visuals on/off |
| F5 | the woodcutter on/off |
| F7 | autologin: on at the title screen = login now; on while running = abort |
| F3, F4, F6, F8 | free, for your plugins |

Default-on plugins are **NPC Indicators** (every NPC in range: hull and name; narrow it with the list)
and **Player Indicators** (names over you and other players); the 0xClient `NpcVisuals`/`PlayerVisuals`
examples stay in the list, off. **Anti-idle** taps a camera key every few minutes so the server does
not log the account out; pair it with Autologin's "Log in again after a disconnect" for a session that
also survives the disconnects it cannot prevent.

That table covers the built-ins only. The five `RlitePlugin` entries in the registry below declare no
hotkey; a ported RuneLite plugin's keys come from its own keybind settings, not this table.

If nothing appears, see [Troubleshooting](#troubleshooting).

---

## Writing a plugin

Extend `Plugin`, override what you need, add one line to the list. This one draws a marker on the
nearest cow and counts them:

```java
package oxclient.plugins;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Point;

import oxclient.Plugin;
import oxclient.api.Entity;
import oxclient.api.Npcs;
import oxclient.ui.Hud;

public final class CowSpotter extends Plugin {

    public CowSpotter() {
        config.number("range", "Range", "How far to look", 10, 1, 30);
        config.colour("colour", "Colour", "", new Color(255, 120, 200));
    }

    @Override public String name()        { return "Cow spotter"; }
    @Override public String description() { return "Marks the nearest cow."; }
    @Override public int    hotkey()      { return 5; }          // F6

    @Override
    public void render(Graphics2D g) {
        Entity cow = Npcs.nearestWithin(config.number("range"), 2805);
        if (cow == null) return;
        Point at = cow.screen();
        if (at != null) Hud.entityBox(g, at, 16, 30, config.colour("colour"));
    }
}
```

Add it in `java/oxclient/OxClient.java`:

```java
private static final List<Plugin> PLUGINS = new ArrayList<>(List.of(
        new oxclient.plugins.PlayerVisuals(),
        new oxclient.plugins.NpcVisuals(),
        new oxclient.plugins.Woodcutter(),
        new oxclient.plugins.CowSpotter(),         // <- yours
        new oxclient.rl.RlitePlugin("Shortest Path", "Pathfinder over the world map, with auto-walk",
                shortestpath.ShortestPathPlugin::new),
        new oxclient.rl.RlitePlugin("NPC Indicators", "Highlight NPCs by name or id: hull box, tile, true tile, name",
                net.runelite.client.plugins.npchighlight.NpcIndicatorsPlugin::new),
        new oxclient.rl.RlitePlugin("Player Indicators", "Names over players, coloured by own/others",
                net.runelite.client.plugins.playerindicators.PlayerIndicatorsPlugin::new),
        new oxclient.rl.RlitePlugin("Test Rlite", "Shim smoke test: config, events, overlay",
                oxclient.rl.TestRlite::new),
        new oxclient.rl.RlitePlugin("Test Actors", "Shim smoke test: NPC/player actors, hull, name text, spawn events",
                oxclient.rl.TestActors::new),
        new oxclient.plugins.AutoLogin(),          // types your login (panel settings or autologin.properties) at the title screen
        new oxclient.plugins.AntiIdle()            // a camera-key tap every few minutes so the server does not log you out
));
```

The `RlitePlugin` lines are not hand-written plugins but the shim pattern: an adapter
(`oxclient.rl.RlitePlugin`) hosting a plugin ported off RuneLite's API — Shortest Path, and RuneLite's
own NPC Indicators and Player Indicators ported source-shaped onto the shim's actor API
(`java/net/runelite/client/plugins/`). They are ordinary registry entries — panel, settings,
profiles and all — and the reason the real list has ten lines, not five. The two Indicators ports
are the **default-on** visuals since 2026-09-06, when hull, name and tile were seen sitting at each
entity's real height in-game (0xClient's own `NpcVisuals`/`PlayerVisuals` stay in the list as this
README's worked examples, off unless you switch them on). They say in each setting's description
what the shim approximates; what RuneLite offers but the client cannot read yet (menu recolouring,
outlines, respawn timers, friend/clan colours) is omitted from their panels rather than shown dead.

Rebuild, restart the client. Your plugin is in the panel with a range slider and a colour picker you
never wrote, and its state is saved with everyone else's. **That list is the entire plugin system** —
no scanning, no annotations, no manifest, nothing that can silently fail to find your class.

**Read [`Woodcutter.java`](java/oxclient/plugins/Woodcutter.java) next.** It is the worked example and does
all four things at once: settings, a decision loop that drives the game's action API, a world overlay,
and a statistics panel.

Every setting type, the profile model, and how to ship a plugin through the hub instead of the list
are in [`docs/plugin-system.md`](docs/plugin-system.md).

### The two methods

| | for | rules |
|---|---|---|
| `tick()` | deciding and acting | runs every frame while enabled |
| `render(Graphics2D)` | drawing, and nothing else | runs after every plugin has ticked |

Keeping them apart means you can turn the drawing off without changing behaviour.

**Neither may block.** They run about thirty times a second on the overlay thread; a `Thread.sleep` in
either freezes the overlay. For "every few seconds", keep a `long lastX` field and compare
`System.currentTimeMillis()` — `Woodcutter` shows the pattern, and also shows why you want a timer
*and* an animation check rather than either alone.

### What a plugin can see and do

```java
// oxclient.api.Game -- the world, as of the start of this frame
Game.ready()                          Game.me()
Game.npcs()   Game.players()          Game.entities()
Game.toScene(worldX, worldY)          // world -> scene, null if not loaded

// projection, through the game's own camera maths
Game.projectWorld(x, y)               // -> Point, or null when off screen
Game.tileOutlineWorld(x, y)           // -> Polygon lying flat on the ground

// oxclient.api.Npcs / Players -- finding things
Npcs.nearest(1278)                    Npcs.nearestWithin(10, 2805)
Npcs.withId(ids...)                   Players.nearest()

// an Entity
e.id()  e.uid()  e.worldX()  e.worldY()  e.distance()
e.animation()  e.isIdle()  e.orientation()  e.screen()

// you
Game.me().worldX()   .isIdle()   .health()   .healthPercent()   .runEnergy()

// stats
Skills.level(Skill.WOODCUTTING)   Skills.experience(...)   Skills.boost(...)

// oxclient.api.Actions -- doing things, in WORLD coordinates
Actions.walkTo(x, y)
Actions.object(treeId, x, y)          // chop / mine / open
Actions.npc(entity)                   // first option
Actions.npc(entity, 2)                // second option

// oxclient.api.Input -- typing and clicking, by posting messages to the game window
Input.typeChar(c)                     // one character, as WM_CHAR (never a key-down: see jvm.hpp)
Input.key(Input.VK_TAB, down)         // Tab / Enter / Backspace as KEYDOWN / KEYUP
Input.mouseDown(x, y)                 // canvas coordinates; honoured on client-240-6 (the autologin Login click is the proof)
Input.target(grab)                    // where it goes: exists / render view / focused / foreground / size
```

**Through the RuneLite shim** (for ported plugins, `net.runelite.api`), the same snapshot is reachable
in RuneLite's own shape -- `client.getNpcs()`, `client.getPlayers()` (you included), `wv.npcs()` /
`wv.players()` as `IndexedObjectSet`s, and `NpcSpawned`/`NpcDespawned`/`PlayerSpawned`/`PlayerDespawned`
events. Each `NPC`/`Player` is an `Actor` with a stable identity per handle across ticks, and offers
`getLocalLocation()` (the fine render position), `getWorldLocation()` (the local player's plane),
`getCanvasTilePoly()` / `getCanvasTextLocation()` / `getConvexHull()` at the actor's own ground height,
plus `OverlayUtil.renderActorOverlay`. What is approximated or defaulted, and says so in its javadoc:
the convex hull (a prism, no model access), the logical height (a tuning constant, `Actor.logicalHeight`),
combat level 0, NPC size 1, and names that may read `""` until the name offset is confirmed on this build.

**Two coordinate systems, and mixing them up is the most common bug here.** World coordinates are what
your minimap shows (Lumbridge ≈ 3222, 3218). Scene coordinates are 0–103 within the loaded chunk (it
is 104 tiles a side, so 104 is one past the edge — `Game.toScene` returns null for it), and
they are what the game's click function actually wants. Everything a plugin touches is in **world**
coordinates; the conversion happens in one place.

### Drawing

`render()` hands you a `Graphics2D` over the whole game window, already antialiased, with (0,0) at the
top-left of the game's client area. Anything Java2D can do works. `oxclient.ui.Hud` has the three things
every overlay ends up wanting:

```java
Hud.entityBox(g, point, 16, 30, colour);          // a box standing on a point
Hud.tile(g, Game.tileOutlineWorld(x, y), colour); // a tile highlight lying on the ground
Hud.text(g, "hello", x, y, colour);               // text with an outline, readable on any background

Hud.panel(g, 12, 12, "My plugin", new Hud.Lines() // a statistics panel that sizes itself
        .add("state", "running")
        .add("xp/hr", 41234));
```

Tile outlines go through the game's own projection corner by corner, so they sit on the ground with the
right perspective and stay correct while the camera turns.

---

## Autologin

The **Autologin** plugin (`oxclient.plugins.AutoLogin`, last in the registry, off by default) types your
account into the title screen. The credentials come from one of two places, checked afresh on every
attempt:

1. **The panel.** The first two settings on the plugin's config page are `Username` and `Password`
   — type them into the AutoLogin settings in the panel (the ImGui strip of the launcher; the
   direct-inject Java2D panel shows them but cannot edit text yet). The password field is a
   password field: masked as you type, masked wherever either panel shows it, and never printed.
   Like every setting, both are saved by the profile store into
   `~/.0xclient/profiles/<id>/config.json` — local, outside the repository, but on disk. Both
   must be filled in for the panel to be used; a half-filled panel falls back to the file.
   **The field holds 63 UTF-8 bytes**, the width of the bridge's `valueText` string (format 2), and
   refuses the next one rather than accepting a value it could not publish back. A longer password
   goes in the file below; widening the string is a bridge format 3 change.
2. **The file**, for anyone who would rather keep the password out of the profile. In the client's
   data directory (`OxClient.dataDir()` — `~/.0xclient`, i.e. `C:\Users\<you>\.0xclient` on
   Windows):

```
~/.0xclient/autologin.properties

username=
password=
```

Fill the two values in after the `=`. Everything after the first `=` is the value, verbatim — a
backslash is a backslash, not an escape — with only surrounding whitespace trimmed. Lines starting
with `#` are comments.

**Both places are local.** Nothing under `~/.0xclient` is tracked, and neither the properties
file nor a profile's `config.json` must ever be committed, pasted into an issue, or attached to a
log. The plugin itself never prints a character of either value, and never a length: every log line
and the status column say only where the credentials came from and whether each is set
(`credentials: panel, username set, password set` / `credentials: file, username set, password
empty`). The `Password` setting is a `config.secret` (see `oxclient.config.Config`): the same TEXT
setting underneath, flagged so that both panels mask it and the launcher edits it in a password-mode
field.

**What "masked" does not mean.** Masking is about what is *drawn*, not about where the bytes live.
Two places hold the password in clear, both under the same trust boundary — any process running as
you can read them, nothing else can:

- a profile's `config.json`, if you used the panel settings (that is the trade the panel makes);
- the **shared-memory bridge**, while the game is running and only if you used the panel settings.
  The strip is a separate process, so a text setting's value has to cross to it: the model region
  (`Local\0xClientBridge-<gamePid>`) carries the setting's `valueText` in clear so the ImGui field
  can round-trip it, and anything else running in your session can `OpenFileMappingW` that name and
  read it for the life of the game process. A committed edit passes through the edit ring too, but
  only for the frame it takes to apply: the DLL wipes the slot before bumping the tail. (Review
  2026-09-06: the model region's copy was true but undocumented; the ring's is now transient.)
  Nothing in the launcher ever prints
  or copies a `FLAG_SECRET` value — password mode disables copy, and the tooltip, the debug tab and
  the unknown-kind branch all refuse to render it — but that is a display rule, not a boundary.
  **The file path does not cross the bridge**: leave the two panel settings empty and their
  `valueText` is the empty string, so `autologin.properties` really does keep the password out of
  both the profile store and shared memory.

**Using it.** Switch it on in the panel or press F7. On at the title screen means "login now" (a
0.5 s settle); on during start-up means "wait for the login screen, then type after `Settle delay`";
pressing F7 again while it runs aborts. The profile remembers the switch, so it stays on across
restarts once you have turned it on. The panel settings and the file are re-read at the start of
every attempt, so filling either in while the client sits at the title screen is enough — it
re-checks every 5 s.

**After a logout.** Once logged in, the plugin stays quiet: if the title screen comes back — a
deliberate logout looks exactly like a disconnect from the outside — it goes idle (`logged out --
idle` in the status column) and waits for F7 / the panel toggle before typing anything again. Turn
on `Log in again after a disconnect` (`reloginAfterDisconnect`, off by default) to have it re-type
the login automatically whenever the title screen returns. The status panel is drawn only while
not logged in.

**Click here to play.** At state 30 the client first shows a "Welcome to Old School RuneScape /
Welcome back" screen and the world only loads after its **CLICK HERE TO PLAY** button (seen live
2026-09-06: not letterboxed, button centre at canvas centre − 3, top + 334). With `clickPlay` (on
by default) the plugin clicks it once, `playDelaySec` (2 s) after state 30 arrives, logs
`[autologin] clicked play`, and the status column shows `logged in -- clicked play`; the `play`
crosshair is drawn until then. Only a login the plugin typed itself gets the click — a login done by
hand while it sits idle does not. `playX` / `playY` move the target.

**The flow it drives** (seen live 2026-09-06 on client-240-6): the title screen opens on a
"Welcome to Old School RuneScape" box with New User / Existing User buttons; Existing User opens the
form. With **"Remember username" ticked** — the default path, and what this machine has — the form
opens with the Login field pre-filled and the caret already in the Password field. **Enter does not
submit the form** on this build (neither a real keyboard Enter nor a posted one); clicking the Login
button does. A rejected login shows a separate "Incorrect username or password" screen with a
**Try again** button, and the raw game state stays 10 the whole time — a rejection is not
distinguishable from "nothing happened" by state alone.

What it does, per attempt **on the welcome screen**: settle → click Existing User → settle → 20
backspaces (clearing the password field, where the caret already is) → password → Enter (harmless,
kept in case another build honours it) → **click Login** → wait `Result timeout` for the game state
to leave the login value. If the state has not moved by then, that is the rejection screen: it logs
`no state change after Login click: assuming the rejection screen, clicking Try again`, clicks Try
again, settles, and runs the attempt again, up to `Stop after N rejections` (2). With
`Username remembered by the client` off it instead clicks the
username field, backspaces, types the username, Tabs (or clicks) into the password field and goes on
from there. Text is typed as `WM_CHAR` messages posted to the game's own render window, control keys
as key-down/key-up, one keystroke per `Key delay` (60 ms) — never `SendInput`, so nothing can be
typed into another application if you alt-tab away.

**Which screen is up — state 10 is not one screen.** Live 2026-09-06: from a **cold start** the
client draws the welcome box and the form only appears after the Existing User click; after a
**disconnect** it draws the form straight away, username pre-filled, with *"Please enter your
password"* and no welcome box at all. The old script assumed the cold start unconditionally — it
clicked where Existing User would have been (on that screen it is not a button), typed into whatever
had focus, submitted an empty password field twice and stopped with `rejected 2 times`. So the script
now branches on the **screen** (`oxclient.plugins.autologin.LoginScreen`), not on the state, and the two
things it changes are all that differ: whether Existing User is clicked, and whether the password
field is clicked before typing.

The screen is decided from two sources, in order:

1. **The client's loaded interface groups** (`Natives.loadedGroups()`) as a fingerprint. Widget
   *positions* are not trustworthy on this build (see `client/offsets.hpp`: a packed id's stored x/y
   are read as canvas coordinates with no parent-offset accumulation), but "which groups are loaded"
   is a plain list the client hands over and it changes with the screen. `LoginScreen.KNOWN` maps a
   fingerprint to a screen and **ships empty**: no id has been confirmed against a real screen yet,
   and a wrong entry would make the cold start skip a click it needs. Run once with `OXC_LOG` set
   and the log carries one `login screen fingerprint [ids] -- ...` line per distinct screen; those
   ids are what belongs in that map.
2. **How the plugin got there**, used for every unknown fingerprint — i.e. all of them today. Title
   screen reached from the loaded world = the **disconnect** screen; reached after a Try again click
   = the **form**; anything else, a cold start above all, = the **welcome** box, which is exactly the
   behaviour that has been working. A server bounce (20 → 10) deliberately keeps the old behaviour:
   nobody has yet seen which screen follows one.

The panel's `screen` line and the crosshairs say which is believed: on the disconnect screen
`existing` goes dim and `pass` lights up.

**Setting the fields directly (experimental, off).** `Set the fields directly (experimental)`
(`setFieldsDirectly`) writes the username and password into the client's own buffers instead of
typing them, which is immune to which screen is up and where the caret is. It is **off by default and
NOT VERIFIED**: the username's address is found by searching memory for the value the client is
already rendering, and the password's is taken at `LOGIN_PASSWORD_DELTA` (508) from it — a delta
derived from a single live probe run, recorded in `client/offsets.hpp` and mirrored in
`FieldWriter.PASSWORD_DELTA`. Every gate has to pass or it falls back to typing and says why in the
status line:

- a username hit that pairs with a password hit across **printable text** is a *document*, not the
  client — on 2026-09-06 that document was this client's own `config.json` in the JVM heap, gap
  `","password":"` — and is discarded;
- the candidate password buffer must be **all zeroes** (the "Please enter your password" state);
- there must be **exactly one** surviving candidate;
- `Natives.setLoginField` — the only write into game memory in the whole client — refuses unless the
  target is committed, `PAGE_READWRITE` (there is no `VirtualProtect`, ever), already holds either
  all zeroes or exactly the value being written, has room in its existing content plus zero run, and
  is not shaped like an inline `NxtString` whose length byte it will not guess.

Nothing on any of those paths prints a value, a length, or a byte of a buffer — the bytes are
classified, never dumped (`FieldWriterTest` asserts that across every path).

**Jagex Accounts.** The standalone client cannot log a Jagex Account in at all — the rejection
screen's own text says to use the Jagex Launcher — so for one of those every attempt is rejected and
the plugin stops with `rejected 2 times -- check the credentials, and whether this is a Jagex
Account ...`. Nothing here can work around that.

**Reading the log.** OXC_LOG carries the whole story under `[autologin]` and `[input]`:

| line | means |
|---|---|
| `[input] target 0x... class=JagRenderView` | messages go to NXT's input window (anything else: wrong window) |
| `[autologin] enabled: credentials: file, username set, password set; raw state 10` | credentials resolved (`panel` or `file`); 10 is the title screen |
| `[autologin] state 0 -> 10 (raw)` | the client reached the title screen |
| `[autologin] raw state X is not the configured login state 10 -- ...` | the title screen holds another value on this build: set `Raw game state of the login screen` to X |
| `[autologin] attempt 1/3: target=JagRenderView focused=1 foreground=1 canvas=1314x900 (screen WELCOME, user remembered by the client, pass set)` | typing starts; `focused=0` with nothing typed is the thing to report |
| `[autologin] login screen fingerprint [ids] -- not in LoginScreen.KNOWN; going by how we got here, which says DISCONNECT` | one line per distinct screen. **Note which screen was really up and put the ids in `LoginScreen.KNOWN`** — that is how the fingerprint stops being a fallback |
| `[autologin] screen: DISCONNECT (from how we got here) -- no Existing User click` | which script this attempt got, and why |
| `[autologin] direct write: 2 candidate field pair(s) among 4 username hits (1 ruled out as documents, ...) -- typing instead` | `setFieldsDirectly` is on and refused; counts and reasons only, never a value |
| `[autologin] script done (K keys/clicks, Login clicked); waiting up to ...` | the script was posted (keys and clicks are counted, characters never — a count would give away the password length) |
| `[autologin] submitted; state 10 -> 20 after N ms` | the client accepted the typed login — the injection works |
| `[autologin] logged in (state 30)` | done |
| `[autologin] no state change after Login click: assuming the rejection screen, clicking Try again (rejection 1/2)` | the "Incorrect username or password" screen (the state does not move for it); Try again is clicked and the attempt re-run |
| `[autologin] rejected 2 times -- check the credentials, and whether this is a Jagex Account ...` | stopped, so a wrong password is never hammered; also what a Jagex Account looks like from here |
| `[autologin] server bounced us back to the login screen (rejection 1/2)` | the state did leave 10 and came back — a server-side rejection on a build that changes state for it |
| `[autologin] authenticator screen (state 11) -- stopped` | a human is needed |

**Calibrating clicks.** Every click target is a pair of settings: **x is the offset from the canvas
centre, y is the offset from the canvas TOP** (in canvas pixels). That convention is not arbitrary:
NXT letterboxes its title screen — about 1090×670 of drawn content, centred horizontally in the
canvas but top-aligned — so x offsets hold relative to the centre while y offsets hold relative to
the top, whatever the window size. The defaults are the live measurements from 2026-09-06 (canvas
1314×900): Existing User (+69, 288), Login button (−93, 315), Try again (−14, 288), username field
(−82, 234), password field (−82, 257). All of them are drawn as crosshairs over the title screen
while `Draw the click targets` is on (bright = the script will click it, dim = preview only), so
line them up against the real buttons before anything is typed and adjust the numbers in the panel
if your client draws the screen elsewhere. Posted clicks are honoured by this build (the Login click
is what submits); the log's `state 10 -> 20` line is the proof for yours.

**What stops it.** The authenticator screen, two rejections (the rejection screen after a Login
click, or a bounce back to the login screen after the state changed), three attempts, the plugin
being switched off, or the game state reaching one of the **four** values `LoginSequence.leftTitle`
knows — 20 (logging in) or 25 (loading) = submitted, 30 = logged in, 11 = authenticator. After
a disconnect it goes idle (or re-arms with the counters reset when `Log in again after a
disconnect` is on).

Any *other* state the client may pass through mid-script — the setter compares against 1, 2, 5, 6,
40, 45 and 1000 as well — is not one of those four, so the script keeps posting the remaining
keystrokes and the Login click into whatever screen is up. Nothing seen live has done that (the
title screen holds 10 until the login is accepted), but do not read the old "leaving the login
screen for any reason" wording as a guarantee: the guarantee is the four states above. Review
2026-09-06.


---

## How it works

### The launcher
`launcher/main.cpp` — an ImGui window with a "+ client" button. Pressing it spawns `osclient.exe`
(the path comes from `[oxclient] game=` in `0xclient.ini`), injects `0xclient.dll` into it with
`CreateRemoteThread` + `LoadLibraryW` (the oldest, most boring injection there is), and reparents the
game's window into the launcher's as a child. From then on one window holds the game on the left and
a 286px Dear ImGui panel strip on the right, drawn by the launcher process with the CPU
(`client/imgui_sw.hpp`) — the game owns the only OpenGL context, so the panel never touches a GPU.

The panel's data lives in the game process (Java owns the plugin model, the profiles and the hub), so
it crosses a shared-memory bridge: Java packs a snapshot (`oxclient.panel.PanelBridge`), the DLL repacks
it into the model region (`client/bridge.hpp`), the launcher reads and renders it
(`launcher/bridge_layout.hpp`). Clicks travel back as small edit records in a ring in the same region,
and the DLL applies them by calling Java — through `Setting.set` and the owning managers, never
around them. The static_asserts in both C++ files make the sides fail a build rather than disagree
about where byte 40 is, and `tools/bridge-roundtrip-probe.cpp` checks the whole round trip byte for
byte against the real jar.

Running `osclient.exe` yourself and injecting the DLL by hand skips all of this: the DLL detects
that it was not launcher-spawned and builds its own host window and Java2D panel exactly as it always
did (`tools/wine_inject.exe` does the injecting on Linux). Same plugins, same settings, same profiles
— a different thing drawing the panel.

### The native half
- `client/offsets.hpp` — every game-specific number, in one file, each with a note on how to re-find it.
- `client/game.hpp` — guarded memory reads, entity enumeration, projection, and `doAction`.
- `client/overlay.hpp` — the transparent window, and the one function that puts pixels in it.
- `client/jvm.hpp` — starts the JVM and registers the natives. The whole unsafe surface.
- `client/bridge.hpp` — the DLL's half of the shared-memory panel bridge (model out, edits in).
- `client/dllmain.cpp` — finds the game window, detects launcher mode, runs the 30 fps loop.
- `client/imgui_sw.hpp` — the software rasterizer the launcher draws ImGui with (no GPU: the game owns
  OpenGL).
- `launcher/main.cpp` + `launcher/panel_ui.hpp` — the launcher window, the "+ client" spawn/inject/
  embed, and the ImGui strip itself (plugins, config, profiles, hub, debug, collapse, search).

### The Java half
- `java/oxclient/Natives.java` — the natives, declared. You will not call these directly.
- `java/oxclient/api/` — the world with names on it: `Game`, `Entity`, `Local`, `Npcs`, `Players`, `Skills`,
  `Actions`.
- `java/oxclient/Plugin.java` — the base class, and `java/oxclient/plugins/` — the plugins.
- `java/oxclient/config/` — settings that build their own controls.
- `java/oxclient/plugin/` — `PluginManager` (the one place a plugin is switched on or off) and
  `plugin/hub/` — the external plugin hub (manifest, download, verify, classload, remove).
- `java/oxclient/profile/` — profiles and persistence: per-profile enabled states and settings, debounced
  atomic writes under `~/.0xclient`.
- `java/oxclient/panel/PanelBridge.java` — the packed panel model Java publishes and the edits it accepts.
- `java/oxclient/ui/` — `Theme` (all the colours), `Hud` (drawing helpers), and the Java2D panel views the
  direct-inject path draws (`SidePanel`, `PluginListView`, `ConfigView`, `ProfilesView`, `DebugView`).

More on all of this: [`docs/architecture-after.md`](docs/architecture-after.md) is the architecture
map, [`docs/plugin-system.md`](docs/plugin-system.md) covers plugins, config, profiles and the hub,
[`docs/scripting-api.md`](docs/scripting-api.md) is the one-page guide to writing scripts against
`oxclient.api`, and [`docs/testing.md`](docs/testing.md) is what is tested and how to verify the rest by hand.

The overlay is a **transparent always-on-top window**, not a renderer hook. Hooking would need a detour
library, a graphics API to get right, and it crashes inside someone else's render loop when you get it
wrong. A layered window is a page of code you can read in one sitting. The cost: it will not show up in
screenshots or recordings, and it can flicker. That trade is on purpose.

**Java does the drawing.** C++ used to draw the boxes and it was the wrong split: every new visual meant
a new primitive exposed across the JNI boundary. Now Java renders the frame into an image and C++ shows
it, so a plugin has the whole of Java2D and C++ has one job. It costs one memcpy of a full-screen image
per frame — about 8 MB at 1080p, well under a millisecond, and we were already blitting those pixels.

### Why there is no packet code
Bot clients usually reimplement the game's network protocol — a big table of opcodes and byte layouts
that changes every update and fails silently when it drifts.

0xClient does not. `doAction` is the client's own menu-action entry point: the same function that runs
when you right-click a tree and choose "Chop down". We call it with the same arguments and the client
builds and sends the packet itself. Consequences:

- We never need to know the wire format.
- Anti-cheat sees a normally-constructed packet, because it *is* one.
- One function to re-find after an update instead of a hundred opcodes.

**The honest state of that right now:** `DO_ACTION` is 0 — not derived for the current build — and
`client/game.hpp` refuses to call a null RVA, so every action call is a guarded no-op by design
(calling the wrong address would crash the game; reading a wrong offset only shows a wrong number).
Plugins can be written and overlays run, but nothing can walk, chop or talk until someone re-derives
the function by hook-and-log — `client/offsets.hpp` documents the method and the current candidate.
Everything above describes the design and what it buys you once that one number is back.

### RuneLite plugins

There is a second way to write a plugin: port one. `java/net/runelite/` is a **shim, not RuneLite** —
our own implementation of the `net.runelite.*` API that RuneLite plugins are written against. A plugin
ported to `java/shortestpath/` (Shortest Path, from the plugin hub) reads the game through that shim and
runs as an ordinary 0xClient plugin on the overlay thread; see `java/net/runelite/README.md` for what is
vendored, what is shimming, and what waits on a new offset. (`resources/NOTICE-shortest-path` and
`resources/LICENSE-shortest-path` are the licence side of the same story, not a technical one.)

The honest state of it (2026-09-06, logged in on client-240-6): the offsets are mostly landed.
Wired to registered natives are varps and varbits, item containers, widgets (bounds, text and
children), NPC/player names, and the world map's origin position; everything derivable from those
is built and unit-tested — pathfinding, tile overlays, config, events, the right-click popup.

**Seen live**, in a logged-in client: autologin end to end (Existing User click → backspaces →
password → Login click → state 10 → 20 → 25 → 30 → the *click here to play* click); entity boxes and
hull prisms sitting on the actual models at each entity's own render height; names over NPCs and
players (`DEF_NAME` reads on this build — 10 of 11 nearby NPCs named, the eleventh is a nameless
one); and Shortest Path end to end — shift+right-click → the 0xClient popup's **Set Target** → a 32-step
path → red tiles on the ground, the minimap line and the debug panel. The game's own *Walk here* row
sits under our Set Target row, so setting a target also walks you there.

**Still unverified in-game:** NPC Indicators driven from a name list, profile switching, and the
plugin hub. And `DO_ACTION` is still 0, so the shim's own action path cannot act (see below) — the
walking above is the game's menu doing it, not us.

What still returns an honest default through `net.runelite.api.ClientState`, each method naming the
offset it is waiting for:

- **The game menu struct.** `DO_ACTION` is not derived yet, so nothing can read the game's own
  right-click menu. `oxclient/rl/MenuPopup.java` is the deliberate fallback: 0xClient detects the right-click,
  fires the same events RuneLite would, and draws the plugin-contributed entries itself.
- **World-map zoom.** The map's centre is derived and live (`oxclient/rl/Events.java`'s `pushWorldMap`:
  `centreTile = 8*WM_CENTRE = WM_ORIGIN + 48`, pinned in the decompile and cross-checked at the GE),
  but there is deliberately no zoom, because the binary provably has no zoom field to read. The map
  overlays' maths run on a placeholder zoom, so on-map drawing is anchored at the correct centre at a
  guessed scale.
- **Minimap zoom and camera yaw.** Both are placeholder defaults in `ClientState` until offsets near
  the camera/viewport code are derived.

Porting more hub plugins mostly means deriving the remaining offsets once; the shim is shared.

Auto-walk is the one place 0xClient extends a ported plugin rather than just hosting it: upstream Shortest
Path never moves for you, the `Auto-walk` toggle in its panel does, using the same `doAction` walk as
every other plugin here.

---

## When the game updates

Jagex rebuilds the client roughly weekly. Two kinds of number the DLL depends on rot at different
speeds:

- **Function RVAs** (`WORLD_TO_SCREEN`, `GET_VARBIT`) — move on almost every build.
- **Struct offsets** (`ENTITY_SCENE_X`, `SCENE`…) — stabler, but they do move. Between 241-1 and the
  241-3 staging build, every field on the client object past the skill arrays moved by `0x10`.

### You should not have to do anything

Offsets are **data, not code**. The DLL reads the game's version resource at start-up and loads
`offsets/client-<build>.json` from beside itself; if it has no file for that build it fetches one from
this repository and caches it. A new game build therefore needs one thing: that file to exist. It is
produced by the updater and published by a scheduled GitHub Actions workflow
([`.github/workflows/offsets.yml`](.github/workflows/offsets.yml)) that checks Jagex's CDN every few
hours, derives the file for any build it has not seen, and opens a pull request with the diff.

Every entry in the file carries a status, so you always know what you are running on:

| status | meaning |
|---|---|
| `derived` | read off this build's own code by `DeriveOffsets.java` (evidence: the anchor and the instruction) |
| `verified` | derived, and confirmed against a running game |
| `shifted` | not derived; moved by the same delta as the derived fields on both sides of it |
| `carried` | not derived; last build's value, labelled as such |
| `suspect` / `refuted` | known to be doubtful or wrong; the feature that needs it refuses rather than guesses |

The DLL's `[build]` log line says which file it loaded and what was missing.

### Running the updater yourself

```bash
python tools/update/update.py --latest            # the build Jagex is shipping right now
python tools/update/update.py --build 241-3       # any build the archive holds, including staging
python tools/update/update.py --exe "C:\...\osclient.exe"
```

It downloads the build (`tools/update/fetch_client.py`, CDN pieces sha256-checked against the signed
metafile), runs Ghidra headless (free; 10–20 minutes the first time per build, cached after), derives
every offset it has a rule for, merges with the previous build's file, prints the diff and writes
`offsets/client-<build>.json`. Commit that file and the DLL in the wild picks it up.

**The method, which the script only mechanises:** anchor on a name the client uses for itself, then
walk to what you want. The client ships a Lua binding layer that registers its own functions by name,
and those names are plain text in the binary — `getVarp`, `npcCoord`, `worldToScreenCoord`. The string
leads to the registration, the registration stores the leaf it binds, and the leaf's own instructions
name the field. Every value the script writes quotes the instruction it was read from.

**Do not scan for byte patterns.** A byte pattern is a guess about instructions the compiler may
rearrange. When it breaks it does not error — it gives you an address that decompiles into something
plausible and wrong.

When the script cannot derive something (the file says `carried`), the manual tools are still here:
`tools/ghidra_scripts/DumpAnchors.java` prints the instruction context behind every binding name so a
changed shape can be read and taught to `DeriveOffsets.java`; `DecompileAnchors.java <rva>` decompiles
a function; `tools/ida_headless.ps1` does the same with IDA if you own it. The `deob` skill describes
the whole procedure for an agent.

### Finding a new action

`Game.OPLOC1 = 3` is "first option on a scenery object". Other actions have other numbers. To find one,
attach a debugger to `DO_ACTION`, do the action by hand in game, and read the opcode argument. That is
how every number in this repo was found — none of them were guessed.

---

## The wiki

[`wiki/index.html`](wiki/index.html) is a single self-contained page that documents everything in this
repository: every Java type with its javadoc, fields and methods; every native; every offset with the
note that says how it was found; every C++ file; every document; every skill and agent. It is generated
from the sources, so it cannot drift from them, and it is committed, so it works from a fresh clone
with no build. Open it in a browser and press `/` to search.

```bash
python tools/wiki/build_wiki.py          # regenerate wiki/index.html
python tools/wiki/build_wiki.py --check  # exit 1 if the committed wiki is stale
```

Documentation lives in the sources — javadoc on Java, header comments on C++, the HOW FOUND note on
every offset — and the wiki renders it. If something is not in the wiki, it is not documented; fix
the source, not the wiki.

---

## Agents and skills

The repository carries its own working instructions for coding agents, so the parts of maintenance
that are procedure rather than judgement can be handed off. They live in `.claude/` and are plain
Markdown; any agent that reads a file can follow them, and so can a person.

| What | Where | Use it when |
|---|---|---|
| **`wiki` skill** | [`.claude/skills/wiki/SKILL.md`](.claude/skills/wiki/SKILL.md) | After any change to Java, C++, offsets, docs, skills or agents. Says what "documented" means here and rebuilds the wiki. |
| **`update-client` skill** | [`.claude/skills/update-client/SKILL.md`](.claude/skills/update-client/SKILL.md) | Jagex shipped a build. The whole procedure: fetch, derive, merge, verify live, publish — and what to do about anything the script could not derive. |
| **`deob` skill** | [`.claude/skills/deob/SKILL.md`](.claude/skills/deob/SKILL.md) | One offset broke, or a feature needs memory nothing exposes yet. The manual method: anchor on a name, walk, read, never byte-scan. |
| **`client-updater` agent** | [`.claude/agents/client-updater.md`](.claude/agents/client-updater.md) | Runs `update-client` end to end for a named or the latest build and reports counts per status, every carried name, and the commit. |
| **Working rules** | [`CLAUDE.md`](CLAUDE.md) | The three rules of the codebase, the layout, and the conventions (plain commit messages, no copies of the game, nothing from outside the repository). |

**Using them.** In a Claude Code session opened on this folder, type `/update-client` or `/wiki` to
load a skill, or ask for the `client-updater` agent by name ("run the client-updater for 241-3"). In
any other tool, paste the skill file into the conversation; it is written to be followed without the
harness. The scheduled workflow needs no agent at all.

**Updating them.** A skill is the procedure as it is today; when the procedure changes, the skill
changes in the same commit. Concretely:

- Added an offset to `client/offsets.hpp`? Regenerate `client/offsets_table.inc`
  (`python tools/update/gen_offset_table.py`), give `DeriveOffsets.java` a rule for it, and add the
  name to every current `offsets/*.json` (`update.py --no-analyse` re-merges). The `update-client`
  skill's "adding a brand-new offset" section is that list; keep it true.
- Changed how the wiki is built, or added a source directory? Update the `wiki` skill's list of what
  counts as documented.
- Found a registration shape `DeriveOffsets.java` does not know? Teach the script, then note the
  shape in the `deob` skill's "two registration shapes" section so the next person reads it there.
- Every one of these ends with `python tools/wiki/build_wiki.py`, because the skills and agents are
  wiki pages too.

---

## Good first contributions

Genuinely useful, roughly easiest first:

1. **Find the nearest tree automatically.** The woodcutter needs a typed-in tile because nothing here can
   enumerate scenery — that means walking the scene's object grid, about six more offsets. Land this and
   every gathering plugin gets shorter, and the woodcutter loses its most awkward setting.
   *(the highest-value one on this list, by a distance)*
2. **The game menu struct.** `DO_ACTION` is still 0, so nothing can read the game's own menu entries or
   click records — every menu action goes through the drawn-popup fallback in
   `oxclient/rl/MenuPopup.java`, and until this lands nothing can actually act in-game (see
   [the RuneLite plugins section](#runelite-plugins) for what that means for auto-walk). Hook-and-log
   is the method; `client/offsets.hpp` explains it.
3. **Minimap zoom and camera yaw.** Both sit near the camera/viewport code — `worldToScreenCoord`'s
   camera reads are the anchor. `ClientState.getMinimapZoom()` and `getCameraYawTarget()` hold the
   placeholders today; landing them is what makes a rotated minimap and a oxc-native minimap
   overlay possible.
4. **Ground items.** Item containers are readable now; the scene's ground-item stack walk on top of
   that is what makes a looter possible.
5. ~~**Names.**~~ Done — the `entityName` native walks the client's own `npcName` binding, and NPC and
   player names come through (`oxclient/api/Entity.java`, `net/runelite/api/Player.java`).
6. ~~**Inventory reading.**~~ Done — the `container` native reads any item container in one call
   (wired through `net.runelite.api.ClientState.getItemContainer`).
7. ~~**Saving settings.**~~ Done — settings, enabled states, pins and profiles persist under
   `~/.0xclient` (see `oxclient/profile/ProfileManager.java`). Kept on the list so nobody redoes it:
   the open problem there now is *syncing* a profile between machines, which is a file format
   question, not an offsets one.
8. ~~**A minimap overlay.**~~ Done for the ported plugin — Shortest Path ships a minimap overlay.
   What is still open for 0xClient's own plugins is item 3: without the minimap zoom offset nothing of
   ours can draw on it correctly.

Please keep the three rules this codebase is built on: **no packet building**, **every offset gets a
comment saying how it was found**, and **nothing claims to work until it has been seen working**.

---

## Troubleshooting

**Nothing happens when I press the button.**
Is the game actually running and logged in? The launcher looks for `osclient.exe` by name. If it says the
game refused the DLL, you built 32-bit — the game is x64 and rejects a 32-bit DLL silently.

**The overlay says "java: could not load jvm.dll".**
`java=` in `0xclient.ini` is wrong. It needs a folder with `bin\server\jvm.dll` under it. A JDK always
has that; some JREs do not.

**The build says "CMake is not installed (or not on your PATH)".**
Install it from the table above. If you just installed it, **restart IntelliJ** — it caches the `PATH`
it was started with, so a new install is invisible to it until then.

**The build says "No CMAKE_CXX_COMPILER could be found".**
No C++ compiler. Install the Build Tools from the table above.

**The overlay says "oxclient/OxClient not found".**
`0xclient.jar` must sit next to `0xclient.dll`. `gradlew dist` puts all four files in `build\dist\`
and now checks they are actually there before claiming success — it used to be possible for the build
to pass while producing no DLL at all.

**Boxes are in the wrong place, or the client crashes on inject.**
The game updated and the offsets moved. See [When the game updates](#when-the-game-updates).

**Boxes flicker.**
Expected — it is a layered window, not a renderer hook. See "The native half".

**The panel strip says "bridge: opening…" forever, or shows nothing.**
The DLL could not create the shared-memory mapping, or the jar beside the DLL predates the bridge.
The game's stdout prints `[bridge]` lines saying which; the debug tab in the strip shows the same
state. A jar without `oxclient.panel.PanelBridge` gets an empty panel rather than a hung one.

**My settings did not come back after a restart.**
They live in `~/.0xclient/profiles/` — a profile is a complete statement, so only what differs from
a plugin's declared defaults is written, and a plugin that was never switched on under the active
profile starts off. A corrupt file is quarantined as `.bad` and fallen back from; look for
`[profile]` lines in the game's stdout.

---

## Licence

**GPL-3.0.** See [`LICENSE`](LICENSE). In plain terms:

- **Anyone can use it**, for anything, including commercially.
- **It has to stay open.** If you distribute a modified version — or anything built on it — you have to
  ship the source under the GPL too. You cannot take this closed.
- **Credit stays with it.** Keep the copyright notices and say what you changed.

If you fork it, a link back here is appreciated on top of what the licence requires.

Copyright (C) 2026 StoneShorts and the 0xClient contributors.

## Support

0xClient is free and will stay free. If it saved you an evening, you can buy the author a coffee:
**[buymeacoffee.com/0xbiggs](https://buymeacoffee.com/0xbiggs)**.

---

This is a personal-use tool for your own account. Automating a game breaks its rules and can get that
account banned. That is your call to make, and yours to live with.
