# JEI Fast Start: measured join times

Pack: All the Mods 9 Expert (CurseForge), Forge 47.4.10, JEI 15.59.0.210, 349 mods.
Server: a dedicated server on the same pack. Client JVM: Java 25, ZGC, 16.5 GB heap.
All times are from the client's `logs/latest.log`. "Join" is from `Connecting to <server>`
to `Starting JEI took ...`, the point where the game stops being frozen.
JEI reports 81,743 ingredients and 35,745 crafting recipes in this pack.

## Before (no mod)

| Join (log time)      | Connect to JEI done | JEI start | of which `Registering recipes: jei:minecraft` |
|----------------------|---------------------|-----------|-----------------------------------------------|
| Nov 15 15:38         | 131 s               | 118.4 s   | not captured                                  |
| Nov 15 15:50         | 139 s               | 127.5 s   | not captured                                  |
| Nov 15 16:19         | 142 s               | 127.2 s   | 70.5 s                                        |
| Nov 16 15:10         | 105 s               | 94.6 s    | 56.0 s                                        |

The server side of the same Nov 16 15:10 join took 2 s (authenticated 15:09:59, in world 15:10:01).

## With jeifast 0.1.0 (item stack cache replacement)

| Join (log time)      | Connect to JEI done | JEI start | `jei:minecraft` recipes |
|----------------------|---------------------|-----------|-------------------------|
| Nov 16 15:32         | 75 s                | 63.2 s    | 16.9 s                  |

Mod's own counter for that start: 15,162,163 stacks reused from their source, 21,404,031 cache
hits, 17,605,965 stacks rebuilt.

## With jeifast 0.2.0 (adds the KubeJS hide fix)

| Join (log time)      | Connect to JEI done | JEI start | `jei:minecraft` recipes | `Sending Runtime: kubejs:jei` |
|----------------------|---------------------|-----------|-------------------------|-------------------------------|
| Nov 16 15:37         | 65 s                | 54.2 s    | 17.2 s                  | under 0.1 s (was 3.4 to 9.3 s) |
| Nov 16 15:47         | 66 s                | 54.9 s    | 18.2 s                  | under 0.1 s                    |

Mod's counter for the 15:47 start: 15,162,149 stacks reused from their source, 15,827,289 cache
hits, 1,909,573 stacks rebuilt (0.1.0 rebuilt 17,605,965).

Remaining JEI time in that join: ingredients 3.5 s, categories 4.6 s (Just Enough Resources 4.0 s),
recipes 37.0 s (jei:minecraft 17.2, gtceu 6.7, create 4.4, others 8.7), ingredient filter 7.1 s.

## What was slow (stack samples of the render thread, 315 samples, no mod)

- 61% of JEI start: adding the crafting recipes to JEI's recipe index
  (`VanillaPlugin.registerRecipes` -> `RecipeMap.addRecipe`).
- 43% of JEI start: `TypedItemStack.getIngredient()`. JEI stores only (item, tag) per ingredient
  and rebuilds the ItemStack on demand through a Guava cache with `expireAfterAccess(1s)` and
  `concurrencyLevel(1)`. 30% is Guava bookkeeping (`expireEntries`, access queue), 13% is the
  rebuild (tag copy, Forge capability attachment).
- 16% of JEI start: Forestry's legacy subtype interpreter calling `getCapability`, which
  deserializes the individual from NBT for each fresh stack.
- KubeJS `HideJEIEventJS.hide(...)` iterates JEI's full ingredient view once per call; the view
  rebuilds an ItemStack per element per pass. 8 s in this pack.

## Summary for 0.2.0

| | Before (4 joins) | jeifast 0.2.0 (2 clean joins) |
|---|---|---|
| Connect to JEI done | 105 to 142 s (mean 129 s) | 65 and 66 s |
| JEI start | 94.6 to 127.5 s (mean 117 s) | 54.2 and 54.9 s |
| `Registering recipes: jei:minecraft` | 56.0 and 70.5 s | 17.2 and 18.2 s |
| `Sending Runtime: kubejs:jei` | 3.4 to 9.3 s | under 0.1 s |

A third 0.2.0 join (Nov 16 15:52) ran with the stack sampler attached: 67 s, JEI start 56.8 s.

## What is left (45 samples of that third join)

No single hotspot remains. Recipe registration across all plugins is about 69% of the samples
(JEI calling each category's setRecipe to collect ingredients 24%, tag copies when creating typed
ingredients 13%, GregTech 11%, Create 9%), the search index build about 11% (tooltip text for
every ingredient), Just Enough Resources category setup about 7%, ingredient list about 7%.

## Release build 1.0.0

Same fixes as 0.2.0, with optional (fail-soft) injections and a periodic table sweep.

| Join (log time)      | Connect to JEI done | JEI start | `jei:minecraft` recipes |
|----------------------|---------------------|-----------|-------------------------|
| Nov 17 13:43         | 58 s                | 45.9 s    | 15.1 s                  |

Counter: 15,162,229 stacks reused from their source, 15,714,308 cache hits, 2,022,635 rebuilt.
Every phase was a little faster in this join than in the 0.2.0 joins, including phases the mod
does not touch, so treat the difference from 0.2.0 as run-to-run variation.

## Release build 1.0.1 (fix for the JEI 15.74 crash)

1.0.0 crashed the client with a `VerifyError` on JEI 15.74.0.234 (see ANALYSIS.md). 1.0.1 was
tested on both JEI versions, Nov 18:

| Test | JEI | Result | JEI start | `jei:minecraft` recipes |
|---|---|---|---|---|
| Single-player world | 15.74.0.234 | started, no crash | 33.3 s | 7.0 s |
| Server join after a fresh launch | 15.59.0.210 | clean | 44.7 s | 16.8 s |
| Second join, same launch | 15.59.0.210 | clean | 50.2 s | 27.3 s |
| Control: server join without the mod | 15.59.0.210 | clean | 85.4 s | 48.8 s |

The single-player figure is not comparable with the server joins (different JEI version and
world). On the second join in the same launch JEI created about 41 million typed ingredients
instead of about 15 million; whether JEI also does that extra work without the mod was not
measured.

One earlier launch with 1.0.1 was disconnected twice by the server 6 to 10 seconds after login
with a Cyclops Core `PacketCodecException` while decoding EvilCraft's
`UpdateSoulNetworkCachePacket`. That happened before JEI started, the stack trace contains no
JEI or jeifast frames, and it did not recur on the next launch with the same jar. Its cause is
not known.
