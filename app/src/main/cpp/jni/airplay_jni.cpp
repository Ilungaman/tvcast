/*
 * JNI bridge between com.tvcast.receiver.airplay.AirPlayReceiver (Kotlin)
 * and UxPlay's protocol core (vendored under third_party/uxplay/lib).
 *
 * This mirrors, as closely as practical, the startup sequence UxPlay's own
 * uxplay.cpp uses in its start_dnssd() / start_raop_server() /
 * register_dnssd() (see that file for the reference implementation this is
 * adapted from) -- but targets our own raop_callbacks_t instead of
 * uxplay.cpp's GStreamer-based renderer.
 *
 * Phase 1 confirmed pairing + the RTSP/RTP session against a real iPhone.
 * Phase 2 (this file, now): video frames are forwarded to Kotlin's
 * AirPlayBridge, which feeds them into a MediaCodec decoder onto a Surface.
 * Audio is still not wired up.
 *
 * raop_callbacks_t fires on UxPlay's own internal worker threads, never on
 * a thread already attached to the JVM, so every callback that needs to
 * call back into Kotlin attaches via g_vm->AttachCurrentThread() first.
 */

#include <jni.h>
#include <android/log.h>
#include <cstring>
#include <cstdlib>
#include <vector>

#include "raop.h"
#include "dnssd.h"

#define LOG_TAG "TVCastAirPlay"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM *g_vm = nullptr;

/* Attaches the calling (native) thread to the JVM if it isn't already.
 * Callers must not detach -- UxPlay reuses a small pool of long-lived
 * worker threads, so we attach once per thread and let them stay attached
 * for the process lifetime rather than attach/detach on every callback. */
JNIEnv *attachCurrentThread() {
    JNIEnv *env = nullptr;
    if (g_vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
        return env;
    }
    if (g_vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
        return nullptr;
    }
    return env;
}

jclass g_bridgeClass = nullptr;
jmethodID g_onVideoFrame = nullptr;
jmethodID g_onMirrorStateChanged = nullptr;

/* Must be called from JNI_OnLoad (i.e. on the thread that called
 * System.loadLibrary), NOT lazily from a UxPlay worker thread attached via
 * AttachCurrentThread. FindClass resolves against the CALLER's classloader,
 * and a natively-attached thread has no app classloader in its context --
 * it only sees bootstrap/system classes, so FindClass("com/tvcast/...")
 * silently returns null there and every later callback into Kotlin is a
 * no-op. (This bit us: pairing worked, but the screen never switched
 * because onMirrorStateChanged was never actually reaching Kotlin.) */
void cacheBridge(JNIEnv *env) {
    jclass local = env->FindClass("com/tvcast/receiver/airplay/AirPlayBridge");
    if (!local) {
        LOGE("AirPlayBridge class not found");
        env->ExceptionClear();
        return;
    }
    g_bridgeClass = (jclass) env->NewGlobalRef(local);
    env->DeleteLocalRef(local);
    g_onVideoFrame = env->GetStaticMethodID(g_bridgeClass, "onVideoFrame", "([BZJ)V");
    g_onMirrorStateChanged = env->GetStaticMethodID(g_bridgeClass, "onMirrorStateChanged", "(Z)V");
    if (!g_onVideoFrame || !g_onMirrorStateChanged) {
        LOGE("AirPlayBridge methods not found");
        env->ExceptionClear();
    } else {
        LOGI("AirPlayBridge cached OK");
    }
}

raop_t *g_raop = nullptr;
dnssd_t *g_dnssd = nullptr;
unsigned short g_raop_port = 0;

void parse_hw_addr(const char *mac_str, std::vector<char> &out) {
    out.clear();
    const char *p = mac_str;
    while (*p) {
        out.push_back((char) strtol(p, nullptr, 16));
        p = strchr(p, ':');
        if (!p) break;
        p++;
    }
}

void log_callback(void *cls, int level, const char *msg) {
    (void) cls;
    if (level >= 2) {
        LOGE("%s", msg);
    } else {
        LOGI("%s", msg);
    }
}

/* ---- raop_callbacks_t ---- */

void cb_audio_process(void *cls, raop_ntp_t *ntp, audio_decode_struct *data) {
    // Not decoded yet -- only video is wired up to MediaCodec so far.
    (void) cls; (void) ntp;
    LOGI("audio frame: %d bytes, ct=%d", data->data_len, data->ct);
}

void cb_video_process(void *cls, raop_ntp_t *ntp, video_decode_struct *data) {
    (void) cls; (void) ntp;
    JNIEnv *env = attachCurrentThread();
    if (!env) return;
    if (!g_onVideoFrame) return;

    jbyteArray arr = env->NewByteArray(data->data_len);
    if (!arr) {
        env->ExceptionClear();
        return;
    }
    env->SetByteArrayRegion(arr, 0, data->data_len, reinterpret_cast<jbyte *>(data->data));
    env->CallStaticVoidMethod(g_bridgeClass, g_onVideoFrame, arr,
                               (jboolean) data->is_h265, (jlong) data->ntp_time_remote);
    if (env->ExceptionCheck()) env->ExceptionClear();
    env->DeleteLocalRef(arr);
}

void cb_video_pause(void *cls) { (void) cls; LOGI("video_pause"); }
void cb_video_resume(void *cls) { (void) cls; LOGI("video_resume"); }
void cb_conn_feedback(void *cls) { (void) cls; }
void cb_conn_reset(void *cls, int reason) { (void) cls; LOGI("conn_reset reason=%d", reason); }
void cb_video_reset(void *cls, reset_type_t reset_type) { (void) cls; LOGI("video_reset type=%d", (int) reset_type); }
void cb_conn_init(void *cls) { (void) cls; LOGI("conn_init (client connected)"); }
void cb_conn_destroy(void *cls) { (void) cls; LOGI("conn_destroy (client disconnected)"); }
void cb_audio_flush(void *cls) { (void) cls; }
void cb_video_flush(void *cls) { (void) cls; LOGI("video_flush"); }
double cb_audio_set_client_volume(void *cls) { (void) cls; return 1.0; }
void cb_audio_set_volume(void *cls, float volume) { (void) cls; (void) volume; }
void cb_audio_set_metadata(void *cls, const void *buffer, int buflen) { (void) cls; (void) buffer; (void) buflen; }
void cb_audio_set_coverart(void *cls, const void *buffer, int buflen) { (void) cls; (void) buffer; (void) buflen; }
void cb_audio_stop_coverart_rendering(void *cls) { (void) cls; }
void cb_audio_remote_control_id(void *cls, const char *dacp_id, const char *active_remote_header) {
    (void) cls; (void) dacp_id; (void) active_remote_header;
}
void cb_audio_set_progress(void *cls, uint32_t *start, uint32_t *curr, uint32_t *end) {
    (void) cls; (void) start; (void) curr; (void) end;
}
void cb_audio_get_format(void *cls, unsigned char *ct, unsigned short *spf, bool *usingScreen, bool *isMedia, uint64_t *audioFormat) {
    (void) cls; (void) ct; (void) spf; (void) usingScreen; (void) isMedia; (void) audioFormat;
}
void cb_video_report_size(void *cls, float *width_source, float *height_source, float *width, float *height) {
    (void) cls;
    LOGI("video_report_size source=%.0fx%.0f display=%.0fx%.0f", *width_source, *height_source, *width, *height);
}
void cb_mirror_video_running(void *cls, bool is_running) {
    (void) cls;
    LOGI("mirror_video_running=%d", is_running);
    JNIEnv *env = attachCurrentThread();
    if (!env) return;
    if (!g_onMirrorStateChanged) return;
    env->CallStaticVoidMethod(g_bridgeClass, g_onMirrorStateChanged, (jboolean) is_running);
    if (env->ExceptionCheck()) env->ExceptionClear();
}
void cb_report_client_request(void *cls, char *deviceid, char *model, char *name, bool *admit) {
    (void) cls;
    LOGI("client request: id=%s model=%s name=%s -> admitting", deviceid, model, name);
    *admit = true;
}
void cb_display_pin(void *cls, char *pin) { (void) cls; LOGI("display_pin: %s", pin); }
void cb_register_client(void *cls, const char *device_id, const char *pk_str, const char *name) {
    (void) cls; (void) device_id; (void) pk_str; (void) name;
}
bool cb_check_register(void *cls, const char *pk_str) { (void) cls; (void) pk_str; return false; }
const char *cb_passwd(void *cls, int *len) { (void) cls; *len = 0; return nullptr; }
void cb_export_dacp(void *cls, const char *active_remote, const char *dacp_id) {
    (void) cls; (void) active_remote; (void) dacp_id;
}
int cb_video_set_codec(void *cls, video_codec_t codec) { (void) cls; LOGI("video_set_codec %d", (int) codec); return 0; }
void cb_on_video_play(void *cls, const char *location, const float start_position) {
    (void) cls; (void) location; (void) start_position;
}
void cb_on_video_scrub(void *cls, const float position) { (void) cls; (void) position; }
void cb_on_video_rate(void *cls, const float rate) { (void) cls; (void) rate; }
void cb_on_video_stop(void *cls) { (void) cls; }
void cb_on_video_acquire_playback_info(void *cls, playback_info_t *playback_video) { (void) cls; (void) playback_video; }
float cb_on_video_playlist_remove(void *cls) { (void) cls; return 0.0f; }

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void * /* reserved */) {
    g_vm = vm;
    JNIEnv *env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK && env != nullptr) {
        cacheBridge(env);
    } else {
        LOGE("JNI_OnLoad: could not get JNIEnv");
    }
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_tvcast_receiver_airplay_AirPlayReceiver_nativeStart(
        JNIEnv *env, jobject /* this */,
        jstring jServerName, jstring jHwAddrStr, jstring jKeyfilePath) {

    if (g_raop != nullptr) {
        LOGE("nativeStart: already running");
        return JNI_FALSE;
    }

    const char *serverName = env->GetStringUTFChars(jServerName, nullptr);
    const char *hwAddrStr = env->GetStringUTFChars(jHwAddrStr, nullptr);
    const char *keyfilePath = env->GetStringUTFChars(jKeyfilePath, nullptr);

    std::vector<char> hw_addr;
    parse_hw_addr(hwAddrStr, hw_addr);

    int dnssd_error = 0;
    g_dnssd = dnssd_init(serverName, (int) strlen(serverName), hw_addr.data(), (int) hw_addr.size(),
                          /* pin_pw = */ 0, &dnssd_error);
    if (dnssd_error || g_dnssd == nullptr) {
        LOGE("dnssd_init failed: error %d", dnssd_error);
        env->ReleaseStringUTFChars(jServerName, serverName);
        env->ReleaseStringUTFChars(jHwAddrStr, hwAddrStr);
        env->ReleaseStringUTFChars(jKeyfilePath, keyfilePath);
        return JNI_FALSE;
    }

    dnssd_set_airplay_features(g_dnssd, 0, 0); // video-app (HLS) not supported
    dnssd_set_airplay_features(g_dnssd, 1, 1); // photo supported
    dnssd_set_airplay_features(g_dnssd, 2, 0); // no FairPlay DRM video
    dnssd_set_airplay_features(g_dnssd, 3, 0); // no remote volume control
    dnssd_set_airplay_features(g_dnssd, 4, 0); // no HLS
    dnssd_set_airplay_features(g_dnssd, 5, 1); // slideshow supported
    dnssd_set_airplay_features(g_dnssd, 7, 1); // mirroring supported

    raop_callbacks_t cbs;
    memset(&cbs, 0, sizeof(cbs));
    cbs.audio_process = cb_audio_process;
    cbs.video_process = cb_video_process;
    cbs.video_pause = cb_video_pause;
    cbs.video_resume = cb_video_resume;
    cbs.conn_feedback = cb_conn_feedback;
    cbs.conn_reset = cb_conn_reset;
    cbs.video_reset = cb_video_reset;
    cbs.conn_init = cb_conn_init;
    cbs.conn_destroy = cb_conn_destroy;
    cbs.audio_flush = cb_audio_flush;
    cbs.video_flush = cb_video_flush;
    cbs.audio_set_client_volume = cb_audio_set_client_volume;
    cbs.audio_set_volume = cb_audio_set_volume;
    cbs.audio_set_metadata = cb_audio_set_metadata;
    cbs.audio_set_coverart = cb_audio_set_coverart;
    cbs.audio_stop_coverart_rendering = cb_audio_stop_coverart_rendering;
    cbs.audio_remote_control_id = cb_audio_remote_control_id;
    cbs.audio_set_progress = cb_audio_set_progress;
    cbs.audio_get_format = cb_audio_get_format;
    cbs.video_report_size = cb_video_report_size;
    cbs.mirror_video_running = cb_mirror_video_running;
    cbs.report_client_request = cb_report_client_request;
    cbs.display_pin = cb_display_pin;
    cbs.register_client = cb_register_client;
    cbs.check_register = cb_check_register;
    cbs.passwd = cb_passwd;
    cbs.export_dacp = cb_export_dacp;
    cbs.video_set_codec = cb_video_set_codec;
    cbs.on_video_play = cb_on_video_play;
    cbs.on_video_scrub = cb_on_video_scrub;
    cbs.on_video_rate = cb_on_video_rate;
    cbs.on_video_stop = cb_on_video_stop;
    cbs.on_video_acquire_playback_info = cb_on_video_acquire_playback_info;
    cbs.on_video_playlist_remove = cb_on_video_playlist_remove;

    g_raop = raop_init(&cbs);
    if (g_raop == nullptr) {
        LOGE("raop_init failed");
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
        env->ReleaseStringUTFChars(jServerName, serverName);
        env->ReleaseStringUTFChars(jHwAddrStr, hwAddrStr);
        env->ReleaseStringUTFChars(jKeyfilePath, keyfilePath);
        return JNI_FALSE;
    }
    raop_set_log_callback(g_raop, log_callback, nullptr);
    raop_set_log_level(g_raop, 1);

    /* nohold = 1: a new client capturing mirroring takes over from a stale one */
    if (raop_init2(g_raop, /* nohold = */ 1, hwAddrStr, keyfilePath)) {
        LOGE("raop_init2 failed");
        raop_destroy(g_raop);
        g_raop = nullptr;
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
        env->ReleaseStringUTFChars(jServerName, serverName);
        env->ReleaseStringUTFChars(jHwAddrStr, hwAddrStr);
        env->ReleaseStringUTFChars(jKeyfilePath, keyfilePath);
        return JNI_FALSE;
    }

    unsigned short tcp[3] = {0, 0, 0};
    unsigned short udp[3] = {0, 0, 0};
    raop_set_tcp_ports(g_raop, tcp);
    raop_set_udp_ports(g_raop, udp);

    g_raop_port = raop_get_port(g_raop);
    raop_start_httpd(g_raop, &g_raop_port);
    raop_set_port(g_raop, g_raop_port);
    raop_set_dnssd(g_raop, g_dnssd);

    int err = dnssd_register_raop(g_dnssd, g_raop_port);
    if (err) {
        LOGE("dnssd_register_raop failed: error %d", err);
    }
    err = dnssd_register_airplay(g_dnssd, g_raop_port);
    if (err) {
        LOGE("dnssd_register_airplay failed: error %d", err);
    }

    LOGI("AirPlay receiver started: name=\"%s\" hw_addr=%s port=%u", serverName, hwAddrStr, g_raop_port);

    env->ReleaseStringUTFChars(jServerName, serverName);
    env->ReleaseStringUTFChars(jHwAddrStr, hwAddrStr);
    env->ReleaseStringUTFChars(jKeyfilePath, keyfilePath);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_com_tvcast_receiver_airplay_AirPlayReceiver_nativeStop(JNIEnv *env, jobject /* this */) {
    (void) env;
    if (g_dnssd) {
        dnssd_unregister_raop(g_dnssd);
        dnssd_unregister_airplay(g_dnssd);
    }
    if (g_raop) {
        raop_stop_httpd(g_raop);
        raop_destroy(g_raop);
        g_raop = nullptr;
    }
    if (g_dnssd) {
        dnssd_destroy(g_dnssd);
        g_dnssd = nullptr;
    }
    LOGI("AirPlay receiver stopped");
}
