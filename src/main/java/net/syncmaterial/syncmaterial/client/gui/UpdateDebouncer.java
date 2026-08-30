package net.syncmaterial.syncmaterial.client.gui;

/**
 * 按 tick 计数的发包去抖器。
 *
 * 为什么需要：坐标文本框的 onTextChange 每敲一个字符就触发一次，而服务端收到
 * 一次 UPDATE_WAREHOUSE / UPDATE 会做「写库 → 清空容器明细 → 全量重扫区域内
 * 所有容器 → 推容器明细 → 全服广播线框 → 逐个引用方广播材料状态」这一整套。
 * 把坐标从 100 改成 -2048 要经过 10、1、-、-2、-20、-204、-2048 七个中间态，
 * 每个中间态都打一次服务端 —— 单人测试看不出来，多人服上是实际负担。
 *
 * 语义：schedule 只登记"最后一次意图"，连续输入期间不断刷新静默计时；
 * 静默满 quietTicks 后才真正发出。中间态被自然丢弃，因为它们没有意义
 * （谁也不关心坐标途经过 -20）。
 *
 * 不做纯延迟（固定等 N tick 后发）而做去抖，是因为纯延迟只是把每次按键的发包
 * 往后挪，总量不变。
 */
public final class UpdateDebouncer {

    private final int quietTicks;

    private Runnable pending;
    private int idleTicks;

    /**
     * @param quietTicks 输入停止后需静默多少 tick 才发包；必须为正
     */
    public UpdateDebouncer(int quietTicks) {
        if (quietTicks <= 0) {
            throw new IllegalArgumentException("quietTicks 必须为正: " + quietTicks);
        }
        this.quietTicks = quietTicks;
    }

    /**
     * 登记一次待发送动作，覆盖此前尚未发出的动作并重置静默计时。
     *
     * 覆盖而非排队是关键：连续输入产生的中间态无需逐个上报，只有最终值有意义。
     */
    public void schedule(Runnable action) {
        this.pending = action;
        this.idleTicks = 0;
    }

    /** 每客户端 tick 调用一次。静默期满则发出待发送动作 */
    public void tick() {
        if (this.pending == null) {
            return;
        }
        if (++this.idleTicks >= this.quietTicks) {
            flushNow();
        }
    }

    /**
     * 立即发出待发送动作（若有）。
     *
     * 用于关界面与按钮类操作：关界面不发就丢了用户刚敲的值；按钮类操作
     * （移到玩家、准星重选、改名）本就是单次动作，没有连发问题，等静默
     * 反而让人以为没生效。
     */
    public void flushNow() {
        Runnable action = this.pending;
        this.pending = null;
        this.idleTicks = 0;
        if (action != null) {
            action.run();
        }
    }

    /** 丢弃待发送动作，不执行。用于取消类路径 */
    public void discard() {
        this.pending = null;
        this.idleTicks = 0;
    }

    public boolean hasPending() {
        return this.pending != null;
    }
}
