#include <jni.h>
#include <unistd.h>

#include <string>

#include "zygisk.hpp"

extern "C" {
struct SdaRuntimeInitArgs {
    const char *process_name;
    int daemon_fd;
};

enum SdaRuntimeStatus : int {
    SDA_RUNTIME_OK = 0,
    SDA_RUNTIME_INVALID_ARGUMENT = 1,
    SDA_RUNTIME_WRONG_PROCESS = 2,
    SDA_RUNTIME_ALREADY_INITIALIZED = 3,
    SDA_RUNTIME_INTERNAL_ERROR = 255,
};

SdaRuntimeStatus sda_runtime_init(const SdaRuntimeInitArgs *args);
void sda_companion_handle(int client_fd);
}

namespace {
constexpr const char *kTargetProcess = "com.android.providers.downloads";

class SdaZygiskModule final : public zygisk::ModuleBase {
public:
    void onLoad(zygisk::Api *api, JNIEnv *env) override {
        api_ = api;
        env_ = env;
    }

    void preAppSpecialize(zygisk::AppSpecializeArgs *args) override {
        target_ = false;
        process_name_.clear();

        if (args == nullptr || args->nice_name == nullptr) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        const char *name = env_->GetStringUTFChars(args->nice_name, nullptr);
        if (name == nullptr) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        process_name_ = name;
        env_->ReleaseStringUTFChars(args->nice_name, name);
        target_ = process_name_ == kTargetProcess;

        if (!target_) {
            api_->setOption(zygisk::Option::DLCLOSE_MODULE_LIBRARY);
            return;
        }

        bootstrap_fd_ = api_->connectCompanion();
        if (bootstrap_fd_ >= 0 && !api_->exemptFd(bootstrap_fd_)) {
            close(bootstrap_fd_);
            bootstrap_fd_ = -1;
        }
    }

    void postAppSpecialize(const zygisk::AppSpecializeArgs *) override {
        if (!target_ || bootstrap_fd_ < 0) {
            return;
        }

        const SdaRuntimeInitArgs args{
            .process_name = process_name_.c_str(),
            .daemon_fd = bootstrap_fd_,
        };
        const auto status = sda_runtime_init(&args);
        if (status != SDA_RUNTIME_OK && status != SDA_RUNTIME_ALREADY_INITIALIZED) {
            close(bootstrap_fd_);
            bootstrap_fd_ = -1;
        }
    }

private:
    zygisk::Api *api_ = nullptr;
    JNIEnv *env_ = nullptr;
    bool target_ = false;
    int bootstrap_fd_ = -1;
    std::string process_name_;
};

void companion_entry(int client_fd) {
    sda_companion_handle(client_fd);
}
}  // namespace

REGISTER_ZYGISK_MODULE(SdaZygiskModule)
REGISTER_ZYGISK_COMPANION(companion_entry)
