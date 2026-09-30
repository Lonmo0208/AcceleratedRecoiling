#pragma once
// OpenMP stub：与 Windows 构建一致（未开 /openmp 时 pragma 被忽略、串行执行）。
// 仅提供 AR 用到的 4 个 omp_* 入口。
#ifdef __cplusplus
extern "C" {
#endif
inline int omp_get_max_threads(void) { return 1; }
inline int omp_get_thread_num(void) { return 0; }
inline int omp_get_num_threads(void) { return 1; }
inline void omp_set_num_threads(int threads) { (void)threads; }
#ifdef __cplusplus
}
#endif
