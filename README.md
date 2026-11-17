# JEI Fast Start

A small client-side Forge mod for Minecraft 1.20.1 that shortens the freeze when you join a
world in packs with very large recipe sets (GregTech-scale packs such as All the Mods 9 Expert).

JEI still loads synchronously, before you get control, and shows exactly what it showed before.
The mod only removes wasted work during JEI's startup.

## Measured result

All the Mods 9 Expert, 349 mods, 81,743 JEI ingredients, joining a dedicated server:

| | Without the mod (4 joins) | With the mod (2 joins) |
|---|---|---|
| Connect to playable | 105 to 142 s | 65 and 66 s |
| JEI startup (`Starting JEI took`) | 95 to 127 s | 54 and 55 s |
| `Registering recipes: jei:minecraft` | 56 and 70 s | 17 and 18 s |
| `Sending Runtime: kubejs:jei` | 3 to 9 s | under 0.1 s |

One machine, one pack. Smaller packs have less of this work to remove and will gain less.
Per-join data is in [docs/RESULTS.md](docs/RESULTS.md).

## What it changes

1. **JEI item stack cache.** JEI stores only the item and NBT for each ingredient and rebuilds
   an `ItemStack` on demand through a Guava cache that expires after one second. While JEI
   indexes recipes, that cache is written and expired millions of times. The mod replaces it
   with a fixed-size table with the same one second lifetime, and during JEI startup reuses the
   stack an ingredient was created from instead of rebuilding it.
2. **KubeJS item hiding** (only if KubeJS is installed). `JEIEvents.hideItems` rescans JEI's
   whole ingredient list on every `hide()` call, rebuilding every stack each time. The mod
   copies that list once per event.

The full analysis, with profiles, is in [docs/ANALYSIS.md](docs/ANALYSIS.md).

## Requirements

- Minecraft 1.20.1, Forge 47.x
- JEI 15.59.x
- Client only. It does nothing on a dedicated server and is not needed there.

## Compatibility and limits

- The mod hooks JEI internals. If a JEI update changes them, the hooks are skipped, a warning
  is logged, and JEI runs unmodified. It should not crash the game in that case.
- During JEI startup, JEI sees the original recipe stack for up to one second instead of a
  copy. A mod that changes a stack right after registering it with JEI could be affected. None
  was seen in All the Mods 9 Expert.
- Not tested together with async JEI loaders such as Just Enough Threads.
- Tested on All the Mods 9 Expert only.

After JEI finishes loading, the log shows a line like:

```
JEI start finished in 54.9 s; item stack cache: 15162149 reused from source, 15827289 hits, 1909573 rebuilt
```

## Building

The build uses plain `javac`, no Gradle. You need a JDK 17 or newer, the JEI 15.59 jar, and
the `libraries` folder of a Forge 1.20.1 (47.x) install.

```bash
JEI_JAR=/path/to/jei-1.20.1-forge-15.59.0.210.jar MC_LIBS=/path/to/libraries bash build.sh
```

The jar is written to `build/libs/`. Set `FORGE_VERSION` if your libraries folder has a Forge
version other than 47.4.10. The jar runs on a normal Forge install; it does not run in a
Mojang-mapped development environment because it calls one Minecraft method by its SRG name.

## License

MIT. See [LICENSE](LICENSE).
