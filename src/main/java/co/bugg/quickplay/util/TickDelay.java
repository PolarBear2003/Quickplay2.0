package co.bugg.quickplay.util;

import co.bugg.quickplay.Quickplay;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/**
 * Class to delay code by a certain number ofgame ticks
 * @author bugfroggy
 */
public class TickDelay {

    /**
     * Constructor
     * @param fn Code to be delayed
     * @param ticks How many ticks to delay it
     */
    public TickDelay(Runnable fn, int ticks) {
        this.fn = fn;
        this.delay = ticks;

        Quickplay.INSTANCE.registerEventHandler(this);
    }

    /**
     * Default 20 ticks when unprovided
     * @param fn Code to be delayed
     */
    public TickDelay(Runnable fn) {
        this(fn, 20);
    }

    /**
     * Runnable code to delay
     */
    public Runnable fn;
    /**
     * Number of ticks to delay by
     */
    public int delay;

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent event) {
        if(event.phase == TickEvent.Phase.START) {
            // Delay expired
            if(delay < 1) {
                run();
                destroy();
            }
            delay--;
        }
    }

    /**
     * Run the delayed code
     */
    public void run() {
        fn.run();
    }

    /**
     * Destroy this object by unregistering it from the event bus
     */
    public void destroy() {
        Quickplay.INSTANCE.unregisterEventHandler(this);
    }
}
