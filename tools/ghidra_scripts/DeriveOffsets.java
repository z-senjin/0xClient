// DeriveOffsets.java -- derive 0xClient's offsets from a client build, mechanically, with evidence.
//
// Run by tools/update/update.py through Ghidra headless:
//
//   analyzeHeadless <proj> osrs -import osclient.exe -scriptPath tools/ghidra_scripts \
//       -postScript DeriveOffsets.java <out.json>
//
// THE METHOD is the one every number in client/offsets.hpp was found with by hand: anchor on a name
// the client gives one of its own Lua bindings ("getVarp", "npcCoord", "worldToScreenCoord"...),
// walk from the string to the registration that binds it, from the registration to the LEAF the
// binding calls, and read the displacement off the instruction that touches the field. No byte
// patterns: a string survives a rebuild; a byte pattern does not.
//
// Three registration shapes carry the leaf (seen identical on client-240-6 and client-241-3):
//
//   ClientState thunks   LEA RDX,[name]; CALL assign; LEA R8,[leaf]; CALL T1(wrapper, state, leaf)
//   specialised T1       LEA RDX,[name]; CALL assign; CALL T1 -- and inside T1: MOV [wrapper+0x10],leaf
//   ScriptOps            LEA RDX,[name]; CALL assign; LEA RDX,[leaf]; CALL register
//   closure before name  LEA RAX,[leaf]; MOV [stack],RAX; ... LEA RAX,[name]        (getMapCoordinate)
//
// Every value written carries the anchor, the leaf address and the instruction it was read from, so
// a wrong number can be argued with. Anything a rule cannot establish is simply absent from the
// output: update.py then carries the previous build's value and LABELS it carried. Never guess here.
//
// A debug listing of every leaf this script looked at goes next to the output (<out>.debug.txt);
// when a build changes a shape, that listing is what to read to extend the rules below.
//
//@category 0xClient
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.Address;
import ghidra.program.model.listing.Function;
import ghidra.program.model.listing.Instruction;
import ghidra.program.model.mem.MemoryBlock;
import ghidra.program.model.symbol.Reference;
import ghidra.program.util.DefinedDataIterator;

import java.io.File;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class DeriveOffsets extends GhidraScript {

    private long base;
    private MemoryBlock text;
    private final Map<String, Map<String, Object>> out = new LinkedHashMap<>();
    private final StringBuilder debug = new StringBuilder();
    private final List<String> notes = new ArrayList<>();

    // --- a memory operand, parsed from Ghidra's text: [BASE + INDEX*SCALE + DISP]
    static final class Mem {
        String base = "", index = "";
        long scale = 1, disp = 0;
        boolean hasDisp;
        Address absolute; // RIP-relative / absolute, resolved by Ghidra
    }

    @Override
    public void run() throws Exception {
        base = currentProgram.getImageBase().getOffset();
        text = currentProgram.getMemory().getBlock(".text");
        String[] args = getScriptArgs();
        File outFile = args.length > 0 ? new File(args[0])
                : new File(new File(getSourceFile().getAbsolutePath()).getParentFile().getParentFile(), "derived.json");

        try { derive(); } catch (Exception e) { notes.add("derivation aborted: " + e); printerr("" + e); }

        String sha = sha256(currentProgram.getExecutablePath());
        try (PrintWriter w = new PrintWriter(outFile, "UTF-8")) {
            w.println("{");
            w.println("  \"source\": \"DeriveOffsets.java\",");
            w.println("  \"sha256\": \"" + sha + "\",");
            w.println("  \"image_base\": " + base + ",");
            w.println("  \"notes\": \"" + esc(String.join(" | ", notes)) + "\",");
            w.println("  \"offsets\": {");
            int i = 0;
            for (Map.Entry<String, Map<String, Object>> e : out.entrySet()) {
                Object v = e.getValue().get("value");
                w.print("    \"" + e.getKey() + "\": {\"value\": " + v + ", \"evidence\": \"" + esc("" + e.getValue().get("evidence")) + "\"}");
                w.println(++i < out.size() ? "," : "");
            }
            w.println("  }");
            w.println("}");
        }
        try (PrintWriter w = new PrintWriter(new File(outFile.getPath() + ".debug.txt"), "UTF-8")) {
            w.print(debug);
        }
        println("wrote " + outFile.getAbsolutePath() + " (" + out.size() + " offsets)");
    }

    // ===========================================================================================
    // The rules. Each one is a few lines because the work is in the helpers below.
    // ===========================================================================================
    private void derive() {
        // ---- the registration that carries "getVarp" IS the build fingerprint
        Instruction varpRef = firstRef("getVarp");
        if (varpRef != null) {
            Function f = getFunctionContaining(varpRef.getAddress());
            if (f != null) put("BUILD_ID", rva(f.getEntryPoint()), "entry of the ClientState registration, the function that references \"getVarp\"");
        }

        // ---- varps: getVarp leaf is `mov rax,[rip+VARP]; movsxd rcx,edx; mov eax,[rax+rcx*4]`
        List<Instruction> varp = leaf("getVarp");
        Instruction ld = firstRipLoad(varp);
        if (ld != null) put("VARP_ARRAY_PTR", rva(ripTarget(ld)), "getVarp leaf: " + at(ld));

        // ---- the varbit decoder: getVarbit leaf is a thunk `mov ecx,edx; jmp DECODER`
        List<Instruction> varbit = leaf("getVarbit");
        Address jmp = thunkTarget(varbit);
        if (jmp != null) put("GET_VARBIT", rva(jmp), "getVarbit leaf is a thunk; its jump target: " + at(varbit.get(varbit.size() - 1)));

        // ---- the client object and the three skill arrays
        for (String[] s : new String[][]{{"getStatEffectiveLevel", "SKILL_EFFECTIVE"}, {"getStatBaseLevel", "SKILL_BASE"}, {"getStatXP", "SKILL_XP"}}) {
            List<Instruction> l = leaf(s[0]);
            Instruction c = firstRipLoad(l);
            if (c != null && !out.containsKey("CLIENT_OBJ_PTR"))
                put("CLIENT_OBJ_PTR", rva(ripTarget(c)), s[0] + " leaf loads the client object: " + at(c));
            Instruction idx = firstScaledLoad(l, 4);
            if (idx != null) put(s[1], mem(idx).disp, s[0] + " leaf reads the array: " + at(idx));
        }
        long client = val("CLIENT_OBJ_PTR");

        // ---- game state: isLoggedIn leaf compares the state dword to 30
        List<Instruction> logged = leaf("isLoggedIn");
        Instruction cmp30 = firstMatch(logged, "CMP", m -> m.hasDisp && m.disp > 0x100, "0x1e");
        if (cmp30 != null) {
            put("GAME_STATE", mem(cmp30).disp, "isLoggedIn leaf compares it to 30: " + at(cmp30));
            long cycle = mem(cmp30).disp + 4;
            Instruction inc = findInc(cycle);
            if (inc != null) put("CYCLE", cycle, "GAME_STATE+4, confirmed by the per-frame increment " + at(inc));
            else notes.add("CYCLE: no `inc dword ptr [reg+0x" + Long.toHexString(cycle) + "]` found; not derived");
        }

        // ---- the scene pointer: getMapCoordinate leaf is `mov rax,[rip+client]; mov r8,[rax+SCENE]; mov eax,[r8+0x18]`
        List<Instruction> mapc = leaf("getMapCoordinate");
        Instruction scene = loadAfterClient(mapc, client, 0x1000);
        if (scene != null) put("SCENE", mem(scene).disp, "getMapCoordinate leaf: " + at(scene));

        // ---- the world map object and its origin
        List<Instruction> origin = leaf("getMapOrigin");
        Instruction wm = loadAfterClient(origin, client, 0x1000);
        if (wm != null) {
            put("WORLD_MAP", mem(wm).disp, "getMapOrigin leaf: " + at(wm));
            Instruction movsd = firstMatch(origin, "MOVSD", m -> m.hasDisp && m.disp > 0x1000, null);
            if (movsd == null) movsd = firstMatch(origin, "MOV", m -> m.hasDisp && m.disp > 0x1000 && !m.base.isEmpty() && !m.base.equals(mem(wm).base), null);
            if (movsd != null) {
                long o = mem(movsd).disp;
                put("WM_ORIGIN_LEVEL", o, "getMapOrigin leaf reads the MapCoord: " + at(movsd));
                put("WM_ORIGIN_X", o + 4, "MapCoord {level, x, z}: origin + 4");
                put("WM_ORIGIN_Z", o + 8, "MapCoord {level, x, z}: origin + 8");
                put("WM_CENTRE_X", o + 0xC, "the centre pair sits right after the MapCoord (origin + 0xC), same layout as 240-6");
                put("WM_CENTRE_Z", o + 0x10, "origin + 0x10");
            }
        }

        // ---- world->screen: the Graphics usertype's worldToScreenCoord leaf is the projection itself
        Address w2s = leafAddress("worldToScreenCoord");
        if (w2s != null) {
            put("WORLD_TO_SCREEN", rva(w2s), "Graphics.worldToScreenCoord leaf (stored at wrapper+0x10 by its T1)");
            // The camera: three consecutive ints the projection reads off the client object and
            // subtracts from the fine input. The view object is the first qword it takes off the
            // client (a small displacement).
            List<Instruction> body = listing(w2s, 600);
            dump("worldToScreenCoord body", body);
            TreeMap<Long, Instruction> reads = new TreeMap<>();
            Instruction view = null;
            for (Instruction i : body) {
                if (!i.getMnemonicString().equals("MOV")) continue;
                Mem m = memOf(i);
                if (m == null || !m.hasDisp || !m.index.isEmpty() || !isClientBase(body, m, i)) continue;
                if (i.toString().contains("dword ptr") && m.disp > 0x1000) reads.put(m.disp, i);
                if (view == null && i.toString().contains("qword ptr") && m.disp < 0x1000) view = i;
            }
            for (Long d : reads.keySet()) {
                if (reads.containsKey(d + 4) && reads.containsKey(d + 8)) {
                    put("CAMERA_FINE_X", d, "three consecutive ints the projection reads off the client: " + at(reads.get(d)));
                    put("CAMERA_FINE_H", d + 4, at(reads.get(d + 4)));
                    put("CAMERA_FINE_Y", d + 8, at(reads.get(d + 8)));
                    break;
                }
            }
            if (view != null) put("VIEW_OBJ", mem(view).disp, "the projection's view object, first qword it takes off the client: " + at(view));
        }

        // ---- entities: npcCoord / playerCoord / coord
        List<Instruction> npcCoord = leaf("npcCoord", 260);
        List<Instruction> coord = leaf("coord", 260);
        Instruction reg = firstMatch(npcCoord, "LEA", m -> m.hasDisp && m.disp > 0x1000 && m.index.isEmpty(), null);
        if (reg != null && isClientReg(npcCoord, reg)) {
            long r = mem(reg).disp;
            put("REGISTRY_MAP", r, "npcCoord leaf hands the registry map (client+disp) to the lookup: " + at(reg));
            put("REGISTRY_GROUPS", r + 0x20, "map+0x20 (group head array), layout as on 240-6");
            put("REGISTRY_GROUP_COUNT", r + 0x28, "map+0x28 (group count), layout as on 240-6");
        }
        // npcCoord returns {scene+0x18, entity+X, entity+Y}: the X read is stored to result+4 and
        // the Y read to result+8, so the store that follows each load names the field.
        for (int k = 0; k + 1 < npcCoord.size(); k++) {
            Instruction rd = npcCoord.get(k), st = npcCoord.get(k + 1);
            Mem a = memOf(rd), b = memOf(st);
            if (a == null || b == null || !rd.getMnemonicString().equals("MOV") || !st.getMnemonicString().equals("MOV")) continue;
            if (!rd.toString().contains("dword ptr") || !a.hasDisp || a.disp < 0x100 || !st.getDefaultOperandRepresentation(0).contains("[")) continue;
            if (b.hasDisp && b.disp == 4 && !out.containsKey("ENTITY_SCENE_X")) put("ENTITY_SCENE_X", a.disp, "npcCoord leaf reads it into the result's x slot: " + at(rd));
            if (b.hasDisp && b.disp == 8 && !out.containsKey("ENTITY_SCENE_Y")) put("ENTITY_SCENE_Y", a.disp, "npcCoord leaf reads it into the result's y slot: " + at(rd));
        }
        Set<Long> npcFields = dwordFields(npcCoord, 0x100, 0x1000);
        Set<Long> coordFields = dwordFields(coord, 0x100, 0x1000);
        long ex = val("ENTITY_SCENE_X"), ey = val("ENTITY_SCENE_Y");
        for (Long d : coordFields) if (!npcFields.contains(d) && d != ex && d != ey) { put("ENTITY_PLANE_COORD", d, "the one dword the coord leaf reads off the entity that npcCoord does not (its level)"); break; }
        // (The render position is NOT taken from these two leaves any more: on client-241-3 the triple this
        //  heuristic picked (+0x1F8..0x200) read 0 in game. derive2() reads it off npcCoordFine instead.)
        if (!out.containsKey("ENTITY_SCENE_X")) notes.add("entity coords: npcCoord fields " + hex(npcFields) + ", coord fields " + hex(coordFields) + " -- no load/store pair found");

        // ---- getNpcIdAll walks the registry (client+GROUPS / client+GROUP_COUNT) and, per group,
        //      reads the NPC uid array (table+UIDS, count at +8)
        List<Instruction> uids = leaf("getNpcIdAll", 300);
        TreeMap<Long, Instruction> creads = new TreeMap<>();
        for (Instruction i : uids) {
            Mem m = memOf(i);
            if (m != null && m.hasDisp && m.index.isEmpty() && m.disp > 0x1000 && i.getMnemonicString().equals("MOV") && isClientBase(uids, m)) creads.put(m.disp, i);
        }
        if (creads.size() >= 2) {
            long g = creads.firstKey(), c = creads.lastKey();
            if (c == g + 8) {
                put("REGISTRY_GROUPS", g, "getNpcIdAll leaf walks the group heads: " + at(creads.get(g)));
                put("REGISTRY_GROUP_COUNT", c, "getNpcIdAll leaf bounds the walk: " + at(creads.get(c)));
                if (!out.containsKey("REGISTRY_MAP")) put("REGISTRY_MAP", g - 0x20, "group heads sit at map+0x20 (layout as on 240-6)");
            }
        }
        TreeMap<Long, Instruction> pairs = new TreeMap<>();
        for (Instruction i : uids) {
            Mem m = memOf(i);
            if (m != null && m.hasDisp && m.index.isEmpty() && m.disp >= 0x10 && m.disp < 0x400 && !m.base.equals("RSP") && !m.base.equals("RBP") || (m != null && m.hasDisp && m.base.equals("RBP") && m.disp > 0x10 && m.disp < 0x400)) pairs.put(m.disp, i);
        }
        for (Long d : pairs.keySet()) {
            Instruction a = pairs.get(d), b = pairs.get(d + 8);
            if (b == null) continue;
            if (a.toString().contains("qword ptr") && b.toString().contains("dword ptr") && b.getMnemonicString().equals("CMP")) {
                put("SCENE_NPC_UIDS", d, "getNpcIdAll leaf reads the uid array: " + at(a));
                put("SCENE_NPC_UID_COUNT", d + 8, "and its count: " + at(b));
                break;
            }
        }

        // ---- the local player handle: playerFindSelf gates on client+LOCAL_PLAYER_IDX
        List<Instruction> self = leaf("playerFindSelf", 120);
        Instruction gate = firstMatch(self, "CMP", m -> m.hasDisp && m.disp > 0x1000, "-0x1");
        if (gate == null) gate = firstMatch(self, "MOV", m -> m.hasDisp && m.disp > 0x1000 && isClientBase(self, m), null);
        if (gate != null && isClientBase(self, mem(gate))) put("LOCAL_PLAYER_IDX", mem(gate).disp, "playerFindSelf leaf: " + at(gate));

        // ---- names. playerName reads the player's name pointer itself; npcName hands the entity to
        //      a resolver, which reads the inline override (entity+OVERRIDE, flag byte at +0x17) and
        //      otherwise the definition (entity+DEF, name NxtString at def+8).
        List<Instruction> npcName = leaf("npcName", 300);
        List<Instruction> playerName = leaf("playerName", 300);
        Instruction pn = firstMatch(playerName, "MOV", m -> m.hasDisp && m.index.isEmpty() && m.disp >= 0x400 && m.disp < 0x1000, null);
        if (pn != null) put("PLAYER_NAME_PTR", mem(pn).disp, "playerName leaf: " + at(pn));
        for (Address callee : callees(npcName)) {
            List<Instruction> body = listing(callee, 400);
            TreeMap<Long, Instruction> fields = new TreeMap<>();
            for (Instruction i : body) {
                Mem m = memOf(i);
                if (m != null && m.hasDisp && m.index.isEmpty() && m.disp >= 0x400 && m.disp < 0x1000 && !m.base.equals("RSP") && !m.base.equals("RBP")) fields.put(m.disp, i);
            }
            if (fields.isEmpty()) continue;
            dump("npcName resolver rva 0x" + Long.toHexString(rva(callee)), body);
            // the definition pointer is loaded as a qword and then dereferenced
            Long def = null;
            for (Map.Entry<Long, Instruction> e : fields.entrySet())
                if (e.getValue().toString().contains("qword ptr") && e.getValue().getMnemonicString().equals("MOV")) def = e.getKey();
            if (def == null) continue;
            put("ENTITY_DEF_PTR", def, "npcName's resolver loads the definition: " + at(fields.get(def)));
            // the inline override: either its data (LEA/MOV at +0) or its SSO flag byte (+0x17) is touched
            for (Map.Entry<Long, Instruction> e : fields.entrySet()) {
                long d = e.getKey();
                if (d == def) continue;
                long start = e.getValue().toString().contains("byte ptr") ? d - 0x17 : d;
                if (start < def && start >= def - 0x40) { put("ENTITY_NAME_OVERRIDE", start, "npcName's resolver checks the inline override: " + at(e.getValue())); break; }
            }
            break;
        }

        // ---- interface manager: ifType's chain starts with a one-instruction getter `mov rax,[rcx+IFACE_MANAGER]`
        List<Instruction> ift = leaf("ifType", 200);
        for (Instruction i : ift) {
            if (!i.getMnemonicString().equals("CALL")) continue;
            Address t = callTarget(i);
            if (t == null) continue;
            List<Instruction> g = listing(t, 4);
            if (g.size() >= 2 && g.get(0).getMnemonicString().equals("MOV") && g.get(1).getMnemonicString().equals("RET")) {
                Mem m = memOf(g.get(0));
                if (m != null && m.hasDisp && m.disp > 0x10000) { put("IFACE_MANAGER", m.disp, "one-instruction getter called from the ifType leaf: " + at(g.get(0))); break; }
            }
        }

        // ---- the rest is derive2(): rules added for client-241-3, each anchored on a name the client
        //      binds or on the call graph out of one, the same discipline as above.
        try { derive2(); } catch (Exception e) { notes.add("derive2 aborted: " + e); printerr("derive2: " + e); }
        try { derive3(); } catch (Exception e) { notes.add("derive3 aborted: " + e); printerr("derive3: " + e); }
        try { derive4(); } catch (Exception e) { notes.add("derive4 aborted: " + e); printerr("derive4: " + e); }

        // ---- item containers: the invGetObjId leaf is a Lua trampoline; the one function it calls
        //      directly is the implementation, which walks a global bucket table (count, then array)
        //      The lookup: `mov r10,[COUNT]; ... div r8; mov rdx,[BUCKETS]; mov rax,[rdx+rax*8]`, then
        //      a chain walk `cmp ecx,[rax]; mov rax,[rax+NEXT]`, then `mov rdx,[rax+IDS]; mov rcx,[rax+IDS_END];
        //      sub rcx,rdx`. invGetNum has the same shape over the quantity arrays.
        for (String[] s : new String[][]{{"invGetObjId", "CONTAINER_NODE_IDS", "CONTAINER_NODE_IDS_END"}, {"invGetNum", "CONTAINER_NODE_QTYS", "CONTAINER_NODE_QTYS_END"}}) {
            List<Instruction> inv = leaf(s[0], 200);
            for (Address callee : callees(inv, 2)) {
                List<Instruction> body = listing(callee, 300);
                List<Instruction> globals = new ArrayList<>();
                for (Instruction i : body) {
                    Mem m = memOf(i);
                    if (m != null && m.absolute != null && i.getMnemonicString().equals("MOV") && m.index.isEmpty()) globals.add(i);
                }
                boolean hasDiv = false;
                for (Instruction i : body) if (i.getMnemonicString().equals("DIV") || i.getMnemonicString().equals("IDIV")) hasDiv = true;
                if (globals.size() < 2 || !hasDiv) continue;
                dump(s[0] + " implementation rva 0x" + Long.toHexString(rva(callee)), body);
                if (!out.containsKey("CONTAINER_MASK")) {
                    put("CONTAINER_MASK", rva(mem(globals.get(0)).absolute), "container lookup reads the bucket count first: " + at(globals.get(0)));
                    put("CONTAINER_BUCKETS", rva(mem(globals.get(1)).absolute), "then the bucket array pointer: " + at(globals.get(1)));
                }
                for (int k = 0; k + 2 < body.size(); k++) {
                    Instruction a = body.get(k), b = body.get(k + 1), c = body.get(k + 2);
                    Mem ma = memOf(a), mb = memOf(b);
                    if (ma != null && mb != null && a.getMnemonicString().equals("MOV") && b.getMnemonicString().equals("MOV") && c.getMnemonicString().equals("SUB")
                            && ma.hasDisp && mb.hasDisp && ma.base.equals(mb.base) && mb.disp == ma.disp + 8 && a.toString().contains("qword ptr")) {
                        put(s[1], ma.disp, s[0] + ": the array's first-entry pointer on the node: " + at(a));
                        put(s[2], mb.disp, s[0] + ": one-past-last pointer on the node: " + at(b));
                        break;
                    }
                    if (!out.containsKey("CONTAINER_NODE_NEXT") && a.getMnemonicString().equals("MOV") && ma != null && ma.hasDisp && ma.disp > 0x10
                            && a.getDefaultOperandRepresentation(0).equals(ma.base) && a.toString().contains("qword ptr"))
                        put("CONTAINER_NODE_NEXT", ma.disp, "the chain walk advances through this field: " + at(a));
                }
                break;
            }
        }
    }

    // ===========================================================================================
    // Rules added for client-241-3. Each one was worked out by hand on that build (the evidence each
    // writes quotes the instruction it read), then written down here so the next build is automatic.
    // ===========================================================================================
    private void derive2() {
        long client = val("CLIENT_OBJ_PTR");

        // ---- IfType: the usertype registration is a TABLE of (field offset, name) pairs. Each property
        //      is registered as `MOV dword [RBP+0x20],OFFSET; ...; LEA RAX,[name]; ...; CALL register<T>`
        //      -- the client stating its own struct layout, one field per name.
        Function ifReg = functionReferencing("osrs.IfType");
        Map<String, long[]> iftab = ifReg == null ? new LinkedHashMap<>() : propertyTable(ifReg);
        if (iftab.containsKey("width") && iftab.containsKey("height") && iftab.containsKey("hidden")) {
            long w = iftab.get("width")[0], h = iftab.get("height")[0];
            String where = "IfType registration rva 0x" + Long.toHexString(rva(ifReg.getEntryPoint()));
            put("IFTYPE_WIDTH", w, where + " binds \"width\" to this offset (" + hexAt(iftab.get("width")[1]) + ")");
            put("IFTYPE_HEIGHT", h, where + " binds \"height\" to this offset (" + hexAt(iftab.get("height")[1]) + ")");
            put("IFTYPE_HIDDEN", iftab.get("hidden")[0], where + " binds \"hidden\" to this offset (" + hexAt(iftab.get("hidden")[1]) + ")");
            // x/y are not bound by name. The laid-out rect is {x, y, width, height}, contiguous, right
            // after the bound cache rect {dataX, dataY, dataWidth, dataHeight}: accepted only when the
            // table shows exactly that shape (dataHeight + 0xC == width, height == width + 4).
            if (iftab.containsKey("dataHeight") && iftab.get("dataHeight")[0] + 0xC == w && h == w + 4) {
                put("IFTYPE_X", w - 8, "the two unbound ints between the bound dataHeight (0x" + Long.toHexString(w - 0xC)
                        + ") and width (0x" + Long.toHexString(w) + "): the laid-out rect {x, y, width, height}");
                put("IFTYPE_Y", w - 4, "x + 4 in the same rect (see IFTYPE_X)");
            }
            // String properties (text, text2...) are registered without an offset; their getters are
            // lambdas emitted right after the registration, each of the NxtString shape
            // `movzx eax,byte [rcx+S+0x17]; shr al,7; ...; mov rax,[rcx+S]; ret; lea rax,[rcx+S]; ret`.
            // The registration's last two string properties are "text" then "text2", and their lambdas
            // are the last two such getters, 0x18 apart (two consecutive NxtStrings).
            List<long[]> sso = ssoGettersAfter(ifReg, 0x1000);
            List<String> names = new ArrayList<>(iftab.keySet());
            if (sso.size() >= 2 && names.indexOf("text2") > names.indexOf("text") && names.indexOf("text") >= 0) {
                long[] a = sso.get(sso.size() - 2), b = sso.get(sso.size() - 1);
                if (b[0] == a[0] + 0x18) {
                    put("IFTYPE_TEXT", a[0], "the \"text\" property's getter lambda after the IfType registration: " + at(getInstructionAt(toAddr(a[1]))));
                    put("IFTYPE_TEXT_FLAG", a[0] + 0x17, "the same getter tests bit 7 of this byte (the NxtString heap flag)");
                    put("IFTYPE_TEXT2", b[0], "the \"text2\" property's getter lambda: " + at(getInstructionAt(toAddr(b[1]))));
                    put("IFTYPE_TEXT2_FLAG", b[0] + 0x17, "the same getter tests bit 7 of this byte");
                }
            }
        } else {
            notes.add("IfType: no property table (registration " + (ifReg == null ? "not found" : "has no width/height/hidden") + ")");
        }

        // ---- the interface manager and its group table. The client object has a one-instruction getter
        //      `mov rax,[rcx+MGR]; ret`; its result goes straight into the widget lookup, which splits the
        //      packed id (`sar r,0x10` / `movzx r,dx`), bounds the group against [mgr+COUNT], indexes
        //      [mgr+COUNT+8] with 24-byte entries (`lea rdx,[rax+rax*2]` then `*8`), reads the entry's
        //      component count at +8 and data at +0x10, and returns the static empty object on failure.
        //      Anchor: call-graph position -- the getter's result is the lookup's first argument.
        WidgetLookup wl = findWidgetLookup(client);
        if (wl != null) {
            put("IFACE_MANAGER", wl.mgr, "one-instruction getter " + at(wl.getterInsn) + " whose result is passed to the widget lookup at rva 0x" + Long.toHexString(rva(wl.lookup)));
            put("IFACE_GROUP_COUNT", wl.count, "widget lookup bounds the group id: " + at(wl.cmpInsn));
            put("IFACE_GROUP_ARRAY", wl.count + 8, "widget lookup indexes the group array: " + at(wl.arrInsn));
            put("IFACE_GROUP_ENTRY_STRIDE", 24, "widget lookup scales the group index by 3 then 8: " + at(wl.leaInsn));
            if (wl.entryCount >= 0) put("IFACE_GROUP_ENTRY_COUNT", wl.entryCount, "widget lookup bounds the component index: " + at(wl.entryCountInsn));
            if (wl.entryData >= 0) put("IFACE_GROUP_ENTRY_DATA", wl.entryData, "widget lookup reads the component array: " + at(wl.entryDataInsn));
            if (wl.sentinel != null) put("IFACE_EMPTY_SENTINEL", rva(wl.sentinel), "widget lookup returns this static empty object on a miss: " + at(wl.sentinelInsn));
            // Sub-children: the function that calls the lookup, takes the component (+8 of the 16-byte
            // entry) and bounds an index against [comp+COUNT] before adding [comp+COUNT+8].
            for (Reference r : getReferencesTo(wl.lookup)) {
                Function f = getFunctionContaining(r.getFromAddress());
                if (f == null) continue;
                List<Instruction> body = listing(f.getEntryPoint(), 60);
                Instruction cmp = null;
                for (Instruction i : body) {
                    Mem m = memOf(i);
                    if (m == null || !m.hasDisp || m.disp < 0x200) continue;
                    if (i.getMnemonicString().equals("CMP") && i.toString().contains("qword ptr")) cmp = i;
                    if (cmp != null && i.getMnemonicString().equals("ADD") && m.disp == mem(cmp).disp + 8) {
                        put("IFTYPE_CHILDREN_COUNT", mem(cmp).disp, "child lookup (rva 0x" + Long.toHexString(rva(f.getEntryPoint())) + ", calls the widget lookup) bounds the child index: " + at(cmp));
                        put("IFTYPE_CHILDREN_DATA", m.disp, "and adds the child array: " + at(i));
                        break;
                    }
                }
                if (out.containsKey("IFTYPE_CHILDREN_COUNT")) break;
            }
        } else {
            notes.add("interface manager: no getter whose result feeds a widget lookup");
        }

        // ---- the entity registry, through playerFindSelf. Its leaf gates on LOCAL_PLAYER_IDX and calls
        //      a resolver R1. R1 calls G -- `mov edx,[rcx+SEL]; add rcx,MAP; jmp GROUP_LOOKUP` -- and then
        //      the player-table lookup with [client+LOCAL_PLAYER_IDX]. Both lookups are the same hash-walk
        //      shape: bucket count and array off the table, `div`, `cmp key,[node]`, `mov rax,[node+NEXT]`,
        //      and the value `mov rax,[node+VALUE]` on a hit.
        List<Instruction> self = leaf("playerFindSelf", 120);
        Address r1 = null;
        for (Instruction i : self) if (i.getMnemonicString().equals("CALL") && callTarget(i) != null) { r1 = callTarget(i); break; }
        if (r1 != null) {
            List<Instruction> rb = linear(r1, 20);
            dump("playerFindSelf resolver rva 0x" + Long.toHexString(rva(r1)), rb);
            Address g = null, t = null;
            for (Instruction i : rb) {
                String mn = i.getMnemonicString();
                if ((mn.equals("CALL") || mn.equals("JMP")) && i.getFlows().length > 0 && text.contains(i.getFlows()[0])) {
                    if (g == null) g = i.getFlows()[0]; else if (t == null) t = i.getFlows()[0];
                }
            }
            if (g != null) {
                List<Instruction> gb = linear(g, 6);
                dump("registry group resolver entry rva 0x" + Long.toHexString(rva(g)), gb);
                Instruction sel = firstMatch(gb, "MOV", m -> m.hasDisp && m.disp > 0x1000, null);
                Instruction add = null;
                for (Instruction i : gb) if (i.getMnemonicString().equals("ADD") && i.toString().startsWith("ADD RCX,0x")) add = i;
                if (sel != null) put("REGISTRY_GROUP_SEL", mem(sel).disp, "the registry resolver keys the group lookup on it: " + at(sel));
                Address gl = null;
                for (Instruction i : gb) if (i.getMnemonicString().equals("JMP") || i.getMnemonicString().equals("CALL")) { gl = i.getFlows().length > 0 ? i.getFlows()[0] : null; }
                if (add != null && gl != null) {
                    long map = Long.parseLong(i2s(add).replaceAll(".*,0x", ""), 16);
                    HashWalk hw = hashWalk(listing(gl, 60));
                    if (hw != null && hw.valueInsn != null && hw.buckets == 0x20 && hw.count == 0x28 && map == val("REGISTRY_MAP")) {
                        put("GROUP_NEXT", hw.next, "registry group lookup (rva 0x" + Long.toHexString(rva(gl)) + ") walks the chain: " + at(hw.nextInsn));
                        put("GROUP_TABLE", hw.value, "and returns the group's table pair: " + at(hw.valueInsn));
                    } else if (hw != null) {
                        notes.add("registry group lookup: buckets 0x" + Long.toHexString(hw.buckets) + " count 0x" + Long.toHexString(hw.count) + " map 0x" + Long.toHexString(map));
                    }
                }
            }
            if (t != null) {
                HashWalk hw = hashWalk(listing(t, 60));
                if (hw != null && hw.valueInsn != null) {
                    String w = "player-table lookup rva 0x" + Long.toHexString(rva(t)) + " (called with LOCAL_PLAYER_IDX by playerFindSelf's resolver)";
                    put("PLAYER_BUCKETS", hw.buckets, w + ": " + at(hw.bucketsInsn));
                    put("PLAYER_BUCKET_COUNT", hw.count, w + ": " + at(hw.countInsn));
                    if (hw.keyAtZero) put("NODE_UID", 0, w + " compares the uid with [node]: " + at(hw.keyInsn));
                    put("NODE_NEXT", hw.next, w + " walks the bucket chain: " + at(hw.nextInsn));
                    put("NODE_ENTITY", hw.value, w + " returns the entity: " + at(hw.valueInsn));
                }
            }
        }

        // ---- the NPC table: npcName's lookup (client + REGISTRY_MAP) walks every group and, per group's
        //      table pair, divides the uid by the NPC bucket count and indexes the NPC bucket array.
        List<Instruction> nn = leaf("npcName", 300);
        for (Address c1 : callees(nn)) {
            for (Address c2 : callees(listing(c1, 30))) {
                List<Instruction> body = listing(c2, 500);
                HashWalk hw = hashWalk(body);
                if (hw != null && hw.buckets != val("PLAYER_BUCKETS") && hw.buckets > 0x10) {
                    String w = "npcName's group walk rva 0x" + Long.toHexString(rva(c2));
                    put("NPC_BUCKETS", hw.buckets, w + ": " + at(hw.bucketsInsn));
                    put("NPC_BUCKET_COUNT", hw.count, w + ": " + at(hw.countInsn));
                    break;
                }
            }
            if (out.containsKey("NPC_BUCKETS")) break;
        }

        // ---- the player handle list: `movsxd r,[client+COUNT]` then `lea r,[client+COUNT+4]` (ids
        //      inline after the count), in a loop that looks each id up in the player table (a `div` by
        //      [pair+PLAYER_BUCKET_COUNT]). Several fields fit the first half; only one feeds the table.
        long pbc = val("PLAYER_BUCKET_COUNT");
        if (pbc >= 0) {
            Instruction cur = getFirstInstruction();
            while (cur != null && text.contains(cur.getAddress())) {
                String s = cur.toString();
                if (s.startsWith("MOVSXD") && s.contains("dword ptr [")) {
                    Mem m = memOf(cur);
                    if (m != null && m.hasDisp && m.disp > 0x1000 && m.disp < 0x100000 && m.index.isEmpty()) {
                        Instruction j = cur, lea = null, div = null;
                        for (int k = 0; k < 30 && j != null; k++) {
                            j = j.getNext();
                            if (j == null) break;
                            Mem mj = memOf(j);
                            if (lea == null && j.getMnemonicString().equals("LEA") && mj != null && mj.base.equals(m.base) && mj.disp == m.disp + 4) lea = j;
                            if (lea != null && mj != null && mj.hasDisp && mj.disp == pbc && j.toString().contains("dword ptr")) { div = j; break; }
                        }
                        if (lea != null && div != null) {
                            put("PLAYER_COUNT", m.disp, "the player-list walk reads the count: " + at(cur));
                            put("PLAYER_IDS", m.disp + 4, "takes the inline id array right after it: " + at(lea) + ", and looks each id up in the player table: " + at(div));
                            break;
                        }
                    }
                }
                cur = cur.getNext();
            }
        }

        // ---- the scene's world-tile base. Every scene->world conversion in the client is
        //      `mov r,[client+SCENE]; ...; add r2,[r+BASE_X]` (and +4 for y). The pair the client ADDS to
        //      most often right after loading the scene pointer is the base.
        long sceneOff = val("SCENE");
        if (sceneOff > 0) {
            Map<Long, Integer> adds = new TreeMap<>();
            Map<Long, Instruction> ex = new TreeMap<>();
            String needle = "+ 0x" + Long.toHexString(sceneOff) + "]";
            Instruction cur = getFirstInstruction();
            while (cur != null && text.contains(cur.getAddress())) {
                String s = cur.toString();
                if (s.startsWith("MOV ") && s.contains("qword ptr [") && s.endsWith(needle)) {
                    String reg = destReg(cur);
                    Instruction j = cur;
                    for (int k = 0; k < 10; k++) {
                        j = j.getNext();
                        if (j == null) break;
                        Mem mj = memOf(j);
                        if (j.getMnemonicString().equals("ADD") && mj != null && mj.base.equals(reg) && mj.hasDisp && j.toString().contains("dword ptr")) {
                            adds.merge(mj.disp, 1, Integer::sum);
                            ex.putIfAbsent(mj.disp, j);
                        }
                        if (destReg(j).equals(reg) && !j.getMnemonicString().equals("CMP") && !j.getMnemonicString().equals("TEST")) break;
                    }
                }
                cur = cur.getNext();
            }
            long best = -1;
            int bestN = 0;
            for (Map.Entry<Long, Integer> e : adds.entrySet()) {
                Integer y = adds.get(e.getKey() + 4);
                if (y == null) continue;
                int n = Math.min(e.getValue(), y);
                if (n > bestN) { bestN = n; best = e.getKey(); }
            }
            if (best >= 0 && bestN >= 3) {
                put("SCENE_BASE_X", best, bestN + " scene->world conversions add it right after loading the scene pointer, e.g. " + at(ex.get(best)));
                put("SCENE_BASE_Y", best + 4, "and this one alongside it, e.g. " + at(ex.get(best + 4)));
            } else {
                notes.add("scene base: no scene field pair is added often enough (best 0x" + Long.toHexString(best) + " x" + bestN + ")");
            }
        }

        // ---- the render (fine) position: the client's own npcCoordFine binding reads it through a small
        //      coordinate object on the entity -- `lea rcx,[entity+OBJ]; call getX` then `lea rcx,[entity+OBJ];
        //      call getY`, each getter one instruction (`mov eax,[rcx+D]; ret`) -- and computes the height
        //      from the terrain rather than storing it, so there is no height field to derive here.
        List<Instruction> ncf = leaf("npcCoordFine", 400);
        List<long[]> pairs2 = new ArrayList<>();
        List<Instruction> where = new ArrayList<>();
        for (int k = 0; k + 1 < ncf.size(); k++) {
            Instruction a = ncf.get(k), c = null;
            for (int q = k + 1; q < Math.min(ncf.size(), k + 4); q++) if (ncf.get(q).getMnemonicString().equals("CALL")) { c = ncf.get(q); break; }
            Mem m = memOf(a);
            if (!a.toString().startsWith("LEA RCX,[") || m == null || !m.hasDisp || m.disp < 0x100 || c == null || callTarget(c) == null) continue;
            List<Instruction> g = linear(callTarget(c), 2);
            if (g.size() == 2 && g.get(1).getMnemonicString().equals("RET") && g.get(0).toString().startsWith("MOV EAX,dword ptr [RCX + ")) {
                pairs2.add(new long[]{m.disp, mem(g.get(0)).disp});
                where.add(a);
            }
        }
        if (pairs2.size() >= 2 && pairs2.get(0)[0] == pairs2.get(1)[0]) {
            put("ENTITY_FINE_X", pairs2.get(0)[0] + pairs2.get(0)[1], "npcCoordFine reads x through the entity's coordinate object: " + at(where.get(0)) + " then a getter of +0x" + Long.toHexString(pairs2.get(0)[1]));
            put("ENTITY_FINE_Y", pairs2.get(1)[0] + pairs2.get(1)[1], "and y: " + at(where.get(1)) + " then a getter of +0x" + Long.toHexString(pairs2.get(1)[1]));
        } else {
            notes.add("npcCoordFine: no coordinate-object getter pair found");
        }

        // ---- the NPC definition's name: npcName's resolver falls back to `mov rdx,[entity+DEF_PTR];
        //      add rdx,NAME` -- the NxtString on the definition.
        long defPtr = val("ENTITY_DEF_PTR");
        if (defPtr > 0) {
            outer:
            for (Address callee : callees(nn)) {
                List<Instruction> body = listing(callee, 400);
                for (int k = 0; k + 1 < body.size(); k++) {
                    Instruction a = body.get(k), b = body.get(k + 1);
                    Mem m = memOf(a);
                    if (a.getMnemonicString().equals("MOV") && m != null && m.hasDisp && m.disp == defPtr && b.getMnemonicString().equals("ADD")
                            && destReg(b).equals(destReg(a)) && b.toString().contains(",0x")) {
                        long name = Long.parseLong(b.toString().replaceAll(".*,0x", ""), 16);
                        put("DEF_NAME", name, "npcName's resolver takes the definition's name string: " + at(a) + "; " + at(b));
                        break outer;
                    }
                }
            }
        }

        // ---- the projection's view object and rescale. worldToScreenCoord calls the view getter
        //      `mov rax,[rcx+VIEW]; ret` on the client, then hands `view+BASE` to the final rescale, which
        //      computes x * [s+OUT_W] / [s+IN_W] and y * [s+OUT_H] / [s+IN_H].
        Address w2s = leafAddress("worldToScreenCoord");
        if (w2s != null) {
            List<Instruction> body = listing(w2s, 600);
            for (int k = 0; k + 2 < body.size(); k++) {
                Instruction call = body.get(k);
                if (!call.getMnemonicString().equals("CALL") || callTarget(call) == null) continue;
                List<Instruction> g = listing(callTarget(call), 3);
                if (g.size() < 2 || !g.get(0).getMnemonicString().equals("MOV") || !g.get(1).getMnemonicString().equals("RET")) continue;
                Mem gm = memOf(g.get(0));
                if (gm == null || !gm.hasDisp || !gm.base.equals("RCX")) continue;
                Instruction lea = null, rc = null;
                for (int q = k + 1; q < Math.min(body.size(), k + 8); q++) {
                    Instruction x = body.get(q);
                    Mem xm = memOf(x);
                    if (lea == null && x.toString().startsWith("LEA RCX,[RAX + ") && xm != null) lea = x;
                    else if (lea != null && x.getMnemonicString().equals("CALL")) { rc = x; break; }
                }
                if (lea == null || rc == null || callTarget(rc) == null) continue;
                Mem lm = memOf(lea);
                List<Instruction> rs = listing(callTarget(rc), 60);
                dump("projection rescale rva 0x" + Long.toHexString(rva(callTarget(rc))), rs);
                // track the last [RCX+d] load into each XMM register; each DIVSS a,b is (load a)/(load b)
                Map<String, Instruction> lastLoad = new LinkedHashMap<>();
                List<Instruction[]> divs = new ArrayList<>();
                for (Instruction i : rs) {
                    Mem m = memOf(i);
                    if (i.getMnemonicString().equals("MOVD") && m != null && m.base.equals("RCX") && m.hasDisp) lastLoad.put(destReg(i), i);
                    if (i.getMnemonicString().equals("DIVSS")) {
                        Instruction num = lastLoad.get(destReg(i)), den = lastLoad.get(i.getDefaultOperandRepresentation(1));
                        if (num != null && den != null) divs.add(new Instruction[]{num, den});
                    }
                }
                if (divs.size() >= 2) {
                    put("VIEW_OBJ", gm.disp, "worldToScreenCoord calls the view getter " + at(g.get(0)));
                    put("VIEW_OBJ_SCALE_BASE", lm.disp, "and passes view+this to the rescale: " + at(lea));
                    put("VIEW_OUT_W", mem(divs.get(0)[0]).disp, "rescale numerator for x: " + at(divs.get(0)[0]));
                    put("VIEW_IN_W", mem(divs.get(0)[1]).disp, "rescale divisor for x: " + at(divs.get(0)[1]));
                    put("VIEW_OUT_H", mem(divs.get(1)[0]).disp, "rescale numerator for y: " + at(divs.get(1)[0]));
                    put("VIEW_IN_H", mem(divs.get(1)[1]).disp, "rescale divisor for y: " + at(divs.get(1)[1]));
                }
                break;
            }
        }

        // ---- the pending menu-action record. The widget-menu action method (a virtual; reached from its
        //      vtable, not called by name) bumps a u16 sequence, stores its three int arguments, sets a
        //      "pending" byte to 1, and then looks the widget up through the interface manager with the
        //      first two -- which is what ties it to the child lookup found above.
        if (wl != null) {
            Address childLookup = null;
            for (Reference r : getReferencesTo(wl.lookup)) {
                Function f = getFunctionContaining(r.getFromAddress());
                if (f != null && out.containsKey("IFTYPE_CHILDREN_COUNT") && f.getBody().contains(r.getFromAddress())) {
                    List<Instruction> b = listing(f.getEntryPoint(), 60);
                    for (Instruction i : b) { Mem m = memOf(i); if (m != null && m.hasDisp && m.disp == val("IFTYPE_CHILDREN_COUNT")) { childLookup = f.getEntryPoint(); break; } }
                }
                if (childLookup != null) break;
            }
            if (childLookup != null) {
                for (Reference r : getReferencesTo(childLookup)) {
                    Function f = getFunctionContaining(r.getFromAddress());
                    if (f == null) continue;
                    List<Instruction> pro = linear(f.getEntryPoint(), 24);
                    Long seq = null, pend = null, a1 = null, a2 = null, a3 = null;
                    String lea3 = null;
                    Instruction seqI = null;
                    for (Instruction i : pro) {
                        String s = i.toString();
                        Mem m = memOf(i);
                        if (m == null || !m.base.equals("RCX") || !m.hasDisp) {
                            // `lea r14,[rcx+X]; ... mov dword [r14],r9d` stores the third argument
                            if (s.startsWith("MOV dword ptr [") && s.endsWith(",R9D") && lea3 != null && s.contains("[" + lea3 + "]")) a3 = lea3Disp;
                            continue;
                        }
                        if (s.startsWith("INC word ptr")) { seq = m.disp; seqI = i; }
                        if (s.startsWith("LEA ")) { lea3 = destReg(i); lea3Disp = m.disp; }
                        if (s.startsWith("MOV dword ptr") && s.endsWith(",EDX")) a1 = m.disp;
                        if (s.startsWith("MOV dword ptr") && s.endsWith(",R8D")) a2 = m.disp;
                        if (s.startsWith("MOV dword ptr") && s.endsWith(",R9D")) a3 = m.disp;
                        if (s.startsWith("MOV byte ptr") && s.endsWith(",0x1")) pend = m.disp;
                    }
                    if (seq != null && pend != null && a1 != null && a2 != null && a3 != null) {
                        String w = "widget-menu action method rva 0x" + Long.toHexString(rva(f.getEntryPoint())) + " (calls the child lookup with its first two arguments)";
                        put("PENDING_ACTION_PACKED_ID", a1, w + " stores its 1st int argument here");
                        put("PENDING_ACTION_INDEX", a2, w + " stores its 2nd int argument here");
                        put("PENDING_ACTION_TARGET", a3, w + " stores its 3rd int argument here");
                        put("PENDING_ACTION_SEQ", seq, w + ": " + at(seqI));
                        put("PENDING_ACTION_PENDING", pend, w + " sets this byte to 1");
                        notes.add("DO_ACTION candidate for a hook-and-log run: rva 0x" + Long.toHexString(rva(f.getEntryPoint()))
                                + " is the widget-menu action path (its args are a packed widget id and a component index, NOT scene x/y)");
                        break;
                    }
                }
            }
        }
    }

    private long lea3Disp;

    // ===========================================================================================
    // derive3: the action senders (offsets.hpp's ACTIONS block). Found on client-241-3 by a
    // hook-and-log run that watched real clicks, then written as structural rules -- each must match
    // EXACTLY ONE function or it writes nothing (an ambiguous match is a note, never a guess).
    // ===========================================================================================
    private void derive3() {
        // ---- the per-frame tick: the function that does `inc dword [client+CYCLE]`
        long cyc = val("CYCLE");
        if (cyc > 0) {
            Instruction inc = findInc(cyc);
            Function tick = inc == null ? null : getFunctionContaining(inc.getAddress());
            if (tick != null) put("ACT_TICK", rva(tick.getEntryPoint()), "the function that increments CYCLE once per frame: " + at(inc));
        }

        // ---- the packet-start function P: called as `mov r8,[reg+CONN]; add r8,IMM; mov edx,OPCODE; call P`
        //      (the order varies). The callee that shape reaches most often is P.
        Pattern movEdx = Pattern.compile("MOV EDX,0x([0-9a-f]+)");
        Map<Address, Integer> count = new LinkedHashMap<>();
        Instruction cur = getFirstInstruction();
        java.util.ArrayDeque<String> win = new java.util.ArrayDeque<>();
        while (cur != null && text.contains(cur.getAddress())) {
            String s = cur.toString();
            if (cur.getMnemonicString().equals("CALL") && win.size() >= 4) {
                boolean edx = false, add = false, mov = false;
                for (String w : win) {
                    if (movEdx.matcher(w).matches()) edx = true;
                    if (w.startsWith("ADD R8,0x")) add = true;
                    if (w.startsWith("MOV R8,qword ptr [") && w.contains(" + 0x")) mov = true;
                }
                Address t = callTarget(cur);
                if (edx && add && mov && t != null) count.merge(t, 1, Integer::sum);
            }
            win.addLast(s);
            if (win.size() > 5) win.removeFirst();
            cur = cur.getNext();
        }
        Address p = null;
        int best = 0, second = 0;
        for (Map.Entry<Address, Integer> e : count.entrySet()) {
            if (e.getValue() > best) { second = best; best = e.getValue(); p = e.getKey(); }
            else if (e.getValue() > second) second = e.getValue();
        }
        if (p == null || best < 20 || best < 2 * second) {
            notes.add("actions: no clear packet-start function (best " + best + ", next " + second + ")");
            return;
        }
        notes.add("packet-start function rva 0x" + Long.toHexString(rva(p)) + " (" + best + " call sites)");

        // ---- group P's callers: each sender, with the distinct packet opcodes it starts
        Map<Function, Set<Long>> senders = new LinkedHashMap<>();
        for (Reference r : getReferencesTo(p)) {
            Function f = getFunctionContaining(r.getFromAddress());
            Instruction call = getInstructionAt(r.getFromAddress());
            if (f == null || call == null) continue;
            Long imm = null;
            Instruction q = call;
            for (int k = 0; k < 6 && q != null; k++) {
                q = q.getPrevious();
                if (q == null) break;
                Matcher m = movEdx.matcher(q.toString());
                if (m.matches()) { imm = Long.parseLong(m.group(1), 16); break; }
            }
            senders.computeIfAbsent(f, x -> new LinkedHashSet<>()).add(imm);
        }
        List<Function> npc = new ArrayList<>(), loc = new ArrayList<>(), walk = new ArrayList<>(), ifop = new ArrayList<>();
        Pattern cmpArg = Pattern.compile("CMP dword ptr \\[RDX( \\+ 0x[48])?\\],EAX");
        for (Map.Entry<Function, Set<Long>> e : senders.entrySet()) {
            Function f = e.getKey();
            List<Instruction> body = new ArrayList<>();
            for (Instruction i : currentProgram.getListing().getInstructions(f.getBody(), true)) body.add(i);
            if (body.isEmpty()) continue;
            int ops = e.getValue().size();
            boolean cmpR8 = false, cmpR9 = false, usesR8 = false;
            int argCmps = 0;
            for (int k = 0; k < body.size(); k++) {
                String s = body.get(k).toString();
                if (s.equals("CMP R8D,0x1")) cmpR8 = true;
                if (s.equals("CMP R9D,0x1")) cmpR9 = true;
                if (k < 20 && cmpArg.matcher(s).matches()) argCmps++;
                if (k < 12 && s.matches("MOV E?[A-Z0-9]+,R8D?")) usesR8 = true;
            }
            // NPC option: tests its entity argument first, five opcodes, switches on the option (R8D)
            if (ops >= 5 && cmpR8 && body.get(0).toString().equals("TEST RDX,RDX")) npc.add(f);
            // object option: five opcodes, switches on the option (R9D)
            if (ops >= 5 && cmpR9) loc.add(f);
            // menu walk: one opcode; its {level,x,y} argument compared against the last-destination
            // global first; no third argument (the direct-click walk takes one in R8D)
            if (ops == 1 && argCmps == 3 && !usesR8) walk.add(f);
            // item / interface-button option: two opcodes (subop 0 or not), and the slot's option mask
            // shifted by the option number in CL before anything is sent
            boolean sarCl = false;
            for (Instruction i : body) if (i.toString().equals("SAR EAX,CL")) { sarCl = true; break; }
            if (ops == 2 && sarCl) ifop.add(f);
        }
        putUnique("ACT_NPC_OP", npc, "the only packet sender that tests its entity argument, starts five different packets and switches on the option in R8D");
        putUnique("ACT_LOC_OP", loc, "the only packet sender that starts five different packets and switches on the option in R9D");
        putUnique("ACT_WALK", walk, "the only one-packet sender that compares a three-int {level,x,y} argument against the last-destination global and takes no third argument (the menu's Walk here)");
        putUnique("ACT_IF_OP", ifop, "the only packet sender that starts exactly two packets and shifts the slot's option mask by CL (SAR EAX,CL)");
    }

    // ===========================================================================================
    // derive4: scenery (offsets.hpp's SCENERY block). Anchored on the loc_find Lua binding's leaf,
    // which names itself in an error string; everything else is read off the two functions it calls.
    // ===========================================================================================
    private void derive4() {
        Instruction s = firstRef("Could not find supplied coord's world instance in loc_find.");
        Function lf = s == null ? null : getFunctionContaining(s.getAddress());
        if (lf == null) { notes.add("scenery: the loc_find leaf was not found"); return; }
        List<Instruction> body = new ArrayList<>();
        for (Instruction i : currentProgram.getListing().getInstructions(lf.getBody(), true)) body.add(i);

        // ---- the tile probe: a call whose RCX is `mov rcx,[view+SMALL]` and whose body indexes the
        //      grid with two IMULs off RCX and loads the tile array off RCX
        Address probe = null, getter = null;
        long gridDisp = -1;
        Instruction gridLoad = null;
        for (int k = 0; k < body.size() && probe == null; k++) {
            Instruction c = body.get(k);
            if (!c.getMnemonicString().equals("CALL")) continue;
            Address t = callTarget(c);
            if (t == null) continue;
            Instruction rcx = null;
            for (int b = k - 1; b >= 0 && b >= k - 8; b--) {
                Instruction q = body.get(b);
                if (q.getMnemonicString().equals("MOV") && destReg(q).equals("RCX")) { rcx = q; break; }
            }
            Mem rm = rcx == null ? null : memOf(rcx);
            if (rm == null || !rm.hasDisp || rm.disp <= 0 || rm.disp >= 0x100 || rm.absolute != null) continue;
            if (!gridProbe(t)) continue;
            probe = t;
            gridDisp = rm.disp;
            gridLoad = rcx;
            for (int n = k + 1; n < body.size() && n < k + 20; n++) {
                if (body.get(n).getMnemonicString().equals("CALL")) { getter = callTarget(body.get(n)); break; }
            }
        }
        if (probe == null) { notes.add("scenery: loc_find's tile probe was not recognised"); return; }
        put("SCENE_GRID", gridDisp, "loc_find passes the world view's grid to the tile probe: " + at(gridLoad));

        List<Instruction> pl = linear(probe, 48);
        Map<Long, Integer> imuls = new LinkedHashMap<>();
        for (Instruction i : pl) {
            Mem m = memOf(i);
            if (i.getMnemonicString().equals("IMUL") && m != null && m.base.equals("RCX") && m.hasDisp) imuls.merge(m.disp, 1, Integer::sum);
        }
        long dimX = -1, dimY = -1;
        for (Map.Entry<Long, Integer> e : imuls.entrySet()) {
            if (e.getValue() == 1) dimX = e.getKey();
            else if (e.getValue() == 2) dimY = e.getKey();
        }
        long tiles = -1;
        Instruction tilesAt = null;
        boolean afterDimX = false;
        for (Instruction i : pl) {
            Mem m = memOf(i);
            if (m == null) continue;
            if (i.getMnemonicString().equals("IMUL") && m.base.equals("RCX") && m.disp == dimX) afterDimX = true;
            else if (afterDimX && i.getMnemonicString().equals("MOV") && m.base.equals("RCX") && m.hasDisp) { tiles = m.disp; tilesAt = i; break; }
        }
        if (imuls.size() != 2 || dimX < 0 || dimY < 0 || tilesAt == null) {
            notes.add("scenery: the tile probe at rva 0x" + Long.toHexString(rva(probe)) + " did not read as dimX/dimY/tiles " + imuls + " listing " + pl.size());
        } else {
            put("GRID_DIM_X", dimX, "the tile probe multiplies the level by it (once): rva 0x" + Long.toHexString(rva(probe)));
            put("GRID_DIM_Y", dimY, "the tile probe multiplies by it on both paths: rva 0x" + Long.toHexString(rva(probe)));
            put("GRID_TILES", tiles, "the tile array the probe indexes on the level path: " + at(tilesAt));
        }

        // ---- walls: the probe reports a match on the wall slot as layer 0 (`mov word ptr [rbx],0x100`);
        //      the slot is the `mov rcx,[tile+N]` that branch tested, the handle the `mov rcx,[rcx+H]` after it
        {
            List<Instruction> wl = linear(probe, 160);
            for (int k = 0; k < wl.size(); k++) {
                if (!wl.get(k).toString().equals("MOV word ptr [RBX],0x100")) continue;
                Instruction slot = null, handle = null;
                for (int b = k - 1; b >= 0 && b >= k - 10; b--) {
                    Instruction q = wl.get(b);
                    Mem qm = memOf(q);
                    if (qm == null || !q.getMnemonicString().equals("MOV") || !destReg(q).equals("RCX") || !qm.hasDisp) continue;
                    if (qm.base.equals("RCX") && handle == null) handle = q;
                    else if (!qm.base.equals("RCX") && qm.disp >= 0x100) { slot = q; break; }
                }
                if (slot != null && handle != null) {
                    put("TILE_WALL", memOf(slot).disp, "the tile probe's layer-0 (wall) slot: " + at(slot));
                    put("WALL_HANDLE", memOf(handle).disp, "the wall's handle, read before the id test: " + at(handle));
                } else notes.add("scenery: the wall slot did not read as slot + handle");
                break;
            }
        }

        // ---- the game-object walk: the probe's callee that loops over a tile's object entries and
        //      compares each object's origin against the tile
        for (Address t : callees(linear(probe, 160))) {
            List<Instruction> ol = linear(t, 120);
            Instruction cnt = null, arr = null, handle = null;
            List<Instruction> cmps = new ArrayList<>();
            for (int k = 0; k < ol.size(); k++) {
                Instruction i = ol.get(k);
                Mem m = memOf(i);
                String mn = i.getMnemonicString();
                if (m == null || !m.hasDisp || m.disp <= 0 || !m.index.isEmpty()) continue;
                if (cnt == null && mn.equals("MOVSXD") && m.disp < 0x100) { cnt = i; continue; }
                if (cnt != null && arr == null && mn.equals("MOV") && i.toString().contains("qword ptr") && m.disp < 0x100
                        && destReg(i).equals(m.base)) { arr = i; continue; }
                if (arr != null && handle == null && mn.equals("MOV") && destReg(i).equals("RCX") && m.disp >= 0x100
                        && k + 1 < ol.size() && ol.get(k + 1).getMnemonicString().equals("CALL")) { handle = i; continue; }
                if (handle != null && mn.equals("CMP") && i.toString().startsWith("CMP dword ptr [") && m.disp >= 0x100) cmps.add(i);
            }
            if (cnt == null || arr == null || handle == null || cmps.size() < 2) continue;
            put("TILE_OBJ_COUNT", memOf(cnt).disp, "the object walk's entry count: " + at(cnt));
            put("TILE_OBJS", memOf(arr).disp, "the object walk's entry array: " + at(arr));
            put("LOC_HANDLE", memOf(handle).disp, "the handle the object walk tests for kind 2: " + at(handle));
            put("LOC_X", memOf(cmps.get(0)).disp, "the object's origin x, compared against the tile: " + at(cmps.get(0)));
            put("LOC_Y", memOf(cmps.get(1)).disp, "the object's origin y, compared against the tile: " + at(cmps.get(1)));
            break;
        }
        if (val("LOC_HANDLE") < 0) { StringBuilder cb = new StringBuilder(); for (Address t : callees(linear(probe, 160))) cb.append(" 0x").append(Long.toHexString(rva(t))); notes.add("scenery: no game-object walk among the tile probe's callees:" + cb); }

        // ---- the loc definition getter: the call right after the probe. Its first instructions load
        //      the bucket count (dword) and the bucket array (qword) of its cache, adjacent globals.
        if (getter == null) { notes.add("scenery: no call after the tile probe in loc_find"); return; }
        Address count = null, buckets = null;
        Instruction bAt = null;
        for (Instruction i : linear(getter, 16)) {
            Mem m = memOf(i);
            if (m == null || m.absolute == null || !i.getMnemonicString().equals("MOV")) continue;
            if (i.toString().contains("dword ptr") && count == null) count = m.absolute;
            else if (i.toString().contains("qword ptr") && buckets == null) { buckets = m.absolute; bAt = i; }
        }
        if (count == null || buckets == null || count.getOffset() != buckets.getOffset() + 8) {
            notes.add("scenery: the loc definition getter at rva 0x" + Long.toHexString(rva(getter)) + " did not load an adjacent {buckets, count} pair");
            return;
        }
        put("LOCDEF_CACHE", rva(buckets), "the loc definition getter (called after the tile probe in loc_find) loads its cache's buckets, count at +8: " + at(bAt));

        // ---- the definition's name: the callers of the getter that build a menu entry -- they colour
        //      the loc's name with "<col=00FFFF>" -- take `lea r,[def+N]` and test the string's flag
        //      byte at N+0x17 (an NxtString). The first such read after the getter call is the name.
        Set<Function> menuFns = new HashSet<>();
        for (Address sa : findStrings("<col=00FFFF>")) for (Reference r : getReferencesTo(sa)) {
            Function f = getFunctionContaining(r.getFromAddress());
            if (f != null) menuFns.add(f);
        }
        Map<Long, Integer> names = new LinkedHashMap<>();
        Map<Long, Integer> opsAt = new LinkedHashMap<>();
        Set<Function> seenF = new HashSet<>();
        for (Reference r : getReferencesTo(getter)) {
            Function f = getFunctionContaining(r.getFromAddress());
            if (f == null || !menuFns.contains(f) || !seenF.add(f)) continue;
            List<Instruction> fl = new ArrayList<>();
            for (Instruction i : currentProgram.getListing().getInstructions(f.getBody(), true)) fl.add(i);
            int from = 0;
            while (from < fl.size() && !(fl.get(from).getMnemonicString().equals("CALL") && getter.equals(callTarget(fl.get(from))))) from++;
            boolean got = false;
            for (int k = from; k < fl.size() && !got; k++) {
                Instruction i = fl.get(k);
                Mem m = memOf(i);
                if (!i.getMnemonicString().equals("LEA") || m == null || !m.hasDisp || !m.index.isEmpty() || m.disp <= 0 || m.disp > 0x400) continue;
                String dst = destReg(i);
                for (int n = k + 1; n < fl.size() && n < k + 12; n++) {
                    Instruction q = fl.get(n);
                    Mem qm = memOf(q);
                    if (qm == null || !qm.hasDisp || !qm.index.isEmpty() || !q.toString().contains("byte ptr")) continue;
                    if ((qm.base.equals(m.base) && qm.disp == m.disp + 0x17) || (qm.base.equals(dst) && qm.disp == 0x17)) {
                        names.merge(m.disp, 1, Integer::sum);
                        got = true;   // the first string read after the definition is fetched
                        break;
                    }
                }
                // the options vector, after the name: `add r,IMM; ... mov rcx,r; call` -- the vector
                // object handed to the option helpers (its entries are 0x40 bytes, `sar rax,6`)
                if (got) {
                    for (int n = k + 1; n < fl.size() && n < k + 120; n++) {
                        Instruction a = fl.get(n);
                        String as = a.toString();
                        if (!as.startsWith("ADD R") || !as.contains(",0x")) continue;
                        long imm;
                        try { imm = Long.parseLong(as.substring(as.indexOf(",0x") + 3), 16); } catch (NumberFormatException e) { continue; }
                        if (imm < 0x80 || imm > 0x400) continue;
                        String reg = destReg(a);
                        boolean handed = false;
                        for (int q = n + 1; q < fl.size() && q < n + 5; q++) {
                            String qs = fl.get(q).toString();
                            if (qs.equals("MOV RCX," + reg)) handed = true;
                            if (handed && fl.get(q).getMnemonicString().equals("CALL")) {
                                Address c = callTarget(fl.get(q));
                                if (c != null && stride40(c)) { opsAt.merge(imm, 1, Integer::sum); }
                                break;
                            }
                        }
                        if (handed) break;
                    }
                }
            }
        }
        long name = -1;
        int nb = 0, n2 = 0;
        for (Map.Entry<Long, Integer> e : names.entrySet()) {
            if (e.getValue() > nb) { n2 = nb; nb = e.getValue(); name = e.getKey(); }
            else if (e.getValue() > n2) n2 = e.getValue();
        }
        if (name > 0 && nb > n2) put("LOCDEF_NAME", name, "the first NxtString the loc menu builders read off the definition after fetching it (" + nb + " sites, next " + n2 + ")");
        else notes.add("scenery: no clear loc definition name field (" + names + ")");
        if (opsAt.size() == 1) put("LOCDEF_OPS", opsAt.keySet().iterator().next(), "the loc menu builder hands def+N to the option helpers (0x40-byte entries) right after reading the name");
        else notes.add("scenery: no clear loc definition options field (" + opsAt + ")");
    }

    /** A helper over a vector of 0x40-byte entries: `sar rax,0x6` / `shl r,0x6`, here or one call down. */
    private boolean stride40(Address f) {
        for (Instruction i : linear(f, 30)) {
            String s = i.toString();
            if (s.endsWith(",0x6") && (s.startsWith("SAR ") || s.startsWith("SHL "))) return true;
        }
        for (Address c : callees(linear(f, 12))) {
            for (Instruction i : linear(c, 30)) {
                String s = i.toString();
                if (s.endsWith(",0x6") && (s.startsWith("SAR ") || s.startsWith("SHL "))) return true;
            }
        }
        return false;
    }

    /** A grid tile probe: two-or-more IMULs off RCX with large displacements and a tile array load. */
    private boolean gridProbe(Address t) {
        int imul = 0;
        for (Instruction i : linear(t, 40)) {
            Mem m = memOf(i);
            if (i.getMnemonicString().equals("IMUL") && m != null && m.base.equals("RCX") && m.hasDisp && m.disp >= 0x100) imul++;
        }
        return imul >= 2;
    }

    private void putUnique(String name, List<Function> fs, String why) {
        if (fs.size() == 1) {
            put(name, rva(fs.get(0).getEntryPoint()), why + ": rva 0x" + Long.toHexString(rva(fs.get(0).getEntryPoint())));
        } else {
            StringBuilder b = new StringBuilder();
            for (Function f : fs) b.append(" 0x").append(Long.toHexString(rva(f.getEntryPoint())));
            notes.add(name + ": " + fs.size() + " candidates (" + b.toString().trim() + ") -- not derived");
        }
    }

    /** A straight run of `n` instructions from `a`, no flow following (prologues). */
    private List<Instruction> linear(Address a, int n) {
        List<Instruction> l = new ArrayList<>();
        Instruction i = getInstructionAt(a);
        for (int k = 0; k < n && i != null; k++, i = i.getNext()) l.add(i);
        return l;
    }

    private String i2s(Instruction i) { return i.toString(); }

    private String hexAt(long addr) { return String.format("%06x", addr - base); }

    /** The function containing the first instruction that references string `name`. */
    private Function functionReferencing(String name) {
        Instruction i = firstRef(name);
        return i == null ? null : getFunctionContaining(i.getAddress());
    }

    /**
     * A usertype registration's (name -> {offset, instruction address}) pairs, for the properties
     * registered with an explicit field offset: `MOV dword ptr [RBP + 0x20],OFFSET` ... `LEA RAX,[name]`
     * ... `CALL register`. Properties registered without an offset map to {-1, addr}.
     */
    private Map<String, long[]> propertyTable(Function f) {
        Map<String, long[]> tab = new LinkedHashMap<>();
        Long imm = null;
        String pend = null;
        long pendAt = 0;
        for (Instruction i : currentProgram.getListing().getInstructions(f.getBody(), true)) {
            String s = i.toString();
            String mn = i.getMnemonicString();
            if (mn.equals("MOV") && s.startsWith("MOV dword ptr [RBP + 0x20],0x")) imm = Long.parseLong(s.substring(s.lastIndexOf("0x") + 2), 16);
            if (mn.equals("LEA")) {
                for (Reference r : i.getReferencesFrom()) {
                    String str = stringAt(r.getToAddress());
                    if (str != null) { pend = str; pendAt = i.getAddress().getOffset(); }
                }
            }
            if (mn.equals("CALL") && pend != null) {
                tab.putIfAbsent(pend, new long[]{imm == null ? -1 : imm, pendAt});
                pend = null;
                imm = null;
            }
        }
        return tab;
    }

    /** The C string at `a` if it is printable ASCII (defined as data or not). */
    private String stringAt(Address a) {
        try {
            var d = getDataAt(a);
            if (d != null && d.hasStringValue()) return String.valueOf(d.getValue());
            if (!currentProgram.getMemory().getBlock(a).isInitialized() || currentProgram.getMemory().getBlock(a).isExecute()) return null;
            StringBuilder sb = new StringBuilder();
            for (int k = 0; k < 64; k++) {
                int b = currentProgram.getMemory().getByte(a.add(k)) & 0xff;
                if (b == 0) break;
                if (b < 0x20 || b > 0x7e) return null;
                sb.append((char) b);
            }
            return sb.length() >= 3 ? sb.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }

    /** NxtString getter lambdas in the `span` bytes after function f: {S, insn address}, address order. */
    private List<long[]> ssoGettersAfter(Function f, long span) {
        List<long[]> res = new ArrayList<>();
        Address end = f.getBody().getMaxAddress();
        Instruction i = getInstructionAfter(end);
        Pattern flag = Pattern.compile("MOVZX EAX,byte ptr \\[RCX \\+ 0x([0-9a-f]+)\\]");
        Pattern load = Pattern.compile("MOV RAX,qword ptr \\[RCX \\+ 0x([0-9a-f]+)\\]");
        while (i != null && i.getAddress().subtract(end) < span) {
            Matcher m = flag.matcher(i.toString());
            if (m.matches() && i.getNext() != null && i.getNext().toString().equals("SHR AL,0x7")) {
                long fl = Long.parseLong(m.group(1), 16);
                Instruction j = i.getNext();
                for (int k = 0; k < 4 && j != null; k++) {
                    j = j.getNext();
                    if (j == null) break;
                    Matcher ml = load.matcher(j.toString());
                    if (ml.matches()) {
                        long s = Long.parseLong(ml.group(1), 16);
                        if (s + 0x17 == fl) res.add(new long[]{s, i.getAddress().getOffset()});
                        break;
                    }
                }
            }
            i = i.getNext();
        }
        return res;
    }

    static final class WidgetLookup {
        long mgr, count, entryCount = -1, entryData = -1;
        Address lookup, sentinel;
        Instruction getterInsn, cmpInsn, arrInsn, leaInsn, entryCountInsn, entryDataInsn, sentinelInsn;
    }

    private WidgetLookup findWidgetLookup(long clientRva) {
        // every one-instruction client getter with a large displacement, then its callers' next call
        Pattern get = Pattern.compile("MOV RAX,qword ptr \\[RCX \\+ 0x([0-9a-f]+)\\]");
        for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
            if (f.getBody().getNumAddresses() > 16) continue;
            Instruction a = getInstructionAt(f.getEntryPoint());
            if (a == null || a.getNext() == null || !a.getNext().getMnemonicString().equals("RET")) continue;
            Matcher m = get.matcher(a.toString());
            if (!m.matches()) continue;
            long disp = Long.parseLong(m.group(1), 16);
            if (disp < 0x100000) continue;
            for (Reference r : getReferencesTo(f.getEntryPoint())) {
                Instruction call = getInstructionAt(r.getFromAddress());
                if (call == null || !call.getMnemonicString().equals("CALL")) continue;
                Instruction j = call;
                for (int k = 0; k < 4; k++) {
                    j = j.getNext();
                    if (j == null) break;
                    if (!j.getMnemonicString().equals("CALL")) continue;
                    Address t = callTarget(j);
                    WidgetLookup wl = t == null ? null : parseWidgetLookup(t);
                    if (wl != null) { wl.mgr = disp; wl.getterInsn = a; return wl; }
                    break;
                }
            }
        }
        return null;
    }

    private WidgetLookup parseWidgetLookup(Address t) {
        List<Instruction> body = listing(t, 60);
        boolean split = false;
        WidgetLookup wl = new WidgetLookup();
        wl.lookup = t;
        for (Instruction i : body) {
            String s = i.toString();
            if ((s.startsWith("SAR") || s.startsWith("SHR")) && s.endsWith(",0x10")) split = true;
            Mem m = memOf(i);
            if (m == null) continue;
            if (i.getMnemonicString().equals("CMP") && m.base.equals("RCX") && m.hasDisp && m.disp > 0x1000 && wl.cmpInsn == null) { wl.count = m.disp; wl.cmpInsn = i; }
            if (wl.cmpInsn != null && i.getMnemonicString().equals("MOV") && m.base.equals("RCX") && m.hasDisp && m.disp == wl.count + 8 && wl.arrInsn == null) wl.arrInsn = i;
            if (s.matches("LEA ([A-Z0-9]+),\\[([A-Z0-9]+) \\+ \\2\\*0x2\\]") && wl.leaInsn == null) wl.leaInsn = i;
            if (wl.arrInsn != null && m.scale == 8 && !m.index.isEmpty() && m.hasDisp && wl.entryCountInsn == null) { wl.entryCount = m.disp; wl.entryCountInsn = i; }
            if (wl.entryCountInsn != null && i.getMnemonicString().equals("MOV") && m.hasDisp && m.index.isEmpty() && m.disp == wl.entryCount + 8 && wl.entryDataInsn == null && !m.base.equals("RSP")) { wl.entryData = m.disp; wl.entryDataInsn = i; }
            if (i.getMnemonicString().equals("LEA") && m.absolute != null && !text.contains(m.absolute)) { wl.sentinel = m.absolute; wl.sentinelInsn = i; }
        }
        return split && wl.cmpInsn != null && wl.arrInsn != null && wl.leaInsn != null ? wl : null;
    }

    static final class HashWalk {
        long buckets = -1, count = -1, next = -1, value = -1;
        boolean keyAtZero;
        Instruction bucketsInsn, countInsn, keyInsn, nextInsn, valueInsn;
    }

    /**
     * The hash-table walk shape both registry lookups share:
     *   mov r,dword [T+COUNT]; mov r2,qword [T+BUCKETS]; ... div ...; mov rax,[r2+idx*8]
     *   loop: cmp key,dword [rax]; je hit; mov rax,qword [rax+NEXT]; test; jnz loop
     *   hit:  ... mov rax,qword [rax+VALUE]      (the last node-relative qword load)
     * Returns null unless the bucket pair and the chain step are all present.
     */
    private HashWalk hashWalk(List<Instruction> body) {
        HashWalk h = new HashWalk();
        int div = -1;
        for (int k = 0; k < body.size(); k++) if (body.get(k).getMnemonicString().equals("DIV")) { div = k; break; }
        if (div < 0) return null;
        for (int k = div - 1; k >= Math.max(0, div - 8); k--) {
            Instruction i = body.get(k);
            Mem m = memOf(i);
            if (m == null || !m.hasDisp || !m.index.isEmpty() || !i.getMnemonicString().equals("MOV")) continue;
            if (i.toString().contains("dword ptr") && h.countInsn == null) { h.count = m.disp; h.countInsn = i; }
            if (i.toString().contains("qword ptr") && h.bucketsInsn == null) { h.buckets = m.disp; h.bucketsInsn = i; }
        }
        for (int k = div + 1; k < body.size(); k++) {
            Instruction i = body.get(k);
            String s = i.toString();
            Mem m = memOf(i);
            if (i.getMnemonicString().equals("CMP") && s.matches("CMP [A-Z0-9]+,dword ptr \\[RAX\\]") && h.keyInsn == null) { h.keyAtZero = true; h.keyInsn = i; }
            if (m == null || !m.hasDisp || !m.index.isEmpty() || !s.startsWith("MOV RAX,qword ptr [RAX")) continue;
            if (h.nextInsn == null) { h.next = m.disp; h.nextInsn = i; }
            else if (m.disp != h.next) { h.value = m.disp; h.valueInsn = i; }
        }
        if (h.value < 0) {
            // the group lookup returns through RCX: `mov rax,qword ptr [RCX + VALUE]` after the miss check
            for (int k = body.size() - 1; k > div; k--) {
                Instruction i = body.get(k);
                Mem m = memOf(i);
                if (m != null && m.hasDisp && m.index.isEmpty() && i.toString().startsWith("MOV RAX,qword ptr [RCX") && m.disp != h.buckets && m.disp != h.count) {
                    h.value = m.disp; h.valueInsn = i; break;
                }
            }
        }
        return h.bucketsInsn != null && h.countInsn != null && h.nextInsn != null ? h : null;
    }

    // ===========================================================================================
    // Leaf discovery
    // ===========================================================================================
    private Address leafAddress(String name) {
        for (Address s : findStrings(name)) {
            for (Reference r : getReferencesTo(s)) {
                Instruction ref = getInstructionAt(r.getFromAddress());
                if (ref == null || !ref.getMnemonicString().equals("LEA")) continue;
                // 1. after the name's assign call: ClientState thunks (LEA R8,[leaf]) and ScriptOps
                //    (LEA RDX,[leaf]) hand the leaf to the very next call
                Instruction cur = ref.getNext();
                Instruction assign = null, t1call = null;
                for (int i = 0; i < 12 && cur != null; i++, cur = cur.getNext()) if (cur.getMnemonicString().equals("CALL")) { assign = cur; break; }
                if (assign != null) {
                    cur = assign.getNext();
                    for (int i = 0; i < 10 && cur != null; i++, cur = cur.getNext()) {
                        Address t = textLea(cur);
                        if (t != null) return t;
                        if (cur.getMnemonicString().equals("CALL")) { t1call = cur; break; }
                    }
                }
                // 2. a closure built just before the name: LEA RDX,[leaf]; LEA RCX,[slot]; CALL ctor
                //    (ScriptOps npcName/playerName) or LEA RAX,[leaf]; MOV [slot],RAX (getMapCoordinate)
                //    The closure may be built a few argument-shape setups earlier, so look back up to
                //    40 instructions, but never past another binding name (a string LEA in .rdata).
                Instruction p = ref.getPrevious();
                for (int i = 0; i < 40 && p != null; i++, p = p.getPrevious()) {
                    Address t = textLea(p);
                    if (t != null) return t;
                    if (i > 0 && p.getMnemonicString().equals("LEA") && refersToString(p)) break;
                }
                // 3. a per-binding wrapper constructor that stores the leaf itself at wrapper+0x10
                if (t1call != null) {
                    Address t1 = callTarget(t1call);
                    if (t1 != null) {
                        Address inside = leafStoredByT1(t1);
                        if (inside != null) return inside;
                        debug.append("!! T1 for ").append(name).append(" at rva 0x").append(Long.toHexString(rva(t1))).append(" stores no leaf:\n");
                        for (Instruction i : listing(t1, 40)) debug.append("      ").append(at(i)).append('\n');
                    }
                }
            }
        }
        debug.append("!! no leaf for ").append(name).append('\n');
        return null;
    }

    /**
     * Inside a per-binding wrapper constructor. The leaf is the only code address the constructor
     * takes: `LEA RAX,[leaf]` then either `MOV [RCX+0x10],RAX` or a 16-byte copy through XMM0 into
     * wrapper+0x8..0x18 (both seen). The vtable it also loads lives in .rdata, so "the first .text
     * LEA in a short function" is exactly the leaf. Long functions are not constructors: refuse.
     */
    private Address leafStoredByT1(Address t1) {
        List<Instruction> body = listing(t1, 40);
        if (body.size() >= 40) return null;
        for (Instruction i : body) {
            Address t = textLea(i);
            if (t != null) return t;
        }
        return null;
    }

    private List<Instruction> leaf(String name) { return leaf(name, 64); }

    private List<Instruction> leaf(String name, int max) {
        Address a = leafAddress(name);
        List<Instruction> l = a == null ? new ArrayList<>() : listing(a, max);
        debug.append("\n==== ").append(name).append(a == null ? "  (no leaf)" : "  leaf rva 0x" + Long.toHexString(rva(a))).append('\n');
        for (Instruction i : l) debug.append("   ").append(at(i)).append('\n');
        return l;
    }

    /**
     * The instructions at `a`. When `a` is the entry of a function Ghidra knows, this is the whole
     * function body in address order (a leaf's interesting reads often sit in a branch after its
     * first RET). Otherwise (a label thunk) it is the linear run from `a` to the first RET.
     */
    private List<Instruction> listing(Address a, int max) {
        // A flow walk: fallthrough plus every jump target (never calls), in address order, so a
        // leaf's reads in a branch past its first RET -- or in a block Ghidra split off into its
        // own function -- are still part of the listing. Bounded by `max` instructions.
        TreeMap<Long, Instruction> seen = new TreeMap<>();
        List<Address> work = new ArrayList<>();
        work.add(a);
        while (!work.isEmpty() && seen.size() < max) {
            Address at = work.remove(work.size() - 1);
            Instruction cur = getInstructionAt(at);
            if (cur == null) { disassemble(at); cur = getInstructionAt(at); }
            while (cur != null && seen.size() < max) {
                if (seen.containsKey(cur.getAddress().getOffset())) break;
                seen.put(cur.getAddress().getOffset(), cur);
                String mn = cur.getMnemonicString();
                if (mn.equals("RET") || mn.equals("INT3")) break;
                for (Address t : cur.getFlows()) {
                    if (text.contains(t) && !seen.containsKey(t.getOffset())) {
                        if (mn.equals("CALL")) continue;
                        work.add(t);
                    }
                }
                if (mn.equals("JMP") || mn.startsWith("RET")) break;
                cur = cur.getNext();
            }
        }
        return new ArrayList<>(seen.values());
    }

    /** Direct call targets (in .text, by address) of a listing, in order of first appearance. */
    private List<Address> callees(List<Instruction> l) {
        List<Address> out = new ArrayList<>();
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals("CALL") || i.toString().contains("ptr")) continue;
            Address t = callTarget(i);
            if (t != null && !out.contains(t)) out.add(t);
        }
        return out;
    }

    /** Callees to a depth: depth 1 = direct, depth 2 = also what those call. */
    private List<Address> callees(List<Instruction> l, int depth) {
        List<Address> out = callees(l);
        if (depth > 1) {
            for (Address c : new ArrayList<>(out))
                for (Address d : callees(listing(c, 300), depth - 1)) if (!out.contains(d)) out.add(d);
        }
        return out;
    }

    private void dump(String title, List<Instruction> l) {
        debug.append("\n---- ").append(title).append('\n');
        for (Instruction i : l) debug.append("   ").append(at(i)).append('\n');
    }

    // ===========================================================================================
    // Instruction helpers
    // ===========================================================================================
    private static final Pattern MEM = Pattern.compile("\\[([A-Za-z0-9]+)?(?:\\s*\\+\\s*([A-Z0-9]+)\\*(0x[0-9a-f]+))?(?:\\s*\\+\\s*(-?0x[0-9a-f]+))?\\]");

    private Mem memOf(Instruction i) {
        for (int op = 0; op < i.getNumOperands(); op++) {
            String s = i.getDefaultOperandRepresentation(op);
            if (!s.contains("[")) continue;
            Mem m = mem(i, op, s);
            if (m != null) return m;
        }
        return null;
    }

    private Mem mem(Instruction i) { return memOf(i); }

    private Mem mem(Instruction i, int op, String s) {
        Mem m = new Mem();
        Matcher mt = MEM.matcher(s);
        if (!mt.find()) return null;
        String b = mt.group(1);
        if (b != null && b.startsWith("0x")) {           // absolute / RIP-resolved
            m.absolute = toAddr(Long.parseLong(b.substring(2), 16));
            return m;
        }
        m.base = b == null ? "" : b;
        if (mt.group(2) != null) { m.index = mt.group(2); m.scale = Long.parseLong(mt.group(3).substring(2), 16); }
        if (mt.group(4) != null) {
            String d = mt.group(4);
            boolean neg = d.startsWith("-");
            m.disp = Long.parseLong(d.substring(neg ? 3 : 2), 16) * (neg ? -1 : 1);
            m.hasDisp = true;
        }
        // Ghidra prints `[RAX + 0x3360]` but an index without displacement as `[RAX + RCX*0x4]`
        if (m.base.equals("RSP") || m.base.equals("RBP") && !m.hasDisp) return m;
        return m;
    }

    private interface MemTest { boolean ok(Mem m); }

    private Instruction firstMatch(List<Instruction> l, String mnemonic, MemTest t, String immediate) {
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals(mnemonic)) continue;
            Mem m = memOf(i);
            if (m == null || !t.ok(m)) continue;
            if (immediate != null && !i.toString().endsWith("," + immediate)) continue;
            return i;
        }
        return null;
    }

    private Instruction firstRipLoad(List<Instruction> l) {
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals("MOV")) continue;
            Mem m = memOf(i);
            if (m != null && m.absolute != null && !i.getDefaultOperandRepresentation(0).contains("[")) return i;
        }
        return null;
    }

    private Address ripTarget(Instruction i) { return memOf(i).absolute; }

    private Instruction firstScaledLoad(List<Instruction> l, long scale) {
        for (Instruction i : l) {
            Mem m = memOf(i);
            if (m != null && m.scale == scale && !m.index.isEmpty() && m.hasDisp && i.getMnemonicString().equals("MOV")) return i;
        }
        return null;
    }

    /** The first `MOV reg,[clientReg + disp]` after the instruction that loaded the client pointer. */
    private Instruction loadAfterClient(List<Instruction> l, long clientRva, long minDisp) {
        String creg = null;
        for (Instruction i : l) {
            Mem m = memOf(i);
            if (m == null) continue;
            if (creg == null) {
                if (m.absolute != null && rva(m.absolute) == clientRva && i.getMnemonicString().equals("MOV")) creg = destReg(i);
                continue;
            }
            if (i.getMnemonicString().equals("MOV") && m.base.equals(creg) && m.hasDisp && m.disp >= minDisp && i.getDefaultOperandRepresentation(1).contains("[")) return i;
        }
        return null;
    }

    private boolean isClientReg(List<Instruction> l, Instruction use) {
        Mem m = memOf(use);
        return m != null && isClientBase(l, m);
    }

    /**
     * Does the base register of `m` hold the client object pointer? True when SOME instruction in the
     * listing loads that register from the client global and nothing between that load and `use`
     * writes the register again (calls clobber the volatile ones). `use` null = anywhere.
     */
    private boolean isClientBase(List<Instruction> l, Mem m) { return isClientBase(l, m, null); }

    private boolean isClientBase(List<Instruction> l, Mem m, Instruction use) {
        long client = val("CLIENT_OBJ_PTR");
        int end = use == null ? l.size() : l.indexOf(use);
        if (end < 0) end = l.size();
        boolean live = false;
        for (int k = 0; k < end; k++) {
            Instruction i = l.get(k);
            String mn = i.getMnemonicString();
            Mem x = memOf(i);
            boolean loadsClient = x != null && x.absolute != null && rva(x.absolute) == client && mn.equals("MOV") && destReg(i).equals(m.base);
            if (loadsClient) { live = true; continue; }
            if (use == null) continue;
            if (mn.equals("CALL") && isVolatile(m.base)) live = false;
            else if (!mn.equals("CMP") && !mn.equals("TEST") && !mn.startsWith("J") && i.getNumOperands() > 0
                    && destReg(i).equals(m.base) && !i.getDefaultOperandRepresentation(0).contains("[")) live = false;
        }
        return use == null ? live : live;
    }

    private static boolean isVolatile(String r) {
        return r.equals("RAX") || r.equals("RCX") || r.equals("RDX") || r.equals("R8") || r.equals("R9") || r.equals("R10") || r.equals("R11");
    }

    private Set<Long> dwordFields(List<Instruction> l, long min, long max) {
        Set<Long> s = new LinkedHashSet<>();
        for (Instruction i : l) {
            if (!i.getMnemonicString().equals("MOV")) continue;
            String src = i.getNumOperands() > 1 ? i.getDefaultOperandRepresentation(1) : "";
            if (!src.contains("[") || !i.toString().contains("dword ptr")) continue;
            Mem m = memOf(i);
            if (m != null && m.hasDisp && m.index.isEmpty() && m.disp >= min && m.disp < max && !m.base.equals("RSP") && !m.base.equals("RBP")) s.add(m.disp);
        }
        List<Long> sorted = new ArrayList<>(s);
        java.util.Collections.sort(sorted);
        return new LinkedHashSet<>(sorted);
    }

    private Instruction findInc(long disp) {
        String needle = "0x" + Long.toHexString(disp) + "]";
        Instruction cur = getFirstInstruction();
        while (cur != null) {
            if (cur.getMnemonicString().equals("INC") && cur.toString().contains("dword ptr") && cur.toString().endsWith(needle)) return cur;
            cur = cur.getNext();
        }
        return null;
    }

    private Address thunkTarget(List<Instruction> l) {
        for (int i = 0; i < Math.min(3, l.size()); i++) {
            Instruction ins = l.get(i);
            if (ins.getMnemonicString().equals("JMP")) {
                for (Reference r : ins.getReferencesFrom()) if (text.contains(r.getToAddress())) return r.getToAddress();
            }
        }
        return null;
    }

    /** True when the instruction references defined string data (another binding's name). */
    private boolean refersToString(Instruction i) {
        for (Reference r : i.getReferencesFrom()) {
            var d = getDataAt(r.getToAddress());
            if (d != null && d.hasStringValue()) return true;
        }
        return false;
    }

    private Address textLea(Instruction i) {
        if (!i.getMnemonicString().equals("LEA")) return null;
        for (Reference r : i.getReferencesFrom()) if (text != null && text.contains(r.getToAddress())) return r.getToAddress();
        return null;
    }

    private Address callTarget(Instruction i) {
        for (Reference r : i.getReferencesFrom()) if (r.getReferenceType().isCall() && text.contains(r.getToAddress())) return r.getToAddress();
        return null;
    }

    private String destReg(Instruction i) { return i.getNumOperands() > 0 ? i.getDefaultOperandRepresentation(0) : ""; }

    private Instruction firstRef(String name) {
        for (Address s : findStrings(name)) for (Reference r : getReferencesTo(s)) {
            Instruction i = getInstructionAt(r.getFromAddress());
            if (i != null) return i;
        }
        return null;
    }

    private List<Address> findStrings(String want) {
        List<Address> hits = new ArrayList<>();
        for (var data : DefinedDataIterator.byDataInstance(currentProgram, d -> d.hasStringValue())) {
            String v = data.getDefaultValueRepresentation();
            if (v != null && v.replaceAll("^\"|\"$", "").equals(want)) hits.add(data.getAddress());
        }
        if (hits.isEmpty()) {
            byte[] pat = (want + "\0").getBytes();
            for (MemoryBlock b : currentProgram.getMemory().getBlocks()) {
                if (!b.isInitialized() || b.isExecute()) continue;
                Address a = currentProgram.getMemory().findBytes(b.getStart(), b.getEnd(), pat, null, true, monitor);
                while (a != null) {
                    // only a match at a string START counts (previous byte is NUL or the block start)
                    try { if (a.equals(b.getStart()) || currentProgram.getMemory().getByte(a.subtract(1)) == 0) hits.add(a); } catch (Exception ignored) { }
                    a = currentProgram.getMemory().findBytes(a.add(1), b.getEnd(), pat, null, true, monitor);
                }
            }
        }
        return hits;
    }

    // ===========================================================================================
    // Output helpers
    // ===========================================================================================
    private void put(String name, long value, String evidence) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("value", value);
        e.put("evidence", evidence);
        out.put(name, e);
        println(String.format("  %-24s = 0x%-10x %s", name, value, evidence));
    }

    private long val(String name) {
        Map<String, Object> e = out.get(name);
        return e == null ? -1 : (Long) e.get("value");
    }

    private long rva(Address a) { return a.getOffset() - base; }

    private String at(Instruction i) { return String.format("%06x  %s", rva(i.getAddress()), i.toString()); }

    private String hex(Set<Long> s) { StringBuilder b = new StringBuilder("["); for (Long v : s) b.append("0x").append(Long.toHexString(v)).append(' '); return b.append(']').toString(); }

    private static String esc(String s) { return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " "); }

    private static String sha256(String path) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(Files.readAllBytes(new File(path).toPath()));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
}
