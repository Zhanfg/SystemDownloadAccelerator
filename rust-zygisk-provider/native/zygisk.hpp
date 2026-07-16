/*
 * Compact form of the public Zygisk module API v4.
 * Original interface copyright 2022-2023 John Wu, ISC license.
 * No private Magisk implementation is included here.
 */
#pragma once

#include <jni.h>
#include <stdint.h>
#include <sys/types.h>

#define ZYGISK_API_VERSION 4

namespace zygisk {

struct Api;
struct AppSpecializeArgs;
struct ServerSpecializeArgs;

class ModuleBase {
public:
    virtual ~ModuleBase() = default;
    virtual void onLoad([[maybe_unused]] Api *api, [[maybe_unused]] JNIEnv *env) {}
    virtual void preAppSpecialize([[maybe_unused]] AppSpecializeArgs *args) {}
    virtual void postAppSpecialize([[maybe_unused]] const AppSpecializeArgs *args) {}
    virtual void preServerSpecialize([[maybe_unused]] ServerSpecializeArgs *args) {}
    virtual void postServerSpecialize([[maybe_unused]] const ServerSpecializeArgs *args) {}
};

struct AppSpecializeArgs {
    jint &uid;
    jint &gid;
    jintArray &gids;
    jint &runtime_flags;
    jobjectArray &rlimits;
    jint &mount_external;
    jstring &se_info;
    jstring &nice_name;
    jstring &instruction_set;
    jstring &app_data_dir;

    jintArray *const fds_to_ignore;
    jboolean *const is_child_zygote;
    jboolean *const is_top_app;
    jobjectArray *const pkg_data_info_list;
    jobjectArray *const whitelisted_data_info_list;
    jboolean *const mount_data_dirs;
    jboolean *const mount_storage_dirs;

    AppSpecializeArgs() = delete;
};

struct ServerSpecializeArgs {
    jint &uid;
    jint &gid;
    jintArray &gids;
    jint &runtime_flags;
    jlong &permitted_capabilities;
    jlong &effective_capabilities;

    ServerSpecializeArgs() = delete;
};

namespace internal {
struct api_table;
template <class T> void entry_impl(api_table *, JNIEnv *);
}

enum Option : int {
    FORCE_DENYLIST_UNMOUNT = 0,
    DLCLOSE_MODULE_LIBRARY = 1,
};

enum StateFlag : uint32_t {
    PROCESS_GRANTED_ROOT = (1u << 0),
    PROCESS_ON_DENYLIST = (1u << 1),
};

struct Api {
    int connectCompanion();
    int getModuleDir();
    void setOption(Option opt);
    uint32_t getFlags();
    bool exemptFd(int fd);
    void hookJniNativeMethods(
        JNIEnv *env,
        const char *class_name,
        JNINativeMethod *methods,
        int method_count);
    void pltHookRegister(
        dev_t device,
        ino_t inode,
        const char *symbol,
        void *replacement,
        void **original);
    bool pltHookCommit();

private:
    internal::api_table *tbl;
    template <class T> friend void internal::entry_impl(internal::api_table *, JNIEnv *);
};

namespace internal {

struct module_abi {
    long api_version;
    ModuleBase *impl;
    void (*preAppSpecialize)(ModuleBase *, AppSpecializeArgs *);
    void (*postAppSpecialize)(ModuleBase *, const AppSpecializeArgs *);
    void (*preServerSpecialize)(ModuleBase *, ServerSpecializeArgs *);
    void (*postServerSpecialize)(ModuleBase *, const ServerSpecializeArgs *);

    explicit module_abi(ModuleBase *module)
        : api_version(ZYGISK_API_VERSION), impl(module) {
        preAppSpecialize = [](auto instance, auto args) { instance->preAppSpecialize(args); };
        postAppSpecialize = [](auto instance, auto args) { instance->postAppSpecialize(args); };
        preServerSpecialize = [](auto instance, auto args) { instance->preServerSpecialize(args); };
        postServerSpecialize = [](auto instance, auto args) { instance->postServerSpecialize(args); };
    }
};

struct api_table {
    void *impl;
    bool (*registerModule)(api_table *, module_abi *);
    void (*hookJniNativeMethods)(JNIEnv *, const char *, JNINativeMethod *, int);
    void (*pltHookRegister)(dev_t, ino_t, const char *, void *, void **);
    bool (*exemptFd)(int);
    bool (*pltHookCommit)();
    int (*connectCompanion)(void *);
    void (*setOption)(void *, Option);
    int (*getModuleDir)(void *);
    uint32_t (*getFlags)(void *);
};

template <class T>
void entry_impl(api_table *table, JNIEnv *env) {
    static Api api;
    api.tbl = table;
    static T module;
    ModuleBase *base = &module;
    static module_abi abi(base);
    if (!table->registerModule(table, &abi)) {
        return;
    }
    base->onLoad(&api, env);
}

}  // namespace internal

inline int Api::connectCompanion() {
    return tbl->connectCompanion ? tbl->connectCompanion(tbl->impl) : -1;
}

inline int Api::getModuleDir() {
    return tbl->getModuleDir ? tbl->getModuleDir(tbl->impl) : -1;
}

inline void Api::setOption(Option opt) {
    if (tbl->setOption) {
        tbl->setOption(tbl->impl, opt);
    }
}

inline uint32_t Api::getFlags() {
    return tbl->getFlags ? tbl->getFlags(tbl->impl) : 0;
}

inline bool Api::exemptFd(int fd) {
    return tbl->exemptFd != nullptr && tbl->exemptFd(fd);
}

inline void Api::hookJniNativeMethods(
    JNIEnv *env,
    const char *class_name,
    JNINativeMethod *methods,
    int method_count) {
    if (tbl->hookJniNativeMethods) {
        tbl->hookJniNativeMethods(env, class_name, methods, method_count);
    }
}

inline void Api::pltHookRegister(
    dev_t device,
    ino_t inode,
    const char *symbol,
    void *replacement,
    void **original) {
    if (tbl->pltHookRegister) {
        tbl->pltHookRegister(device, inode, symbol, replacement, original);
    }
}

inline bool Api::pltHookCommit() {
    return tbl->pltHookCommit != nullptr && tbl->pltHookCommit();
}

}  // namespace zygisk

#define REGISTER_ZYGISK_MODULE(clazz)                                                \
    extern "C" __attribute__((visibility("default"))) void zygisk_module_entry(   \
        zygisk::internal::api_table *table, JNIEnv *env) {                          \
        zygisk::internal::entry_impl<clazz>(table, env);                            \
    }

#define REGISTER_ZYGISK_COMPANION(function)                                         \
    extern "C" __attribute__((visibility("default"))) void zygisk_companion_entry(\
        int client) {                                                                \
        function(client);                                                            \
    }
