#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

int32_t rz_runtime_on_load(void);
int32_t rz_runtime_pre_app(const char *process_name, int32_t uid);
int32_t rz_runtime_post_app(const char *process_name, int32_t uid, int32_t companion_fd);
void rz_companion_entry(int32_t client_fd);

#ifdef __cplusplus
}
#endif
