#include "pty.hpp"
#include "extract.hpp"
#include <jni.h>
#include <cerrno>
#include <exception>
#include <fcntl.h>
#include <string>
#include <stdexcept>
#include <unistd.h>

namespace {
std::atomic_bool extraction_cancelled{false};
std::string string(JNIEnv* env, jstring value) {
    if (!value) throw std::invalid_argument("Missing string");
    const char* bytes = env->GetStringUTFChars(value, nullptr);
    if (!bytes) throw std::runtime_error("Cannot access Java string");
    std::string out(bytes);
    env->ReleaseStringUTFChars(value, bytes);
    return out;
}
std::vector<std::string> strings(JNIEnv* env, jobjectArray array) {
    if (!array) throw std::invalid_argument("Missing arguments");
    std::vector<std::string> out;
    for (int i = 0; i < env->GetArrayLength(array); ++i) {
        auto item = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        out.push_back(string(env, item));
        env->DeleteLocalRef(item);
    }
    return out;
}
void report(JNIEnv* env, const std::exception& error) {
    if (!env->ExceptionCheck()) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), error.what());
}
}
extern "C" JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_createSubprocess(
    JNIEnv* env, jclass, jstring executable, jstring cwd, jobjectArray argv, jobjectArray vars,
    jintArray pid_array, jint rows, jint columns, jint width, jint height) {
    try {
        if (!pid_array || env->GetArrayLength(pid_array) != 1) throw std::invalid_argument("Missing process id output");
        auto process = archterminal::spawn(string(env, executable), string(env, cwd), strings(env, argv), strings(env, vars), rows, columns, width, height);
        jint pid = process.pid;
        env->SetIntArrayRegion(pid_array, 0, 1, &pid);
        return process.master;
    } catch (const std::exception& error) { report(env, error); return -1; }
}
extern "C" JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_setPtyWindowSize(
    JNIEnv* env, jclass, jint fd, jint rows, jint columns, jint width, jint height) {
    try { archterminal::resize(fd, rows, columns, width, height); }
    catch (const std::exception& error) { report(env, error); }
}
extern "C" JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_waitFor(JNIEnv* env, jclass, jint pid) {
    try { return archterminal::wait(pid); } catch (const std::exception& error) { report(env, error); return -1; }
}
extern "C" JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_close(JNIEnv*, jclass, jint fd) { ::close(fd); }
extern "C" JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_duplicate(JNIEnv* env, jclass, jint fd) {
    int duplicate = fcntl(fd, F_DUPFD_CLOEXEC, 3);
    if (duplicate < 0) env->ThrowNew(env->FindClass("java/lang/IllegalStateException"), "Cannot duplicate PTY descriptor");
    return duplicate;
}
extern "C" JNIEXPORT jint JNICALL Java_com_termux_terminal_JNI_read(JNIEnv* env, jclass, jint fd, jbyteArray array) {
    jbyte buffer[16384];
    int length = std::min(static_cast<int>(sizeof(buffer)), env->GetArrayLength(array));
    ssize_t result;
    do { result = ::read(fd, buffer, length); } while (result < 0 && errno == EINTR);
    if (result <= 0) return -1; // Linux PTYs report EIO when the final slave closes.
    env->SetByteArrayRegion(array, 0, static_cast<jsize>(result), buffer);
    return static_cast<jint>(result);
}
extern "C" JNIEXPORT void JNICALL Java_com_termux_terminal_JNI_write(JNIEnv* env, jclass, jint fd, jbyteArray array, jint count) {
    if (count < 0 || count > env->GetArrayLength(array)) { env->ThrowNew(env->FindClass("java/lang/IllegalArgumentException"), "Invalid write count"); return; }
    std::vector<jbyte> buffer(count);
    env->GetByteArrayRegion(array, 0, count, buffer.data());
    size_t offset = 0;
    while (offset < buffer.size()) {
        ssize_t result = ::write(fd, buffer.data() + offset, buffer.size() - offset);
        if (result < 0 && errno == EINTR) continue;
        if (result <= 0) { env->ThrowNew(env->FindClass("java/io/IOException"), "PTY write failed"); return; }
        offset += static_cast<size_t>(result);
    }
}
extern "C" JNIEXPORT void JNICALL Java_de_archterminal_Native_extract(JNIEnv* env, jclass, jstring archive, jstring destination) {
    try { archterminal::extract(string(env, archive), string(env, destination), &extraction_cancelled); }
    catch (const std::exception& error) { report(env, error); }
}
extern "C" JNIEXPORT void JNICALL Java_de_archterminal_Native_repairDirectoryModes(JNIEnv* env, jclass, jstring rootfs) {
    try { archterminal::repair_directory_modes(string(env, rootfs)); }
    catch (const std::exception& error) { report(env, error); }
}
extern "C" JNIEXPORT void JNICALL Java_de_archterminal_Native_resetExtractionCancellation(JNIEnv*, jclass) {
    extraction_cancelled.store(false, std::memory_order_relaxed);
}
extern "C" JNIEXPORT void JNICALL Java_de_archterminal_Native_cancelExtraction(JNIEnv*, jclass) {
    extraction_cancelled.store(true, std::memory_order_relaxed);
}
