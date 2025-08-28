#ifndef BITMAP_UTILS_H
#define BITMAP_UTILS_H

#include <jni.h>
#include <android/bitmap.h>

// 前向声明
struct ImageInfo;

class BitmapUtils {
public:
    static bool lockBitmap(JNIEnv *env, jobject bitmap, AndroidBitmapInfo *info, void **pixels);

    static void unlockBitmap(JNIEnv *env, jobject bitmap);

    static bool validateBitmap(JNIEnv *env, jobject bitmap);

    static bool getBitmapInfo(JNIEnv *env, jobject bitmap, AndroidBitmapInfo *info);

    static bool getBitmapInfo(JNIEnv *env, jobject bitmap, ImageInfo &info);
};

#include "../include/native_lut_processor.h"

#endif // BITMAP_UTILS_H