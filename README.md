# JEI Fast Start

<img src="icon.png" width="96" alt="JEI Fast Start icon">

A small client-side Forge mod for Minecraft 1.20.1 that shortens the freeze when you join a
world in packs with very large recipe sets (GregTech-scale packs such as All the Mods 9 Expert).

JEI still loads synchronously, before you get control, and shows exactly what it showed before.
The mod only removes wasted work during JEI's startup.

## Measured result

All the Mods 9 Expert, 349 mods, 81,743 JEI ingredients, joining a dedicated server:

| | Without the mod (4 joins) | With the mod (3 joins) |
|---|---|---|
| Connect to playable | 105 to 142 s | 58 to 66 s |
| JEI startup (`Starting JEI took`) | 95 to 127 s | 46 to 55 s |
| `Registering recipes: jei:minecraft` | 56 and 70 s | 15 to 18 s |
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
- JEI 15.x. Tested in game on 15.59.0.210 and 15.74.0.234.
- Client only. It does nothing on a dedicated server and is not needed there.

## Compatibility and limits

- The mod hooks JEI internals with bytecode mixins. Before patching a class it checks that
  every member it needs is present; if not, it logs a warning and leaves that class unmodified.
- That check cannot rule out every way a JEI update could break a hook. Version 1.0.0 crashed
  on JEI 15.74 because one hook read a method argument after JEI had reused its local slot.
  Since 1.0.1, arguments are only read at method entry, where that cannot happen. Each release
  is tested in game against the JEI versions listed above.
- During JEI startup, JEI sees the original recipe stack for up to one second instead of a
  copy. At the start of each JEI load the mod compares the first 512 reused stacks with what
  JEI itself would build, and turns reuse off for the session if any differ.
- Works alongside Just Enough Threads with its async startup: the startup tracking follows JEI
  onto JET's background thread. Any JEI plugin call that JET moves to the client thread still
  uses the faster cache, but not source stack reuse.
- Tested on All the Mods 9 Expert only.

After JEI finishes loading, the log shows a line like:

```
JEI start finished in 54.9 s; item stack cache: 15162149 reused from source, 15827289 hits, 1909573 rebuilt
```

## Building

The build uses plain `javac`, no Gradle. You need a JDK 17 or newer and the `libraries` folder
of a Forge 1.20.1 (47.x) install. No JEI or KubeJS jar is needed to compile.

```bash
MC_LIBS=/path/to/libraries bash build.sh
```

The jar is written to `build/libs/`. Set `FORGE_VERSION` if your libraries folder has a Forge
version other than 47.4.10. The jar runs on a normal Forge install; it does not run in a
Mojang-mapped development environment because it calls Minecraft methods by their SRG names.

## License

MIT. See [LICENSE](LICENSE).
