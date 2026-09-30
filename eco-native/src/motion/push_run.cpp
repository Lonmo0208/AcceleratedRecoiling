#include "eco/collision_api.h"
#include "motion/collision_body.h"
#include "motion/push_math.h"
#include "motion/push_sweep.h"
#include "state/entity_metadata.h"

#include <algorithm>
#include <cmath>
#include <cstddef>
#include <cstdint>
#include <cstring>
#include <vector>

namespace {

void push(eco::CollisionBody& body, double x, double z) noexcept {
    // Entity.push first rejects non-finite input, then setDeltaMovement rejects non-finite sums.
    // The latter still sets needsSync; it does not partially accept individual components.
    if (!std::isfinite(x) || !std::isfinite(z)) return;
    const double vx = body.vx + x;
    const double vy = body.vy + 0.0; // Preserve vanilla's signed-zero addition, too.
    const double vz = body.vz + z;
    body.needsSync = 1;
    if (!std::isfinite(vx) || !std::isfinite(vy) || !std::isfinite(vz)) return;
    body.vx = vx;
    body.vy = vy;
    body.vz = vz;
    ++body.velocityVersion;
}

bool acceptsImpulse(const eco::CollisionBody& body) noexcept {
    return (body.state & (eco::PUSHABLE | eco::VEHICLE)) == eco::PUSHABLE;
}

} // namespace

int executePushRun(void* bodyPointer, int capacity, int sourceSlot, const int* targetSlots, int count) {
    if (bodyPointer == nullptr || targetSlots == nullptr || count < 0
            || sourceSlot < 0 || sourceSlot >= capacity) return -1;
    // Validate the whole request before modifying persistent state. Query deduplication supplies
    // unique targets; their original order, including source accumulation, remains unchanged.
    for (int i = 0; i < count; ++i) {
        if (targetSlots[i] < 0 || targetSlots[i] >= capacity || targetSlots[i] == sourceSlot) return -1;
    }
    auto* bodies = static_cast<eco::CollisionBody*>(bodyPointer);
    auto& source = bodies[sourceSlot];
    const bool pushSource = acceptsImpulse(source);
    if (source.state & eco::NO_PHYSICS) return 0;
    for (int i = 0; i < count; ++i) {
        auto& target = bodies[targetSlots[i]];
        if (target.state & (eco::NO_PHYSICS | eco::SLEEPING)) continue;
        if (((source.state | target.state) & eco::PASSENGER) && source.root == target.root) continue;
        const bool pushTarget = acceptsImpulse(target);
        if (!pushSource && !pushTarget) continue;
        double x, z;
        if (!eco::pushImpulse(source.x, source.z, target.x, target.z, x, z)) continue;
        if (pushTarget) push(target, -x, -z);
        // Source accumulation is strictly sequential: never reduce a sum of impulses first.
        if (pushSource) push(source, x, z);
    }
    return 0;
}

namespace {

// Team filter mirroring query/collision_rules.cpp. reserved packs (teamId<<2)|rule.
inline bool teamAccepts(int sourceTeam, int sourceRule, int targetTeam, int targetRule) noexcept {
    const bool allied = sourceTeam >= 0 && sourceTeam == targetTeam;
    unsigned allowed;
    if (allied) {
        allowed = (sourceRule == eco::COLLISION_NEVER || sourceRule == eco::COLLISION_PUSH_OWN_TEAM)
                ? 0u : (1u << eco::COLLISION_ALWAYS) | (1u << eco::COLLISION_PUSH_OTHER_TEAMS);
    } else {
        allowed = (sourceRule == eco::COLLISION_NEVER || sourceRule == eco::COLLISION_PUSH_OTHER_TEAMS)
                ? 0u : (1u << eco::COLLISION_ALWAYS) | (1u << eco::COLLISION_PUSH_OWN_TEAM);
    }
    return (allowed & (1u << targetRule)) != 0;
}

// Vanilla's pair admission is AABB.intersects as used by EntitySection.getEntities:
// strict inequalities, no epsilon, no inflation.
inline bool intersects(const eco::SweepBox& a, const eco::SweepBox& b) noexcept {
    return a.minX < b.maxX && a.maxX > b.minX
            && a.minY < b.maxY && a.maxY > b.minY
            && a.minZ < b.maxZ && a.maxZ > b.minZ;
}

// A degenerate or non-finite box can never take part in a push; dropping it also keeps the
// sweep key monotonic, which the early exit below depends on. Mirrors eco::isIndexable.
// Box filter and sweep construction now live in motion/push_sweep.h so the CPU run and the
// GPU order build cannot drift apart.

} // namespace

int executeFullPushRun(void* bodyPointer, int capacity, int* neighborCounts, const double* aabbs,
                       int partnerCap) {
    if (bodyPointer == nullptr || capacity < 0
            || (capacity > 0 && (neighborCounts == nullptr || aabbs == nullptr))) return -1;
    auto* bodies = static_cast<eco::CollisionBody*>(bodyPointer);
    for (int i = 0; i < capacity; ++i) neighborCounts[i] = 0;
    if (capacity < 2) return 0;
    try {
        // Pair enumeration sweeps the boxes along X. A candidate is admitted only while the
        // sweep still reaches it and its real box intersects the source, so the visited pair
        // set equals a full (i, j) traversal while most of the O(N^2) space is never touched.
        // The boxes are compacted into one array so the scan walks memory in order; the body
        // rows are 104 bytes wide and would otherwise turn every candidate test into a
        // scattered read.
        //
        // 一对只走一次是为了省时间，但**施加次数必须补成两次**（见下面的注释）：原版
        // pushEntities 是每个实体各推对方一次，同一对一帧吃两遍冲量。
        std::vector<eco::SweepBox>& sweep = eco::sweepScratch();
        eco::buildSweep(bodies, capacity, aabbs, sweep);
        const std::size_t live = sweep.size();
        if (live < 2) return 0;
        for (std::size_t a = 0; a < live; ++a) {
            const eco::SweepBox& sourceBox = sweep[a];
            auto& source = bodies[sourceBox.index];
            const bool pushSource = acceptsImpulse(source);
            const int sourceTeam = source.reserved >> 2;
            const int sourceRule = source.reserved & 3;
            for (std::size_t b = a + 1; b < live; ++b) {
                const eco::SweepBox& targetBox = sweep[b];
                if (targetBox.minX - targetBox.reach >= sourceBox.maxX) break;
                // 精确包围盒判定放在最前面：它只读紧凑的扫描数组，而下面的 state/team 检查
                // 要按随机下标访问 body 行。窗口内的候选大多不相交，先判相交能把 body 行的
                // 随机访问量压到只剩真正相交的对。
                // 原版候选过滤就是 AABB.intersects，严格不等且无 epsilon。1.21.1 传给
                // Level.getEntities 的是未膨胀的自身盒，上游 26.2 的 X/Z +0.2 膨胀不能照搬，
                // 否则这里会比原版多推一圈实体，PARITY 也就不再等价。
                if (!intersects(sourceBox, targetBox)) continue;
                // 实验档（SPARSE）的「漏」：每个实体最多保留 partnerCap 个对手，与 AR 的
                // maxCollision 同义。判据按**每个实体各自**算，不是按这一对算——原版与 AR 都是
                // 「每个实体各自建自己的候选表」，所以一对只对「还没满的那一侧」生效。
                // 这样两侧的判断互不影响，GPU 内核里一个 work-item 也能独立算出自己那一半。
                // partnerCap <= 0 表示不限，此时两个 keep 恒为真，与加这个参数之前逐字节一致。
                const bool keepTarget = partnerCap <= 0
                        || neighborCounts[targetBox.index] < partnerCap;
                const bool keepSource = partnerCap <= 0
                        || neighborCounts[sourceBox.index] < partnerCap;
                if (!keepTarget && !keepSource) continue;
                auto& target = bodies[targetBox.index];
                if (target.state & (eco::NO_PHYSICS | eco::SLEEPING)) continue;
                if (((source.state | target.state) & eco::PASSENGER) && source.root == target.root) continue;
                const bool pushTarget = acceptsImpulse(target);
                if (!pushSource && !pushTarget) continue;
                if (!teamAccepts(sourceTeam, sourceRule, target.reserved >> 2, target.reserved & 3)) continue;
                double x, z;
                if (!eco::pushImpulse(source.x, source.z, target.x, target.z, x, z)) continue;
                // 计数只记「保留下来」的伙伴（SPARSE 档下就是前 partnerCap 个）；
                // 默认档两个 keep 恒真，与旧行为一致。
                if (keepTarget) {
                    ++neighborCounts[targetBox.index];
                    if (pushTarget) push(target, -x, -z);
                }
                if (keepSource) {
                    ++neighborCounts[sourceBox.index];
                    if (pushSource) push(source, x, z);
                }
            }
        }
    } catch (...) {
        // 分配失败等异常绝不能越过 FFI 边界，交给调用方回退原版路径。
        return -1;
    }
    return 0;
}

// ---------------------------------------------------------------------------
// GPU 推挤后端的两端：原生只负责「建序 + 打包」与「回写」，中间的计算由 OpenCL 内核做。
// 之所以把这两步留在原生：它们要按 body 行（104 字节）随机读字段，走 FFM 逐字段访问
// 会把每帧几万次调用摊在 Java 侧，白白吃掉 GPU 省下来的时间。
// ---------------------------------------------------------------------------

int prepareGpuPush(void* bodyPointer, int capacity, const double* aabbs,
                   int* order, int* outLive, double* packed, int* meta, int* range,
                   int partnerCap) {
    if (bodyPointer == nullptr || aabbs == nullptr || order == nullptr || outLive == nullptr
            || packed == nullptr || meta == nullptr || range == nullptr || capacity < 0) return -1;
    *outLive = 0;
    if (capacity == 0) return 0;
    try {
        auto* bodies = static_cast<const eco::CollisionBody*>(bodyPointer);
        // packed 每行 12 个 double：x,z,vx,vy,vz,minX,minY,minZ,maxX,maxY,maxZ,保留
        // meta 每行 4 个 int：state,root,reserved,sortedPos（-1 表示不进扫描序列）
        for (int i = 0; i < capacity; ++i) {
            const eco::CollisionBody& body = bodies[i];
            const double* box = aabbs + static_cast<std::size_t>(i) * 6;
            double* row = packed + static_cast<std::size_t>(i) * 12;
            row[0] = body.x;
            row[1] = body.z;
            row[2] = body.vx;
            row[3] = body.vy;
            row[4] = body.vz;
            for (int k = 0; k < 6; ++k) row[5 + k] = box[k];
            int* m = meta + static_cast<std::size_t>(i) * 4;
            m[0] = body.state;
            m[1] = body.root;
            m[2] = body.reserved;
            m[3] = -1;
            range[static_cast<std::size_t>(i) * 2] = 0;
            range[static_cast<std::size_t>(i) * 2 + 1] = 0;
        }
        // 与 executeFullPushRun 用同一个构造器，得到的次序因此逐位相同：内核靠它复刻
        // 原生的累加次序，两处共用一份实现才不会分叉。
        std::vector<eco::SweepBox>& sweep = eco::sweepScratch();
        eco::buildSweep(bodies, capacity, aabbs, sweep);
        const int live = static_cast<int>(sweep.size());
        for (int a = 0; a < live; ++a) {
            const int index = sweep[a].index;
            order[a] = index;
            meta[static_cast<std::size_t>(index) * 4 + 3] = a;
        }
        // 候选窗口：CPU 那版靠 X 轴早退出剪枝，只扫得动窗口内的少数盒子；内核若不剪枝，
        // 每个实体都要全表扫一遍（实测这一步占了显卡推动的 5.5 ms，比 CPU 还慢）。
        // 这里按同一个几何关系给每个实体算出它唯一可能相交的一段 [lo, hi)，内核只扫这一段。
        // 剪枝只可能少扫「本来也不相交」的对，相交判定仍在核心里做，所以结果与不剪枝完全一致。
        if (live > 0) {
            // reach[0] 是整条序列的后缀最大值，也就是全局最大半宽——不是 0 号自己的半宽
            // （写成 0 号的会把下界收得过紧，比 0 号宽的实体就丢配了）。
            const double totalWidth = sweep[0].reach * 2.0;
            for (int a = 0; a < live; ++a) {
                const int index = sweep[a].index;
                const double lower = sweep[a].minX - totalWidth;
                const double upper = sweep[a].maxX;
                // 升序序列上两分：相交要求 minX < 自身 maxX，且 minX + 2*reach > 自身 minX。
                const auto lowerBound = std::lower_bound(sweep.begin(), sweep.end(), upper,
                        [](const eco::SweepBox& box, double value) noexcept { return box.minX < value; });
                const auto upperBound = std::upper_bound(sweep.begin(), sweep.end(), lower,
                        [](double value, const eco::SweepBox& box) noexcept { return value < box.minX; });
                int lo = static_cast<int>(upperBound - sweep.begin());
                int hi = static_cast<int>(lowerBound - sweep.begin());
                if (lo < 0) lo = 0;
                if (hi > live) hi = live;
                if (lo > a) lo = a;
                if (hi <= a) hi = a + 1;
                range[static_cast<std::size_t>(index) * 2] = lo;
                range[static_cast<std::size_t>(index) * 2 + 1] = hi;
            }
        }
        *outLive = live;
    } catch (...) {
        return -1;
    }
    return 0;
}

int applyGpuPush(void* bodyPointer, int capacity, const double* outVel, const int* outAux,
                 int* neighborCounts) {
    if (bodyPointer == nullptr || outVel == nullptr || outAux == nullptr
            || (capacity > 0 && neighborCounts == nullptr) || capacity < 0) return -1;
    auto* bodies = static_cast<eco::CollisionBody*>(bodyPointer);
    // 内核已经把 push() 的逐次结果算完（含「和为非有限则不落盘」这条），这里只做搬运。
    for (int i = 0; i < capacity; ++i) {
        eco::CollisionBody& body = bodies[i];
        const std::size_t o = static_cast<std::size_t>(i) * 3;
        body.vx = outVel[o];
        body.vy = outVel[o + 1];
        body.vz = outVel[o + 2];
        const int pushes = outAux[o + 1];
        if (pushes != 0) body.velocityVersion += static_cast<std::uint64_t>(pushes);
        if (outAux[o + 2] != 0) body.needsSync = 1;
        neighborCounts[i] = outAux[o];
    }
    return 0;
}

int verifyGpuPush(void* bodyPointer, int capacity, const double* aabbs, int* neighborCounts,
                  const double* outVel, const int* outAux, int* outDiff, int partnerCap) {
    if (bodyPointer == nullptr || aabbs == nullptr || neighborCounts == nullptr
            || outVel == nullptr || outAux == nullptr || outDiff == nullptr || capacity < 0) return -1;
    *outDiff = 0;
    if (capacity == 0) return 0;
    try {
        auto* bodies = static_cast<eco::CollisionBody*>(bodyPointer);
        // 先把这一帧的行整个存下来：下面要拿 CPU 全量推当参考跑在真实行上，
        // 跑完再把 GPU 的结果恢复回去，所以开了对拍也不会改变游戏看到的东西。
        std::vector<eco::CollisionBody> snapshot(bodies, bodies + capacity);
        // 参考必须用**同一个** partnerCap：SPARSE 档下拿满配去比稀疏，必然处处不一致。
        if (executeFullPushRun(bodyPointer, capacity, neighborCounts, aabbs, partnerCap) != 0) return -1;
        int diff = 0;
        for (int i = 0; i < capacity; ++i) {
            const eco::CollisionBody& cpu = bodies[i];
            const std::size_t o = static_cast<std::size_t>(i) * 3;
            // 用原始位比对：±0.0 与 NaN 的差别同样要被抓出来。
            if (std::memcmp(&cpu.vx, &outVel[o], sizeof(double)) != 0) ++diff;
            if (std::memcmp(&cpu.vy, &outVel[o + 1], sizeof(double)) != 0) ++diff;
            if (std::memcmp(&cpu.vz, &outVel[o + 2], sizeof(double)) != 0) ++diff;
            if (neighborCounts[i] != outAux[o]) ++diff;
            const std::uint64_t versionDelta = cpu.velocityVersion - snapshot[i].velocityVersion;
            if (versionDelta != static_cast<std::uint64_t>(outAux[o + 1])) ++diff;
            if ((cpu.needsSync != 0) != (outAux[o + 2] != 0)) ++diff;
        }
        std::copy(snapshot.begin(), snapshot.end(), bodies);
        *outDiff = diff;
    } catch (...) {
        return -1;
    }
    return 0;
}
