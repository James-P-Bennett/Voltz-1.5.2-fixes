# Voltz 1.5.2 fixes

Bug fixes for mods in the **Voltz** modpack (Minecraft 1.5.2), applied as bytecode
patches to the shipped jars.

**These are server-side patches and they work against an unmodified Voltz client.**

Each patch is selectable individually.

| Mod | Patches |
|---|---|
| [Atomic Science v0.6.2.117](#atomic-science-v062117) | `assemblerwear` · `syncspawn` · `plasma` · `noblastdamage` |
| [MPS Addons 0.2.3](#mps-addons-023) | `magnet` |
| [MFFS 3.1.0 — BalancedMFFS](#mffs-310--balancedmffs) | `zones` · `logging` · `mergedupe` · `stabilizedupe` |
| [Mekanism 5.5.6](#mekanism-556) | `chestcrash` · `chestdupe` · `chestremote` · `machinedupe` · `robitdupe` · `tntdupe` · `tntsource` · `timeitems` |
| [ICBM Explosion 1.2.1](#icbm-explosion-121) | `redmatter` · `sonic` · `remote` · `explosivetype` · `empradius` · `launchertier` · `multiblock` · `cruiselauncher` · `designator` · `defuser` · `missilestack` · `listeners` · `radarradius` · `radargun` · `chunkload` |
| [ICBM Sentry 1.2.1](#icbm-sentry-121) | `terminal` · `turretpackets` · `multiblock` · `ammodupe` · `listeners` · `consolecap` · `targetcommand` · `antimatterammo` · `doubleenergy` · `accesscommands` |
| [ICBM Contraption 1.2.1](#icbm-contraption-121) | `camouflage` · `detector` · `listeners` · `chunkload` |
| [Modular Powersuits 0.7.0](#modular-powersuits-070) | `blink` |
| [MineFactoryReloaded 2.6.4](#minefactoryreloaded-264) | `ghostslot` |

---

## Atomic Science v0.6.2.117

Four fixes for particle accelerator, fusion reactor and Atomic Assembler bugs. The
patcher rewrites five classes in `Atomic_Science_v0.6.2.117.jar` and adds one.

<details>
<summary><b>1. <code>assemblerwear</code> — the Assembler only wears 5 of its 6 cells</b></summary>

**The bug.** `TGouCheng.yong()` checks all six slots for a strange matter cell, then
wears them in a loop that stops one short:

```java
if (nengYong()) {
    for (int i = 0; i < 5; i++) {        // requires 6 cells, damages 5
        if (containingItems[i] != null) {
            containingItems[i].setItemDamage(containingItems[i].getItemDamage() + 1);
            if (containingItems[i].getItemDamage() >= 64) containingItems[i] = null;
        }
    }
```

Slot 5's cell is checked for presence every cycle and never takes a point of damage, so
it lasts forever. Real cost is **5 cells per 64 duplications instead of 6**.

**The patch.** The loop bound becomes `VoltzFixConfig.assemblerSlots` — 6 when enabled,
5 when not. Targeted by the `ICONST_5` immediately followed by `IF_ICMPGE` inside
`yong()`; the build fails unless exactly one such site exists.

![Atomic Assembler mid-duplication with all six strange matter cells loaded](media/assembler-six-cells.png)

*All six cells loaded and wearing. Before the patch the bottom slot's cell sat at full
durability forever while the other five ground down.*

</details>

<details>
<summary><b>2. <code>syncspawn</code> — accelerators desync on placement</b></summary>

**The bug.** `TJiaSuQi.ticks` starts at `0` when the TileEntity is *constructed*, and
the particle spawn gate is:

```java
if (wattsReceived >= 10000 && slot0 != null && ticks % 20 == 0) { spawn }
```

Two accelerators placed at different moments carry a permanent phase offset. Their
particles never meet at the midpoint of the tube, so the pair produces nothing — and a
lone particle flies the full corridor and detonates on the machine. The only cure is a
world reload, which reconstructs both TileEntities on the same tick.

This is why a freshly built accelerator is a coin flip, and why *repairing* one breaks it.

**Measured.** Re-placing one accelerator left it **5 ticks** out of phase and more than
halved output. A later re-place left them **9 ticks** out, and every cycle detonated a
lone particle with nothing within 4.28 blocks:

```
t=52176  suDu=0.08931  cell=1271/4/-76  dist=0.10201  expl=false
t=52275  suDu=0.15300  cell=1276/4/-76  dist=4.28307  expl=true    <- alone
```

**The patch.** Gate on world time instead of the per-machine counter:

```
before:  this.ticks % 20 == 0
after:   VoltzFixConfig.spawnClock(world.getWorldTotalTime(), this.ticks) % 20 == 0
```

`spawnClock` returns world time when the option is on and the original counter when it
is off, so every accelerator shares one clock regardless of placement order.
**No restart is ever needed.** `getWorldTotalTime` (`func_82737_E`) is the
monotonic counter and is *not* moved by `/time set` — verified, because the alternative
`func_72820_D` is the day clock.

Only the spawn gate is touched. It is identified by the `LDC 20L` that follows it, so the
unrelated `ticks % 5` packet-send gate is left alone.

The before/after pair in the `noblastdamage` section below shows the combined effect: a
synced pair colliding cleanly, and nothing left standing when they don't.

</details>

<details>
<summary><b>3. <code>plasma</code> — stranded plasma is permanent and lethal</b></summary>

**The bug.** `BDengLiZiTi` (plasma) gets exactly one decay tick, scheduled in
`onBlockAdded`:

```java
world.scheduleBlockUpdate(x, y, z, blockID, getBlockMetadata(world) * 5);
```

The fusion reactor spawns plasma at metadata 7, so that is 35 ticks. **Nothing re-arms
it.** `onBlockAdded` never runs again for an existing block, and the only other scheduler
is `onNeighborBlockChange`. If that pending entry is ever lost, the block is permanent.

That matters because touching plasma is instant death — no armour helps — and it deletes
any item that falls into it rather than dropping it.

A stuck block also **deadlocks the reactor**. The spawn cell two blocks out from the
reactor's facing side fails `canPlace`, so `spawn()` becomes a no-op — but the 20,000 W
and the deuterium are consumed anyway. And the heat that drives the boiler is a one-shot
`+100 °C` pulse delivered in `onBlockAdded`, so plasma merely sitting there contributes
nothing: no new placements, no heat, no steam, no power. The reactor burns fuel forever
at zero output with nothing anywhere reporting a fault.

The only recovery in the stock mod is `onNeighborBlockChange` — changing a block next to
the plasma schedules its decay. That means breaking into a containment full of something
that kills you on contact, and opening the shell lets the plasma out: it spreads at 80%
per face and **replaces** anything that isn't bedrock, an iron block, or an electromagnet.

**The patch.** `setTickRandomly(VoltzFixConfig.plasmaDecay)` in the constructor. Random
ticks are re-derived from the chunk every tick and cannot be lost, and `updateTick`
unconditionally converts plasma to fire, so any orphan dies on its next random tick. The
normal 35-tick scheduled decay still fires first; this is purely a backstop.

![Fusion reactor containment seen from below, packed with purple plasma blocks](media/plasma-containment.png)

</details>

<details>
<summary><b>4. <code>noblastdamage</code> — particle explosions destroy your machine</b></summary>

**The bug.** A correctly running accelerator detonates a survivor on its own
electromagnet **every single cycle**. Not an accident — normal operation:

```
collision separations: {'0.20017': 31, '-1.00000': 31}
```

31 of 31 survivors died with nothing in range, at `cell=1270/4/-76`, `blockAt=3778`,
`suDu=0.59452` — strength `0.59452 * 2.5 = 1.486` onto a magnet, once per 420 ticks.

Whether a given blast eats the block is **vanilla RNG**: `Explosion.doExplosionA` casts
rays with `f = size * (0.7F + rand.nextFloat() * 0.6F)`, so the effective radius varies
±30% per explosion. Identical blasts destroy different blocks. That is the entire source
of "it randomly blew up after working for hours" — the machine was firing survivable
blasts the whole time and one finally rolled high.

**The patch.** `World.createExplosion(e,x,y,z,size,flag)` routes `flag` to `isSmoking`:

```java
return newExplosion(e, x, y, z, size, false /*isFlaming*/, flag /*isSmoking*/);
```

and `Explosion.doExplosionB` gates the **entire** block-destruction loop on it
(`getfield b:Z` / `ifeq`), while entity damage and knockback happen in `doExplosionA`,
which runs regardless. The argument becomes `VoltzFixConfig.blastDamage`, so switching it
off removes all block damage and block drops while leaving the knockback — which is the
strange-matter production mechanism — completely intact.

There is exactly **one** `createExplosion` call site in `EWuSu`, inside `explode()`, and
every path reaches it (collision, `!canCunZai`, `isCollidedHorizontally`).

**Verified.** 31+ explosions landing directly on electromagnets, **zero blocks lost**,
against a stock baseline of roughly 1 block per 16 knocks.

**Configurable** — see below. Default is damage off.

**Before**

![A particle explosion has blown a crater through the ground beneath the accelerator](media/blast-damage-before.png)

**After**

![The same accelerator intact after an explosion, with strange matter cells dropped nearby](media/blast-damage-after.png)

</details>

<details>
<summary><b>Configuration</b></summary>

The four Atomic Science patches are toggleable in `config/VoltzFixes.cfg`, written at mod init. Every
option defaults to the fixed behaviour; set one to `false` to restore stock Atomic
Science for that fix alone. No rebuild needed.

```
general {
    B:"Assembler Wears All Six Cells"=true
    B:"Disable Explosion Block Damage"=true
    B:"Plasma Self Decay Backstop"=true
    B:"Sync Accelerator Spawns To World Time"=true
}
```

| Option | `true` (default) | `false` |
|---|---|---|
| Assembler Wears All Six Cells | consumes all 6 cells | stock: slot 5 never wears |
| Disable Explosion Block Damage | no block damage or drops; knockback intact | stock: blasts destroy blocks |
| Plasma Self Decay Backstop | plasma ticks randomly, orphans still decay | stock: one scheduled tick only |
| Sync Accelerator Spawns To World Time | one shared clock, placement order irrelevant | stock: per-machine counter |

The values are read at startup and logged, so you can confirm what is active:

```
[VoltzFixes] blastDamage=false syncSpawn=true plasmaDecay=true assemblerSlots=6
```

Note `blastDamage` is the internal inverse of the user-facing
`Disable Explosion Block Damage`.

Turning **Disable Explosion Block Damage** off is only sensible if a third injector
suppresses the blast — otherwise a correctly synced accelerator detonates a survivor on
its own electromagnet every cycle and the machine slowly eats itself.

</details>

<details>
<summary><b>Known-good telemetry</b></summary>

What a correctly running accelerator pair looks like — use this to diagnose:

| Metric | Value |
|---|---|
| Spawn events | both accelerators, **same tick**, phase constant |
| Collision separation | **0.20017**, identical every cycle |
| Cycle length | **420 ticks** |
| Knocks | **100%** of collisions, `0.07531 -> 0.59732` |
| Survivor death | `suDu=0.59452`, on an electromagnet |
| Output | ~1 strange matter cell per 2.7 min |

Deviations and what they mean:

- **540-tick solo cycle** — one accelerator is out of cobble. Hand-filled hoppers never
  hold the same amount, so one always empties first and the other fires unpaired
  particles into your machine. This is the most common real-world failure. Automate it.
- **`dist` in the 0.377–0.700 range** — dead band. The collision is *detected* (box is
  0.6 plus 0.1 entity half-width) but the impulse radius is `5 * suDu`, so below
  `suDu 0.14` a collision can register with no knock at all. The particle is consumed
  for nothing.
- **`dist` above ~1.0 with `expl=true`** — the particle died alone.

### Tube geometry

For two accelerators firing on the same tick, spawn centres `G` apart, straight corridor:

```
separation at check n:  d(n) = G - 0.0007 * n * (n+1)     n = 9, 19, 29, ...
speed at check n:       s    = 0.0007 * n
impulse radius:         R    = 5 * s
```

A knock happens only when `|d| < R`. Verified exactly: a 6.0 gap gives `d(89) = 0.393`
against `R = 0.3115`, logged as `dist=0.39300` with **zero** knocks.

- Gaps that knock: **4.5, 5.5, 7.0, 8.5, 10.0**
- Gaps that phase through entirely: 11.0, 12.5, 14.5, 16.5, 17.0, 19.0, 21.0+

Above gap 10 the 10-tick sampling can no longer keep up: the pair closes
`0.0007 * (20n + 110)` per check against a 1.4-wide detection window, so past ~10 blocks
of travel they can step clean over each other. **Longer is not better.**

</details>

<details>
<summary><b>Gotchas (not patched)</b></summary>

- **Electromagnet glass collects no heat.** `BDianCiBuoLi.hasTileEntity()` returns
  `false`, so it has no `TDianCiKe` and the `+100 °C` from adjacent plasma is silently
  discarded. Only the **solid** electromagnet boils water. A containment built entirely
  of glass gives a reactor that burns 20,000 W and deuterium forever at zero output with
  no error anywhere. Deliberate, but undiscoverable. Left alone because changing it would
  make glass strictly better than the solid block.
- **`Math.min(motion, 1.0)` only caps positive motion.** Particles travelling west, north
  or down are uncapped and sail past `suDu 1.0`, at which point the accelerator consumes
  them for antimatter — remotely, with no return trip and **no explosion**. East, south
  and up clamp at exactly 1.0 and can never convert.

</details>
## MPS Addons 0.2.3

<details>
<summary><b>Item magnet fix</b></summary>

### 1. `magnet` — never worked in multiplayer

The Magnet module has never done anything on a server. The pulling was only ever
implemented client-side in `ClientTickHandler`, and item entity positions are server
authoritative, so the client's motion was overwritten by the next tracker update every
tick while the module carried on draining 200 J. It appeared to work in singleplayer only
because the client and the integrated server share one world object.

The patch moves the pull into `CommonTickHandler`, which already walked every nearby item
and computed the deltas but used them for nothing but a range-1.0 pickup check. The server
now does the pulling and vanilla's entity tracker syncs it to every client. Pickup
behaviour is unchanged.

Written to `config/VoltzFixes-MPSA.cfg`:

```
general {
    B:"Server Side Item Magnet"=true
    D:"Magnet Pull Speed"=0.35
}
```

![The Magnet module in the MPS tinker table, showing its description and 200J energy cost](media/magnet-module.png)

</details>

---

## MFFS 3.1.0 — BalancedMFFS

<details>
<summary><b>Zone flags and admin logging for interdiction matrices</b></summary>

Anti-Personnel and Confiscate are useful but almost impossible to administrate, so
servers tend to ban them outright. **BalancedMFFS** makes them governable instead:
admins deny them where they cause trouble and get a console record of everything they do.

### What the modules actually do

Read off the bytecode, because neither is obvious in game:

- **Anti-Personnel** strips the player's **entire inventory into the matrix**, then deals
  `Integer.MAX_VALUE` damage. The victim's gear ends up inside the projector, not on the
  ground.
- **Confiscate** silently moves filtered stacks out of player inventories into the matrix.

Neither leaves any trace, which is the real administrative problem.

### Zone flags

`config/BalancedMFFS.txt`, re-read within seconds of an edit — no restart, no commands:

```
world <dim> <flag>
zone  <dim> <x1> <y1> <z1> <x2> <y2> <z2> <flag>

world 0 mffs.antipersonnel
zone  0 -200 0 -200 200 256 200 *
```

Flags: `mffs.antipersonnel` `mffs.confiscate` `mffs.antihostile` `mffs.antifriendly`
`mffs.warn`, or `*` for all. Boxes are inclusive and accept corners in any order.

Every interdiction module acts through `onDefend(IInterdictionMatrix, EntityLiving)`, and
the base class's own body is `return false`. The guard returns false inside a denied zone,
so a blocked module behaves exactly like one that chose not to act — no exception, no
half-applied effect. The check uses the **entity's** position, so a player in a safe zone
is protected wherever the matrix is.

### Logging

```
[BalancedMFFS] matrix loaded at dim0 123,64,-77  modules: AntiPersonnel, Confiscate
[BalancedMFFS] KILL  Steve at dim0 120,64,-75  by matrix dim0 123,64,-77  (inventory absorbed into matrix)
[BalancedMFFS] CONFISCATE  Steve lost 12x Iron Ingot  at dim0 120,64,-75  to matrix dim0 123,64,-77
```

Kept quiet deliberately:

- **Matrix load** is keyed on the TileEntity instance, which is rebuilt on each chunk
  load, so it prints once per load — and only for a matrix actually carrying
  Anti-Personnel or Confiscate.
- **Kills** are hooked *after* the damage call, so only a real kill prints. `onDefend`
  runs against every nearby entity every scan; logging there would flood the console.
- **Confiscations** are hooked per stack, giving the real item and count.

`logging off` in the config disables all of it.

</details>

<details>
<summary><b><code>mergedupe</code> — conveyor belts multiply everything the matrix takes</b></summary>

**The bug.** Confiscate and Anti-Personnel hand every stack they take to the matrix, which
offers it to each adjacent inventory through `TileEntityInventory.addStackToInventory`:

```java
inventory.setInventorySlotContents(slot, stack);
if (inventory.getStackInSlot(slot) == null) return stack;   // "refused" - keep it
return null;
```

An MineFactoryReloaded conveyor belt is an inventory whose insert drops a **copy** of the item
on the belt, and whose slots always read empty. So the matrix treats every insert as refused,
offers the same stack to the next side, and finally drops it on top of itself. Each stack
comes out once per accepting belt plus the original. Stand on the belts in range and the
copies come straight back to be taken again, every 10 ticks.

A belt on top of the matrix, or running past its side, accepts. A flat belt pointing straight
into it refuses its front face and does not dupe.

**The patch.** An insert into an empty slot counts as done, as it does for vanilla hoppers
and pipes.

**Verified.** One matrix merge of 64 diamonds, on the Forge test server:

| Belt next to the matrix | Stock | Patched |
|---|---|---|
| On top | 128 | 64 |
| Running past the side | 128 | 64 |
| Flat, pointing in | 64 | 64 |
| None | 64 | 64 |

In game on stock, one stack filled a 31-slot inventory within a minute.

</details>

<details>
<summary><b><code>stabilizedupe</code> — the Stabilize module builds free blocks out of filter slots</b></summary>

**The bug.** A projector with the Stabilize module builds its field from block items in the
inventories touching it. It reads **every slot** of those inventories:

```java
for (int slot = 0; slot < inventory.getSizeInventory(); slot++) {
    ItemStack stack = inventory.getStackInSlot(slot);
    if (stack != null && stack.getItem() instanceof ItemBlock) {
        placeBlock(stack); inventory.decrStackSize(slot, 1);
```

Machines mark slots that must not be pulled from through `ISidedInventory`, which hoppers,
Applied Energistics buses and MFR's own Ejector all respect. Stabilize ignores it. The worst
case is MFR's filter ("ghost") slots, which hold a free size-1 copy of whatever the player
clicks with. Put a diamond block in an Item Router filter, set a Stabilize projector next to
it, and every 2 seconds the copy becomes a real placed block. Click the filter again and it
refills for free. Any block item in any MFR filter slot works.

**The patch.** For a sided inventory, Stabilize now takes from a slot only if the face touching
the projector exposes it and `canExtractItem` allows it, the same rule a hopper uses. Plain
inventories such as chests are unaffected.

None of the seven MFR machines with filter slots exposes them for extraction, so none can feed
Stabilize from a filter any more.

**Checked** by running the real `ItemModuleStablize` bytecode, stock and patched, against mock
Minecraft classes. The inventory sits east of the projector, so its touching face is west:

| Inventory next to the projector | Stock takes | Patched takes |
|---|---|---|
| Chest, block in slot 0 | slot 0 | slot 0 |
| Filter slot only | filter slot | nothing |
| Filter slot + real slot exposed on the touching face | filter slot | real slot |
| Real slot exposed on another face only | real slot | nothing |
| Filter slot exposed, but `canExtractItem` refuses it | filter slot | nothing |

Not yet exercised on a live server.

</details>

---

## Mekanism 5.5.6

Eight fixes for crashes, dupes and packet exploits, including the Electric Chest, the Robit,
Obsidian TNT, and the Stopwatch and Weather Orb. The patcher rewrites 15 classes in
`Mekanism-v5.5.6.bugfix1.jar` and adds `VoltzMekanism` and `BalancedTimeItems`.

Verified with a server-side test harness on a dedicated Forge 1.5.2 server and on MCPC+
1.5.2 (Legacy-653). Every bug below except `tntsource` reproduced on the stock jar and was
gone on the patched one, on both servers.

<details>
<summary><b>1. <code>chestcrash</code> — a hopper on an Electric Chest crashes the server</b></summary>

**The bug.** `TileEntityElectricChest.getAccessibleSlotsFromSide` builds its slot list one
past the end of the array:

```java
int[] ret = new int[55];
for (int i = 0; i <= ret.length; i++) ret[i] = i;   // writes ret[55]
```

Every side except the bottom throws. A hopper or pipe asks for the side it faces, so the
exception comes out of a tile entity tick and takes the server down — and again on every
restart, because the chunk loads with the hopper still in place. That fits the ban list's
"corrupts chunks".

**The patch.** `new int[54]` and `i < ret.length`: storage slots 0–53, the same 54 that
`getSizeInventorySide` reports. The bottom face still exposes only the energy slot.

**Verified.** Stock: `ArrayIndexOutOfBoundsException: 55`. Patched: 54 slots.

</details>

<details>
<summary><b>2. <code>chestdupe</code> — Electric Chest item dupe, and chests inside chests</b></summary>

**The bug.** An Electric Chest opened from the hand saves through `getItemStack()`:

```java
public ItemStack getItemStack() {
    return entityPlayer.getCurrentEquippedItem();   // whatever is held NOW
}
```

If the held slot changes while the GUI is open, every later save writes the chest's
contents into the new item instead. Another Mekanism machine item accepts that inventory,
and the chest keeps its own copy.

Nothing stops an Electric Chest going inside an Electric Chest either, and each one carries
its whole inventory in its NBT.

**The patch.**

- The inventory is bound to the slot and the exact stack it was opened from. Once that
  stack leaves the slot, saves do nothing and the server closes the GUI.
- `SlotElectricChest.isItemValid` and `TileEntityElectricChest.isItemValidForSlot` refuse
  Electric Chests. Slots in the player's own inventory are unaffected.

**Verified.** Stock: a machine item switched into the held slot picked up an `Items` tag
holding the chest's contents, and a chest was accepted into a chest slot and through
automation. Patched: no tag, the chest kept its contents, and both inserts were refused.

</details>

<details>
<summary><b>3. <code>chestremote</code> — open or re-password any Electric Chest from anywhere</b></summary>

**The bug.** The password screen runs on the client, which then sends the chest's
coordinates to the server:

```java
TileEntityElectricChest chest = (TileEntityElectricChest) world.getBlockTileEntity(x, y, z);
chest.password = pass;
chest.authenticated = true;
```

The server checks nothing — not the distance, not even that a chest is there. Any client
can re-password, unlock or open any Electric Chest by naming its coordinates.

**The patch.** The coordinates must name a loaded Electric Chest within 8 blocks, the reach
vanilla chests use. Anything else is dropped and logged, at most once per player every 10
seconds:

```
[VoltzFixes] refused Electric Chest packet for 10,180,256 (out of reach) from Steve
```

Entering a password is still checked on the client, against a copy the server syncs to it.
The server never sees what was typed, so that part cannot be fixed without a client change.

**Verified.** From 30 blocks away, stock let the password be changed and opened the chest.
Patched refused both.

</details>

<details>
<summary><b>4. <code>machinedupe</code> — Mekanism GUIs outlive their machine</b></summary>

**The bug.** `TileEntityContainerBlock.isUseableByPlayer` is `return true`. Every Mekanism
machine and the Electric Chest inherit it, so an open GUI stays live at any distance, and
after the machine is broken or wrenched.

**The patch.** The vanilla chest rule: the tile entity must still be the one at its
position, and the player within 8 blocks. Otherwise the server closes the GUI.

**Verified.** Stock: usable from 30 blocks, and after the block was removed. Patched: not
usable in either case, and still usable when adjacent.

</details>

<details>
<summary><b>5. <code>robitdupe</code> — open any Robit's inventory from anywhere</b></summary>

**The bug.** The Robit GUI buttons send the Robit's entity id, and the server opens that
Robit's GUI without checking anything. The Robit containers' `canInteractWith` is
`return true`, so a Robit's inventory also stays open after it is picked up or killed.

**The patch.** The id must be a live Robit within 8 blocks, or no GUI opens. Robit GUIs
close when the Robit dies or goes out of reach.

**Verified.** Stock: a Robit's inventory opened from 30 blocks, and stayed usable after the
Robit died. Patched: nothing opened, and the container was closed in both cases.

</details>

<details>
<summary><b>6. <code>tntdupe</code> — breaking Obsidian TNT drops two</b></summary>

**The bug.** `BlockObsidianTNT.onBlockDestroyedByPlayer`:

```java
if ((meta & 1) == 0) {
    dropBlockAsItem_do(world, x, y, z, new ItemStack(Mekanism.ObsidianTNT, 1, 0));
}
```

The normal harvest has already dropped the block, so every unprimed break gives two.
Vanilla TNT only primes itself here.

**The patch.** That drop is removed. Priming is unchanged.

**Verified.** Stock: the method spawned one extra item. Patched: none.

</details>

<details>
<summary><b>7. <code>tntsource</code> — Obsidian TNT explodes with no source</b></summary>

**The bug.** `EntityObsidianTNT.explode()` calls `world.createExplosion(null, ...)`. Vanilla
TNT passes itself. MCPC+ hands that entity to `EntityExplodeEvent`, so with `null` there is
nothing to attribute the blast to.

**The patch.** It passes itself.

Not exercised by the test harness. It is a one-instruction change (`aconst_null` to
`aload_0`), and the class passes bytecode verification.

</details>

<details>
<summary><b>8. <code>timeitems</code> — BalancedTimeItems: the Stopwatch and Weather Orb</b></summary>

**The bug.** The Stopwatch and Weather Orb screens send a packet, and the server trusts it:

```java
player.getCurrentEquippedItem().damageItem(4999, player);
MekanismUtils.setHourForward(world, dataStream.readInt());
```

It never checks that the held item is a Stopwatch, or that it has recharged. Any client can
change the time or weather at will.

**The patch.** BalancedTimeItems runs first. The sender must hold the real item, fully
recharged. Then the configured mode decides:

| Mode | What happens |
|---|---|
| `allow` | stock behaviour, subject to the cooldown |
| `disable` | nothing; the player is told the item is disabled |
| `command` | the change is cancelled and a command runs as the player instead |

Commands go through Bukkit on MCPC+, so plugin commands such as `/voteday` work, and
through the vanilla command manager on plain Forge.

`config/BalancedTimeItems.cfg`, comments trimmed:

```
stopwatch {
    I:"Cooldown Seconds"=250
    S:Mode=allow
    S:"Sunrise Command"=voteday
    S:"Noon Command"=voteday
    S:"Sunset Command"=votenight
    S:"Midnight Command"=votenight
}

"weather orb" {
    I:"Cooldown Seconds"=250
    S:Mode=allow
    S:"Clear Command"=votesun
    S:"Storm Command"=voterain
    S:"Haze Command"=voterain
    S:"Rain Command"=voterain
}
```

- **Cooldown Seconds** is per player. The item's damage is set to match, capped at the
  stock 250 seconds, so its bar shows the wait.
- Commands need no leading slash. `{player}` becomes the player's name. An empty command
  makes that choice unavailable in `command` mode.

**Verified.** Stock: holding dirt changed the time, and a Stopwatch could be used again
instantly. Patched: both refused. `disable` and `command` modes behaved as above, and on
MCPC+ the command ran through Bukkit (`* VTest voted for noon via VTest`).

</details>

---

## ICBM Explosion 1.2.1

Fifteen fixes: explosives that never stop or flood the server with entities, packet handlers
that trust the client, item dupes and losses, and a chunk-loading and listener leak. The patcher
rewrites fourteen classes in `ICBM_Explosion_v1.2.1.172.jar` and adds `VoltzICBM`.

Verified with the same harness on the dedicated Forge server. On MCPC+ the explosions never
ticked in the harness at all — Spigot's entity activation skips entities with no player
nearby — so they were not exercised there. Every fix from `remote` on was tested on Forge only.
None of them has any platform-specific code.

<details>
<summary><b>1. <code>redmatter</code> — a Red Matter black hole never ends</b></summary>

**The bug.** An ICBM explosion entity runs until its explosive's `doBaoZha` returns
`false`. Red matter's always returns `true`. The black hole is saved with the chunk,
rescans a radius-35 sphere every tick even once there is nothing left to eat, and deletes
every non-living entity within 4 blocks, dropped items included.

**The patch.** It ends after **Red Matter Max Ticks**. The tick count is saved with the
entity, so a black hole already older than the limit ends on its next tick.

**Verified.** Stock: still alive after 160 ticks. Patched with the limit set to 100: gone.

</details>

<details>
<summary><b>2. <code>sonic</code> — Sonic and Hypersonic flood the server with flying blocks</b></summary>

**The bug.** Both explosives cast rays and queue every position a ray passes through, 0.3
blocks at a time, so each block is queued many times over. Then nearly every block they
break within about 7 blocks of the centre becomes a flying block entity.

**The patch.**

- Each block is queued once.
- Past **Max Flying Blocks Per Sonic Explosion**, blocks are still broken but no longer
  become entities.

**Measured** over 200 ticks per explosion on a Ryzen 7 9700X. Terrain differs per run, so
these are ranges:

| | Stock | Patched |
|---|---|---|
| Hypersonic positions queued | 31,342–35,820 | 5,236–7,984 |
| Hypersonic flying blocks at once | 2,575–3,174 | 256 |
| Hypersonic mean tick | 4.9–6.6 ms | 2.8–2.9 ms |
| Sonic positions queued | 6,818–7,897 | 809–1,395 |

</details>

<details>
<summary><b>3. <code>remote</code> — detonate any explosive from anywhere</b></summary>

**The bug.** The Remote detonator sends a packet naming an explosive block. The server only
checks that the sender is holding a Remote:

```java
if (player.getCurrentItem().getItem() instanceof ItYaoKong) {
    BZhaDan.yinZha(world, x, y, z, explosiveId, 0);   // detonate
    remote.drain(1500);
}
```

The Remote's real rules live in the client alone: it fires only Condensed, Breaching and S-Mine
explosives, needs more than 1,500 J, and reaches 100 blocks unless linked to the explosive. A
modified client can detonate any explosive block in the dimension, antimatter and red matter
included, with an empty Remote and from any distance.

**The patch.** The server checks the same three rules before detonating, and logs refusals at
most once per player every 10 seconds:

```
[VoltzFixes] refused remote detonation of 1541,200,-76 (not a remote-detonatable explosive) from VTest
```

**Verified** on the Forge test server, sending real `ICBM|E` packets from a server-side player:

| Remote packet | Stock | Patched |
|---|---|---|
| Condensed, charged, 10 blocks away | detonates | detonates |
| Antimatter | detonates | refused |
| Remote at 1,000 J | detonates | refused |
| Remote at exactly 1,500 J | detonates | refused |
| 500 blocks away, not linked | detonates | refused |
| 500 blocks away, linked to that explosive | detonates | detonates |
| Holding stone | nothing | nothing |

</details>

<details>
<summary><b>4. <code>explosivetype</code> — any client can change an explosive, or crash the server</b></summary>

**The bug.** The same handler has a second packet type that sets the block's explosive id:

```java
if (packetId == 1) this.explosiveId = data.readInt();
```

It exists so the server can tell clients what a block is, but the server accepts it from
clients too. Any explosive block can be turned into antimatter or red matter. An id past the
end of the explosive list is worse: the next redstone update indexes the list with it and
throws `Exception while updating neighbours`, which crashes the server, and the block renderer
does the same lookup on every client that can see it.

**The patch.** The server ignores that packet type and logs it. Clients still apply it when
the server sends it.

**Verified** on the same server. Stock took id 99 from the packet, and a redstone block placed
next to it threw `Exception while updating neighbours`. Patched kept the original id. A mock
test confirmed a client still applies the packet from the server.

</details>

<details>
<summary><b>5. <code>empradius</code> — one packet freezes the server with an EMP tower</b></summary>

**The bug.** The EMP tower's GUI sends its radius as a packet, and the server stores whatever
number arrives. The tower declares `MAX_RADIUS = 150` and never uses it. When it fires, the
radius goes straight into a cube-shaped block loop of `(2r+1)³` blocks: a radius of 100,000 is
about 8×10¹⁵ iterations, and the server stops responding. The tower also accepts its full
description packet from clients, which sets the charge, the EMP-disabled timer and the radius
at once, on anyone's tower, from any distance.

**The patch.** The server ignores the description packet, and every radius write (packet, NBT
load, constructor) is clamped to 0–150, which also repairs a tower saved with a bad radius. The
GUI's own radius and mode packets work as before.

**Verified** on the Forge test server. The tower was never fired:

| EMP tower packet | Stock | Patched |
|---|---|---|
| Radius 60 (GUI) | 60 | 60 |
| Radius 100,000 | 100,000 | 150 |
| Description packet with radius 7,000 | 7,000 | ignored |
| Mode 1 (GUI) | 1 | 1 |

</details>

<details>
<summary><b>6. <code>launchertier</code> — free launcher upgrades</b></summary>

**The bug.** The launcher base, frame and screen accept their description packet from clients,
and it sets their tier. A tier 0 base becomes tier 2: full missile range, and it drops as a
tier 2 base when broken. The screen's packet also sets the launch height outside the 3–99
range its GUI enforces.

**The patch.** The server ignores those packets. The screen's frequency, target and height
packets from its GUI are untouched.

**Verified** on the Forge test server:

| Launcher packet | Stock | Patched |
|---|---|---|
| Base, tier 2 | tier 2 | unchanged |
| Frame, tier 2 | tier 2 | unchanged |
| Screen description, tier 2 | tier 2 | unchanged |
| Screen height 40 (GUI) | 40 | 40 |

</details>

<details>
<summary><b>7. <code>multiblock</code> — use or delete someone's machine through a fake dummy block</b></summary>

**The bug.** Multiblock machines (launcher base and frame, EMP tower, radar) are a main block
plus dummy blocks. Each dummy remembers where its main block is, passes right-clicks to it, and
deletes the whole machine when it is broken. The dummy's packet sets that position from any
client. Point a dummy of your own machine at someone else's machine anywhere in the dimension,
then right-click it to open their GUI, or break it to delete their machine with no drops.

The dummy class is shared Universal Electricity code that ships in six Voltz jars, and
Galacticraft ships its own copy with the same flaw, so which copy runs depends on load order.

**The patch.** The machines check their dummies instead: a right-click needs the player within
10 blocks of the machine, and only a dummy within 2 blocks of the machine can remove it. The
ICBM Sentry railgun gets the same patch.

**Verified** on the Forge test server:

| Dummy pointed at an EMP tower | Stock | Patched |
|---|---|---|
| Right-clicked from 100 blocks away | opens its GUI | refused |
| Broken 100 blocks away | tower deleted | tower kept |
| The dummy on top of the tower, broken | tower removed | tower removed |

</details>

<details>
<summary><b>8. <code>cruiselauncher</code> — free cruise launcher charge</b></summary>

**The bug.** Like the launcher screen, the cruise launcher accepts its description packet from
clients: charge, frequency, EMP-disabled timer and target. A client can fill the 800,000 J launch
charge for free, or clear an EMP's disable.

**The patch.** The server ignores that packet. The GUI's frequency and target packets work as
before.

**Verified** on the same server: a description packet setting frequency 55 was applied on stock
and ignored when patched; the GUI's frequency packet (77) worked on both.

</details>

<details>
<summary><b>9. <code>designator</code> — airstrikes with no frequency, charge or cooldown</b></summary>

**The bug.** The laser designator's airstrike packet only checks that the sender holds a
designator. Its real rules live in the client: a frequency, more than 6,000 J, no strike already
counting down, and a target in range. A client can start strikes with an empty designator and
repeat them endlessly, each one spawning a light-beam entity.

**The patch.** The server checks those rules before starting the strike.

**Verified** on the same server:

| Designator strike | Stock | Patched |
|---|---|---|
| No frequency | started | refused |
| Frequency and charge, target 10 blocks away | started | started |
| Again while the first counts down | started | refused |
| No charge | started | refused |

</details>

<details>
<summary><b>10. <code>defuser</code> — defuse the same explosive twice</b></summary>

**The bug.** The defuser drops the explosive (or TNT) and kills the entity, but never checks that
the entity is still alive. A dead entity can still be hit until the tick ends, so two hits in one
tick drop two.

**The patch.** Hitting an entity that is already dead does nothing.

**Verified** on the same server: defusing the same primed TNT twice in one tick dropped 2 TNT on
stock and 1 when patched.

</details>

<details>
<summary><b>11. <code>missilestack</code> — loading a launcher deletes the rest of the stack</b></summary>

**The bug.** Right-clicking the launcher base or cruise launcher with missiles moves the whole
held stack into its one-missile slot and empties the player's hand. A stack of 16 missiles
becomes 1.

**The patch.** One missile is loaded and the rest stay in the hand.

**Verified** on the same server: loading either launcher from a stack of 16 left 0 in hand on
stock and 15 when patched.

</details>

<details>
<summary><b>12. <code>listeners</code> — GUI listener sets never emptied</b></summary>

**The bug.** The EMP tower, launcher screen, radar and cruise launcher keep a set of players
with their GUI open and send each one an update every tick. A player joins from any distance,
and is removed only when their client says the GUI closed, so a disconnect, death or teleport
leaves them in the set (and the packets flowing) until the chunk unloads.

**The patch.** A player joins only within 10 blocks, and each tick drops any listener that is
dead, in another world, or more than 16 blocks away.

**Verified** on the Forge test server as part of the detector and terminal listener tests below;
a subscribe from 100 blocks away was accepted on stock and refused when patched.

</details>

<details>
<summary><b>13. <code>radarradius</code> — an unclamped radar radius</b></summary>

**The bug.** The radar's GUI clamps its alarm and safety radius to 0..500, but the server stores
any value a packet sends.

**The patch.** Every radius write is clamped to 0..500.

**Verified** on the Forge test server: a radius of 100,000 was stored on stock and clamped to 500
when patched; a GUI value of 40 was kept on both.

</details>

<details>
<summary><b>14. <code>radargun</code> — a free radar gun ping</b></summary>

**The bug.** The radar gun's packet stores the player's target coordinates on the held gun and
drains 1,000 J, without the client's check that the gun has more than 1,000 J.

**The patch.** The server checks the charge first.

**Verified** on the Forge test server: an empty radar gun's packet stored its coordinates on
stock and was refused when patched; a charged gun worked on both.

</details>

<details>
<summary><b>15. <code>chunkload</code> — a packet loads a far chunk</b></summary>

**The bug.** The shared packet router looks up a tile packet's target with `getBlockTileEntity`,
which loads (or generates) the chunk at the client's coordinates. A client spamming far
coordinates can force the server to generate chunks. The router class ships in six Voltz jars and
Galacticraft's own copy, so the copy that loads is not ours to patch.

**The patch.** ICBM's own packet handlers gate the router: a tile packet whose chunk is not
already loaded is dropped.

**Verified** on the Forge test server: a tile packet aimed 100,000 blocks away loaded a new chunk
on stock and was dropped when patched.

</details>

`config/VoltzFixes-ICBM.cfg` covers `redmatter` and `sonic`. `0` restores the stock behaviour for either:

```
general {
    I:"Max Flying Blocks Per Sonic Explosion"=256
    I:"Red Matter Max Ticks"=3000
}
```

---

## ICBM Sentry 1.2.1

Ten fixes for turret platforms and turrets that obey any client, an ammo dupe, a listener leak,
an unbounded console, and broken commands and combat logic. The patcher rewrites ten classes in
`ICBM_Sentry_v1.2.1.172.jar` and adds `VoltzSentry`. Tested on Forge only.

<details>
<summary><b>1. <code>terminal</code> — run platform commands as the owner, from anywhere</b></summary>

**The bug.** A turret platform's terminal packet carries a username, and the server runs the
command as that player:

```java
CommandRegistry.onCommand(world.getPlayerEntityByName(data.readUTF()), this, data.readUTF());
```

The packet's real sender is never looked at, and neither is distance. While a platform's owner
is online, any client can `destroy` it, add itself as a user and raise itself to owner, or
remove the owner, from anywhere in the dimension.

**The patch.** The command runs as the player who sent the packet, and only within 8 blocks of
the platform. Out of reach it is dropped and logged:

```
[VoltzFixes] refused terminal command at 1537,200,-56 sent as VOwner (500 blocks away) from VTest
```

**Verified** on the Forge test server, with `VOwner` listed as the platform's owner:

| `destroy` command | Stock | Patched |
|---|---|---|
| Attacker 500 blocks away, sent as `VOwner` | destroyed | refused |
| `VOwner`, 3 blocks away | destroyed | destroyed |
| Attacker 3 blocks away, sent as `VOwner` | destroyed | access denied |

</details>

<details>
<summary><b>2. <code>turretpackets</code> — set any turret's health, aim or saved data</b></summary>

**The bug.** Every turret packet (rotation, NBT description, shot, health) is one the server
sends to clients, but the server applies them from clients too. Any client can make a turret
unkillable or switch it off through its health, aim someone else's railgun, or load arbitrary
NBT into a turret, including coordinates that later make its `destroy` delete a block
somewhere else.

**The patch.** The server ignores turret packets. No real client sends one.

**Verified** on the same server: a health packet of 12,345 set a gun turret's health on stock,
and was ignored when patched.

</details>

<details>
<summary><b>3. <code>multiblock</code> — mount, fire or delete someone's railgun from afar</b></summary>

**The bug.** The railgun is a multiblock with the same dummy-block flaw as ICBM Explosion's
machines (see `multiblock` there). A dummy pointed at someone's railgun lets a player mount it
(a free trip into their base), fire it with their ammo, or delete it by breaking the dummy.

**The patch.** Mounting or firing needs the player within 10 blocks, and only a dummy within 2
blocks of the railgun can remove it.

**Verified** on the Forge test server:

| Dummy 100 blocks away, pointed at a railgun | Stock | Patched |
|---|---|---|
| Right-clicked | mounts the railgun | refused |
| Broken | railgun deleted | railgun kept |

</details>

<details>
<summary><b>4. <code>ammodupe</code> — take a broken platform's ammo twice</b></summary>

**The bug.** A turret platform's GUI never closes (`isUseableByPlayer` is always `true`), and
breaking a platform drops copies of its ammunition while leaving the originals in the slots. Keep
the GUI open while the platform is broken - by another player, a block breaker, or `destroy` -
and the ammo lands on the ground and can still be taken out of the GUI.

**The patch.** The GUI closes once the platform is gone or the player is more than 8 blocks away,
and each slot is emptied as its contents drop.

**Verified** on the same server, with 64 rounds in the platform:

| Turret platform | Stock | Patched |
|---|---|---|
| GUI from 100 blocks away | usable | closed |
| Rounds left in the slot after breaking it | 64 | 0 |
| GUI after breaking it | usable | closed |

</details>

<details>
<summary><b>5. <code>listeners</code> — the terminal listener set never emptied</b></summary>

**The bug.** A turret platform's terminal keeps a set of players with the GUI open and sends
each one the console every few ticks. A player joins from any distance and is removed only when
their client says the GUI closed.

**The patch.** A player joins only within 10 blocks, and each send drops any listener that is
dead, in another world, or more than 16 blocks away.

**Verified** on the Forge test server:

| Terminal GUI | Stock | Patched |
|---|---|---|
| Subscribe from 100 blocks away | joined | refused |
| Subscribe from 3 blocks away | joined | joined |
| Still listed after moving 100 blocks away | yes | pruned |

</details>

<details>
<summary><b>6. <code>consolecap</code> — the terminal console grows without bound</b></summary>

**The bug.** The terminal only ever appends console lines, and every command resends the whole
list to each viewer in one packet. Past about 630 lines the packet's 16-bit length wraps and
corrupts the stream for anyone with that platform open. A client can spam commands to force it.

**The patch.** The console keeps its newest 100 lines.

**Verified** on the Forge test server: 200 lines left the console at 200 on stock and 100 when
patched.

</details>

<details>
<summary><b>7. <code>targetcommand</code> — <code>target &lt;type&gt; true</code> never worked</b></summary>

**The bug.** The `target` command parsed its on/off flag with `Boolean.getBoolean`, which reads
a Java system property instead of the argument, so it was always false. Only the no-argument
toggle worked.

**The patch.** It parses the argument with `Boolean.parseBoolean`.

**Verified by construction:** the one `Boolean.getBoolean` call in the command becomes
`Boolean.parseBoolean`, checked with an ASM verifier. Not exercised in game.

</details>

<details>
<summary><b>8. <code>antimatterammo</code> — antimatter rounds fired as normal</b></summary>

**The bug.** The railgun decided whether a round was antimatter with `ammo.equals(antimatterBullet)`,
a reference comparison that is never true, so antimatter rounds fired with the normal blast.

**The patch.** It compares the item and damage instead (`ItemStack.isItemEqual`).

**Verified by construction:** the reference compare becomes `isItemEqual`, checked with an ASM
verifier. Not exercised in game.

</details>

<details>
<summary><b>9. <code>doubleenergy</code> — each shot cost energy twice</b></summary>

**The bug.** A sentry's `onWeaponActivated` subtracts the shot's energy from the platform after
`onFire` has already subtracted it.

**The patch.** The second subtraction is removed.

**Verified by construction:** the duplicate energy write is removed, checked with an ASM verifier.
Not exercised in game.

</details>

<details>
<summary><b>10. <code>accesscommands</code> — access-list gaps</b></summary>

**The bug.** An admin could `users add <owner>` to silently demote the owner to a plain user,
`users remove` someone at or above their own level, or `access set` a player to a level above
their own.

**The patch.** `users add` refuses a name already listed, `users remove` needs a level above the
target's (or the owner removing themselves), and `access set` may not grant a level above the
sender's own.

**Verified** on the Forge test server, as an admin: `users add VOwner` left the owner at OWNER
(demoted on stock), and `access set VVictim owner` left the victim at USER (raised to OWNER on
stock).

</details>

## ICBM Contraption 1.2.1

Three fixes, for the camouflage block, the proximity detector, and a chunk-loading and listener
leak. The patcher rewrites three classes in `ICBM_Contraption_v1.2.1.172.jar` and adds
`VoltzContraption`. Tested on Forge only.

<details>
<summary><b>1. <code>camouflage</code> — crash every nearby player with one packet</b></summary>

**The bug.** The camouflage block's packet sets the block it disguises as, which sides are
see-through and whether it is solid. Only the server should send it, but the server applies it
from any client, at any distance, and saves it. A block id past the end of the block list is
then sent to every client, whose renderer looks it up without a bounds check and crashes, again
each time the chunk loads. Setting "not solid" also lets anyone walk through someone else's
camouflage wall.

**The patch.** The server ignores the packet. A block id outside the block list is reset to 0
when the block loads, so blocks poisoned before the patch stop crashing clients.

**Verified** on the Forge test server:

| Camouflage block | Stock | Patched |
|---|---|---|
| Packet setting block id 5000 | saved 5000 | ignored |
| Loaded from NBT with block id 5000 | 5000 | 0 |

</details>

<details>
<summary><b>2. <code>detector</code> — power, retune or switch off any proximity detector</b></summary>

**The bug.** The proximity detector accepts its description packet from clients (power,
frequency, mode, inversion and range), and its GUI packets from any distance. A client can run
someone's detector with no power, invert it, shrink its range or change its frequency, quietly
disabling whatever defences it triggers.

**The patch.** The server ignores the description packet, and GUI packets need the player within
8 blocks. Closing the GUI is always accepted.

**Verified** on the Forge test server:

| Proximity detector packet | Stock | Patched |
|---|---|---|
| Mode, from 100 blocks away | applied | refused |
| Mode, from 3 blocks away (GUI) | applied | applied |
| Description packet setting frequency 99 | applied | ignored |

</details>

<details>
<summary><b>3. <code>listeners</code> — the detector listener set never emptied</b></summary>

**The bug.** The proximity detector keeps a set of players with its GUI open and sends each one
an update every 20 ticks, joined from any distance and removed only on an explicit close.

**The patch.** A player joins only within 10 blocks, and each tick drops any listener that is
dead, in another world, or more than 16 blocks away.

**Verified** on the Forge test server: a subscribe from 100 blocks away was accepted on stock and
refused when patched.

</details>

<details>
<summary><b>4. <code>chunkload</code> — a packet loads a far chunk</b></summary>

**The bug.** Same as ICBM Explosion's `chunkload`: the shared packet router loads the chunk at a
tile packet's client coordinates.

**The patch.** ICBM Contraption's packet handler drops a tile packet whose chunk is not already
loaded.

**Verified by construction:** shares the guard proven in ICBM Explosion's `chunkload` test, and
the handler override passes an ASM verifier.

</details>

---

## Modular Powersuits 0.7.0

<details>
<summary><b><code>blink</code> — Blink Drive leaves you inside walls</b></summary>

**The bug.** Blink Drive teleports to the raw point its ray hits, without checking that the
player fits there. Aim at the floor right against a wall and the player's hitbox ends up
inside the wall.

That matters because of how 1.5.2 validates movement. `NetServerHandler` only rejects a
move if the player was clear of blocks when it started:

```java
boolean startedClear = world.getCollidingBoundingBoxes(player, box.contract(0.0625)).isEmpty();
...
if (startedClear && (movedWrongly || !endsClear)) reject();
```

A player already inside a block is never corrected, so a modified client walks straight
through walls.

**The patch.** The destination is checked first. If the player would collide there, it
steps back toward them a quarter block at a time to the first clear spot, or does not
teleport at all. A clear destination is used unchanged.

**Verified** on Forge. Blinking onto a floor block beside a wall, stock left the hitbox a
quarter block inside the wall, and patched landed a quarter block clear of it. On MCPC+ the
harness's stub connection cannot complete a teleport, so it was not exercised there; the
teleport call itself is the one stock makes.

</details>

Lux Capacitor, Plasma Cannon, Blade Launcher and Active Camouflage were also decompiled and
checked for crashes, dupes and exploits. None turned up — they work as designed, so they
are not patched. Whether to allow them is a balance call for each server.

## MineFactoryReloaded 2.6.4

<details>
<summary><b><code>ghostslot</code> — copy your held item into any inventory, or empty any slot, from anywhere</b></summary>

**The bug.** MFR filter slots (Item Router, Enchantment Router, Liquid Router, Planter,
Unifier, LiquiCrafter, Auto-Brewer) are ghost slots: clicking one stores a size-1 copy of
the cursor without taking it. The GUI sends packet 19 with the machine's coordinates and
the slot number, and the server does:

```java
TileEntity te = player.worldObj.getBlockTileEntity(x, y, z);
if (te instanceof IInventory) {
    if (cursor == null) ((IInventory) te).setInventorySlotContents(slot, null);
    else                ((IInventory) te).setInventorySlotContents(slot, sizeOneCopyOf(cursor));
}
```

Nothing ties the packet to an open GUI: any coordinates, any loaded inventory, any slot, any
distance. A modified client can write a free copy of whatever it holds into every slot of
someone else's chest, or delete any slot's contents with an empty cursor.

**The patch.** Both writes go through `VoltzMFR.setGhostSlot`, which allows them only when:

- the player's open container has a `SlotFake` at that slot number
- that slot belongs to the inventory at the packet's coordinates, at that same index
- the container is still usable (machine still there, player within 8 blocks)

That is exactly what the real GUI sends, so normal filter clicks work as before. Anything
else is dropped and logged, at most once per player every 10 seconds:

```
[VoltzFixes] refused ghost slot write to TileEntityChest slot 0 (no matching ghost slot open) from <player>
```

**Checked** against MFR's own bytecode: the GUI sends `Slot.slotNumber`, and every stock
container that has ghost slots gives them a slot number equal to their inventory index. The
helper passes a mock test of the legitimate write and of each refusal case. Not yet
exercised on a live server.

</details>

## FML login-sequence crash (server core, not a mod)

<details>
<summary><b><code>loginguard</code> — a client crashes the server during login</b></summary>

**The bug.** During the FML login handshake the server routes a client packet-250 custom
payload through `NetworkRegistry.handleCustomPacket`. For a mod channel (not `FML`/`MC|`, not
`REGISTER`/`UNREGISTER`) it dispatches to the owning mod's packet handler with
`handler.getPlayer()` — which is null during login, because no player entity exists yet. A
modified client that sends such a packet before it has logged in crashes the server thread.

This is not a mod bug — it lives in the Forge/FML core, so it is not one of the mod-jar patches
above and is not built by `./build.sh`.

**On MCPC+ (the usual Voltz server): already fixed by a plugin.** The `LoginSeqFix` +
`ProtocolLib` plugins that ship in the preconfigured Voltz server's `plugins/` folder drop
out-of-order login-phase custom payloads. That is the right layer for MCPC+; keep them enabled.
MCPC+ also defaults `load-chunk-on-request: false` in `mcpc.yml`, a second reason the ICBM
`chunkload` guard is belt-and-suspenders there.

**On a plain-Forge server (no plugin layer): `PatchForgeLogin`.** It adds one guard to
`NetworkRegistry.handleCustomPacket` — the mod-packet branch returns instead of dispatching when
`getPlayer()` is null — leaving the `REGISTER`/`UNREGISTER` login registration untouched. Run it
against your own Forge universal zip and install the result as the server's Forge:

```sh
javac -cp "$ASM" -d build/tool PatchForgeLogin.java
java -cp "$ASM:build/tool" PatchForgeLogin forge-1.5.2-universal.zip forge-1.5.2-universal-loginguard.zip
```

**UNTESTED.** This is the one patch here that could not be exercised: reproducing it needs a
modified client sending an out-of-order login packet, which the server-side harness (it injects
an already-logged-in fake player) cannot do. The guard is copied from the exact `getPlayer()`
call in the same method and the patched class passes an ASM verifier, but it has not run on a
live server. It is plain-Forge only — a Forge server runs obfuscated, matching the packed
names; MCPC+ runs deobfuscated and uses the plugin instead.

</details>

## Build

<details>
<summary><b>Building the patched jars from a stock jar</b></summary>

```sh
./build.sh /path/to/Atomic_Science_v0.6.2.117.jar
```

Builds every patched jar, skipping any whose source jar is missing. Needs `javac`
(any version), a Java 8 `javac` for the helper classes, ASM, and `curl` on first run.

Override paths with `MODS`, `AS_SRC`, `MPSA_SRC`, `MFFS_SRC`, `MEK_SRC`, `ICBM_SRC`, `ICBMS_SRC`, `ICBMC_SRC`,
`MPS_SRC`, `MFR_SRC`, `FORGE`, `ASM`, `JAVAC8`.

Helper classes compile against the **Forge universal zip**, downloaded into `build/` once
and cached — deliberately not against the launcher's `bin/minecraft.jar`, which PolyMC
rewrites on every launch.

Individual patches:

```sh
java -cp "$ASM:build/tool" PatchAS in.jar out.jar syncspawn,assemblerwear \
     build/cls/atomicscience/fanwusu/VoltzFixConfig.class
```

The compiled `VoltzFixConfig.class` is always required as the fourth argument — every
patch reads its settings from it. The patcher throws if any selected patch fails to
apply, so it never writes a jar that silently did nothing.

</details>

## Install

<details>
<summary><b>Server-side install, and what changes inside each jar</b></summary>

Server-side only. Drop the patched jars into the **server's** `mods/` folder, replacing
the stock ones:

| Replace | With |
|---|---|
| `Atomic_Science_v0.6.2.117.jar` | `Atomic_Science_v0.6.2.117-patched.jar` |
| `MPSA-0.2.3-144_MPS-531+.jar` | `MPSA-0.2.3-144_MPS-531+-patched.jar` |
| `Mekanism-v5.5.6.bugfix1.jar` | `Mekanism-v5.5.6.bugfix1-patched.jar` |
| `ICBM_Explosion_v1.2.1.172.jar` | `ICBM_Explosion_v1.2.1.172-patched.jar` |
| `ICBM_Sentry_v1.2.1.172.jar` | `ICBM_Sentry_v1.2.1.172-patched.jar` |
| `ICBM_Contraption_v1.2.1.172.jar` | `ICBM_Contraption_v1.2.1.172-patched.jar` |
| `ModularPowersuits-0.7.0-534.jar` | `ModularPowersuits-0.7.0-534-patched.jar` |
| `MineFactoryReloaded-2.6.4-975.jar` | `MineFactoryReloaded-2.6.4-975-patched.jar` |

**Clients need no changes at all.** Players keep the unmodified Voltz pack — nothing to
download, nothing to install, no launcher changes. Every patch lives in code that only
runs on the server:

- `scheduleBlockUpdate` is a no-op on the client
- entity damage and knockback are server authoritative
- `EWuSu.explode()` is entirely inside `if (!world.isRemote)`
- the Assembler's wear loop runs on the tile entity
- `CommonTickHandler` is the server half of the magnet; item positions are server
  authoritative, which is the whole reason that fix is needed
- the Mekanism fixes are in packet handlers, server-side GUI checks and tile entity methods
- ICBM explosions and the Blink Drive teleport are resolved on the server
- the ICBM packet checks are in the server's packet handlers; clients still apply the same packets from the server
- the MFR ghost slot check is in MFR's server packet handler

FML 1.5.2 matches mods on modid and version strings, not file hashes, and neither is
changed by these patches — so a patched server accepts stock clients with no mod-mismatch
screen. **Verified:** an unmodified client connected to a fully patched server and every
fix behaved correctly, including ones with visible client-side effects like items flying
toward the player.

Every patched jar keeps its original mod id and version, so they can be rolled back by
swapping the stock jars back in. Every fix can also be turned off individually in its
config file without replacing anything.

What actually changes inside each jar, for review:

```
Atomic_Science_v0.6.2.117-patched.jar
  added   atomicscience/fanwusu/VoltzFixConfig.class
  changed atomicscience/TGouCheng.class            assembler wear loop bound
          atomicscience/fanwusu/TJiaSuQi.class      spawn clock
          atomicscience/fanwusu/EWuSu.class         explosion isSmoking flag
          atomicscience/hecheng/BDengLiZiTi.class   setTickRandomly
          atomicscience/ZhuYao.class                config init hook in preInit

MPSA-0.2.3-144_MPS-531+-patched.jar
  added   andrew/powersuits/VoltzMagnetConfig.class
  changed andrew/powersuits/tick/CommonTickHandler.class   server-side item pull
          andrew/powersuits/common/CommonProxy.class       config init hook

Mekanism-v5.5.6.bugfix1-patched.jar
  added   mekanism/common/VoltzMekanism.class (and 2 inner classes)
          mekanism/common/BalancedTimeItems.class
  changed mekanism/common/TileEntityElectricChest.class      slot list, no nesting
          mekanism/common/SlotElectricChest.class            no nesting
          mekanism/common/InventoryElectricChest.class       bound to its stack
          mekanism/common/ContainerElectricChest.class       item GUI closes when unbound
          mekanism/common/network/PacketElectricChest.class  coordinate check
          mekanism/common/TileEntityContainerBlock.class     machine GUI reach
          mekanism/common/CommonProxy.class                  Robit GUI lookup
          mekanism/common/ContainerRobitMain.class           Robit GUI reach
          mekanism/common/ContainerRobitInventory.class      Robit GUI reach
          mekanism/common/ContainerRobitSmelting.class       Robit GUI reach
          mekanism/common/BlockObsidianTNT.class             extra drop removed
          mekanism/common/EntityObsidianTNT.class            explosion source
          mekanism/common/network/PacketTime.class           BalancedTimeItems
          mekanism/common/network/PacketWeather.class        BalancedTimeItems
          mekanism/common/Mekanism.class                     config init hook

ICBM_Explosion_v1.2.1.172-patched.jar
  added   icbm/zhapin/VoltzICBM.class
  changed icbm/zhapin/zhapin/ex/ExHongSu.class        red matter lifetime
          icbm/zhapin/zhapin/ex/ExShengBuo.class      queue dedupe, flying block cap
          icbm/zhapin/zhapin/ex/ExChaoShengBuo.class  queue dedupe, flying block cap
          icbm/zhapin/zhapin/TZhaDan.class            remote and set-type packet checks
          icbm/zhapin/jiqi/TDianCiQi.class            EMP tower packet, radius clamp, dummy checks, listener join/prune
          icbm/zhapin/jiqi/TFaSheDi.class             tier packet, dummy checks, missile loading
          icbm/zhapin/jiqi/TFaSheJia.class            tier packet, dummy checks
          icbm/zhapin/jiqi/TFaSheShiMuo.class         launcher screen description packet, listener join/prune
          icbm/zhapin/jiqi/TLeiDaTai.class            dummy checks
          icbm/zhapin/jiqi/TXiaoFaSheQi.class         description packet, missile loading
          icbm/zhapin/ZhaPinPacketGuanLi.class        laser designator checks
          icbm/zhapin/dianqi/ItJieJa.class            dead entity check
          icbm/zhapin/jiqi/TXiaoFaSheQi.class         listener join/prune (also above)
          icbm/zhapin/jiqi/TLeiDaTai.class            radius clamp, listener join/prune (also above)
          icbm/zhapin/ZhaPinPacketGuanLi.class        laser designator, radar gun charge, chunk guard

ICBM_Sentry_v1.2.1.172-patched.jar
  added   icbm/gangshao/VoltzSentry.class
  changed icbm/gangshao/terminal/TileEntityTerminal.class  command sender and reach
          icbm/gangshao/turret/TPaoDaiBase.class           turret packets ignored on the server
          icbm/gangshao/turret/mount/TPaoTaiQi.class       mount reach
          icbm/gangshao/turret/mount/TCiGuiPao.class       dummy block check, antimatter round compare
          icbm/gangshao/platform/TPaoTaiZhan.class         GUI reach
          icbm/gangshao/platform/BlockTurretPlatform.class slots emptied as they drop
          icbm/gangshao/terminal/TileEntityTerminal.class  command sender/reach (above), listener join/prune, console cap
          icbm/gangshao/turret/sentries/TPaoTaiZiDong.class  no double energy charge
          icbm/gangshao/terminal/command/CommandTarget.class   parseBoolean flag
          icbm/gangshao/terminal/command/CommandUser.class     access-list checks
          icbm/gangshao/terminal/command/CommandAccess.class   access-level checks

ICBM_Contraption_v1.2.1.172-patched.jar
  added   icbm/wanyi/VoltzContraption.class
  changed icbm/wanyi/b/TYinXing.class                     packet ignored on the server, id clamp
          icbm/wanyi/b/TYinGanQi.class                    detector packet checks, listener join/prune
          icbm/wanyi/WanYiPacketGuanLi.class              chunk guard
          icbm/zhapin/ZhuYaoZhaPin.class              config init hook

ModularPowersuits-0.7.0-534-patched.jar
  added   net/machinemuse/powersuits/VoltzMPS.class
  changed net/machinemuse/powersuits/powermodule/movement/BlinkDriveModule.class   teleport check

MineFactoryReloaded-2.6.4-975-patched.jar
  added   powercrystals/minefactoryreloaded/VoltzMFR.class
  changed powercrystals/minefactoryreloaded/net/ServerPacketHandler.class   ghost slot check
```

Nothing else is touched — no ids, no recipes, no rendering, and no packet formats change.

</details>

## Credits

All patched mods are the work of their original authors. These are third-party patches,
not affiliated with or endorsed by any of them.

| Mod | Author |
|---|---|
| Atomic Science | Calclavia |
| Andrew2448's Modular Powersuits Addon | Andrew2448 |
| MachineMuse's Modular Powersuits | MachineMuse |
| Mekanism | aidancbrady |
| ICBM | Calclavia |
| MineFactoryReloaded | PowerCrystals |

The Magnet module patched here belongs to the **Addon**; the Blink Drive fix is in the base
Modular Powersuits mod.
