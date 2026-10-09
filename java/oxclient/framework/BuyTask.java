package oxclient.framework;

import oxclient.api.Dialog;
import oxclient.api.GrandExchange;
import oxclient.api.Inventory;
import oxclient.api.Walker;

/**
 * Buy an item at the Grand Exchange: walk there, open it, buy, wait, collect. NOT SEEN WORKING -- see
 * {@link GrandExchange}: the Buy button must be mapped once (GrandExchange.buyButton) before this can run,
 * and it fails with that reason until then.
 */
public final class BuyTask extends BaseTask {

    private enum Step { WALK, OPEN, BUY, SEARCH, PICK, QUANTITY, PRICE, CONFIRM, WAIT, COLLECT, CLOSE }

    private final String item;
    private final int quantity, priceClicks;
    private Step step = Step.WALK;
    private Walker.Route route;
    private int startCount;

    /** @param priceClicks how many "+5%" clicks over the guide price (3 = +15%) */
    public BuyTask(String item, int quantity, int priceClicks) {
        this.item = item;
        this.quantity = quantity;
        this.priceClicks = priceClicks;
    }

    @Override public String name() { return "Buy " + quantity + " x " + item; }

    @Override public void start() { step = Step.WALK; route = null; startCount = Inventory.count(item); }

    @Override
    protected Result step(long now) {
        if (GrandExchange.buyButton == null) return fail("the Grand Exchange Buy button is not mapped yet (GrandExchange.buyButton)");
        state(step.name().toLowerCase());
        switch (step) {
            case WALK -> {
                if (route == null) route = new Walker.Route(Places.GRAND_EXCHANGE.x(), Places.GRAND_EXCHANGE.y(), 0, Places.GRAND_EXCHANGE.radius());
                Walker.Status st = route.step();
                if (st == Walker.Status.ARRIVED) step = Step.OPEN;
                else if (st == Walker.Status.NO_ROUTE || st == Walker.Status.STUCK) return fail("cannot reach the Grand Exchange");
            }
            case OPEN -> {
                if (GrandExchange.isOpen()) { step = Step.BUY; return Result.CONTINUE; }
                if (inState(now) > 15000) return fail("the Grand Exchange never opened");
                GrandExchange.open();
                sleep(now, 2000, 3000);
            }
            case BUY -> { GrandExchange.clickBuy(); step = Step.SEARCH; sleep(now, 1200, 1800); }
            case SEARCH -> { GrandExchange.search(item); step = Step.PICK; sleep(now, 1500, 2200); }
            case PICK -> {
                if (!GrandExchange.pickResult(item)) { if (inState(now) > 6000) return fail("no search result named " + item); return Result.CONTINUE; }
                step = Step.QUANTITY;
                sleep(now, 1000, 1600);
            }
            case QUANTITY -> { if (quantity > 1) GrandExchange.setQuantity(quantity); step = Step.PRICE; sleep(now, 1200, 1800); }
            case PRICE -> { GrandExchange.raisePrice(priceClicks); step = Step.CONFIRM; sleep(now, 800, 1200); }
            case CONFIRM -> { if (!GrandExchange.confirm()) return fail("no Confirm button"); step = Step.WAIT; sleep(now, 3000, 5000); }
            case WAIT -> { step = Step.COLLECT; }
            case COLLECT -> {
                if (Inventory.count(item) > startCount) { step = Step.CLOSE; return Result.CONTINUE; }
                if (inState(now) > 60000) return fail("the offer did not complete in a minute");
                GrandExchange.collect();
                sleep(now, 2500, 4000);
            }
            case CLOSE -> { if (Dialog.isOpen() || GrandExchange.isOpen()) GrandExchange.close(); return Result.DONE; }
        }
        return Result.CONTINUE;
    }
}
