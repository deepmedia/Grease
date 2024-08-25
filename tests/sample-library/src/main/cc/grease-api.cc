#include <android/log.h>

void grease_test() {
    __android_log_print(ANDROID_LOG_WARN, "Grease", "Test!");
}