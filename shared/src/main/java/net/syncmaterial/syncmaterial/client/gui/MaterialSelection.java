package net.syncmaterial.syncmaterial.client.gui;

import java.util.AbstractList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 材料多选：每个 databaseId 各带一个独立的意图版本。
 *
 * <p>批量分配是异步的，回包到达时用户可能已经改了勾选。因此提交时必须冻结
 * “当时哪些 id 处于何种选择意图”，完成时只清除版本仍然一致的 id：提交后
 * 新增的、取消后重选的、甚至被删除后又重新出现的 id 都带新版本，不会被旧
 * 回包误清。</p>
 *
 * <p>{@link #ids()} 返回一个 List 兼容视图，供既有界面与测试继续使用
 * {@code add/remove/clear/contains/removeIf}；真正的状态与版本仍由本类持有。
 * 这不是通用集合框架，只服务材料勾选这一条路径。</p>
 */
public final class MaterialSelection {
    private final Object lock = new Object();
    private final LinkedHashMap<Integer, Long> intents = new LinkedHashMap<>();
    private final View view = new View();
    private long lastVersion;

    /** 既有代码使用的 List 兼容壳；修改会同步推进对应 id 的意图版本。 */
    public List<Integer> ids() {
        return this.view;
    }

    public int size() {
        synchronized (this.lock) {
            return this.intents.size();
        }
    }

    public boolean isEmpty() {
        synchronized (this.lock) {
            return this.intents.isEmpty();
        }
    }

    public boolean contains(int materialId) {
        synchronized (this.lock) {
            return this.intents.containsKey(materialId);
        }
    }

    /** 新增一次勾选意图；已经选中时保持原意图不变。 */
    public boolean select(int materialId) {
        synchronized (this.lock) {
            if (this.intents.containsKey(materialId)) {
                return false;
            }
            this.lastVersion = Math.incrementExact(this.lastVersion);
            this.intents.put(materialId, this.lastVersion);
            this.view.changed();
            return true;
        }
    }

    /** 取消勾选；不存在时返回 false。 */
    public boolean deselect(int materialId) {
        synchronized (this.lock) {
            if (this.intents.remove(materialId) == null) {
                return false;
            }
            this.view.changed();
            return true;
        }
    }

    public void clear() {
        synchronized (this.lock) {
            if (this.intents.isEmpty()) {
                return;
            }
            this.intents.clear();
            this.view.changed();
        }
    }

    /** 提交时捕获给定 id 的当前意图；未选中的 id 不进入快照。 */
    public Snapshot capture(Collection<Integer> materialIds) {
        synchronized (this.lock) {
            if (materialIds == null || materialIds.isEmpty()) {
                return Snapshot.empty();
            }
            Map<Integer, Long> captured = new LinkedHashMap<>();
            for (Integer materialId : materialIds) {
                if (materialId == null) {
                    continue;
                }
                Long version = this.intents.get(materialId);
                if (version != null) {
                    captured.put(materialId, version);
                }
            }
            return captured.isEmpty() ? Snapshot.empty() : new Snapshot(this, captured);
        }
    }

    /**
     * 清除仍保持提交时意图的 id，返回实际数量。同一快照重复完成是幂等的，
     * 新勾选和其他界面实例的选择不受影响。
     */
    public int clearCaptured(Snapshot snapshot) {
        synchronized (this.lock) {
            if (snapshot == null || snapshot.owner != this || snapshot.isEmpty() || this.intents.isEmpty()) {
                return 0;
            }
            int removed = 0;
            Iterator<Map.Entry<Integer, Long>> iterator = this.intents.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Integer, Long> entry = iterator.next();
                if (Objects.equals(snapshot.versions.get(entry.getKey()), entry.getValue())) {
                    iterator.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                this.view.changed();
            }
            return removed;
        }
    }

    /** 材料目录更新后丢弃已经不在目录里的 id。 */
    public int retainIds(Collection<Integer> materialIds) {
        synchronized (this.lock) {
            if (this.intents.isEmpty()) {
                return 0;
            }
            Set<Integer> keep = new HashSet<>();
            if (materialIds != null) {
                for (Integer materialId : materialIds) {
                    if (materialId != null) {
                        keep.add(materialId);
                    }
                }
            }
            int removed = 0;
            Iterator<Map.Entry<Integer, Long>> iterator = this.intents.entrySet().iterator();
            while (iterator.hasNext()) {
                if (!keep.contains(iterator.next().getKey())) {
                    iterator.remove();
                    removed++;
                }
            }
            if (removed > 0) {
                this.view.changed();
            }
            return removed;
        }
    }

    /** 提交时的不可变意图快照。 */
    public static final class Snapshot {
        private static final Snapshot EMPTY = new Snapshot(null, Map.of());
        private final MaterialSelection owner;
        private final Map<Integer, Long> versions;

        private Snapshot(MaterialSelection owner, Map<Integer, Long> versions) {
            this.owner = owner;
            this.versions = Map.copyOf(versions);
        }

        public static Snapshot empty() {
            return EMPTY;
        }

        public boolean isEmpty() {
            return this.versions.isEmpty();
        }

        public int size() {
            return this.versions.size();
        }

        public Set<Integer> materialIds() {
            return this.versions.keySet();
        }
    }

    private final class View extends AbstractList<Integer> {
        @Override
        public Integer get(int index) {
            synchronized (MaterialSelection.this.lock) {
                if (index < 0 || index >= MaterialSelection.this.intents.size()) {
                    throw new IndexOutOfBoundsException("index: " + index
                            + ", size: " + MaterialSelection.this.intents.size());
                }
                int current = 0;
                for (Integer materialId : MaterialSelection.this.intents.keySet()) {
                    if (current++ == index) {
                        return materialId;
                    }
                }
                throw new IndexOutOfBoundsException("index: " + index);
            }
        }

        @Override
        public int size() {
            return MaterialSelection.this.size();
        }

        @Override
        public boolean add(Integer materialId) {
            Objects.requireNonNull(materialId, "materialId");
            return MaterialSelection.this.select(materialId);
        }

        @Override
        public Integer remove(int index) {
            synchronized (MaterialSelection.this.lock) {
                Integer materialId = get(index);
                MaterialSelection.this.deselect(materialId);
                return materialId;
            }
        }

        @Override
        public boolean remove(Object materialId) {
            return materialId instanceof Integer id && MaterialSelection.this.deselect(id);
        }

        @Override
        public void clear() {
            MaterialSelection.this.clear();
        }

        @Override
        public boolean contains(Object materialId) {
            return materialId instanceof Integer id && MaterialSelection.this.contains(id);
        }

        @Override
        public int indexOf(Object materialId) {
            if (!(materialId instanceof Integer id)) {
                return -1;
            }
            synchronized (MaterialSelection.this.lock) {
                int index = 0;
                for (Integer selected : MaterialSelection.this.intents.keySet()) {
                    if (selected.equals(id)) {
                        return index;
                    }
                    index++;
                }
                return -1;
            }
        }

        @Override
        public boolean removeIf(Predicate<? super Integer> filter) {
            Objects.requireNonNull(filter, "filter");
            synchronized (MaterialSelection.this.lock) {
                boolean removed = false;
                Iterator<Map.Entry<Integer, Long>> iterator =
                        MaterialSelection.this.intents.entrySet().iterator();
                while (iterator.hasNext()) {
                    if (filter.test(iterator.next().getKey())) {
                        iterator.remove();
                        removed = true;
                    }
                }
                if (removed) {
                    changed();
                }
                return removed;
            }
        }

        /** AbstractList 的迭代器依赖 modCount 发现结构变化。 */
        private void changed() {
            this.modCount++;
        }
    }
}
