# JEI startup takes 95 to 127 s on join in All the Mods 9 Expert: two causes and a tested fix

## Summary

Joining a server on All the Mods 9 Expert freezes the client for about two minutes after login
while JEI starts on the render thread. Stack sampling shows two causes:

1. **JEI:** `TypedItemStack.getIngredient()` rebuilds an `ItemStack` through a Guava cache that
   expires after one second. During recipe indexing this was 43% of the whole JEI start.
2. **KubeJS:** `HideJEIEventJS.hide()` walks JEI's entire ingredient list once per call, and that
   list rebuilds an `ItemStack` per element on every pass. This was 3 to 9 s.

A small client-only mixin mod that addresses both cut the join from 105-142 s to 65-66 s.

## Environment

- All the Mods 9 Expert (CurseForge), Minecraft 1.20.1, Forge 47.4.10, 349 mods
- JEI 15.59.0.210, KubeJS 2001.6.5-build.26
- JEI reports 81,743 ingredients; the pack has 35,745 crafting recipes
- Client: Java 25, ZGC, 16.5 GB heap
- Dedicated server; the server side of a join takes about 2 s, so this is all client time

## Measurements

Times are from the client `latest.log`. "Join" is from `Connecting to <server>` to
`Starting JEI took ...`.

| | Before (4 joins) | With the fix (2 joins) |
|---|---|---|
| Join | 105 to 142 s (mean 129 s) | 65 s and 66 s |
| `Starting JEI took` | 94.6 to 127.5 s (mean 117 s) | 54.2 s and 54.9 s |
| `Registering recipes: jei:minecraft` | 56.0 s and 70.5 s | 17.2 s and 18.2 s |
| `Sending Runtime: kubejs:jei` | 3.4 to 9.3 s | under 0.1 s |

Per-join data is in [RESULTS.md](RESULTS.md).

## How it was profiled

`jcmd <pid> Thread.print` in a loop, keeping only the `Render thread` stack, from
`Starting JEI...` until `Starting JEI took`. 315 samples for the unmodified game. The sampling
slows the join, so sampled joins are not used as timings.

## Cause 1 (JEI): expiring cache behind `TypedItemStack.getIngredient()`

`mezz.jei.common.ingredients.itemStacks.TypedItemStack` stores only the item holder and tag, and
`getIngredient()` goes through:

```java
CacheBuilder.newBuilder()
    .expireAfterAccess(Duration.ofSeconds(1))
    .concurrencyLevel(1)
    .build(loader)   // loader calls createItemStackUncached()
```

The subclasses do not override `equals`/`hashCode`, so the cache is keyed by instance identity.
When recipes are indexed, every recipe ingredient is a fresh `TypedItemStack`, so nearly every
call is a miss followed by an expiry.

Hot path (share of all 315 samples):

```
VanillaPlugin.registerRecipes (crafting recipes)              61%
  RecipeManagerInternal.addRecipe -> RecipeMap.addRecipe      54%
    RecipeMap.getIngredientUid
      TypedItemStack.getIngredient -> LocalCache.get          43%
        Guava bookkeeping (expireEntries, access queue)       30%
        createItemStackUncached (tag copy, Forge caps)        13%
```

Each ingredient's tag is also copied three times: in `TypedItemStack.create(ItemStack)`, again in
the `NormalizedTypedItemStack` constructor, and again in `createItemStackUncached()`.

Every rebuilt stack fires Forge's capability attachment, and for items with lazily initialised
capabilities the work repeats per stack. Forestry's subtype interpreter (`JeiUtil.registerItemSubtypes`,
which calls `getCapability` and deserializes the individual from NBT) was 16% of the samples for
that reason, and dropped out of the profile once stacks were no longer rebuilt.

### What the fix does

- `getIngredient()` uses a fixed-size, identity-keyed table (262,144 slots) with the same one
  second lifetime, instead of the Guava cache. Entries are immutable objects, so reads from other
  threads are safe.
- While `JeiStarter.start()` is running, on that thread only, the `ItemStack` passed to
  `TypedItemStack.create(ItemStack)` is stored in the table for the new ingredient when its count
  is 1, so the following `getIngredient()` returns it instead of rebuilding it.
- The first of the three tag copies is skipped (the constructor still copies).

Counter from one start with the fix: 15,162,149 stacks reused from their source, 15,827,289 table
hits, 1,909,573 stacks rebuilt.

### Suggestion for JEI

Any of these would help large packs: avoid the cache during startup by passing the source stack
through recipe indexing, replace `expireAfterAccess` with something that has no per-access
bookkeeping, or compute the ingredient UID before the source stack is discarded.

Trade-off to be aware of: reusing the source stack during startup means a plugin that mutates a
stack right after registering it could be observed by JEI within that second. No problem was seen
in this pack across three joins, but it was not tested beyond normal play.

## Cause 2 (KubeJS): `HideJEIEventJS.hide()` is O(calls x ingredients)

`dev.latvian.mods.kubejs.integration.forge.jei.HideJEIEventJS` keeps
`allIngredients = ingredientManager.getAllIngredients(type)` and `hide(Object)` iterates it fully
on every call. In JEI 15.59 that collection is a lazy view, so each pass calls
`TypedItemStack.getIngredient()` for all 81,743 items. The pack's `jei_hide.js` calls `hide` a few
hundred times (9,973 items end up hidden).

Before any fix, 15 of 19 samples in this phase were inside `TypedItemStack.getIngredient`. With
only cause 1 fixed, a start still rebuilt 17.6 million stacks and this phase took 8.2 s.

### What the fix does

After the constructor, replace the field with a copy:

```java
this.allIngredients = new ArrayList<>(this.allIngredients);
```

`Sending Runtime: kubejs:jei` went from 3.4-9.3 s to under 0.1 s.

## Relation to Just Enough Threads (async JEI loading)

This is a different approach from the async mod suggested in the ATM Discord on Nov 12, and it
does not have that mod's trade-off. Just Enough Threads lets you into the world while JEI loads
in the background, so JEI is unavailable for a while and, per that thread, `gtceu:jei_plugin`
has to be blacklisted from async or multiblock previews do not render. This fix keeps JEI
synchronous and makes the upfront load itself shorter: JEI is fully available the moment you
are in the world, and nothing about what JEI shows changes.

The stall watchdog output posted in that thread points at the same cause. For
`Registering recipes: jei:minecraft` (92.1 s there) its top frames were `Maps#newHashMap`,
`HashBiMap#seekByValue`, `ItemStack#<init>`, `LocalCache$Segment#expireEntries` and
`LocalCache#connectAccessOrder`: the tag copies, stack rebuilds and Guava cache bookkeeping
described under cause 1. That step is 17 to 18 s with this fix.

The two mods have not been tested together. In principle they are complementary, since the
work removed here is work the background thread would otherwise still do.

## What remains

A second profile with the fix (45 samples) shows no single hotspot. About 69% is recipe
registration across all plugins (JEI collecting ingredients through each category's `setRecipe`
24%, tag copies when creating typed ingredients 13%, GregTech 11%, Create 9%), 11% is the search
index build (tooltip text for every ingredient), 7% is Just Enough Resources category setup and
7% is the ingredient list.

## The mod

"JEI Fast Start" (`jeifast`), client-only, three mixins with `remap = false`:
`TypedItemStackMixin`, `JeiStarterMixin`, `KubeJsHideEventMixin`. The mixin config is
`required: false` with `defaultRequire: 0`, so if a target changes the hook is skipped and JEI runs unmodified. It is tied
to JEI 15.59 internals.
