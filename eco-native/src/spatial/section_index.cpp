#include "spatial/section_index.h"

#include <algorithm>
#include <vector>

namespace eco {
namespace {

Cell sectionOf(const EntityMetadata& metadata) noexcept {
    return {metadata.sectionX, metadata.sectionY, metadata.sectionZ};
}

// 段内顺序键：与原版 EntitySection 的插入序对齐。
//
// 为什么是「构建时排好序再插入」而不是「查询时按需排序」（后者是 2026-09 之前的做法）：
// 本引擎的索引是**每帧全量重建**的（beginCollisionFrame → rebuildSectionIndex），插入顺序
// 就是 Java 侧收集序；把这一遍排序做在构建时，段内向量天然有序，查询路径的 orderDirty /
// std::sort / invalidate 全套失效标记都可以删掉——上游 ECO 在 mirror vanilla section order
// 提交里也是这样收敛的（它删除了 EntityOrderMixin/EntitySectionMixin/序数字段）。
//
// 区块间的遍历顺序仍由查询侧决定（scanSection 的遍历序），这里只保证段内顺序。
bool candidateBefore(const CollisionContext& context, int leftId, int rightId) noexcept {
#if ECO_VANILLA_ORDER
    const auto& left = context.metadata[leftId];
    const auto& right = context.metadata[rightId];
    if (left.sectionOrder != right.sectionOrder) return left.sectionOrder < right.sectionOrder;
    return leftId < rightId;
#else
    (void) context;
    (void) leftId;
    (void) rightId;
    return false;
#endif
}

} // namespace

void insertSectionEntity(CollisionContext& context, int entityId) {
    context.sectionSlots.resize(context.boxes.size(), {nullptr, 0});
    const Cell section = sectionOf(context.metadata[entityId]);
    CellMembers*& entry = context.sections.entry(section);
    if (entry == nullptr) entry = &context.acquireSectionMembers();
    entry->ids.push_back(entityId);
    context.sectionSlots[entityId] = {entry, entry->ids.size() - 1};
}

void removeSectionEntity(CollisionContext& context, int entityId) {
    if (static_cast<std::size_t>(entityId) >= context.sectionSlots.size()) return;
    CellSlot slot = context.sectionSlots[entityId];
    if (slot.members == nullptr || slot.index >= slot.members->ids.size()) return;

    CellMembers& members = *slot.members;
    const int movedId = members.ids.back();
    const bool movedMember = slot.index != members.ids.size() - 1;
    if (movedMember) {
        members.ids[slot.index] = movedId;
        context.sectionSlots[movedId].index = slot.index;
    }
    members.ids.pop_back();
    context.sectionSlots[entityId] = {nullptr, 0};
    if (members.ids.empty()) {
        context.sections.erase(sectionOf(context.metadata[entityId]));
        context.retireSectionMembers(&members);
    }
}

void updateSectionEntity(
        CollisionContext& context,
        int entityId,
        std::int32_t sectionX,
        std::int32_t sectionY,
        std::int32_t sectionZ
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
) {
    EntityMetadata& metadata = context.metadata[entityId];
    const bool moved = metadata.sectionX != sectionX
            || metadata.sectionY != sectionY
            || metadata.sectionZ != sectionZ;
    if (moved) removeSectionEntity(context, entityId);
    metadata.sectionX = sectionX;
    metadata.sectionY = sectionY;
    metadata.sectionZ = sectionZ;
#if ECO_VANILLA_ORDER
    metadata.sectionOrder = sectionOrder;
#endif
    if (moved) insertSectionEntity(context, entityId);
}

void rebuildSectionIndex(CollisionContext& context) {
    context.clearSectionsAndPool();
    context.sectionSlots.resize(context.boxes.size(), {nullptr, 0});
#if ECO_VANILLA_ORDER
    // 段内顺序在构建时定型：按 (sectionOrder, id) 预排一遍槽位序，段内向量随插入自然有序，
    // 查询侧从此不再需要任何失效/重排逻辑。
    std::vector<int> order(context.boxes.size());
    for (std::size_t i = 0; i < order.size(); ++i) order[i] = static_cast<int>(i);
    std::sort(order.begin(), order.end(), [&context](int left, int right) {
        return candidateBefore(context, left, right);
    });
    for (int entityId : order) {
        insertSectionEntity(context, entityId);
    }
#else
    for (std::size_t entityId = 0; entityId < context.boxes.size(); ++entityId) {
        insertSectionEntity(context, static_cast<int>(entityId));
    }
#endif
}

const CellMembers* sectionEntities(CollisionContext& context, const Cell& section) {
    return context.sections.find(section);
}

} // namespace eco
