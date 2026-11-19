package dev.fallen.jeifast;

import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.Arrays;
import java.util.Objects;

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
 *
 * Minecraft methods are called by SRG name (this mod is compiled without remapping):
 * m_41613_ = getCount, m_41720_ = getItem, m_41783_ = getTag.
 */
public final class FastStackCache {
    private static final Logger LOG = LogManager.getLogger("jeifast");
    private static final int MASK = (1 << 18) - 1;
    private static final long TTL_NANOS = 1_000_000_000L;
    /** The whole table is dropped this often so expired stacks are not kept alive. */
    private static final long SWEEP_NANOS = 2_000_000_000L;
    private static final long MAX_START_NANOS = 15L * 60L * 1_000_000_000L;
    /** Reused source stacks compared against JEI's own rebuild at the beginning of each start. */
    private static final int VERIFY_PER_START = 512;

    /** One cached stack. {@code primed} marks a source stack reused instead of a JEI rebuild. */
    public record Entry(Object key, ItemStack stack, long time, boolean primed) {
    }

    private static final Entry[] TABLE = new Entry[MASK + 1];

    private static volatile Thread startThread;
    private static volatile long startTime;
    private static volatile boolean primingDisabled;
    private static long lastSweep = System.nanoTime();
    /** Source of the TypedItemStack.create call in progress on the start thread. */
    private static ItemStack pendingSource;
    private static int verifyBudget;
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

    /** Returns the live entry for this ingredient, or null. */
    public static Entry find(Object key) {
        Entry e = TABLE[index(key)];
        if (e != null && e.key == key && System.nanoTime() - e.time < TTL_NANOS) {
            hits++;
            return e;
        }
        misses++;
        return null;
    }

    public static void store(Object key, ItemStack stack) {
        put(key, stack, false);
    }

    private static void put(Object key, ItemStack stack, boolean isPrimed) {
        long now = System.nanoTime();
        if (now - lastSweep > SWEEP_NANOS) {
            lastSweep = now;
            Arrays.fill(TABLE, null);
        }
        TABLE[index(key)] = new Entry(key, stack, now, isPrimed);
    }

    private static boolean onStartThread() {
        Thread t = startThread;
        return t != null && t == Thread.currentThread();
    }

    /** Called at the head of TypedItemStack.create(ItemStack), where the argument is intact. */
    public static void createBegin(ItemStack source) {
        if (onStartThread() && !primingDisabled) {
            pendingSource = source != null && source.m_41613_() == 1 ? source : null;
        }
    }

    /**
     * Called when TypedItemStack.create(ItemStack) returns. While JEI is starting, the stack an
     * ingredient was created from is a recipe or item-list stack, so it can stand in for the copy
     * JEI would rebuild a moment later. Outside startup the source may be a live inventory stack,
     * so it is never reused then.
     */
    public static void createEnd(Object typedIngredient) {
        if (!onStartThread()) {
            return;
        }
        ItemStack source = pendingSource;
        pendingSource = null;
        if (source == null || typedIngredient == null || primingDisabled) {
            return;
        }
        if (System.nanoTime() - startTime > MAX_START_NANOS) {
            startThread = null;
            return;
        }
        primed++;
        put(typedIngredient, source, true);
    }

    /** True for the first reused stacks of each JEI start, which get checked against a rebuild. */
    public static boolean shouldVerify(Entry e) {
        if (e.primed && verifyBudget > 0) {
            verifyBudget--;
            return true;
        }
        return false;
    }

    public static boolean sameStack(ItemStack a, ItemStack b) {
        return a.m_41720_() == b.m_41720_()
            && a.m_41613_() == b.m_41613_()
            && Objects.equals(a.m_41783_(), b.m_41783_());
    }

    /** A reused source stack did not match what JEI builds: stop reusing them. */
    public static void primingMismatch(ItemStack reused, ItemStack rebuilt) {
        if (!primingDisabled) {
            primingDisabled = true;
            LOG.warn("Source stack reuse is turned off: JEI built {} where the source was {}. "
                + "JEI internals differ from what this version expects; the cache replacement stays active.",
                rebuilt, reused);
        }
    }

    public static void startBegin() {
        hits = 0;
        misses = 0;
        primed = 0;
        pendingSource = null;
        verifyBudget = VERIFY_PER_START;
        startTime = System.nanoTime();
        startThread = Thread.currentThread();
    }

    public static void startEnd() {
        if (startThread == null) {
            return;
        }
        startThread = null;
        pendingSource = null;
        Arrays.fill(TABLE, null);
        String seconds = String.format("%.1f", (System.nanoTime() - startTime) / 1e9);
        if (hits + misses == 0) {
            LOG.warn("JEI start finished in {} s, but the item stack hook is not active. "
                + "JEI is running unmodified.", seconds);
        } else {
            LOG.info("JEI start finished in {} s; item stack cache: {} reused from source, {} hits, {} rebuilt",
                seconds, primed, hits, misses);
        }
    }
}
