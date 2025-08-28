#include "bitmap_utils.h"
#include "../include/native_lut_processor.h"

bool BitmapUtils::lockBitmap(JNIEnv *env, jobject bitmap, AndroidBitmapInfo *info, void **pixels) {
    if (!bitmap) {
        LOGE("Bitmap is null");
        return false;
    }

    int result = AndroidBitmap_getInfo(env, bitmap, info);
    if (result < 0) {
        LOGE("AndroidBitmap_getInfo() failed, error=%d", result);
        return false;
    }

    if (info->format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
        LOGE("Bitmap format is not RGBA_8888");
        return false;
    }

    result = AndroidBitmap_lockPixels(env, bitmap, pixels);
    if (result < 0) {
        LOGE("AndroidBitmap_lockPixels() failed, error=%d", result);
        return false;
    }

    return true;
}

void BitmapUtils::unlockBitmap(JNIEnv *env, jobject bitmap) {
    AndroidBitmap_unlockPixels(env, bitmap);
}

bool BitmapUtils::validateBitmap(JNIEnv *env, jobject bitmap) {
    if (!bitmap) {
        return false;
    }

    AndroidBitmapInfo info;
    int result = AndroidBitmap_getInfo(env, bitmap, &info);
    return result >= 0;
}

bool BitmapUtils::getBitmapInfo(JNIEnv *env, jobject bitmap, AndroidBitmapInfo *info) {
    if (!bitmap || !info) {
        return false;
    }

    int result = AndroidBitmap_getInfo(env, bitmap, info);
    if (result < 0) {
        LOGE("AndroidBitmap_getInfo() failed, error=%d", result);
        return false;
    }

    return true;
}

bool BitmapUtils::getBitmapInfo(JNIEnv *env, jobject bitmap, ImageInfo &info) {
    if (!bitmap) {
        return false;
    }

    AndroidBitmapInfo androidInfo;
    int result = AndroidBitmap_getInfo(env, bitmap, &androidInfo);
    if (result < 0) {
        LOGE("AndroidBitmap_getInfo() failed, error=%d", result);
        return false;
    }

    // 转换到ImageInfo结构
    info.width = androidInfo.width;
    info.height = androidInfo.height;
    info.stride = androidInfo.stride;
    info.format = static_cast<AndroidBitmapFormat>(androidInfo.format);
    info.pixelSize = androidInfo.width * androidInfo.height * 4; // 假设RGBA格式

    return true;
}