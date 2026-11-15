#include <jni.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>

/* statx reports actual birth time explicitly. ctime and mtime are never substitutes. */
static jlong millis(struct statx_timestamp time) {
    if (time.tv_sec <= 0 || time.tv_sec > (INT64_MAX - 999) / 1000 || time.tv_nsec >= 1000000000U) return 0;
    return time.tv_sec * 1000 + time.tv_nsec / 1000000;
}

static jlongArray read_times(JNIEnv *env, int fd, const char *path, int flags) {
    struct statx value = {0};
    if (statx(fd, path, flags, STATX_BTIME | STATX_MTIME | STATX_TYPE, &value) != 0) return NULL;
    /* Pipes, generated streams and special nodes do not describe the selected original. */
    if (!(value.stx_mask & STATX_TYPE) || (!S_ISREG(value.stx_mode) && !S_ISDIR(value.stx_mode))) return NULL;
    jlong result[2] = {
        (value.stx_mask & STATX_BTIME) ? millis(value.stx_btime) : 0,
        (value.stx_mask & STATX_MTIME) ? millis(value.stx_mtime) : 0
    };
    jlongArray output = (*env)->NewLongArray(env, 2);
    if (output != NULL) (*env)->SetLongArrayRegion(env, output, 0, 2, result);
    return output;
}

JNIEXPORT jlongArray JNICALL Java_dev_abdus_apps_shimmer_p1_OriginalFileTimes_nativeFd(
        JNIEnv *env, jobject owner, jint fd) {
    (void)owner;
    return read_times(env, fd, "", AT_EMPTY_PATH);
}

JNIEXPORT jlongArray JNICALL Java_dev_abdus_apps_shimmer_p1_OriginalFileTimes_nativePath(
        JNIEnv *env, jobject owner, jbyteArray bytes) {
    (void)owner;
    jsize length = (*env)->GetArrayLength(env, bytes);
    if (length <= 0 || length > 32768) return NULL;
    char *path = malloc((size_t)length + 1);
    if (path == NULL) return NULL;
    (*env)->GetByteArrayRegion(env, bytes, 0, length, (jbyte *)path);
    if ((*env)->ExceptionCheck(env)) { free(path); return NULL; }
    path[length] = '\0';
    jlongArray output = memchr(path, '\0', (size_t)length) == NULL
        ? read_times(env, AT_FDCWD, path, AT_SYMLINK_NOFOLLOW) : NULL;
    free(path);
    return output;
}
