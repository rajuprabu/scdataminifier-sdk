/*
 * JNI bindings for com.scdataminifier.decoder.ScDecoder (Android). Compiled only when
 * SCDEC_JNI is defined (Android build); the iOS build omits it and calls the C API directly.
 * Method names are format-neutral — no codec is named on either side of the boundary.
 */
#include <jni.h>
#include <stdlib.h>
#include "scdec.h"

JNIEXPORT jint JNICALL
Java_com_scdataminifier_decoder_ScDecoder_nLicense(JNIEnv *env, jclass cls, jbyteArray lic, jstring pkg) {
    (void) cls;
    if (!lic || !pkg) return SCDEC_LIC_MALFORMED;
    jsize len = (*env)->GetArrayLength(env, lic);
    jbyte *buf = (*env)->GetByteArrayElements(env, lic, NULL);
    const char *p = (*env)->GetStringUTFChars(env, pkg, NULL);
    int r = scdec_license((const uint8_t *) buf, (int) len, p);
    (*env)->ReleaseStringUTFChars(env, pkg, p);
    (*env)->ReleaseByteArrayElements(env, lic, buf, JNI_ABORT);
    return r;
}

JNIEXPORT jboolean JNICALL
Java_com_scdataminifier_decoder_ScDecoder_nLicensed(JNIEnv *env, jclass cls) {
    (void) env; (void) cls;
    return scdec_licensed() ? JNI_TRUE : JNI_FALSE;
}

/* Returns int[]{width,height} or null on failure (dims only, no pixel decode). */
JNIEXPORT jintArray JNICALL
Java_com_scdataminifier_decoder_ScDecoder_nInfo(JNIEnv *env, jclass cls, jbyteArray value) {
    (void) cls;
    if (!value) return NULL;
    jsize len = (*env)->GetArrayLength(env, value);
    jbyte *buf = (*env)->GetByteArrayElements(env, value, NULL);
    int w = 0, h = 0;
    int r = scdec_info((const uint8_t *) buf, (int) len, &w, &h);
    (*env)->ReleaseByteArrayElements(env, value, buf, JNI_ABORT);
    if (r != 0) return NULL;
    jintArray out = (*env)->NewIntArray(env, 2);
    if (out) { jint d[2] = { w, h }; (*env)->SetIntArrayRegion(env, out, 0, 2, d); }
    return out;
}

/*
 * Decodes an IMAGE value to RGB. Returns a byte[] laid out as
 * [w hi][w lo][h hi][h lo][ r,g,b ... ] — the 4-byte size prefix lets the caller build the
 * bitmap without a second call. Null on failure.
 */
JNIEXPORT jbyteArray JNICALL
Java_com_scdataminifier_decoder_ScDecoder_nOpen(JNIEnv *env, jclass cls, jbyteArray value) {
    (void) cls;
    if (!value) return NULL;
    jsize len = (*env)->GetArrayLength(env, value);
    jbyte *buf = (*env)->GetByteArrayElements(env, value, NULL);
    int w = 0, h = 0;
    uint8_t *rgb = scdec_open((const uint8_t *) buf, (int) len, &w, &h);
    (*env)->ReleaseByteArrayElements(env, value, buf, JNI_ABORT);
    if (!rgb) return NULL;

    jsize total = 4 + (jsize) w * h * 3;
    jbyteArray out = (*env)->NewByteArray(env, total);
    if (out) {
        jbyte hdr[4] = { (jbyte) (w >> 8), (jbyte) w, (jbyte) (h >> 8), (jbyte) h };
        (*env)->SetByteArrayRegion(env, out, 0, 4, hdr);
        (*env)->SetByteArrayRegion(env, out, 4, total - 4, (const jbyte *) rgb);
    }
    scdec_free(rgb);
    return out;
}
