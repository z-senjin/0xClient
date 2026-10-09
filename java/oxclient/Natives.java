package oxclient;

/**
 * Every native method in OxClient, in one class, so the whole unsafe surface is one screen of code.
 *
 * <p>These are registered from C++ at startup (see {@code client/jvm.hpp}). If you add one here you must
 * add it there too, with a matching JNI signature, or the class will fail to link at the first call with
 * an {@code UnsatisfiedLinkError} naming the method.</p>
 *
 * <p><b>You almost certainly want {@link oxclient.api.Game} instead.</b> This class returns flat int arrays
 * with no bounds checks and no meaning attached; the api package turns them into things with names. The
 * only reason to call anything here directly is that you are adding a capability the api does not have
 * yet.</p>
 */
public final class Natives {

    private Natives() {}

    /** True once the game has built its client object -- i.e. you are in-game, not at the login screen. */
    public static native boolean ready();

    /**
     * Every visible entity, ten ints each, flattened (nEntities in client/jvm.hpp):
     * {@code uid, sceneX, sceneY, isPlayer, id, animation, orientation, fineX, fineH, fineY} --
     * the last three are the rendered position in fine units (128/tile) and the ground height under
     * it (client height axis, negative = up), live on client-240-6.
     *
     * <p>{@code id} is the NPC type for NPCs. For players it is always {@code -1} on this build: the
     * combat-level offset we had is wrong on client-240-6 (it read pointer garbage, not a level) and
     * has been gated off pending re-derivation -- see {@code PLAYER_COMBAT_LEVEL} in
     * {@code client/offsets.hpp}. Treat -1 as "combat level unavailable", never as a level.</p>
     */
    public static native int[] entities();

    /** {@code {worldX, worldY}} of the loaded scene's south-west corner. Empty when nothing is loaded. */
    public static native int[] sceneBase();

    /**
     * One varp (client config variable) by id. 0 before the game's varp array exists -- callers treat
     * 0 as "unknown", never as a real value.
     */
    public static native int varp(int id);

    /**
     * An item container snapshot (inventory, bank, worn equipment...), flattened
     * {@code {slot0Id, slot0Qty, slot1Id, slot1Qty, ...}} -- slot {@code i} is {@code [2i]} / {@code [2i+1]}.
     *
     * <p>Empty when the container does not exist right now (bank closed, pre-login). A missing item is
     * id {@code -1}, quantity 0. The snapshot is a moment in time: the game can resize or reorder a
     * container while you hold it.</p>
     */
    public static native int[] container(int containerId);

    /**
     * You: ELEVEN ints, {@code {uid, sceneX, sceneY, plane, animation, orientation, runEnergy, cycle,
     * fineX, fineH, fineY}}, or empty when you have not spawned. The last three are the render position
     * and ground height, exactly as in {@link #entities} (fine units, 128/tile, height negative = up).
     *
     * <p>This javadoc said "eight" until review 2026-09-06 while nLocal had returned eleven since the
     * render position landed. {@code Local.read()} rejects any other length outright, so a contributor
     * who added a field to the layout the old doc described would have got {@code Local.ABSENT}
     * forever -- {@code Game.ready()} false and every plugin silently dead. Both sides move together:
     * nLocal in {@code client/jvm.hpp}, {@code Local.read()}, and this line.</p>
     */
    public static native int[] local();

    /** All 25 skills: {@code effective[25]}, then {@code base[25]}, then {@code xp[25]}. */
    public static native int[] skills();

    /**
     * Project a point in fine coordinates (tiles &lt;&lt; 7) to screen pixels, using the game's own
     * projection so it stays correct while the camera moves.
     *
     * @return screen x in the high 32 bits, y in the low 32, or {@link Long#MIN_VALUE} if off-screen
     */
    public static native long project(int fineX, int fineHeight, int fineY);

    /**
     * Perform a menu action, in SCENE coordinates. This is the only way OxClient acts on the game.
     *
     * @return true when the action was handed to the client; false when it was NOT issued -- either
     *     the client object is not up yet, or the client's action function could not be derived for
     *     this build ({@code DO_ACTION == 0} in {@code client/offsets.hpp}), in which case this call
     *     is a silent no-op on the game side and this boolean is the only signal that nothing
     *     happened. Callers must not report success on a false.
     */
    public static native boolean doAction(int sceneX, int sceneY, int opcode, int targetId);

    /**
     * Interact with an NPC by uid; the native side looks its tile up.
     *
     * @return false when the uid did not resolve (the NPC despawned this frame) or the action was not
     *     issued -- see {@link #doAction} for what that means.
     */
    public static native boolean interactNpc(int uid, int opcode);

    /**
     * Take an item's option the way clicking it does: option {@code op} (1..10) on the item in
     * {@code slot} of interface component {@code widgetId} ({@code (group << 16) | component}; the
     * inventory is 149:0). {@code itemId} is the item expected in that slot. Queued and sent on the game
     * thread (client/actions.hpp, itemAction).
     *
     * @return true when queued; false when dropped -- ACT_IF_OP not measured on this build, actions off,
     *     bad arguments, or a full queue. The DLL log says which, once.
     */
    public static native boolean itemAction(int widgetId, int slot, int op, int itemId);

    /**
     * The scenery within {@code radius} tiles of you on your floor, four ints each, flattened:
     * {@code {id, sceneX, sceneY, layer}} -- the origin tile in scene coordinates; layer 2 is a game
     * object (trees, booths), 0 a wall (doors, gates). Empty before you spawn. See client/scenery.hpp;
     * {@link oxclient.api.Scenery} is the friendly form.
     */
    public static native int[] locs(int radius);

    /** A loc's name from the client's definition cache ("Tree"), or "" when it is not cached now. */
    public static native String locName(int id);

    /**
     * A loc's five right-click options, newline-separated -- option n is line n, empty where it has none
     * ("Chop down\n\n\n\n"). "" when its definition is not cached right now.
     */
    public static native String locOptions(int id);

    /**
     * Take option {@code op} (1..5) on scenery object {@code id} whose origin is SCENE tile
     * {@code (sceneX, sceneY)}, the way clicking that option does. Queued; false when dropped.
     */
    public static native boolean objectAction(int sceneX, int sceneY, int id, int op);

    /** The game's client area on screen: {@code {x, y, width, height}}. */
    public static native int[] viewport();

    /**
     * Mouse, modifier keys and keyboard edges, read from Windows rather than game memory (no offset
     * needed). The array is EIGHT fixed ints followed by up to SIXTEEN key-edge entries:
     * {@code {mouseX, mouseY, shift, ctrl, alt, leftButton, rightButton, middleButton, vk1, vk2, ...}}.
     *
     * <p>Mouse coordinates are in the game window's client area -- the same space the projection
     * natives produce screen points in. A held modifier or button is 1, a released one 0. The trailing
     * entries are KEY EDGES: the virtual-key codes that went from up to down since the previous call,
     * so plugin hotkeys can dispatch real KeyEvents. There are never more than 16 of them per call and
     * the total length therefore varies -- index the first eight blindly, everything else by length.</p>
     *
     * <p>{@code net.runelite.api.ClientState.setInputState} is the intended parser: it unpacks the
     * fixed fields and turns the key edges into this frame's events.</p>
     */
    public static native int[] input();

    /**
     * The mouse in canvas coordinates, {@code {x, y}}, or empty when the game window is gone. No side
     * effects: unlike {@link #input} it consumes no key edges or click latches, so any plugin may poll it.
     */
    public static native int[] mouse();

    /**
     * Put a finished frame on the overlay. {@code px} must be {@code w*h} <b>premultiplied</b> ARGB
     * pixels, top row first -- which is exactly what a {@code BufferedImage.TYPE_INT_ARGB_PRE} holds.
     */
    public static native void present(int[] px, int w, int h);

    /**
     * The control panel's frame, same pixel contract as {@link #present}. Lands on the panel's own
     * window, pinned to the game's right edge -- {@code w} is the panel's width and {@code h} the
     * game client's height, which SidePanel chooses.
     */
    public static native void presentPanel(int[] px, int w, int h);

    /**
     * The client's own game-state field: 10 title, 20 logging in, 25 loading, 30 logged in.
     * 0 before the client object exists.
     */
    public static native int gameState();

    /**
     * An entity's name by uid AND kind -- players off entity+0x718, NPCs off their definition's
     * +0x8. {@code player} is required because players and NPCs live in two separate client tables
     * with separate uid keyspaces, so the uid alone does not identify an entity. Empty when it
     * despawned or the name could not be read. Names may contain U+00A0 where the game pads;
     * callers that compare against typed text should fold that to a space.
     */
    public static native String entityName(int uid, boolean player);

    /**
     * One widget's state by its client id ({@code (group << 16) | component}):
     * {@code {ok, x, y, width, height, hidden}}, or empty when the group is not loaded right now.
     * x/y are as the widget stores them -- relative to its parent for nested widgets.
     */
    public static native int[] widget(int id);

    /** A widget's primary text line, colour tags included. Empty when not loaded. */
    public static native String widgetText(int id);

    /**
     * A widget's dynamic child by index: {@code {ok, x, y, width, height, hidden}}, or empty when the
     * index is out of range. x/y are relative to the parent widget.
     */
    public static native int[] widgetChild(int id, int childIndex);

    /**
     * One widget's rectangle in CANVAS coordinates:
     * {@code {ok, absX, absY, width, height, hidden, depth, complete}}, or empty when the id is not
     * loaded. This is RuneLite's {@code getCanvasLocation} -- the component's own x/y plus every
     * ancestor's -- taken in C++ because each ancestor is three guarded derefs there and one JNI round
     * trip here, and {@code Perspective.localToMinimap} asks for the minimap rectangle per drawn point.
     *
     * <p>{@code complete} is the field a caller must branch on. It is 1 only when the parent chain was
     * walked all the way to a root, which is the only case where absX/absY are a canvas position. When
     * it is 0 the pair is the stored PARENT-RELATIVE x/y -- byte-identical to {@link #widget} -- and
     * the caller must refuse rather than draw at it. The chain is walked through a parent link that is
     * DERIVED AT RUNTIME rather than hardcoded (client/game.hpp {@code scanWidgetTree}, specified in
     * the "THE PARENT LINK" block of client/offsets.hpp): the derivation only accepts an offset that
     * holds for every component of every loaded group, and refuses outright when two survive, so
     * {@code complete == 0} is what an undecided derivation looks like from here.</p>
     *
     * <p>NOT subtracted: an ancestor's scroll offset, which upstream does subtract and which no offset
     * on this build exposes. Neither map has a scrolling ancestor; a row inside a scrolled list comes
     * back off by the scroll amount.</p>
     */
    public static native int[] widgetAbs(int id);

    /**
     * The parent chain behind one {@link #widgetAbs} answer as a single human-checkable line, e.g.
     * {@code "161:30 (53,8) 152x152 <- 161:22 (1090,4) 224x160 <- 161:0 (0,0) 1314x900 => abs
     * (1143,12) 152x152 via parentId, depth 2, complete"}.
     *
     * <p>Diagnostic: once a session under {@code OXC_LOG}, never per frame. The LAST hop is a
     * self-test that needs no measurement by eye -- a group root must come out {@code (0,0)} at
     * exactly the canvas size, and if it does not then the stored x/y are cache originals rather than
     * the laid-out rectangle and the whole absolute-geometry approach is the wrong one.</p>
     */
    public static native String widgetChain(int id);

    /**
     * Re-derive the widget parent link from scratch and return the tally: which offset carries
     * {@code (group << 16) | component} on every component (the positive control), which carries a
     * same-group parent id or parent pointer, how many candidates survived an acyclic-forest check,
     * and whether {@code IFTYPE_CHILDREN_*} carries the static tree or only runtime children.
     *
     * <p>This is the EVIDENCE behind every absolute rectangle in the shim, printed with counts rather
     * than a verdict so a near miss is visible. Walks every loaded component's first 0x400 bytes
     * twice: call it from a probe, at most once a session, never per frame.</p>
     */
    public static native String widgetTreeProbe();

    /**
     * The world map's state: {@code {level, originX, originZ, centreX, centreZ}}, or empty before the
     * map object exists. The origin is the map's coordinate base in world tiles; the centre ints are
     * the map centre in 8-tile units (centre tile = {@code 8 * centre = origin + 48}), passed through
     * raw and unused by the Java side right now. There is no zoom here on purpose: the client has no
     * zoom field in this object or its view (verified in the binary), so any number we returned would
     * be invented.
     */
    public static native int[] worldMap();

    /** Ids of every widget group whose component data is currently loaded, ascending. */
    public static native int[] loadedGroups();

    /**
     * Addresses whose bytes equal {@code needle} in this process's read/write memory, at most 64.
     *
     * <p>Diagnosis, not a feature: it is how the login form's username field was located without a
     * decompiler. It returns ADDRESSES ONLY, never the bytes, so a caller may pass a secret and log
     * what comes back. A needle under three characters is refused (it would match everywhere).</p>
     */
    public static native long[] findString(String needle);

    /** {@code len} bytes at {@code at} as hex, or "" when the address is not readable. Diagnosis. */
    public static native String peek(long at, int len);

    /**
     * Every loaded interface component that carries text, one per line:
     * {@code "group:component x,y wxh shown|hidden text"}, at most {@code max} lines.
     *
     * <p>How a plugin finds a button by its LABEL rather than by a coordinate somebody measured once:
     * "CLICK HERE TO PLAY" is a component, and its id is stable where a pixel offset is not. Walks the
     * whole interface tree, so call it from a probe or an explicit action, never per frame.</p>
     */
    public static native String dumpWidgetText(int max);

    /**
     * Offsets within the client object whose pointer lands within {@code slack} bytes before
     * {@code target}, searching the first {@code span} bytes. At most 64.
     *
     * <p>The discriminator a value scan cannot give: a string the game renders is reachable from the
     * client object, while an identical copy in the JVM heap is not. A hit says "this buffer belongs to
     * a client structure, at this offset" -- which is what goes in {@code client/offsets.hpp}.</p>
     */
    public static native int[] pointersTo(long target, int span, int slack);

    /**
     * Offsets at which loaded interface components hold the pair {@code (w, h)}, one
     * {@code "group:component w@+HEX h@+HEX"} per line.
     *
     * <p>Pass the canvas size: a top-level interface is canvas-sized, so the offsets that come back are
     * where components really keep their rectangle. {@code IFTYPE_WIDTH}/{@code IFTYPE_HEIGHT} in
     * {@code client/offsets.hpp} read 1 for every component on client-240-6, which is why every widget
     * position in the shim is wrong; this is how the right ones get derived without a decompiler.</p>
     */
    public static native String findWidgetRect(int w, int h);

    /**
     * Write one NUL-terminated login field at an address {@link #findString} produced. The ONLY write
     * into game memory in the whole client, and it is guarded like one.
     *
     * <p>It refuses -- writing nothing -- unless every one of these holds: the address is readable;
     * the region is committed and {@code PAGE_READWRITE} (no {@code VirtualProtect}, ever: a target
     * that is not already writable is a target we got wrong); the {@code cap} bytes there hold either
     * all zeroes or exactly {@code value}; the value fits in the existing content plus the run of
     * zeroes after it, and within {@code cap}; and the buffer is not shaped like an inline NxtString,
     * whose length byte this will not guess at. It never logs the value.</p>
     *
     * @param cap the most bytes of the buffer that may be touched, terminator included; 1..256
     * @return 0 written, or a negative refusal code -- {@code -1} bad argument, {@code -2} not
     *     readable, {@code -3} not writable, {@code -4} the buffer holds something unexpected,
     *     {@code -5} no room, {@code -6} an inline NxtString. {@code oxclient.plugins.autologin.FieldWriter}
     *     mirrors these and turns them into words.
     */
    public static native int setLoginField(long addr, String value, int cap);

    // -- input INTO the game (2026-09-05, not yet exercised live). All four are PostMessageW to NXT's
    //    JagRenderView child and nothing else -- never SendInput, which would type into whatever
    //    application is in front. See the "Input INTO the game" section of client/jvm.hpp for the
    //    reasoning; plugins use oxclient.api.Input, not these.

    /**
     * Post one UTF-16 code unit to the game as WM_CHAR. Text only -- never a WM_KEYDOWN, because the
     * game's own TranslateMessage would derive the case from the physical shift state and emit a
     * second WM_CHAR. Never logs the character: this is the password path.
     *
     * @return false when there is no game window to post to (or the post itself failed)
     */
    public static native boolean postChar(int codeUnit);

    /**
     * Post WM_KEYDOWN ({@code down=true}) or WM_KEYUP for a Win32 virtual key -- 0x09 Tab, 0x08
     * Backspace, 0x0D Enter, 0x1B Escape. Not for letters (see {@link #postChar}).
     *
     * <p>Tab and Backspace go as the bare key pair: NXT's own TranslateMessage supplies their WM_CHAR,
     * and a second one from us would Tab twice. Enter and Escape do NOT behave that way (live
     * 2026-09-05 the login form never submitted with both fields typed), so nPostKey posts the WM_CHAR
     * for those two itself -- {@code '\r'} / {@code 0x1B} -- between the down and the up. So a
     * {@code postKey(VK_RETURN, true)} is a keydown AND a char, and "Enter arrives twice" is a
     * question for the DLL, not for the game: this javadoc claimed the opposite until review
     * 2026-09-06 and would have sent that hunt the wrong way.</p>
     */
    public static native boolean postKey(int vk, boolean down);

    /**
     * Mouse in canvas coordinates (the same space {@link #input} and {@link #viewport} report):
     * action 0 move, 1 left down, 2 left up. Posted clicks ARE honoured on client-240-6: the autologin
     * plugin's "Login" and "Click here to play" clicks are both posted through here and both work
     * (seen live 2026-09-06). The client importing GetAsyncKeyState was the reason to doubt it.
     */
    public static native boolean postMouse(int x, int y, int action);

    /**
     * Diagnosis of where injected input goes: {@code {targetExists, targetIsRenderView,
     * focusIsTarget, gameIsForeground, canvasW, canvasH}}. {@code grab=true} also SetFocus()es the
     * game first, guarded by a 50 ms "is it pumping" probe so it cannot hang the frame thread.
     */
    public static native int[] inputTarget(boolean grab);
}
