#pragma once

#ifndef ECO_VANILLA_ORDER
#define ECO_VANILLA_ORDER 1
#endif

#include "eco/export.h"
#include <cstdint>

extern "C" {
ECO_EXPORT int putCollisionEntity(
        void* context, int id, const double* bounds, int x, int y, int z
#if ECO_VANILLA_ORDER
        ,
        std::int64_t sectionOrder
#endif
);
ECO_EXPORT int removeCollisionEntity(void* context, int id);
ECO_EXPORT int updateCollisionLocation(
        void* context, int id, int x, int y, int z
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
);
ECO_EXPORT int scanCollisionBlocks(const std::uint16_t* const* rows, int* query, int* output, int capacity);

ECO_EXPORT void* createCollisionContext();
ECO_EXPORT void destroyCollisionContext(void* context);
ECO_EXPORT int setCollisionGridSize(void* context, int gridSize);
ECO_EXPORT int beginCollisionFrame(
        void* context,
        const double* aabbs,
        const int* sections,
        int entityCount,
        int gridSize
);
ECO_EXPORT int addCollisionEntity(
        void* context,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int sectionX,
        int sectionY,
        int sectionZ
);
ECO_EXPORT int updateCollisionEntity(
        void* context,
        int entityId,
        const double* bounds,
        int selectable,
        int passenger,
        int vehicle,
        int noPhysics,
        int vanillaEntityPush,
        int vanillaVectorPush,
        int teamId,
        int collisionRule,
        int bodySlot,
        int hardCollidable
#if ECO_VANILLA_ORDER
        , std::int64_t sectionOrder
#endif
);
ECO_EXPORT int invalidateEntityPushabilityCache(void* context, int entityId);
ECO_EXPORT int invalidatePushEligibilityFields(void* context, int fieldsToInvalidate);
ECO_EXPORT int queryCollisionEntities(void* context, int sourceId, int* output, int outputCapacity);
ECO_EXPORT int queryHardCollisionEntities(
        void* context,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int excludeId,
        int hardOnly,
        int* output,
        int outputCapacity
);
// Whole-level box scan for EntitySectionStorage.getEntities. The ordered build
// preserves section and insertion order; the unordered build omits that cost.
ECO_EXPORT int queryEntitiesInBox(
        void* context,
        double minX,
        double minY,
        double minZ,
        double maxX,
        double maxY,
        double maxZ,
        int* output,
        int outputCapacity
);
// output: [metadataRequired, pushableCount, nonPassengerCount], IDs[capacity], bodySlots[capacity].
// On a metadata miss only the header and returned IDs are valid. nativePushOutput has capacity ints.
ECO_EXPORT int queryPushableEntities(
        void* context,
        int sourceId,
        int sourceTeamId,
        int sourceCollisionRule,
        int sourceUsesVanillaPush,
        int* output,
        int* nativePushOutput,
        int outputCapacity
);

// Per-source push: applies one source's pairs. **Currently has no caller** — the engine uses
// executeFullPushRun below instead. Kept for reference; do NOT assume the two agree:
// PushRunEquivalenceTest (tools/test) measured the batch applying each pair once while this
// path applies it once per direction, so per-source comes out ~1.89x stronger, and the two
// also differ in admission rules (the batch has a team filter and only checks the sweep-later
// entity's sleeping state). Making them agree would require unifying admission first, and an
// earlier attempt to "fix" the strength difference made in-game move/collide twice as slow.
ECO_EXPORT int executePushRun(void* bodies, int capacity, int sourceSlot,
                             const int* targetSlots, int count);

// Whole-level batch push: visits every candidate pair once, accumulating impulses in body
// rows. This is what the engine actually runs; see executePushRun above for why the two are
// not interchangeable. The reserved field packs (teamId<<2)|rule
// for team filtering; neighborCounts[i] receives the number of intersecting
// pushable partners (used for vanilla cramming).
//
// aabbs carries 6 doubles per slot (minX, minY, minZ, maxX, maxY, maxZ) exactly as
// read from Entity#getBoundingBox(). Pair admission uses those boxes, which is the
// same test vanilla runs when it filters the getEntities result, so the batch cannot
// diverge from the per-source path on edge-of-range pairs. The boxes double as the
// sweep key for spatial pair enumeration.
// partnerCap caps how many partners each entity keeps (0 or negative = unlimited). It is the
// experimental SPARSE mode's emulation of AcceleratedRecoiling's dropped pairs, where the cap
// is that mod's maxCollision. The cap is decided per entity, not per pair: a pair still
// applies to whichever side has room left, because both vanilla and AR build one candidate
// list per entity. With partnerCap <= 0 the behaviour is byte-for-byte the pre-cap one.
ECO_EXPORT int executeFullPushRun(void* bodies, int capacity, int* neighborCounts, const double* aabbs,
                                 int partnerCap);

// GPU push backend, split into the two ends the host cannot do cheaply itself.
//
// prepareGpuPush builds the sweep order with the very same constructor the CPU batch uses
// and flattens the frame into transfer-friendly arrays:
//   packed[i*12 + 0..4]  = x, z, vx, vy, vz
//   packed[i*12 + 5..10] = minX, minY, minZ, maxX, maxY, maxZ
//   meta[i*4 + 0..2]     = state, root, reserved
//   meta[i*4 + 3]        = position of slot i inside order[0..live), or -1 when the slot is
//                          not part of the sweep (NO_PHYSICS or a degenerate box)
// order[0..live) receives slot indices sorted exactly like the CPU sweep, so an OpenCL
// kernel that walks that order per entity reproduces the CPU accumulation sequence bit for
// bit. *outLive reports the sweep size; the caller passes it on to the kernel.
//
// range[i*2 + 0..1] receives the [lo, hi) slice of order[] that can possibly intersect slot
// i. The CPU scan prunes on the X axis; without the same prune a kernel has to walk every
// entity for every source, which measured slower than the CPU. The slice is derived from
// the same geometry (an intersecting box needs minX < i.maxX, and minX + 2*maxHalfWidth >
// i.minX), so it only skips pairs that could not intersect anyway.
ECO_EXPORT int prepareGpuPush(void* bodies, int capacity, const double* aabbs,
                             int* order, int* outLive, double* packed, int* meta, int* range,
                             int partnerCap);

// applyGpuPush writes a kernel result back into the body rows. outVel carries 3 doubles per
// slot (vx, vy, vz) and outAux 3 ints per slot (neighbor count, accepted push count,
// needsSync). The accepted push count is what the CPU increments velocityVersion by, and
// needsSync mirrors the flag push() sets before it rejects a non-finite sum. neighborCounts
// may be null only when capacity is 0.
ECO_EXPORT int applyGpuPush(void* bodies, int capacity, const double* outVel, const int* outAux,
                           int* neighborCounts);

// Differential check for the GPU backend: snapshots the body rows, runs the CPU batch on the
// very same rows as the reference, compares every field against the kernel result bit for bit
// (raw memcmp, so signed zeros and NaNs count as differences) and then restores the snapshot,
// leaving the frame showing the GPU result. *outDiff receives the number of mismatching fields;
// a clean run reports 0. Debug only — it costs a second full push per frame.
ECO_EXPORT int verifyGpuPush(void* bodies, int capacity, const double* aabbs, int* neighborCounts,
                            const double* outVel, const int* outAux, int* outDiff, int partnerCap);

ECO_EXPORT int solveMovement(const void* body, double* data, const void* shapes, int count, int phase);
ECO_EXPORT int prepareMovement(const double* bounds, double* data);

}
