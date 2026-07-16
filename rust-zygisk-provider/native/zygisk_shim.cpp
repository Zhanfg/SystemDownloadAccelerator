#include <stdint.h>
#include <string.h>
#include <unistd.h>

#include "rz_ffi.h"
#include "zygisk.hpp"

namespace {

constexpr int32_t kDecisionUnload = 0;
constexpr int32_t kDecisionKeepWithCompanion = 2;
constexpr size_t kProcessNameCapacity = 256;

class RustGuestModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
        (void)rz_runtime_on_load();
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        reset_process_state();
        if (api_ == nullptr || env_ == nullptr || args == nullptr || args->nice_name == nullptr) {
            request_unload();
            return;
        }

        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) {
            request_unload();
            return;
        }

        copy_process_name(name);
        const int32_t decision = rz_runtime_pre_app(process_name_, args->uid);
        env_->ReleaseStringUTFChars(args->nice_name, name);

        if (decision == kDecisionUnload) {
            request_unload();
            return;
        }

        keep_loaded_ = true;
        if (decision != kDecisionKeepWithCompanion) {
            return;
        }

        companion_fd_ = api_->connectCompanion();
        if (companion_fd_ < 0) {
            return;
        }

        if (!api_->exemptFd(companion_fd_)) {
            close(companion_fd_);
            companion_fd_ = -1;
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *args) override {
        if (!keep_loaded_ || args == nullptr) {
            close_companion();
            return;
        }

        const int fd = companion_fd_;
        companion_fd_ = -1;
        (void)rz_runtime_post_app(process_name_, args->uid, fd);
    }

private:
    void reset_process_state() {
        keep_loaded_ = false;
        process_name_[0] = '\0';
        close_companion();
    }

    void copy_process_name(const char *name) {
        strncpy(process_name_, name, kProcessNameCapacity - 1);
        process_name_[kProcessNameCapacity - 1] = '\0';
    }

    void request_unload() {
        keep_loaded_ = false;
        if (api_ != nullptr) {
            api_->setOption(zygisk::DLCLOSE_MODULE_LIBRARY);
        }
        close_companion();
    }

    void close_companion() {
        if (companion_fd_ >= 0) {
            close(companion_fd_);
            companion_fd_ = -1;
        }
    }

    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool keep_loaded_ = false;
    int companion_fd_ = -1;
    char process_name_[kProcessNameCapacity] = {};
};

void rust_companion_handler(int client_fd) {
    rz_companion_entry(client_fd);
}

}  // namespace

REGISTER_ZYGISK_MODULE(RustGuestModule)
REGISTER_ZYGISK_COMPANION(rust_companion_handler)
