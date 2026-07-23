/*
 * JNI bindings for com.scdataminifier.image.NativeImageCodec.
 * Used on desktop/server JVMs and Android (excluded from iOS builds).
 */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "scimage_codec.h"
#include "license.h"

/* License gate: the app calls this once with the .lic bytes + its own package name before
 * any encode/decode. Returns 0 (SC_LIC_OK) on success, negative on failure. */
JNIEXPORT jint JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nLicenseInit(JNIEnv* env, jclass cls,
                                                            jbyteArray lic, jstring pkg) {
    (void) cls;
    if (!lic || !pkg) return SC_LIC_ERR_MALFORMED;
    jsize len = (*env)->GetArrayLength(env, lic);
    jbyte* buf = (*env)->GetByteArrayElements(env, lic, NULL);
    const char* pkgStr = (*env)->GetStringUTFChars(env, pkg, NULL);
    int r = sc_license_init((const unsigned char*) buf, (size_t) len, pkgStr);
    (*env)->ReleaseStringUTFChars(env, pkg, pkgStr);
    (*env)->ReleaseByteArrayElements(env, lic, buf, JNI_ABORT);
    return r;
}

JNIEXPORT jboolean JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nLicenseOk(JNIEnv* env, jclass cls) {
    (void) env; (void) cls;
    return sc_license_ok() ? JNI_TRUE : JNI_FALSE;
}

static void throw_ex(JNIEnv* env, const char* msg) {
    jclass cls = (*env)->FindClass(env, "com/scdataminifier/ScDataException");
    if (cls == NULL) cls = (*env)->FindClass(env, "java/lang/RuntimeException");
    if (cls != NULL) (*env)->ThrowNew(env, cls, msg);
}

JNIEXPORT jstring JNICALL
Java_com_scdataminifier_image_NativeImageCodec_codecVersionA(JNIEnv* env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, scimg_webp_version());
}

JNIEXPORT jstring JNICALL
Java_com_scdataminifier_image_NativeImageCodec_codecVersionB(JNIEnv* env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, scimg_avif_version());
}

JNIEXPORT jstring JNICALL
Java_com_scdataminifier_image_NativeImageCodec_codecVersions(JNIEnv* env, jclass cls) {
    (void) cls;
    return (*env)->NewStringUTF(env, scimg_codec_versions());
}

JNIEXPORT jboolean JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nVersionsOk(JNIEnv* env, jclass cls) {
    (void) env; (void) cls;
    return scimg_versions_ok() ? JNI_TRUE : JNI_FALSE;
}

static jbyteArray to_jbytes(JNIEnv* env, const uint8_t* data, size_t size) {
    jbyteArray arr = (*env)->NewByteArray(env, (jsize) size);
    if (arr != NULL) {
        (*env)->SetByteArrayRegion(env, arr, 0, (jsize) size, (const jbyte*) data);
    }
    return arr;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nEncodeA(JNIEnv* env, jclass cls,
        jbyteArray rgb, jint width, jint height, jint quality) {
    jbyte* pixels;
    size_t outSize = 0;
    uint8_t* out;
    jbyteArray result = NULL;
    (void) cls;

    if (rgb == NULL || (*env)->GetArrayLength(env, rgb) < width * height * 3) {
        throw_ex(env, "RGB buffer too small");
        return NULL;
    }
    pixels = (*env)->GetByteArrayElements(env, rgb, NULL);
    out = scimg_encode_webp((const uint8_t*) pixels, width, height, quality, &outSize);
    (*env)->ReleaseByteArrayElements(env, rgb, pixels, JNI_ABORT);
    if (out == NULL) {
        throw_ex(env, scimg_last_error());
        return NULL;
    }
    result = to_jbytes(env, out, outSize);
    scimg_free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nEncodeATarget(JNIEnv* env, jclass cls,
        jbyteArray rgb, jint width, jint height, jint targetBytes) {
    jbyte* pixels;
    size_t outSize = 0;
    uint8_t* out;
    jbyteArray result = NULL;
    (void) cls;

    if (rgb == NULL || (*env)->GetArrayLength(env, rgb) < width * height * 3) {
        throw_ex(env, "RGB buffer too small");
        return NULL;
    }
    pixels = (*env)->GetByteArrayElements(env, rgb, NULL);
    out = scimg_encode_webp_target((const uint8_t*) pixels, width, height, targetBytes, &outSize);
    (*env)->ReleaseByteArrayElements(env, rgb, pixels, JNI_ABORT);
    if (out == NULL) {
        throw_ex(env, scimg_last_error());
        return NULL;
    }
    result = to_jbytes(env, out, outSize);
    scimg_free(out);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nEncodeB(JNIEnv* env, jclass cls,
        jbyteArray rgb, jint width, jint height, jint quality, jint speed) {
    jbyte* pixels;
    size_t outSize = 0;
    uint8_t* out;
    jbyteArray result = NULL;
    (void) cls;

    if (rgb == NULL || (*env)->GetArrayLength(env, rgb) < width * height * 3) {
        throw_ex(env, "RGB buffer too small");
        return NULL;
    }
    pixels = (*env)->GetByteArrayElements(env, rgb, NULL);
    out = scimg_encode_avif((const uint8_t*) pixels, width, height, quality, speed, &outSize);
    (*env)->ReleaseByteArrayElements(env, rgb, pixels, JNI_ABORT);
    if (out == NULL) {
        throw_ex(env, scimg_last_error());
        return NULL;
    }
    result = to_jbytes(env, out, outSize);
    scimg_free(out);
    return result;
}

static jbyteArray decode_common(JNIEnv* env, jbyteArray data, jintArray dims, int isWebp) {
    jbyte* bytes;
    jsize len;
    int w = 0, h = 0;
    uint8_t* rgb;
    jbyteArray result;
    jint dimsOut[2];

    if (data == NULL || dims == NULL || (*env)->GetArrayLength(env, dims) < 2) {
        throw_ex(env, "invalid decode arguments");
        return NULL;
    }
    len = (*env)->GetArrayLength(env, data);
    bytes = (*env)->GetByteArrayElements(env, data, NULL);
    rgb = isWebp
            ? scimg_decode_webp((const uint8_t*) bytes, (size_t) len, &w, &h)
            : scimg_decode_avif((const uint8_t*) bytes, (size_t) len, &w, &h);
    (*env)->ReleaseByteArrayElements(env, data, bytes, JNI_ABORT);
    if (rgb == NULL) {
        throw_ex(env, scimg_last_error());
        return NULL;
    }
    dimsOut[0] = w;
    dimsOut[1] = h;
    (*env)->SetIntArrayRegion(env, dims, 0, 2, dimsOut);
    result = to_jbytes(env, rgb, (size_t) w * h * 3);
    scimg_free(rgb);
    return result;
}

JNIEXPORT jbyteArray JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nDecodeA(JNIEnv* env, jclass cls,
        jbyteArray data, jintArray dims) {
    (void) cls;
    return decode_common(env, data, dims, 1);
}

JNIEXPORT jbyteArray JNICALL
Java_com_scdataminifier_image_NativeImageCodec_nDecodeB(JNIEnv* env, jclass cls,
        jbyteArray data, jintArray dims) {
    (void) cls;
    return decode_common(env, data, dims, 0);
}
