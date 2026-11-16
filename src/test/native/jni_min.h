/*
 * The few JNI declarations the test library needs, for a 64-bit Windows target.
 *
 * The JDK's own jni.h is not used because the Linux JDK ships only the Linux machine header,
 * whose integer sizes are wrong for Windows. The function table below must stay in the order
 * of the JNI specification; only the slots this library calls are named, the rest are padding.
 */
#ifndef JNI_MIN_H
#define JNI_MIN_H

typedef int jint;
typedef long long jlong;
typedef signed char jbyte;
typedef unsigned char jboolean;
typedef int jsize;
typedef void *jobject;
typedef jobject jclass;
typedef jobject jstring;
typedef jobject jbyteArray;
typedef void *jmethodID;

typedef struct {
    const char *name;
    const char *signature;
    void *fnPtr;
} JNINativeMethod;

struct JNINativeInterface;
typedef const struct JNINativeInterface *JNIEnv;

struct JNINativeInterface {
    void *pad0[6];
    jclass (*FindClass)(JNIEnv *, const char *);                                  /*   6 */
    void *pad7[106];
    jmethodID (*GetStaticMethodID)(JNIEnv *, jclass, const char *, const char *); /* 113 */
    void *pad114[15];
    jint (*CallStaticIntMethod)(JNIEnv *, jclass, jmethodID, ...);                /* 129 */
    void *pad130[37];
    jstring (*NewStringUTF)(JNIEnv *, const char *);                              /* 167 */
    void *pad168[1];
    const char *(*GetStringUTFChars)(JNIEnv *, jstring, jboolean *);              /* 169 */
    void (*ReleaseStringUTFChars)(JNIEnv *, jstring, const char *);               /* 170 */
    jsize (*GetArrayLength)(JNIEnv *, jobject);                                   /* 171 */
    void *pad172[4];
    jbyteArray (*NewByteArray)(JNIEnv *, jsize);                                  /* 176 */
    void *pad177[7];
    jbyte *(*GetByteArrayElements)(JNIEnv *, jbyteArray, jboolean *);             /* 184 */
    void *pad185[7];
    void (*ReleaseByteArrayElements)(JNIEnv *, jbyteArray, jbyte *, jint);        /* 192 */
    void *pad193[15];
    void (*SetByteArrayRegion)(JNIEnv *, jbyteArray, jsize, jsize, const jbyte *);/* 208 */
    void *pad209[6];
    jint (*RegisterNatives)(JNIEnv *, jclass, const JNINativeMethod *, jint);     /* 215 */
};

struct JNIInvokeInterface;
typedef const struct JNIInvokeInterface *JavaVM;

struct JNIInvokeInterface {
    void *pad0[6];
    jint (*GetEnv)(JavaVM *, void **, jint);                                      /*   6 */
};

#define JNI_VERSION_1_8 0x00010008
#define JNIEXPORT __declspec(dllexport)

#endif
