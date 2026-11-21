package net.syncmaterial.syncmaterial.client.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import net.syncmaterial.syncmaterial.client.PickupModeState;
import net.syncmaterial.syncmaterial.core.ProjectProgressMaterialView;

/**
 * HUD 的纯投影：输入是当前 {@link ProjectProgressMaterialView} 列表与本机即时背包样本，
 * 输出是"这一帧应该显示哪些行、每行差多少、空态该说什么"。
 *
 * <p>这里没有任何 Minecraft 类型、静态缓存或回调假设。HUD 每次渲染都重新调用
 * {@link #compute}，因此切换模式、目录更新、进度推送和背包变化都会在同一帧生效；
 * 2 秒缓存与"按模式记住列表"的旧逻辑不再存在。排序仍由调用方用
 * {@link MaterialListSorter} 处理，本类只保留输入下标，避免把 GUI 状态带进来。</p>
 *
 * <p>团队缺口直接使用 {@code view.teamMissing()}（与 GUI 行状态同一业务结果），
 * 个人取货则复用 {@link PickupModeState#computePerMaterialPickupNeeds} 的同物品额度
 * 分配，保证同一物品的多个条目不会各自减一遍背包数量。所有数量走饱和
 * 减法/取小，不存在 long 回绕；本类不计算也不展示团队持有总数，避免把
 * 饱和后的总和伪装成精确值。</p>
 */
public final class MaterialHudProjection {

    private MaterialHudProjection() {}

    /** 空列表时 HUD 要显示的稳定状态；每一态对应一个 i18n key。 */
    public enum EmptyState {
        /** 有行可显示，不使用空态文案。 */
        NONE(""),
        /** 本机没有认领任何材料（项目可能有需求）。 */
        NO_PARTICIPATION("syncmaterial.gui.hint.claim_materials"),
        /** 已认领的材料需求为 0，或项目本身没有材料。 */
        NO_DEMAND("syncmaterial.gui.material_status.no_demand"),
        /** 取货模式：本人当前没有需要从仓库取的份额。 */
        PICKUP_NONE_NEEDED("syncmaterial.gui.hint.pickup_none_needed"),
        /** 取货模式：本人仍有缺口，但仓库没有可取的货。 */
        WAREHOUSE_EMPTY("syncmaterial.gui.hint.warehouse_empty"),
        /** 团队总量已够，但备货区还没收到。 */
        TEAM_COLLECTED_AWAITING_STAGING("syncmaterial.gui.material_status.team_collected"),
        /** 备货区已能满足全部已认领需求。 */
        STAGING_READY("syncmaterial.gui.material_status.staging_ready"),
        /** 区域库存未知/过期，不能确认已备齐或已收集。 */
        PREPARATION_UNKNOWN("syncmaterial.gui.material_status.unknown");

        private final String translationKey;

        EmptyState(String translationKey) {
            this.translationKey = translationKey;
        }

        public String translationKey() {
            return this.translationKey;
        }
    }

    /**
     * 一条可见的 HUD 行。
     *
     * @param index 在输入 {@code views} 中的下标，用于回到对应的 {@link MaterialListEntry} 排序/绘制
     * @param count 本行要展示的缺口（收集模式为团队缺口，取货模式为本人可取数），恒为正
     */
    public record Row(int index, long count, ProjectProgressMaterialView view) {
        public Row {
            if (index < 0) {
                throw new IllegalArgumentException("index must be >= 0");
            }
            if (count <= 0L) {
                throw new IllegalArgumentException("count must be > 0");
            }
            Objects.requireNonNull(view, "view");
            if (!view.claimed()) {
                throw new IllegalArgumentException("only claimed materials can become HUD rows");
            }
        }
    }

    public record Model(List<Row> rows, EmptyState emptyState, boolean warningVisible) {
        public Model {
            rows = List.copyOf(Objects.requireNonNull(rows, "rows"));
            Objects.requireNonNull(emptyState, "emptyState");
            if (rows.isEmpty() && emptyState == EmptyState.NONE) {
                throw new IllegalArgumentException("empty result must carry an empty state");
            }
            if (!rows.isEmpty() && emptyState != EmptyState.NONE) {
                throw new IllegalArgumentException("non-empty result cannot carry an empty state");
            }
        }
    }

    /**
     * 计算当前 HUD 模型；纯函数，不读取或写入任何全局状态。
     *
     * @param views           与材料列表同序、已经过 {@code MaterialListEntry.progressView(list)}
     *                        新鲜度闸门的逐材料视图
     * @param pickupMode      true=取货模式（个人可取），false=收集模式（团队缺口）
     * @param liveCounts      本机即时背包样本（含光标）；null 才回退视图里的服务端观测，
     *                        非 null 但缺少某物品表示本机确认该物品为 0
     * @param preparationKnown 项目级区域库存是否已知且当前；与逐行
     *                        {@link ProjectProgressMaterialView#preparationKnown()} 取与
     */
    public static Model compute(List<ProjectProgressMaterialView> views,
                                boolean pickupMode,
                                Map<String, Long> liveCounts,
                                boolean preparationKnown) {
        Objects.requireNonNull(views, "views");

        List<PickupModeState.MaterialSnapshot> snapshots = pickupMode
                ? new ArrayList<PickupModeState.MaterialSnapshot>(views.size())
                : null;
        boolean anyDemand = false;
        boolean claimedPositiveDemand = false;
        boolean allClaimedZeroDemand = true;
        boolean claimedCompletionTrusted = true;
        int claimedCount = 0;

        for (ProjectProgressMaterialView view : views) {
            Objects.requireNonNull(view, "view");
            if (snapshots != null) {
                snapshots.add(new ViewSnapshot(view.itemId(), view.required(), view.staging(),
                        view.myObserved(), view.warehouse(), view.claimed()));
            }
            if (view.required() > 0L) {
                anyDemand = true;
            }
            if (!view.claimed()) {
                continue;
            }
            claimedCount++;
            if (view.required() > 0L) {
                claimedPositiveDemand = true;
                allClaimedZeroDemand = false;
            }
            if (!view.preparationKnown()) {
                claimedCompletionTrusted = false;
            }
        }

        boolean completionTrusted = preparationKnown && claimedCompletionTrusted;
        PickupModeState.MaterialPickupNeed[] pickupNeeds = pickupMode
                ? PickupModeState.computePerMaterialPickupNeeds(snapshots, liveCounts)
                : null;

        List<Row> rows = new ArrayList<>();
        for (int index = 0; index < views.size(); index++) {
            ProjectProgressMaterialView view = views.get(index);
            if (!view.claimed()) {
                continue;
            }
            long count = pickupMode ? pickupNeeds[index].pickupMissing() : view.teamMissing();
            if (count > 0L) {
                rows.add(new Row(index, count, view));
            }
        }

        EmptyState emptyState = rows.isEmpty()
                ? classifyEmpty(views, pickupMode, pickupNeeds, anyDemand, claimedCount,
                        allClaimedZeroDemand, completionTrusted)
                : EmptyState.NONE;

        // 只有"本人已认领且确有需求"时，区域库存未知才影响 HUD 结论；
        // 零需求与尚未参与不显示容易误导的过期警告。
        boolean warningVisible = !completionTrusted && claimedPositiveDemand;

        return new Model(rows, emptyState, warningVisible);
    }

    private static EmptyState classifyEmpty(List<ProjectProgressMaterialView> views,
                                            boolean pickupMode,
                                            PickupModeState.MaterialPickupNeed[] pickupNeeds,
                                            boolean anyDemand,
                                            int claimedCount,
                                            boolean allClaimedZeroDemand,
                                            boolean completionTrusted) {
        // 零需求独立于区域新鲜度，和 MaterialRowStatus 的判定顺序保持一致。
        if (!anyDemand) {
            return EmptyState.NO_DEMAND;
        }
        if (claimedCount == 0) {
            return EmptyState.NO_PARTICIPATION;
        }
        if (allClaimedZeroDemand) {
            return EmptyState.NO_DEMAND;
        }
        if (!completionTrusted) {
            return EmptyState.PREPARATION_UNKNOWN;
        }
        if (pickupMode) {
            boolean personalShortfall = false;
            boolean teamCollected = true;
            for (int index = 0; index < views.size(); index++) {
                ProjectProgressMaterialView view = views.get(index);
                if (!view.claimed()) {
                    continue;
                }
                if (pickupNeeds[index].remaining() > 0L) {
                    personalShortfall = true;
                }
                if (!view.teamCollected()) {
                    teamCollected = false;
                }
            }
            if (!personalShortfall) {
                return EmptyState.PICKUP_NONE_NEEDED;
            }
            // 团队总量已够时，可取数自然为 0 的原因不是仓库没货，而是还没送达备货区。
            return teamCollected
                    ? EmptyState.TEAM_COLLECTED_AWAITING_STAGING
                    : EmptyState.WAREHOUSE_EMPTY;
        }

        boolean stagingReady = true;
        for (ProjectProgressMaterialView view : views) {
            // 零需求行不能被当成"未备齐"；这里只判断有正数需求的已认领行。
            if (view.claimed() && view.required() > 0L && !view.stagingReady()) {
                stagingReady = false;
                break;
            }
        }
        return stagingReady
                ? EmptyState.STAGING_READY
                : EmptyState.TEAM_COLLECTED_AWAITING_STAGING;
    }

    /** 视图到取货计算输入的适配；字段名与 {@link PickupModeState.MaterialSnapshot} 一致。 */
    private record ViewSnapshot(String itemId,
                                long countTotal,
                                long stagingCount,
                                long myCount,
                                long warehouseCount,
                                boolean claimedByCurrentPlayer)
            implements PickupModeState.MaterialSnapshot {
    }
}
