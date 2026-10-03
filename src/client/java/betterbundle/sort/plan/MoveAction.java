package betterbundle.sort.plan;

import betterbundle.sort.model.ItemKey;

import java.util.List;

/**
 * 一个逻辑移动：从 srcBagSlot 取出 key 的 count 个物品（整叠取出到光标），
 * 依次放入 dstBagSlots（每个袋子能塞多少塞多少），装不下的余量自动返回源袋。
 *
 * <p>{@code dstBagSlots} 不含源袋本身；余量由执行器放回源袋。
 */
public record MoveAction(int srcBagSlot, ItemKey key, int count, List<Integer> dstBagSlots) {

    public MoveAction {
        dstBagSlots = List.copyOf(dstBagSlots);
    }

    public static MoveAction to(int srcBagSlot, ItemKey key, int count, int dstBagSlot) {
        return new MoveAction(srcBagSlot, key, count, List.of(dstBagSlot));
    }

    @Override
    public String toString() {
        return "Move[" + srcBagSlot + " -> " + dstBagSlots + " x" + count + "]";
    }
}
