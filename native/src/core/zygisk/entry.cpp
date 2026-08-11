#include <sys/mount.h>
#include <android/dlext.h>
#include <dlfcn.h>
#include <poll.h>
#include <string_view>

#include <base.hpp>
#include <core.hpp>

#include "zygisk.hpp"

using namespace std;

using comp_entry = void(*)(int);
extern "C" void exec_companion_entry(int, comp_entry);

static void zygiskd(int socket) {
    if (getuid() != 0 || fcntl(socket, F_GETFD) < 0)
        exit(-1);

#if defined(__LP64__)
    set_nice_name("zygiskd64");
    LOGI("* Launching zygiskd64\n");
#else
    set_nice_name("zygiskd32");
    LOGI("* Launching zygiskd32\n");
#endif

    // Load modules
    vector<comp_entry> modules;
    {
        auto module_fds = recv_fds(socket);
        for (int fd : module_fds) {
            comp_entry entry = nullptr;
            struct stat s{};
            if (fstat(fd, &s) == 0 && S_ISREG(s.st_mode)) {
                android_dlextinfo info {
                    .flags = ANDROID_DLEXT_USE_LIBRARY_FD,
                    .library_fd = fd,
                };
                if (void *h = android_dlopen_ext("/jit-cache", RTLD_LAZY, &info)) {
                    *(void **) &entry = dlsym(h, "zygisk_companion_entry");
                } else {
                    LOGW("Failed to dlopen zygisk module: %s\n", dlerror());
                }
            }
            modules.push_back(entry);
            close(fd);
        }
    }

    // ack
    write_int(socket, 0);

    // Start accepting requests
    pollfd pfd = { socket, POLLIN, 0 };
    for (;;) {
        poll(&pfd, 1, -1);
        if (pfd.revents && !(pfd.revents & POLLIN)) {
            // Something bad happened in magiskd, terminate zygiskd
            exit(0);
        }
        int client = recv_fd(socket);
        if (client < 0) {
            // Something bad happened in magiskd, terminate zygiskd
            exit(0);
        }
        int module_id = read_int(client);
        if (module_id >= 0 && module_id < modules.size() && modules[module_id]) {
            exec_companion_entry(client, modules[module_id]);
        } else {
            close(client);
        }
    }
}

// Entrypoint where we need to re-exec ourselves
// This should only ever be called internally
int zygisk_main(int argc, char *argv[]) {
    android_logging();
    if (argc == 3 && argv[1] == "companion"sv) {
        zygiskd(parse_int(argv[2]));
    }
    return 0;
}

// Entrypoint of code injection
extern "C" [[maybe_unused]] NativeBridgeCallbacks NativeBridgeItf {
    .version = 2,
    .padding = {},
    .isCompatibleWith = [](auto) {
        zygisk_logging();
        hook_entry();
        ZLOGD("load success\n");

        // When a real native bridge is appended after our loader (e.g.
        // "libzygisk.solibhoudini.so"), keep the legacy behavior: return false so that
        // libnativebridge dlcloses us and our dlclose PLT hook chains into the real bridge.
        auto nb = get_prop(NBPROP);
        auto len = sizeof(ZYGISKLDR) - 1;
        // Only treat a longer property as "real bridge appended after our loader" when it
        // actually starts with our loader name; otherwise the reload path below would skip a
        // non-existent prefix and try to load a garbage path.
        if (nb.size() > len && std::string_view(nb.c_str(), nb.size()).starts_with(ZYGISKLDR))
            return false;

        // Otherwise report a successful load. libnativebridge then keeps the bridge in the
        // kOpened state without raising its internal error flag, so android::NativeBridgeError()
        // returns false in every process forked from zygote. This defeats detection heuristics
        // that probe NativeBridgeError to tell that Zygisk was injected through the native bridge.
        if (!hook_load_success()) {
            ZLOGE("native bridge success setup failed, falling back\n");
            return false;
        }
        return true;
    },
};
