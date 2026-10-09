// offsets.hpp -- every game-specific number 0xClient depends on, in one file.
//
// READ THIS BEFORE CHANGING ANYTHING HERE.
//
// These are offsets into a program we do not control, and Jagex rebuilds it roughly weekly. Two kinds of
// number live here and they rot at very different speeds:
//
//   FUNCTION RVAs   (DO_ACTION, WORLD_TO_SCREEN)  move on almost every update. Assume they are wrong
//                                                 after any patch until you re-derive them.
//   STRUCT OFFSETS  (ENTITY_SCENE_X, ENTITY_TABLE...) are stabler, but they DO move. ENTITY_TABLE moved
//                                                 by 0x10 between two builds a few weeks apart.
//
// BUILD_ID below is the sanity check. It is the RVA of a function we use as a fingerprint for "which
// build is this". If it does not match, every other number in this file is suspect and the client
// refuses to start rather than reading garbage out of a stranger's address space.
//
// HOW TO RE-DERIVE THESE: see README.md, section "When the game updates". Short version: run
// `python tools/update/update.py --latest`. It fetches the build, runs Ghidra headless with
// tools/ghidra_scripts/DeriveOffsets.java (which mechanises the anchors named in the comments below)
// and writes offsets/client-<build>.json. Every one of these was found by anchoring on something the
// client itself names -- a string, a Lua binding, a distinctive constant -- never by scanning for a
// byte pattern and hoping.
//
// THESE ARE DEFAULTS, NOT CONSTANTS. At start-up the DLL reads the host exe's version and loads
// offsets/client-<version>.json (client/offsets_json.hpp), which overwrites every variable below
// by name. The compiled values only apply when no file exists for the running build -- and the DLL
// refuses to start unless that build is exactly BUILD_VERSION. Adding a variable here means
// regenerating client/offsets_table.inc (python tools/update/gen_offset_table.py).
#pragma once
#include <cstdint>
#include <string>

namespace oxc::off {

// ---------------------------------------------------------------------------------------------------
// BUILD FINGERPRINT
// ---------------------------------------------------------------------------------------------------
// The ClientState Lua-binding registration function's RVA. We do not call it; we only use its address
// as a build id. It is found by anchoring on the binding-name string "getVarp" (see the deob skill):
// the function that references that string IS this registration, so it is as easy to re-derive as it
// is unique per build. If your client does not match, DO NOT just bump this number: re-derive the
// whole file, because everything below was measured on this exact build.
//
// Derived statically from osclient.exe client-240-6,
//   sha256 d6a43c08fc8d2c7934b081545a6b26023830c55ccd11cd86549056dd343471e1,
// fetched from Jagex's own CDN (see .claude/skills/deob/SKILL.md). The registry/scene offsets below
// were subsequently VERIFIED LIVE against that exact binary running under Wine (see their comments);
// items marked NOT (re-)VERIFIED were read out of the binary but not confirmed against a running game.
inline std::uintptr_t BUILD_ID = 0xF6140;

// The same build, as the client itself states it: osclient.exe's PE version resource carries the
// release as FileVersion "240-6" (client-240-6 in the CDN metafile, above). BUILD_ID is an RVA and
// cannot be checked without already trusting these offsets; this string CAN be checked before a
// single game byte is read, and dllmain.cpp does exactly that -- a mismatch refuses to start rather
// than reading garbage out of a stranger's address space. Bump it in the same commit as everything
// else in this file, never on its own.
inline std::wstring BUILD_VERSION = L"240-6";

// ---------------------------------------------------------------------------------------------------
// THE ROOT POINTER
// ---------------------------------------------------------------------------------------------------
// *(imageBase + CLIENT_OBJ_PTR) is "the client object" -- the god-object almost everything hangs off.
//
// HOW FOUND (client-240-6): the client's own getStatEffectiveLevel Lua binding reads it. That leaf is
// a `mov rcx, [rip+X]` followed by reads of the skill arrays off rcx -- and those three arrays land
// exactly on the SKILL_* offsets below, which is what makes this the client object, not a neighbour.
// VERIFIED LIVE under Wine: *(base + 0xE95668) is the pointer every live read in this file went through.
inline std::uintptr_t CLIENT_OBJ_PTR = 0xE95668;

// ---------------------------------------------------------------------------------------------------
// FUNCTIONS WE CALL (RVAs from the module base)
// ---------------------------------------------------------------------------------------------------
// The client's own "do a menu action" entry point. We call it instead of building network packets by
// hand: it takes the same arguments the real menu does, and the client builds and sends the packet for
// us. This is why 0xClient does not need to know the wire protocol at all.
//
// Signature (as we use it):
//   void doAction(void* clientObj, int sceneX, int sceneY, int opcode, int targetId,
//                 int a6, long long a7, int itemId, int flags, long long a10)
//
// 0 is the "not derived for this build" sentinel, and game.hpp refuses to act while it is 0. The old
// value was measured on a build months dead and nothing here re-derives it statically: the reliable
// method is hook-and-log (hook the candidate, click in game, read the arguments back), which needs a
// running client. Until someone does that, every action call is a no-op by design -- reading memory
// wrong shows you a wrong number, but CALLING the wrong address crashes the game.
//
// The 240-6 candidate, for whoever does that hook run: FUN_140373F80 (rva 0x373F80) -- it has the old
// DoAction argument SHAPE (obj, a, b, target, flags) and stores into the PENDING_ACTION_* record on the
// client object. But the adversarial pass showed its +0x90/+0x94 args are NOT scene x/y: +0x90 is a
// packed widget id and +0x94 a component index, so this is the widget-menu action path, and the tile
// actions may go through the minimenu entry exec (FUN_14037E990, vtable 0x140BC91B0 slot 1) instead.
// Neither is confirmed against a real click. Hook first, call second.
//
// client-241-3: the same widget-menu path is rva 0x372870 (found by DeriveOffsets.java's pending-action
// rule, which names it in derived.json's notes every build). Its arguments are (this, packedWidgetId,
// componentIndex, targetId, flags, ...) -- not doAction's (client, sceneX, sceneY, opcode, ...) -- so it
// is NOT a drop-in DO_ACTION either. The loader refuses any DO_ACTION that is not derived or verified
// on the running build (client/offsets_json.hpp), so this stays a no-op until a hook-and-log run pins
// the real entry point and its signature.
inline std::uintptr_t DO_ACTION = 0;   // SUPERSEDED by the ACT_* senders below; nothing calls it

// ---------------------------------------------------------------------------------------------------
// ACTIONS -- what replaced DO_ACTION (client/actions.hpp)
// ---------------------------------------------------------------------------------------------------
// This client has no single "do a menu action" function. Each menu entry carries a small callback that
// calls one SENDER per target kind, with the option number as an argument; the sender has the client
// build and queue the packet. Those senders are what 0xClient calls -- with the same arguments the
// client's own callbacks pass, ON THE GAME THREAD, from ACT_TICK:
//
//   ACT_WALK    void walk(void*, int args[3])             args = {level, worldX, worldY}  ("Walk here")
//   ACT_NPC_OP  void npcOp(void*, void* npc, int op, int flag)          op 1..5 = the NPC's options
//   ACT_LOC_OP  void locOp(void*, int id, int args[6], int op, int flag)  args = {level, worldX, worldY,
//                                                                          id, op, flag}; op 1..5
//   ACT_TICK    the client's per-frame tick (the function that increments CYCLE). A hardware execute
//               breakpoint on its entry (client/hwbp.hpp, no code patched) is where queued actions run:
//               the game's own thread, between frames, the same thread every click arrives on.
//
// HOW FOUND (client-241-3, 2026-10-09): a hook-and-log run (client/actionprobe.hpp) caught every click
// in game -- Walk here -> packet 0x37 via the callback at 0x387610 -> 0x387620; Talk-to -> 0x388870 ->
// 0x388910 (op 1); Attack -> 0x389030; Open (a door) -> 0x387cd0 -> 0x387cf0 -- each running on the
// one thread whose stack goes through 0x6b400 (the CYCLE tick). AUTOMATED (DeriveOffsets.java, derive3):
// the packet-start function is the one called as `mov r8,[client+CONN]; add r8,IMM; mov edx,OPCODE;
// call P`; among P's callers, ACT_NPC_OP is the only one that tests its entity argument first, calls P
// with five opcodes and switches on R8D; ACT_LOC_OP the only five-opcode one that switches on R9D;
// ACT_WALK the menu walk -- one opcode, args compared against the three-int last-destination global,
// no third argument. The arguments' layouts were read off the handlers' callers (each copies them from
// its argument block). VERIFIED LIVE 2026-10-09 (client-241-3, a second hook-and-log run): the client's
// own clicks passed walk {plane, x, y}, NPC {uid, option, 0} and object {plane, x, y, id, option, 0}, and
// doAction's walk and NPC options -- same layouts, flag 0 -- were sent from ACT_TICK and acted on in game.
// The object sender's layout matched a real click; doAction has not yet sent one itself.
// 0 = not derived for this build: the loader refuses an unmeasured code RVA, and every action is
// dropped (and says so) rather than calling a stale address.
inline std::uintptr_t ACT_TICK   = 0;
inline std::uintptr_t ACT_WALK   = 0;
inline std::uintptr_t ACT_NPC_OP = 0;
inline std::uintptr_t ACT_LOC_OP = 0;

// The client's world->screen projection leaf. Takes {fineX, fineY, fineZ} and writes {screenX, screenY}.
// "Fine" coordinates are tiles << 7 (i.e. 128 units per tile). It reads the camera out of the client
// object itself, so the first argument is ignored -- pass nullptr.
//
//   float* worldToScreen(void* ignored, float out[2], int fine[3])
//
// HOW FOUND (client-240-6): the Graphics usertype's worldToScreenCoord method. Its closure body is
// this function, and its shape -- same argument order, same out-array write -- matches the leaf the
// previous build used, just with the projection maths now split between two callees it invokes
// internally. OUTPUT SPACE VERIFIED LIVE 2026-09-05 (Windows, logged in): the leaf's final rescale
// read identity (both VIEW_* pairs below == the JagRenderView client size, 1606x900 and 1356x900),
// so what it writes is consistent with canvas pixels in that window's space. POINT ACCURACY NOT
// VERIFIED: the probe projected the tile's south-west corner at height 0 (see jvm.hpp nProject),
// which cannot be judged against the character; the centre probe is the pending test.
inline std::uintptr_t WORLD_TO_SCREEN = 0x2202A0;

// The projection's camera position and canvas-scale pair, read by the leaf above and by its last
// step. HOW FOUND (client-240-6): FUN_1402202a0 (= WORLD_TO_SCREEN) subtracts three ints at
// client+0x895d8 / +0x895dc / +0x895e0 from the fine input before projecting -- the camera position
// in the same fine axis order we pass ({x, height, y}; the axis order is pinned by FUN_1406a8130,
// where the second coordinate only ever reaches the depth and vertical terms). Its final step
// FUN_140618ce0 then rescales the result: x *= (+0x5C)/(+0x20), y *= (+0x60)/(+0x24), all four ints
// hanging off *(client+0x90)+0x10. These exist here only so nProject's probe (jvm.hpp; off unless
// OXC_LOG is set) can print them next to a projected point.
//
// VERIFIED LIVE 2026-09-05 (Windows, logged in, three samples): +0x20/+0x24 read (1606,900) then
// (1356,900), and +0x5C/+0x60 read exactly the same -- i.e. the four ints are TWO (width,height)
// pairs, both equal to the JagRenderView child window's client size printed as canvas= in the same
// line, and the rescale is identity (1606/1606, 900/900). So the leaf returns canvas pixels in that
// window's space and NO ratio correction belongs in projectFine. The old names (BASE_W=0x5C,
// BASE_H=0x20, ...) split each pair across two names and led the probe to print +0x60/+0x5C, a
// height over a width, which read as a bogus 0.56 scale factor; the names below follow the roles the
// decompile note gives them (IN = divisor pair at +0x20/+0x24, OUT = multiplier pair at +0x5C/+0x60).
// Which pair is numerator vs denominator rests on the decompile note only -- moot while both are 1.
// The camera ints are scene-fine (they sat within a tile of the player's sceneX<<7 / sceneY<<7) and
// CAMERA_FINE_H read -852/-801/-849, i.e. the same negative-up height axis the leaf's second input
// uses; the height the terrain sits at in that axis is NOT KNOWN (no heightmap offset is derived).
inline std::uintptr_t CAMERA_FINE_X = 0x895D8;
inline std::uintptr_t CAMERA_FINE_H = 0x895DC;
inline std::uintptr_t CAMERA_FINE_Y = 0x895E0;
// AUTOMATED from client-241-3 (DeriveOffsets.java, derive2): worldToScreenCoord now fetches the view
// through a getter (`mov rax,[rcx+VIEW_OBJ]; ret`) and passes view+VIEW_OBJ_SCALE_BASE to the rescale,
// whose two DIVSS name each pair's numerator and divisor directly.
inline std::uintptr_t VIEW_OBJ            = 0x90;  // -> view object
inline std::uintptr_t VIEW_OBJ_SCALE_BASE = 0x10;  // the scales hang off view+0x10, so a
                                                             // scale address is this base + the VIEW_*
inline std::uintptr_t VIEW_IN_W  = 0x20;   // x rescale denominator (a WIDTH: 1606 live)
inline std::uintptr_t VIEW_IN_H  = 0x24;   // y rescale denominator (a HEIGHT: 900 live)
inline std::uintptr_t VIEW_OUT_W = 0x5C;   // x rescale numerator   (a WIDTH: 1606 live)
inline std::uintptr_t VIEW_OUT_H = 0x60;   // y rescale numerator   (a HEIGHT: 900 live)

// ---------------------------------------------------------------------------------------------------
// FIELDS ON THE CLIENT OBJECT
// ---------------------------------------------------------------------------------------------------
// The scene/world object -- its own fields have their block below.
//
// HOW FOUND (client-240-6): every path that touches the scene dereferences this pointer. The client
// object's constructor (FUN_1400562e0) nulls it with the other scene pointers (`mov [rdi+0xCA90], r14`
// with r14 = 0, at 0x140056FE7); the getMapCoordinate Lua leaf (0x14021C670) is `mov rax,[rip+client];
// mov r8,[rax+0xCA90]; mov eax,[r8+0x18]`; the worldToScreen leaf (FUN_1402202a0) takes the view
// object from *(scene)+0x10; FUN_140381a80 adds tile coords to *(scene)+0x24/+0x28; and the frame
// function (0x1400742c2) loads it a dozen times to run the scene draw passes. VERIFIED LIVE: every
// scene read checked in the GE (SCENE_BASE_X/Y at +0x24/+0x28, the NPC uid array at +0xD0) went
// through this pointer.
inline std::uintptr_t SCENE            = 0xCA90;
inline std::uintptr_t LOCAL_PLAYER_IDX = 0xCC5C;  // your own player handle

// The player handle table: PLAYER_COUNT is how many entries PLAYER_IDS holds, and the ids sit inline
// right after the count (hence array = count + 4). 0xFFFFFFFF = an empty slot.
//
// HOW FOUND (client-240-6): the client resolves these handles through its own player table, over and
// over. FUN_140063a06 is the shape in full: `movsxd r12,[client+0xCCE0]; jle skip; lea
// r15,[client+0xCCE4]`, then per entry `movsxd r8,[r15]; div [pair+0x70]` and a walk of the buckets at
// [pair+0x68] comparing [node+0x00] to the uid and taking [node+0x10] as the entity -- the same
// table-pair/node layout documented in the registry block below. FUN_140088bf0 runs the identical walk
// for the 0xCCE4 array and the scene's NPC uid array (scene+0xD0, count at +0xD8) side by side, and the frame function
// passes &client+0xCCE0 to the player-list builders (0x140073c84, 0x140073cf6). The constructor
// (FUN_1400562e0) builds the structure at 0xCCE0 (count first, ids inline right after), which is why
// the array sits at count+4. VERIFIED LIVE in the GE: the registry walk enumerated exactly the uids
// this array held, local player included.
inline std::uintptr_t PLAYER_COUNT     = 0xCCE0;
inline std::uintptr_t PLAYER_IDS       = 0xCCE4;

// Your stats. Three parallel int arrays of 25, one entry per skill, in the game's own skill order (see
// Skill.java). "Effective" is the boosted/drained number you see in the top of the skill tab; "base" is
// what your xp actually earns you. Hitpoints is index 3, so eff[3] is your current HP and base[3] your
// maximum -- that is the whole health system, and it is why there is no separate HP offset.
//
// The three are 0x64 apart (25 ints), which is a useful check: if you re-derive one, the other two
// should land exactly 100 bytes later.
//
// CONFIRMED UNCHANGED on client-240-6, statically: the client's own getStatEffectiveLevel /
// getStatBaseLevel / getStatXP Lua bindings read exactly these three offsets (each also gates on a
// nonzero dword at client+0x413F34 -- likely "stats loaded"; we do not depend on it).
inline std::uintptr_t SKILL_EFFECTIVE = 0x3360;
inline std::uintptr_t SKILL_BASE      = 0x33C4;
inline std::uintptr_t SKILL_XP        = 0x3428;

// Run energy. NOT DERIVED for client-240-6, and no better number is offered: this line was added on
// 2026-08-10 (commit e03f6e7) while BUILD_ID was still the previous build's 0x64990, with no how-found
// note, and nothing since re-derived it -- the value is carried from an older build. On client-240-6
// the whole .text contains only three accesses with a 0x34D0 displacement (two inside an unrelated
// object constructor at 0x14078f410, one a stack slot), i.e. nothing reads the client object at
// +0x34D0 by a direct [reg+0x34D0] encoding, so runEnergy() in game.hpp is a plausible-looking read of
// an unverified address: treat the number it returns as suspect. There is no energy Lua binding
// either; the only energy string in the binary is the script-event name ON_RUN_ENERGY_TRANSMIT
// (0x140bae408, event id 0x1D in the name table around 0x140badxxx), which suggests the field is
// written from the varp/varbit transmit path. How to re-derive: find the code that raises that event
// and read the client field it copies from, or hook-and-log -- read candidate client fields while
// sprinting until one counts 0..10000.
inline std::uintptr_t RUN_ENERGY = 0x34D0;  // SUSPECT: 0..10000 on the build it came from

// The client's per-frame tick counter. DERIVED STATICALLY; the "+1 per 20ms" cadence is the OSRS frame
// pace and was not re-proven statically -- what the binary shows is one bump per frame callback,
// whatever that frame's length turns out to be.
//
// HOW FOUND (client-240-6): the increment site. FUN_14005aec0 (the per-frame client callback; it also
// reads client+0x2148 and the scene pointer) ends its tick gate with `inc DWORD PTR [rdi+0x2164]` at
// 0x14005AF06, gated on the byte at client+0x4187F0 being clear and on FUN_140031f30() -- the same
// "is the client running" helper the Lua binding registration checks. The frame function (0x1400742c2)
// then reads it (`mov edx,[r14+0x2164]`, 0x140074353) and passes it into the scene draw calls as the
// current tick. It sits directly above GAME_STATE (0x2160), whose isLoggedIn leaf is quoted in that
// entry. NOTE: the client's own getTickCount Lua binding reads client+0x31A8, NOT this -- do not
// anchor a future re-derivation on getTickCount.
inline std::uintptr_t CYCLE      = 0x2164;

// The client's own state machine, next to CYCLE. HOW FOUND (client-240-6): the isLoggedIn Lua leaf is
// literally "load client global; cmpq $-1, 0x3328(%rax); cmpl $0x1e, 0x2160(%rax)" -- it compares this
// dword to 30. There is a dedicated setter at RVA 0x5DB60 (writes +0x2160, switches on the new value).
// Values seen compared against it: 1,2,5,6,10,11,20,25,30,40,45,1000; the Java client's numbering
// (10=title, 20=logging-in, 25=loading, 30=logged-in) fits and 30 is what the field held while we stood
// in the GE. VERIFIED LIVE: read 30 while logged in, and the login form held something else before.
inline std::uintptr_t GAME_STATE = 0x2160;  // int32; 30 = logged in

// The pending menu-action record -- what the game fills when you click a menu entry, and what its
// packet sender reads back. Three ints plus a small state tail -- on the ACTION OBJECT the method below
// is a virtual of, NOT on the client object (client+0x90 is VIEW_OBJ, a pointer; client-241-3 shows the
// method reached only through its vtable at 0x140c7e638/0x140c7e648, with that object as `this`):
//   +0x90 packed widget id (groupId<<16 | componentId -- the same split FUN_1405B64D0 does)
//   +0x94 component index / selector (-1 = "the tile itself, no sub-object")
//   +0x98 target id (npc/player/loc index, as read back by the packet writer)
//   +0x9C u16 sequence, +1 per queued action
//   +0x9E u8 flags,  +0x9F u8 "action pending" (1 when queued)
//
// HOW FOUND (client-240-6): FUN_140373F80 stores its args at exactly these offsets and bumps the seq;
// the packet sender FUN_14038A790 reads the same fields back (+0x90 as 4-byte BE, +0x94 as tri-byte,
// +0x98 as 2-byte BE) and enqueues opcode 0x26 via FUN_140203D30 when connection state == 3. NOTE the
// tempting "scene x / scene y" reading of +0x90/+0x94 is WRONG -- +0x90 is a packed widget id (its high
// half bounds-checks against the interface manager's group count) and +0x94 is an index, not an axis.
// AUTOMATED from client-241-3 (DeriveOffsets.java, derive2): the method is the caller of the IfType
// child lookup whose prologue does `inc word [rcx+SEQ]`, stores EDX/R8D/R9D at the three ints and sets
// the pending byte to 1 -- rva 0x372870 on 241-3, same offsets as 240-6.
// NOT VERIFIED LIVE (needs a hook-and-log against a real click before anything calls into it).
inline std::uintptr_t PENDING_ACTION_PACKED_ID = 0x90;
inline std::uintptr_t PENDING_ACTION_INDEX     = 0x94;
inline std::uintptr_t PENDING_ACTION_TARGET    = 0x98;
inline std::uintptr_t PENDING_ACTION_SEQ       = 0x9C;  // u16
inline std::uintptr_t PENDING_ACTION_PENDING   = 0x9F;  // u8, 1 = action queued

// The world map object. HOW FOUND (client-240-6): the client's own getMapOrigin Lua leaf is
// "mov rax,[rip+X] (the client global); mov rcx,[rax+0x49B8]; movsd xmm0,[rcx+0x54B8]". The object is
// 0x5520 bytes with vtable 0x140BAAD50. Its origin is a MapCoord {int level, int x, int z} at wm+0x54B8.
// VERIFIED LIVE: wm non-null and origin sane while logged in.
//
// The origin is NOT an independent scene base, and the centre ints are NOT an unpinned scroll: the two
// are the same quantity in two encodings, derived exactly from the view's scroll ints
// (view+0x84/0x88 == wm+0x54C4/0x54C8). FUN_1401ce8b0 (tools/decompiled/FUN_1401ce8b0_1ce8b0.c:22-33;
// asm 0x1401ce8e3-0x1401ce944) computes origin = 8*centre - 48 on both axes (`leal -0x30(,%rcx,8)`)
// and stores it into the MapCoord at wm+0x54B8 (setter 0x1405b59b0: edx->level, r8d->x, r9d->z);
// FUN_1401cefe0 (asm 0x1401cf001-0x1401cf05d) does the same for the per-view MapCoord at view+0x58 and
// loads map squares over [(centre-6)>>3, (centre+6)>>3] in centre units. The origin is proven to be in
// WORLD TILES by three independent uses: 0x140075e9a ((client+0x8965c >> 7) + origin.x, then >>6 ->
// map square), 0x1403b02d9 (worldTile - origin) and FUN_1401d0920 ((mapSquare << 6) - origin). Since
// 8*centre lands in the tile domain, one scroll unit is 8 world tiles, and:
//
//     centreTile = 8 * WM_CENTRE = WM_ORIGIN + 48      (inverse: WM_CENTRE = (WM_ORIGIN + 48) / 8)
//
// i.e. the origin is the map centre shifted -48 tiles (the block-rounded load-window corner; 48 = 6
// scroll units). Live cross-check at the GE: scroll (398,429) -> 8*398-48 = 3136, 8*429-48 = 3384,
// exactly the origin pair previously read there. Caveat: the +48 (centre vs window corner) follows
// from the symmetric +-6 load window, not from a live "which tile is under the widget centre"
// measurement -- worth one probe with the map open.
//
// Map zoom is NOT DERIVED: no zoom/scale field exists on the world-map object or its view. wm+0x54C4
// ..0x54DD are fully accounted for (centre, previous centre, per-frame deltas, a u16 flag, a bool) and
// the binary has no map-zoom Lua binding (only widget "modelZoom", which is unrelated). Pixel scaling
// happens in the render path (0x1406334c0 / 0x140634870 / 0x140634b10, which receive tile-space
// coords); the multiplier was not traced. The only candidate seen was FUN_140232aa0's
// ~floor(50*(P + Q/16)) using floats 0.0625f (0x140c4a568) and -50.0f (0x140c4ad1c) -- IF 50.0f is
// fine-coord pixels per tile that implies 2.56 px/tile, but its input unit is unresolved. Do not ship
// a zoom; do not guess one.
inline std::uintptr_t WORLD_MAP              = 0x49B8;  // on the client object
inline std::uintptr_t WM_ORIGIN_LEVEL        = 0x54B8;  // MapCoord on the world map object:
inline std::uintptr_t WM_ORIGIN_X            = 0x54BC;  //   {level, x, z}, in world tiles
inline std::uintptr_t WM_ORIGIN_Z            = 0x54C0;
inline std::uintptr_t WM_CENTRE_X            = 0x54C4;  // int, map centre in 8-tile units
inline std::uintptr_t WM_CENTRE_Z            = 0x54C8;  //   (init -1; centre = origin+48 / 8)

// ---------------------------------------------------------------------------------------------------
// VARPS (client config variables) -- a global, not a client-object field
// ---------------------------------------------------------------------------------------------------
// *(imageBase + VARP_ARRAY_PTR) is the int[] of varp values, indexed by varp id.
//
// HOW FOUND (client-240-6): the ClientState usertype's getVarp binding is three instructions --
// `mov rax, [rip+X]`, `movsxd rcx, edx`, `mov eax, [rax+rcx*4]`. That pointer cell is this offset.
// The varbit decoder confirms it from the other side: it reads the same array (as a direct address,
// imageBase+0x155C520 -- the cell statically points there) when shifting and masking.
inline std::uintptr_t VARP_ARRAY_PTR = 0x155C508;

// The client's own varbit decoder, callable as `unsigned int getVarbit(int varbitId)`. It looks the
// varbit definition up by id, then reads and shifts the varp array -- so it is ground truth and needs
// no definition table on our side. We do NOT call it: its unknown-id path reads an uninitialised
// definition struct, and one garbage mask index is one crash we do not get to debug. The shim decodes
// varbits itself from the varp array plus resources/varbits.csv. Recorded so the option stays visible.
inline std::uintptr_t GET_VARBIT = 0x5B51F0;

// ---------------------------------------------------------------------------------------------------
// ITEM CONTAINERS (inventory, bank, worn...) -- a global open-addressing-ish table
// ---------------------------------------------------------------------------------------------------
// HOW FOUND (client-240-6): the invGetObjId/invGetNum bindings. Both walk the same table: bucket
// `containerId % bucketCount` holds a linked list of container nodes, and the entry at index
// bucketCount is a sentinel the walk stops at.
//
// A node (bytes, from the two bindings' decompilations):
//   +0x00 int    containerId
//   +0x08 ptr    itemIds, first entry      ) (end - start) >> 2 = slot count,
//   +0x10 ptr    itemIds, one past last    )  4 bytes per entry
//   +0x20 ptr    quantities, first entry   ) same shape, parallel array
//   +0x28 ptr    quantities, one past last )
//   +0x38 ptr    next node in the bucket (0 ends the chain)
inline std::uintptr_t CONTAINER_BUCKETS = 0x154C500;  // array of ptr, AT imageBase (not a ptr cell)
inline std::uintptr_t CONTAINER_MASK    = 0x154C508;  // int bucket count; sentinel sits one past it

// Fields on a container node, as listed above. Each pointer field holds the first/one-past-last
// pointer of its array; the id and quantity arrays are parallel, 4 bytes per slot.
inline std::uintptr_t CONTAINER_NODE_IDS      = 0x08;  // ptr field -> item ids, first entry
inline std::uintptr_t CONTAINER_NODE_IDS_END  = 0x10;  // ptr field -> ids, one past last
inline std::uintptr_t CONTAINER_NODE_QTYS     = 0x20;  // ptr field -> quantities, first entry
inline std::uintptr_t CONTAINER_NODE_QTYS_END = 0x28;  // ptr field -> quantities, one past last
inline std::uintptr_t CONTAINER_NODE_NEXT     = 0x38;  // ptr field -> next node in the bucket

// ---------------------------------------------------------------------------------------------------
// THE ENTITY REGISTRY  (fields on the client object; players and NPCs live here, in separate tables)
// ---------------------------------------------------------------------------------------------------
// On this build the scene object holds TILES ONLY -- the old scene+0xB8/0xC0 entity hashtable is gone.
// Entities hang off a map object at client+REGISTRY_MAP, in two levels:
//
//   map+0x20  group heads  (= client+REGISTRY_GROUPS)      -- array of 8-byte heads
//   map+0x28  group count  (= client+REGISTRY_GROUP_COUNT, u64)
//   client+REGISTRY_GROUP_SEL                             -- int key naming the CURRENT group
//
// A group node (walk the chain hanging off a head slot):
//   +0x00 u32  group key (compared against REGISTRY_GROUP_SEL)
//   +0x10 ptr  the group's table pair (the PLAYER_/NPC_ offsets below are fields on it)
//   +0x18 ptr  next group node, 0 ends the chain
//
// The pair holds TWO hash tables with identical node layout, one per entity kind:
//   +0x68 ptr / +0x70 u64   PLAYERS: bucket array, bucket count
//   +0x98 ptr / +0xA0 u64   NPCS:    bucket array, bucket count
// A node lives in bucket (uid % count); the slot at buckets[count] is a 0xFFFF... sentinel, but real
// chains also end in a plain 0, so stopping at 0 is enough.
//
// A node (same shape in both tables):
//   +0x00 u32  uid
//   +0x10 ptr  the ENTITY. NOT +0x08: that is a refcounted wrapper (vtable 0x140b93f28) with no
//              coords -- reading it as the entity was exactly the bug in the first live walk.
//   +0x18 ptr  next node in the bucket, 0 ends it
//
// HOW FOUND (client-240-6): from the client's own accessors, not by shape-guessing. playerFindSelf
// (FUN_1403a9150) gates on client+0xCC5C (the local player index), then FUN_1400a0060 ->
// FUN_14009ffe0 -> FUN_1400ef5e0 resolves map/group, and FUN_1400edb90 walks a table at group+0x68
// counting uids and returning node+0x10 -- the PLAYER table. getNpcIdAll (FUN_1403ae380) reads the
// NPC uid array at scene+0xD0/0xD8, and npcCoord (FUN_1403af380) returns {*(scene+0x18),
// *(entity+0x3F0), *(entity+0x418)}. AUTOMATED from client-241-3 (DeriveOffsets.java, derive2):
// playerFindSelf's resolver gives REGISTRY_GROUP_SEL and, through the two hash-walks it calls (group
// lookup, player-table lookup), GROUP_*, PLAYER_BUCKETS/COUNT and NODE_*; npcName's group walk gives
// NPC_BUCKETS/COUNT; the player-list loop (`movsxd r,[client+COUNT]; lea r,[client+COUNT+4]`, each id
// looked up in the player table) gives PLAYER_COUNT/IDS. VERIFIED LIVE under Wine via /proc/pid/mem at the Grand
// Exchange: the player table enumerated exactly the uids in PLAYER_IDS (local player included), the
// NPC table exactly the 12 uids of scene+0xD0's array -- with sane scene coords, idle animations
// (-1) and orientations in 256-step cardinal values.
inline std::uintptr_t REGISTRY_MAP          = 0xC9C8;  // on the client object: the map object
inline std::uintptr_t REGISTRY_GROUPS       = 0xC9E8;  // = map+0x20: group head array
inline std::uintptr_t REGISTRY_GROUP_COUNT  = 0xC9F0;  // = map+0x28: group count (u64)

// The group key naming the CURRENT group. DERIVED STATICALLY, not confirmed live -- our walk
// enumerates every group, so nothing in the shim reads it.
//
// HOW FOUND (client-240-6): FUN_140078200 -- called from the frame function as (client,
// *(client+SCENE)) at 0x140073c51 -- is the group resolver: `movsxd r9,[rcx+0xCC60]` (this key),
// `div r8d` with the bucket count from [rdx+0xF0], walk the bucket heads at [rdx+0xE8] comparing
// [node+0x00] against the key, next node at [node+0x18], and on a hit take [node+0x10] as the group's
// table pair -- the exact group-node layout documented above. The constructor zero-initialises the
// field (0x14005704d) and 0xCC58/0xCC5C to -1 just before it. One unresolved detail: in that resolver
// the bucket array/count hang off the object passed as rdx, which the caller passes as
// *(client+SCENE) (+0xE8/+0xF0), while the walk we verified live went through client+REGISTRY_MAP
// (+0x20/+0x28 on the map object); if the map object lives at scene+0xC8 the two are the same table,
// but that is not settled statically. The key field itself is not in doubt.
inline std::uintptr_t REGISTRY_GROUP_SEL    = 0xCC60;

inline std::uintptr_t GROUP_TABLE        = 0x10;  // field on a group node: the table pair
inline std::uintptr_t GROUP_NEXT         = 0x18;  // field on a group node
inline std::uintptr_t PLAYER_BUCKETS     = 0x68;  // field on the table pair
inline std::uintptr_t PLAYER_BUCKET_COUNT = 0x70;  // field on the table pair (u64)
inline std::uintptr_t NPC_BUCKETS        = 0x98;  // field on the table pair
inline std::uintptr_t NPC_BUCKET_COUNT   = 0xA0;  // field on the table pair (u64)
inline std::uintptr_t NODE_UID           = 0x00;  // field on an entity node (u32)
inline std::uintptr_t NODE_ENTITY        = 0x10;  // field on an entity node
inline std::uintptr_t NODE_NEXT          = 0x18;  // field on an entity node

// The uid array of the NPCs in the current scene (count at SCENE_NPC_UID_COUNT), read by the client's
// own getNpcIdAll binding. Kept as a cross-check of the registry walk, not used for enumeration.
inline std::uintptr_t SCENE_NPC_UIDS      = 0xD0;  // field on the scene object: int[] of uids
inline std::uintptr_t SCENE_NPC_UID_COUNT = 0xD8;  // field on the scene object

// ---------------------------------------------------------------------------------------------------
// FIELDS ON THE SCENE OBJECT  ( *(clientObj + SCENE) )
// ---------------------------------------------------------------------------------------------------
// The scene's south-west corner in WORLD tiles. Entities carry SCENE coordinates (0..103), so:
//     worldX = SCENE_BASE_X + entity.sceneX
//
// HOW FOUND (client-240-6): the previous build kept these at scene+0x48/0x4C; on this build those
// addresses hold something else entirely -- reading them is what made the panel sit on "waiting for
// the game". Pinned LIVE under Wine while standing at the Grand Exchange: +0x1C/+0x20 hold the scene
// size (104, 104) and +0x24/+0x28 hold (3112, 3440), the GE's world coordinates -- exactly what a
// south-west corner in world tiles should read. VERIFIED LIVE.
// AUTOMATED from client-241-3 (DeriveOffsets.java, derive2): every scene->world conversion in the
// client is `mov r,[client+SCENE]; ... add r2,dword [r+BASE]`; the dword pair the client adds most often
// right after loading the scene pointer is the base (seven sites each on 241-3).
inline std::uintptr_t SCENE_BASE_X = 0x24;
inline std::uintptr_t SCENE_BASE_Y = 0x28;

// ---------------------------------------------------------------------------------------------------
// FIELDS ON AN ENTITY (a player or an NPC)
// ---------------------------------------------------------------------------------------------------
inline std::uintptr_t ENTITY_SCENE_X = 0x3F0;
inline std::uintptr_t ENTITY_SCENE_Y = 0x418;

// The entity's RENDER position: fine units (128 per tile), three consecutive ints {height, x, y}.
// HOW FOUND (client-240-6, live on Windows, logged in, 2026-09-05): the OXC_LOG [proj] probe scanned
// the local player's struct for ints within a tile of the trusted (ENTITY_SCENE_X<<7)+64 and
// (ENTITY_SCENE_Y<<7)+64, and for plausible heights (-1500..-50). The only contiguous triple was
//   +0x1F8 = -312   +0x1FC = 6336   +0x200 = 6720      at scene (49,52) -- x/y exactly the tile centre.
// (Copies: x/y again at +0x268/+0x26C, the height again at +0x788.) The HEIGHT is the prize: the
// terrain heightmap is still unread, but every entity carries its own ground height here, and -312 is
// where the character's feet were on the screenshot while the datum-0 projection sat ~290 canvas px
// too low. VERIFIED LIVE at one spot on plane 0 only; NOT VERIFIED on slopes, stairs or while moving
// (while walking the x/y here should interpolate between tiles -- if they stay at the centre they are
// a tile-derived copy, which is still right for drawing).
// client-241-3: the self-check read 0 at +0x1F8/+0x1FC/+0x200 while logged in, so on that build the
// render position is taken from the client's own npcCoordFine binding instead (DeriveOffsets.java,
// derive2): it reads x/y through a coordinate object at entity+0x260 (+0x8 / +0xC), i.e. +0x268/+0x26C --
// the "copies" above. That binding computes the height from the terrain (rva 0x97960 on 241-3) rather
// than storing it, so ENTITY_FINE_H has no derivation and stays unmeasured.
inline std::uintptr_t ENTITY_FINE_H = 0x1F8;
inline std::uintptr_t ENTITY_FINE_X = 0x1FC;
inline std::uintptr_t ENTITY_FINE_Y = 0x200;

// Which floor an entity is standing on (0..3). NOT RE-DERIVED, and this build's decompile CONTRADICTS
// the value. Provenance: added 2026-08-10 (commit e03f6e7) on the previous build with no note, never
// re-checked. On client-240-6 the entity's own `coord` Lua binding (leaf FUN_1403af050, registered as
// "coord" by the ScriptOps registration FUN_1403a91c0) returns THREE fields off the same resolved
// entity -- {+0x7CC, +0x3F0, +0x418} -- and the last two are the live-verified scene x/y above, so the
// remaining one (the plane) reads entity+0x7CC. Corroboration: FUN_1400a06e0 loads
// `movsxd rax,[entity+0x7CC]` and indexes a 40-byte-stride per-level array with it (rax*5*8 + base) --
// exactly what a plane is for. Nothing on this build reads entity+0x420 as an integer; the only +0x420
// access is FUN_1406a8130 reading a FLOAT on the camera/view object, a different struct. The live walk
// that verified coords/animation/orientation never checked the plane, so 0x420 was never confirmed.
// Candidate replacement: 0x7CC -- verify live (climb a staircase/ladder and watch the field step
// 0..3) before switching; do not flip it on this note alone.
inline std::uintptr_t ENTITY_PLANE   = 0x420;  // SUSPECT: this build points at 0x7CC instead
// The decompile's candidate, as a SEPARATE constant so 0x420 is not flipped on the note alone: the
// `coord` binding (FUN_1403af050) and FUN_1400a06e0 above both read entity+0x7CC as the level.
// Decompile-backed, NOT VERIFIED in-game (2026-09-05). game.hpp reads BOTH: 0x7CC when it is in
// 0..3, else 0x420 when that is, else -1 -- so if 0x7CC is wrong nothing gets worse than before,
// and the OXC_LOG [proj] line prints raw420/raw7CC side by side for the staircase check. Once one
// of them is seen stepping 0..3 on stairs, collapse to a single read and retire the other.
inline std::uintptr_t ENTITY_PLANE_COORD = 0x7CC;
// The reader in game.hpp range-guards the result (0..3, else -1 = unknown) so at least obvious
// garbage is not shipped as a plane; an in-range but wrong value cannot be caught from the number
// alone, which is why the live staircase check above still has to happen before this is trusted.

// The NPC's type id -- what KIND of monster it is, which is what you filter on. It is behind ONE more
// pointer than everything else here:
//
//     typeId = *(int*)( *(void**)(entity + ENTITY_DEF_PTR) )
//
// That extra hop is why a plain memory scan will never find it: the id is not stored in the entity at
// all, only a pointer to the shared definition every NPC of that kind shares. Players have no
// definition here, so this reads as garbage for them -- check isPlayer first.
// NOT re-verified on client-240-6 (the live walk confirmed coords/animation/orientation, not this).
inline std::uintptr_t ENTITY_DEF_PTR = 0x730;

// Names. The client's string type ("NxtString", 24 bytes, used for every name) is:
//   +0x00 char* heap data -- OR the first byte of a 23-byte inline buffer (SSO)
//   +0x08 u64   heap length
//   +0x17 u8    flag: bit7 set = heap (use +0x00 as pointer, +0x08 as length);
//                     clear = inline (length is 0x17 - byte, data inline at +0x00)
// Always NUL-terminated. HOW FOUND: FUN_14004d0e0 (reserve) writes exactly those fields and
// FUN_14004d140 (assign) NUL-terminates both branches.
//
// NPCs: entity+0x710 is an INLINE NxtString holding the NPC's own name override (normally empty), and
// entity+0x730 is the definition whose +0x8 holds the real name (see FUN_1400a2b30, reached from the
// npcName Lua binding). If *(def+0x138) != 0 the name comes from an alternate config instead -- rare,
// we fall back to "" rather than walking that chain. PLAYERS: different layout -- *(entity+0x718) is a
// POINTER to a heap NxtString, pushed directly by the playerName binding; players have no +0x730 def.
// Note the client pads names with U+00A0 (non-breaking space) where the Java client shows ' '.
//
// VERIFIED LIVE in the GE: local player's name came back through +0x718, and every nearby NPC
// ("Banker" x5, "Guard" x2, "Master smithing tutor") through def+0x8.
inline std::uintptr_t ENTITY_NAME_OVERRIDE = 0x710;  // inline NxtString on the entity
inline std::uintptr_t PLAYER_NAME_PTR      = 0x718;  // -> NxtString (players only)
inline std::uintptr_t DEF_NAME             = 0x8;    // NxtString on the NPC definition

// The widget/interface system. It is the classic rs2lib IfType (ctti string in the binary names
// "jag::oldscape::rs2lib::IfType"), NOT NXT's lui system, and the classic OSRS id encoding survives:
// id = (groupId << 16) | componentId. Lookup chain (FUN_1405B64D0 / FUN_1405B65E0, reached from the
// client's own ifType Lua binding -- every step below is quoted instruction-for-instruction in the
// decompile of that chain):
//
//   mgr      = *(client + IFACE_MANAGER)         -- one-instruction getter FUN_1400871B0
//   groupCount = *(u64*)(mgr + IFACE_GROUP_COUNT)
//   groupArray = *(void**)(mgr + IFACE_GROUP_ARRAY)   -- 24-byte group entries:
//                 +0x8 u64 componentCount, +0x10 void* componentData (may be null until lazy-loaded)
//   entry    = componentData + componentId*16       -- 16-byte jag::shared_ptr entries
//   ifType   = *(void**)(entry + 8)                 -- pointee at +8, control block at +0
//   invalid  = ifType == *(imageBase + IFACE_EMPTY_SENTINEL)  (static empty object)
//
// Sub-children of a widget: count at IfType+0xB50, data at IfType+0xB58, same 16-byte entries.
// Text: IfType+0x158 (and +0x170 for the second line) as NxtString-shaped inline-or-pointer with the
// heap flag's bit7 at IfType+0x16F (and +0x187) -- the same read rule as the NxtString above.
//
// AUTOMATED from client-241-3 (DeriveOffsets.java, derive2), and worth knowing because the numbers
// MOVED there: IFACE_MANAGER 0x413BE8 -> 0x413BD8, the group count/array 0x6600/0x6608 -> 0x6888/0x6890,
// the empty sentinel 0x155C5F0 -> 0x155E5E8 -- a file that carried the 240-6 values read nothing. The
// rule: the client getter `mov rax,[rcx+MGR]; ret` whose result is passed straight to the widget lookup
// (the function that splits the packed id with `sar r,0x10`, bounds it against [mgr+COUNT], indexes
// [mgr+COUNT+8] in 24-byte entries and returns the static empty object on a miss); the IfType fields
// come from the IfType usertype registration, which binds each property NAME to its field OFFSET
// (`mov dword [rbp+0x20],OFF; lea rax,["width"]; call register<int>`), and the text fields from the
// NxtString getter lambdas emitted right after that registration. IFTYPE_X/Y are not bound by name:
// they are taken as the two ints between the bound dataHeight and width, accepted only when the table
// has exactly that shape, and the DLL's self-check confirms them (a group root at (0,0), canvas-sized).
//
// VERIFIED LIVE: 970 groups loaded in the GE, sane bounds (canvas-sized 1054x784 on the top-level
// groups), and real strings came back through the text rule ("<col=808080>Chat-channel</col>",
// "Membership: <col=ff0000>None</col>"). The colour fields were derived too but REFUTED in
// adversarial review (registration carries no offsets for them) -- do not add them without re-deriving.
inline std::uintptr_t IFACE_MANAGER       = 0x413BE8;  // on the client object
inline std::uintptr_t IFACE_GROUP_COUNT   = 0x6600;    // u64, on the interface manager
inline std::uintptr_t IFACE_GROUP_ARRAY   = 0x6608;    // -> 24-byte group entries
inline std::uintptr_t IFACE_GROUP_ENTRY_STRIDE = 24;   // bytes per group entry
inline std::uintptr_t IFACE_GROUP_ENTRY_COUNT  = 0x8;  // in the entry: u64 componentCount
inline std::uintptr_t IFACE_GROUP_ENTRY_DATA   = 0x10; // in the entry: -> componentData
inline std::uintptr_t IFACE_EMPTY_SENTINEL = 0x155C5F0;  // global; compare entry+8's
                                                                  // pointee against *(base+this+8)
// A 2026-09-06 probe claimed these were wrong -- every component measuring 1x1 -- and that claim was
// RETRACTED the same day: the probe's own walk read the 16-byte shared_ptr entry at +0 (the control
// block) instead of +8 (the object), which widgetObj() has always done correctly. The rectangle block
// below is not implicated; what a widget's x/y MEAN is still the open question (see the parent-offset
// note in the Remaining limitations section of PROGRESS.md).
inline std::uintptr_t IFTYPE_X            = 0x5C;   // int, relative to the parent
inline std::uintptr_t IFTYPE_Y            = 0x60;
inline std::uintptr_t IFTYPE_WIDTH        = 0x64;
inline std::uintptr_t IFTYPE_HEIGHT       = 0x68;
inline std::uintptr_t IFTYPE_HIDDEN       = 0x78;   // bool
inline std::uintptr_t IFTYPE_CHILDREN_COUNT = 0xB50;  // u64
inline std::uintptr_t IFTYPE_CHILDREN_DATA  = 0xB58;  // -> 16-byte shared_ptr entries
inline std::uintptr_t IFTYPE_TEXT         = 0x158;  // inline-or-char* string; flag:
inline std::uintptr_t IFTYPE_TEXT_FLAG    = 0x16F;  //   bit7 = heap, SSO len = 0x17-flag
inline std::uintptr_t IFTYPE_TEXT2        = 0x170;
inline std::uintptr_t IFTYPE_TEXT2_FLAG   = 0x187;

// ---------------------------------------------------------------------------------------------------
// THE PARENT LINK -- DERIVED AT RUNTIME ON PURPOSE, NOT A NUMBER HERE
// ---------------------------------------------------------------------------------------------------
// A component's IFTYPE_X/IFTYPE_Y are relative to its PARENT, so every absolute (canvas) rectangle in
// the shim is the sum of the component's own x/y and each ancestor's. RuneLite's getCanvasLocation is
// literally that sum, and the client stores the link the sum needs: the if3 cache format decodes a
// 16-bit parent component index and the client widens it to (group << 16) | parentComp, with 0xFFFF
// meaning -1. Everything else the sum needs is already readable here.
//
// WHY THERE IS NO `IFTYPE_PARENT_ID = 0x...` LINE BELOW. Nobody has run a decompiler at this field on
// client-240-6, and this project's rule is that a number in this file is EVIDENCE, not a hypothesis.
// So game.hpp derives the offset live instead, with a whole-tree tally that only accepts an answer
// which holds for EVERY component of EVERY loaded group (oxc::scanWidgetTree). The acceptance rules,
// written down here because they are the derivation:
//
//   idOff         v == ((group << 16) | componentIndex) on every component examined. This field is not
//                 needed for the sum -- it is the POSITIVE CONTROL. The tally is only believable if it
//                 can find a field whose value we already know, using the same rule that finds the
//                 unknown one.
//   parentIdOff   every component reads either -1 (a root) or a value whose high 16 bits are its own
//                 group and whose low 16 bits index inside that group's component array -- and at
//                 least one component reads the latter. Nothing else is allowed at that offset, in any
//                 group. Then the tree it implies is walked from every component: it must be an
//                 acyclic forest that terminates within IFTYPE_CHAIN_MAX steps, which throws out an
//                 unrelated index field that merely happens to look like a packed id.
//   parentPtrOff  the same idea for a C++-shaped link: an 8-byte slot holding either null or a pointer
//                 to ANOTHER component object of the same group. This build is NXT (C++), not the Java
//                 client, so the transliterated IfType may well carry a raw parent pointer where the
//                 Java class carries an int -- the tally looks for both and says which it found.
//
// AMBIGUITY IS A REFUSAL, NOT A COIN FLIP. If two offsets survive all of that, none is used: widgetAbs
// reports complete = 0, Java keeps refusing exactly as it did before, and the probe's report names
// every survivor so a human can pin the right one HERE with the counts as the evidence. Shipping the
// lower offset because it was lower would be a guess wearing a derivation's clothes.
//
// WHAT THIS BLOCK DOES NOT CLAIM, and both matter to whoever reads a wrong rectangle later:
//
//   (i)  SCROLL. RuneLite subtracts each ANCESTOR's scrollX/scrollY while summing. No scroll offset is
//        derived on this build (java Widget.setScrollY stores a number the client never sees), so the
//        sum omits that term. Neither map is affected -- no ancestor of the minimap draw area or of
//        the world-map container scrolls -- but a row inside a scrolled list (quest list, chatbox
//        scrollback) comes out off by the scroll amount. Find them by diffing: read a scrollable
//        container's struct, scroll it a known amount, read again, take the int that moved by exactly
//        that much -- the same "look for a value you already know" method that found the rect block.
//   (ii) CROSS-GROUP PARENTING. parentId is same-group BY THE DECODE RULE, so a group opened onto
//        another group's component (world map 595, bank, any modal) has a root this chain cannot climb
//        past. RuneLite resolves that through the client's component table (a WidgetNode whose hash is
//        the parent component id and whose value is the group id); that table's offset is undiscovered
//        here. The pragmatic reading is that such groups open onto a canvas-sized MAINMODAL at (0,0),
//        which would make the missing term zero -- UNCONFIRMED, and deliberately not assumed: the
//        chain simply terminates at the group root, `complete` still reports 1 for that walk, and the
//        one-shot log line prints 595:7's chain so the reading can be taken rather than guessed.
//
// THE FORK THIS RESTS ON. All of the above assumes IFTYPE_X/Y are the POST-LAYOUT rect (the Java
// client's relativeX/relativeY), not the cache originals -- i.e. that the client has ALREADY applied
// the position/size modes and left the result here. The evidence is in this file: the top-level groups
// read canvas-sized 1054x784 live, and a canvas-sized width is a laid-out value, never a cache
// constant. The runtime self-test in oxc::widgetChainString states the same thing as a measurement
// instead of an argument: a group ROOT must come out abs (0,0) at exactly the canvas size. If it does
// not, these are cache originals, and the position/size MODE bytes and originalX/originalY have to be
// derived and the client's alignment re-implemented -- a much bigger job than this one.
inline std::uintptr_t IFTYPE_SCAN_SPAN = 0x400;  // struct prefix the tally reads per component
inline int            IFTYPE_CHAIN_MAX = 16;     // depth cap on any parent walk

// Both VERIFIED LIVE on client-240-6: every enumerated GE NPC read -1 at 0x4D8 while standing still
// and a cardinal 0/512/1024/1536 at 0x3E0; coords at 0x3F0/0x418 match npcCoord's disasm exactly.
inline std::uintptr_t ENTITY_ANIMATION   = 0x4D8;  // current animation id, -1 when idle
inline std::uintptr_t ENTITY_ORIENTATION = 0x3E0;  // 0..2047, 0 = south, rising clockwise

// Combat level, on a PLAYER entity. NPCs keep theirs on the shared definition instead -- reading this
// on an NPC live returned a pointer fragment (the high half of a heap address), never a level. On the
// local player it read -1, so this offset is simply WRONG on client-240-6. NOT VERIFIED -- re-derive
// from the client's combat-level Lua binding before trusting any number that comes out of here.
inline std::uintptr_t PLAYER_COMBAT_LEVEL = 0x734;

// ---------------------------------------------------------------------------------------------------
// THE LOGIN FORM'S FIELDS -- NOT VERIFIED
// ---------------------------------------------------------------------------------------------------
// Distance from the client's username buffer to its password buffer, so the autologin plugin can SET
// the two fields instead of typing them at a form whose focus it cannot see.
//
// HOW IT WAS FOUND, and it is worth knowing how little that is: nFindString was run live on
// 2026-09-06 for the username the client was already rendering in the Login field. FOUR addresses
// held it. Running the same search for the password paired two of them:
//
//   * one pair 508 bytes apart with BINARY PADDING between the two hits -- the shape of a struct with
//     fixed-size buffers, which is what a login form is. This is the number below.
//   * one pair a short distance apart with PRINTABLE TEXT between them, the text being
//     `","password":"`. That is our OWN profile config.json sitting in the JVM heap, not the client.
//     FieldWriter discards any pair whose gap is printable for exactly this reason -- writing there
//     would corrupt the JVM's heap, not the game.
//
// NOT VERIFIED: nothing has yet confirmed that writing at this delta changes what the form displays.
// The feature is opt-in ("Set the fields directly (experimental)", default OFF) and every write goes
// through nSetLoginField in jvm.hpp, which refuses unless the target is committed, writable, and
// already holds either all zeroes or exactly the value being written. Until a live run shows the form
// filling in by itself, treat this as a hypothesis. (It was one, and it was wrong -- see REFUTED below.)
//
// MIRRORED IN JAVA: oxclient.plugins.autologin.FieldWriter.PASSWORD_DELTA. Nothing checks that the two
// agree -- there is no seam between a C++ constant and a Java one -- so move them together by hand.
// REFUTED, 2026-09-06, by the probe run that followed: 508 was measured while the pair-gap classifier
// read the wrong window (it started inside the password's own bytes whenever the password was longer
// than the username), so it called our profile config.json's `","password":"` gap "binary padding" and
// promoted a JVM-heap document to a struct. With that bug fixed, BOTH pairs classify as TEXT and no
// candidate survives at all: the client's own login buffers were never among the hits. So this number
// is a measurement of our own JSON file, not of the game, and FieldWriter refuses every candidate --
// which is the designed outcome, not a failure. Left here, with its history, because the next attempt
// should start by knowing this one was wrong; the direct-write setting stays off until a probe finds a
// pair whose gap is really binary and whose write really changes the form.
inline std::int32_t LOGIN_PASSWORD_DELTA = 508;

// ---------------------------------------------------------------------------------------------------
// MENU OPCODES
// ---------------------------------------------------------------------------------------------------
// These are the client's INTERNAL menu action numbers, not network opcodes. They are what the game puts
// in its own right-click menu, and handing one to DO_ACTION is exactly equivalent to clicking it.
//
// SINCE client-241-3 these are 0xClient's OWN labels, not game numbers: doAction() (client/actions.hpp)
// maps OP_WALK to ACT_WALK, OPNPC1..5 to ACT_NPC_OP with option 1..5 and OPLOC1 to ACT_LOC_OP with
// option 1. They no longer reach the game, so nothing about them changes per build. The history below
// is kept for whoever reads an older build.
//
// HOW THESE WERE FOUND, and how to confirm another: hook DO_ACTION so it logs its arguments, perform
// the action by hand in game, and read the opcode out of the log. The numbers below were captured that
// way -- by hook-and-log on an OLDER build. They are NOT confirmed on client-240-6, where DO_ACTION is
// 0 and nothing has been hooked yet (see the DO_ACTION block above for the candidate functions a hook
// run should start from), so treat every one of them as unverified until that run happens. Do not
// guess them from a list you found somewhere -- they are per-build and they do get shuffled.
inline int OPLOC1 = 3;   // scenery, first option: Chop down / Mine / Open / Climb...

// NPC options one through five. Attack is normally the first, but not always -- Talk-to is first on a
// shopkeeper -- so a bot that always sends OPNPC1 will happily talk to a cow.
inline int OPNPC1 = 9;
inline int OPNPC2 = 10;
inline int OPNPC3 = 11;
inline int OPNPC4 = 12;
inline int OPNPC5 = 13;

// Walk to a tile. THIRTY-ONE, not twenty-three.
//
// There is a neighbouring opcode that also looks like a walk and is not: it scales the coordinates you
// give it through the viewport ratio before storing them, so feeding it scene tiles walks you to a
// place that has nothing to do with where you asked. This one takes scene tiles directly. If your
// character walks somewhere baffling, this is the first thing to check.
inline int OP_WALK = 31;

}  // namespace oxc::off
