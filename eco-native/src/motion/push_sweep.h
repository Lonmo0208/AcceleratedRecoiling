#pragma once

#include "motion/collision_body.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <vector>

namespace eco {

// One compacted bounding box plus its sweep bound, 64 bytes so the candidate scan stays
// dense in cache. Body rows are 104 bytes wide and would make every window test a
// scattered read across the whole array.
struct SweepBox {
    double minX, minY, minZ;
    double maxX, maxY, maxZ;
    double reach;
    int index;
};

// A degenerate or non-finite box can never take part in a push; dropping it also keeps
// the sweep key monotonic, which the early exit in the scan depends on. Mirrors
// eco::isIndexable.
inline bool boxUsable(const double* box) noexcept {
    return std::isfinite(box[0]) && std::isfinite(box[1]) && std::isfinite(box[2])
            && std::isfinite(box[3]) && std::isfinite(box[4]) && std::isfinite(box[5])
            && box[0] < box[3] && box[1] < box[4] && box[2] < box[5];
}

// Per-thread scratch so a frame never allocates once the buffer has warmed up.
inline std::vector<SweepBox>& sweepScratch() {
    static thread_local std::vector<SweepBox> sweep;
    return sweep;
}

/**
 * 扫描序列构造：过滤、排序、后缀 reach，全量推与 GPU 序构建共用这一份。
 *
 * <p>GPU 侧只能看到这份序列的 index 次序，靠它复刻原生的累加次序（每个实体先吸收
 * 排序中在它之前的伙伴、再吸收之后的），所以这里的过滤条件、比较器、reach 的算法
 * 都必须与原生完全相同——两处一旦分叉，GPU 就不再是同一个推挤。
 */
inline void buildSweep(const CollisionBody* bodies, int capacity, const double* aabbs,
                       std::vector<SweepBox>& sweep) noexcept {
    sweep.clear();
    for (int i = 0; i < capacity; ++i) {
        if (bodies[i].state & NO_PHYSICS) continue;
        const double* box = aabbs + static_cast<std::size_t>(i) * 6;
        if (!boxUsable(box)) continue;
        sweep.push_back({box[0], box[1], box[2], box[3], box[4], box[5], 0.0, i});
    }
    // Sorting on the box minimum keeps the sweep monotonic; every later candidate starts
    // further along X, so once one is out of reach the rest are too.
    std::sort(sweep.begin(), sweep.end(), [](const SweepBox& left, const SweepBox& right) noexcept {
        return left.minX < right.minX;
    });
    // Suffix maximum of the half width makes that bound exact per candidate instead of
    // using the widest entity in the level, while still covering the whole remaining tail.
    double widest = 0.0;
    for (std::size_t a = sweep.size(); a-- > 0;) {
        widest = std::max(widest, (sweep[a].maxX - sweep[a].minX) * 0.5);
        sweep[a].reach = widest;
    }
}

} // namespace eco
