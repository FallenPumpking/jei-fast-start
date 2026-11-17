package dev.fallen.jeifast;

import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;

/**
 * Replacement for the Guava cache behind JEI's TypedItemStack.getIngredient().
 *
 * JEI keeps only (item, tag) per ingredient and rebuilds an ItemStack on demand, remembering it
 * for one second in a Guava cache keyed by ingredient identity. While JEI indexes recipes that
 * cache is written and expired millions of times and every miss builds a new ItemStack (tag copy
 * plus Forge capability attachment). This is a fixed-size table with the same one second
 * lifetime and no per-access bookkeeping.
 *
 * Thread safety: entries are immutable and published through a single array write, so a reader
 * on another thread sees either a complete entry or none. Lost updates only cost a rebuild.
 */
public final class FastStackCache {
    private static final Logger LOG = LogManager.getLogger("jeifast");
    private static final int MASK = (1 << 18) - 1;
    private static final long TTL_NANOS = 1_000_000_000L;
    /** The whole table is dropped this often so expired stacks are not kept alive. */
    private static final long SWEEP_NANOS = 2_000_000_000L;
    private static final long MAX_START_NANOS = 15L * 60L * 1_000_000_000L;

    private record Entry(Object key, ItemStack stack, long time) {
    }

    private static final Entry[] TABLE = new Entry[MASK + 1];

    private static volatile Thread startThread;
    private static volatile long startTime;
    private static long lastSweep = System.nanoTime();
    // Statistics for the log line only; unsynchronized on purpose.
    private static long hits;
    private static long misses;
    private static long primed;

    private FastStackCache() {
    }

    private static int index(Object key) {
        int h = System.identityHashCode(key);
        return (h ^ (h >>> 16)) & MASK;
    }

    public static ItemStack lookup(Object key) {
        Entry e = TABLE[index(key)];
        if (e != null && e.key == key && System.nanoTime() - e.time < TTL_NANOS) {
            hits++;
            return e.stack;
        }
        misses++;
        return null;
    }

    public static void store(Object key, ItemStack stack) {
        long now = System.nanoTime();
        if (now - lastSweep > SWEEP_NANOS) {
            lastSweep = now;
            Arrays.fill(TABLE, null);
        }
        TABLE[index(key)] = new Entry(key, stack, now);
    }

    /**
     * While JEI is starting, the stack an ingredient was created from is a recipe or item-list
     * stack, so it can stand in for the copy JEI would rebuild a moment later. Outside startup
     * the source may be a live inventory stack, so it is never reused then.
     */
    public static void prime(Object key, ItemStack source) {
        Thread t = startThread;
        if (t != null && t == Thread.currentThread()) {
            if (System.nanoTime() - startTime > MAX_START_NANOS) {
                startThread = null;
                return;
            }
            primed++;
            store(key, source);
        }
    }

    public static void startBegin() {
        hits = 0;
        misses = 0;
        primed = 0;
        startTime = System.nanoTime();
        startThread = Thread.currentThread();
    }

    public static void startEnd() {
        if (startThread == null) {
            return;
        }
        startThread = null;
        Arrays.fill(TABLE, null);
        String seconds = String.format("%.1f", (System.nanoTime() - startTime) / 1e9);
        if (hits + misses == 0) {
            LOG.warn("JEI start finished in {} s, but the item stack hook is not active "
                + "(JEI internals changed?). JEI is running unmodified.", seconds);
        } else {
            LOG.info("JEI start finished in {} s; item stack cache: {} reused from source, {} hits, {} rebuilt",
                seconds, primed, hits, misses);
        }
    }
}
