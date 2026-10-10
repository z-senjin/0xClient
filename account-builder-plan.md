# Account Builder — plan

The reference for building the Account Builder plugin out, phase by phase. Read it before working on the
builder; tick items and add findings as they happen. Last updated 2026-10-09.

## The goal

Pick a **profile** from a list, press start, and the builder trains a fresh free-to-play account on its own:
skilling, combat, a few quests, banking and supplying up, and buying/selling on the Grand Exchange to pay
for it all. It does not follow a fixed order: it picks the next goal **at random** from the goals not yet
reached, trains it for a **stint**, then picks again. Where it trains is also picked at random from the
spots that suit its current level.

Kept deliberately simple:
- No scoring system or "AI". Random choice among what's eligible, nothing more.
- Randomness is about *what* to train and *where*. No timing, mouse or break patterns meant to look human,
  no spoofing, no multi-account tooling (the boundary set earlier in this project).
- Everything goes through the game's own functions (repo rule 1), every new offset says how it was found
  (rule 2), nothing is called working until it has been seen working (rule 3).

## Decisions so far (from the user, 2026-10-09)

| Question | Answer |
|---|---|
| Account | Free-to-play, fresh off Tutorial Island. A **F2P / P2P mode** setting exists; only F2P is built now. |
| Skills | Gathering, processing, combat (melee, ranged **and** magic). Members skills: placeholders only, marked TODO. |
| Goals | **Pick a profile from a list** (no typing). For now every profile sets every skill to **99**. |
| Gold | **Sell what it gathers** on the GE to fund tools and supplies. |
| Quests | Starter list: **Cook's Assistant, Sheep Shearer, Romeo & Juliet, The Restless Ghost**. |
| Stint length | **A setting**: min and max minutes; each stint picks a random length between them. |
| Al Kharid toll gate | **Pay it (10 gp) once the account has gold**; avoid it while it has none. |
| Make-X chooser | **Always "All".** |
| Combat food | **Cook its own; buy on the GE if short.** |
| Coordinates | The user provides them (read off the Inspector plugin), one location at a time. |

## How it will work

```
Profile (list)  ──>  Goals (skill → 99, quest → done)
                         │
              ┌──────────┴──────────┐
              │  Picker: random     │  among goals not reached and not parked
              └──────────┬──────────┘
                         │ one stint (random min..max minutes, or goal reached)
              ┌──────────┴──────────┐
              │  Method for current │  from the skill's real level, at a random suitable spot
              │  level + spot        │
              └──────────┬──────────┘
                         │ needs: tools / food / runes / ammo / quest items
              ┌──────────┴──────────┐
              │  Supply: bank first,│  then the GE (sell gathered items for gold if short)
              │  then GE            │
              └──────────┬──────────┘
                         └── task runs (GatherTask, ProcessTask, CombatTask, QuestTask)
```

- A **goal that fails** 3 times in a row is **parked** for a while and the picker moves on; the panel says
  why it was parked.
- A goal whose requirements are not met yet (a level, a quest, gold) is skipped by the picker until they
  are. Example: iron needs Mining 15; Runecrafting needs Rune Mysteries.
- Progress (current goal, stint end, parked goals) is saved like `Script` does today, so a restart resumes.

## Phases

Status: `[ ]` todo · `[~]` built, not seen working · `[x]` seen working live.

### Phase 0 — Foundations (mostly done)
- [x] Walker with doors and stairs (seen: Lumbridge bank top floor).
- [x] Bank: open, deposit-all (op 8), withdraw-1 (op 1).
- [x] Gather loop with banking (seen: fishing with banking).
- [~] Continue dialogues / pick options through the client's continue sender (ACT_RESUME). Needs: a
      level-up clicked through by itself in `gradlew debugRun`.

### Phase 1 — Goals, picker, profiles, spots
- [ ] `Goal` (skill + target, or quest) and `Profile` (named list of goals). Profiles: "F2P — all skills 99".
- [ ] `Picker`: random goal among eligible ones; stint length random between the min/max settings.
- [ ] Method chosen from the **real** level at the start of each stint (not when the plan is read).
- [ ] Each method has **several spots**; one is picked at random per stint.
- [ ] Parking of failing goals; panel shows goal, stint time left, parked goals.
- [ ] AccountBuilder settings: mode (F2P/P2P), profile (list), stint min/max minutes; the plan text box goes.
- [ ] Members skills listed in the profile as placeholders that the picker never chooses (TODO).

### Phase 2 — Supplying up
- [ ] One `Supplies` step before every stint: what this method needs (tools, food, runes, ammo, quest items).
- [ ] Take it from the bank (withdraw-X / withdraw-all ops; withdraw-X needs the amount prompt).
- [ ] Best tool for the level: bronze → iron → steel → mithril → adamant → rune axe/pickaxe.
- [ ] Missing from the bank → a buy request for Phase 3; until then the goal is parked with "needs X".

### Phase 3 — Grand Exchange
- [ ] Reverse engineer the GE: offer slots, Buy/Sell buttons, item search, price and quantity entry,
      confirm, collect. (`GrandExchange.buyButton` is unmapped today.)
- [ ] Buy: an item, an amount, at guide price + a margin; wait; collect.
- [ ] Sell: gathered items above a keep-amount, at guide price − a margin; collect gold.
- [ ] Gold check before buying: short → sell first.
- [ ] Walk to the GE and back (Varrock).
- [ ] Walker: pay the Al Kharid toll gate when carrying 10+ gp (today it is avoided: AVOID_OBJECTS 44050/44051).

### Phase 4 — Processing skills
- [ ] Make-X interface (the "how many?" chooser that cooking, smelting and smithing open): RE once, used by all.
- [ ] Cooking: raw fish on a range (`useItemOn` loc — sender found, not seen working from 0xClient).
- [ ] Firemaking: tinderbox on logs (`useItemOn` item), stepping along a line of open tiles.
- [ ] Smithing: smelt at a furnace, smith at an anvil (smithing interface: RE).
- [ ] Crafting: wool on the spinning wheel (Lumbridge), leather (tanning is in Al Kharid — see open questions), gems.
- [ ] Runecrafting (F2P): needs Rune Mysteries + talisman; listed, after the starter quests.
- Fletching is members-only: placeholder.

### Phase 5 — Combat
- [ ] Reverse engineer: current hitpoints (boosted level), who you are fighting, attack style (combat tab),
      spell selection (spellbook), autocast.
- [ ] Attack an NPC by name in an area (NPC actions exist), eat below a health threshold, leave when out of food.
- [ ] Food: cooked fish from the bank (its own Fishing/Cooking); buy on the GE when short.
- [ ] Melee: switch attack style to the skill the stint is training (Attack / Strength / Defence).
- [ ] Ranged: bow + arrows from supplies; pick up arrows.
- [ ] Magic: strike spells on NPCs (runes from supplies).
- [ ] Prayer: bury bones from kills.
- [ ] Loot: bones, and anything worth selling (GE prices).

### Phase 6 — Quests
- [ ] Quest framework: a quest is a list of steps; the step to do is chosen from the quest's progress value
      in the game's variables (varps/varbits, already readable), so it resumes mid-quest.
- [ ] Step types: walk, talk (with option choices), get item (bank / buy / pick up / make), use item on X.
- [ ] Cook's Assistant, Sheep Shearer, Romeo & Juliet, The Restless Ghost. Each one's progress variable is
      looked up and confirmed live before its steps are written.

### Phase 7 — Members mode (later)
- [ ] Placeholders only: Agility, Herblore, Thieving, Fletching, Slayer, Hunter, Farming, Construction.
- [ ] P2P spots and methods for the existing skills.

## Locations

Coordinates are the tile x, y and plane shown by the Inspector plugin, standing in the middle of the spot.
`TBD` = waiting on the user.

| Spot | Used for | Coordinates |
|---|---|---|
| Lumbridge trees | Woodcutting 1–15 | in `Places` (seen) |
| Draynor oaks | Woodcutting 15–30 | in `Places` |
| Draynor willows | Woodcutting 30+ | in `Places` |
| Varrock East mine | Mining 1–15 copper/tin, 15+ iron | in `Places` |
| Draynor fishing | Fishing shrimps 1–20 | in `Places` (seen) |
| Lumbridge oaks / willows (near castle) | Woodcutting second spot | TBD |
| Varrock West trees / oaks | Woodcutting third spot | TBD |
| Lumbridge Swamp mine (east) | Mining copper/tin second spot | TBD |
| Al Kharid / Rimmington mine | Mining iron second spot | TBD |
| Lumbridge Swamp fishing | Fishing shrimps second spot | TBD |
| Barbarian Village fishing | Fishing trout/salmon 20+ (fly rod + feathers) | TBD |
| Lumbridge range (castle kitchen) | Cooking | TBD |
| Falador / Edgeville furnace | Smithing (smelting) | TBD |
| Varrock West anvils | Smithing | TBD |
| Lumbridge spinning wheel | Crafting / Sheep Shearer | TBD |
| Grand Exchange | Phase 3 | TBD |
| Cows / chickens (Lumbridge) | Combat 1–20 | TBD |
| Al Kharid warriors / Barbarian Village | Combat 20+ | TBD |

## Open questions

- None right now. Add new ones here as they come up.

## Findings log

- 2026-10-09: plan written; Phase 0 continue-dialogue fix delivered, waiting on a live test.
