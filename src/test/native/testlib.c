/*
 * Test library for the native bridge. It is built as a 64-bit Windows DLL and is only ever
 * run inside the CPU emulator; nothing in the test suite loads it into a real process.
 *
 * Each function exercises one part of the bridge:
 *   add         plain arguments and result, bound by exported name
 *   greet       reading and creating Java strings
 *   viaJava     calling back into a Java method
 *   xorBytes    reading and creating byte arrays
 *   triple      bound through RegisterNatives in JNI_OnLoad, not by name
 *   dropMarker  Windows API calls, which the emulator records
 *   scale       a double and a float in, a double out: all through XMM registers
 *   unbox       reads the value field of each box type, as JNA's dispatcher does for boxed
 *               arguments (ten arguments, so most travel on the stack)
 *   jnaInvoke   JNA's shape, (Function, long, int, Object[]): with JNIEnv and the class that is
 *               six arguments, so the last two travel on the stack, as JNA's dispatcher reads them
 *
 * Build:  x86_64-w64-mingw32-gcc -O1 -shared -nostdlib -e DllMain -o testlib.dll testlib.c -lkernel32
 */
#include "jni_min.h"

/* No C runtime is linked, so the two string helpers are local. */
static int length_of(const char *s) {
    int n = 0;
    while (s[n]) {
        n++;
    }
    return n;
}

static void copy(char *to, const char *from, int n) {
    for (int i = 0; i < n; i++) {
        to[i] = from[i];
    }
}

int __stdcall DllMain(void *instance, unsigned long reason, void *reserved) {
    return 1;
}

JNIEXPORT jint Java_dev_javabyte_guest_NativeLib_add(JNIEnv *env, jclass cls, jint a, jint b) {
    return a + b;
}

JNIEXPORT jstring Java_dev_javabyte_guest_NativeLib_greet(JNIEnv *env, jclass cls, jstring name) {
    char buffer[128] = "hello, ";
    const char *chars = (*env)->GetStringUTFChars(env, name, 0);
    int n = length_of(chars);
    if (n > 100) {
        n = 100;
    }
    copy(buffer + 7, chars, n);
    buffer[7 + n] = 0;
    (*env)->ReleaseStringUTFChars(env, name, chars);
    return (*env)->NewStringUTF(env, buffer);
}

JNIEXPORT jint Java_dev_javabyte_guest_NativeLib_viaJava(JNIEnv *env, jclass cls, jint x) {
    jclass target = (*env)->FindClass(env, "dev/javabyte/guest/NativeLib");
    jmethodID twice = (*env)->GetStaticMethodID(env, target, "twice", "(I)I");
    return (*env)->CallStaticIntMethod(env, target, twice, x) + 1;
}

JNIEXPORT jbyteArray Java_dev_javabyte_guest_NativeLib_xorBytes(JNIEnv *env, jclass cls, jbyteArray data, jbyte key) {
    jsize n = (*env)->GetArrayLength(env, data);
    jbyte *bytes = (*env)->GetByteArrayElements(env, data, 0);
    jbyte out[64];
    if (n > 64) {
        n = 64;
    }
    for (int i = 0; i < n; i++) {
        out[i] = bytes[i] ^ key;
    }
    (*env)->ReleaseByteArrayElements(env, data, bytes, 2 /* JNI_ABORT: nothing to copy back */);
    jbyteArray result = (*env)->NewByteArray(env, n);
    (*env)->SetByteArrayRegion(env, result, 0, n, out);
    return result;
}

static jlong triple(JNIEnv *env, jclass cls, jlong value) {
    return value * 3;
}

__declspec(dllimport) void *__stdcall CreateFileA(const char *, unsigned long, unsigned long, void *,
                                                  unsigned long, unsigned long, void *);
__declspec(dllimport) int __stdcall WriteFile(void *, const void *, unsigned long, unsigned long *, void *);
__declspec(dllimport) int __stdcall CloseHandle(void *);

JNIEXPORT jint Java_dev_javabyte_guest_NativeLib_dropMarker(JNIEnv *env, jclass cls) {
    unsigned long written = 0;
    void *file = CreateFileA("C:\\Users\\Public\\native-marker.txt", 0x40000000 /* GENERIC_WRITE */, 0, 0,
                             2 /* CREATE_ALWAYS */, 0x80, 0);
    WriteFile(file, "from native", 11, &written, 0);
    CloseHandle(file);
    return (jint) written;
}

/* Same parameters as JNA's Native.invokeInt; returns 1000 * args.length + flags + fp. */
JNIEXPORT jint Java_dev_javabyte_guest_NativeLib_jnaInvoke(JNIEnv *env, jclass cls, jobject function, jlong fp,
                                                           jint flags, jobject args) {
    return (*env)->GetArrayLength(env, args) * 1000 + flags + (jint) fp;
}

/* Windows x64 passes x in XMM2 and y in XMM3 (their argument positions) and returns in XMM0. */
JNIEXPORT jdouble Java_dev_javabyte_guest_NativeLib_scale(JNIEnv *env, jclass cls, jdouble x, jfloat y) {
    return x * y;
}

/* The value field of one box, found the way JNA's dispatcher finds it. */
static jfieldID value_field(JNIEnv *env, const char *box, const char *type) {
    return (*env)->GetFieldID(env, (*env)->FindClass(env, box), "value", type);
}

/* Unboxes one of each box type and adds them up: 1 + 2 + 65 + 300 + 40000 + 5000000000 + 7 + 250. */
JNIEXPORT jlong Java_dev_javabyte_guest_NativeLib_unbox(JNIEnv *env, jclass cls, jobject z, jobject b, jobject c,
                                                        jobject s, jobject i, jobject j, jobject f, jobject d) {
    jlong sum = (*env)->GetBooleanField(env, z, value_field(env, "java/lang/Boolean", "Z")) ? 1 : 0;
    sum += (*env)->GetByteField(env, b, value_field(env, "java/lang/Byte", "B"));
    sum += (*env)->GetCharField(env, c, value_field(env, "java/lang/Character", "C"));
    sum += (*env)->GetShortField(env, s, value_field(env, "java/lang/Short", "S"));
    sum += (*env)->GetIntField(env, i, value_field(env, "java/lang/Integer", "I"));
    sum += (*env)->GetLongField(env, j, value_field(env, "java/lang/Long", "J"));
    sum += (jlong) (*env)->GetFloatField(env, f, value_field(env, "java/lang/Float", "F"));
    sum += (jlong) (*env)->GetDoubleField(env, d, value_field(env, "java/lang/Double", "D"));
    return sum;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *vm, void *reserved) {
    JNIEnv *env = 0;
    static const JNINativeMethod methods[] = {{"triple", "(J)J", (void *) triple}};
    if ((*vm)->GetEnv(vm, (void **) &env, JNI_VERSION_1_8) != 0) {
        return -1;
    }
    (*env)->RegisterNatives(env, (*env)->FindClass(env, "dev/javabyte/guest/NativeLib"), methods, 1);
    return JNI_VERSION_1_8;
}
