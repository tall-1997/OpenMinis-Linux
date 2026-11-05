// [T-android-text-segmenter] JNI bridge to CppJieba for TextSegmenter's Chinese
// path. Loads the dictionary trie + HMM model once from a directory the Kotlin
// side extracted out of Assets (JNI can't read Assets by path), then serves
// exact-mode (`Cut`) and search-mode (`CutForSearch`) segmentation.
//
// We deliberately build DictTrie + HMMModel directly and share them between the
// segmenters instead of using the full cppjieba::Jieba facade — Jieba pulls in
// KeywordExtractor, which needs idf.utf8 + stop_words.utf8 (~6MB we don't
// ship). Segmentation only needs the dict + HMM model, so this keeps the
// shipped asset down to ~5.4MB.
//
// Thread-safety: dictionary LOADING is serialized by g_state_mutex (nativeInit
// is a one-shot ~5MB parse; two concurrent inits would double the work and the
// file cache in DictTrie is load-time only). Segmentation is lock-free: after
// init the JiebaState is immutable and shared via a const shared_ptr (the
// mutex is held only to copy that pointer), and each call builds its own
// MixSegment/QuerySegment over the shared dict — cppjieba's Cut paths are
// const and read-only over the trie/model (MixSegment::Cut/MPSegment::Cut/
// HMMSegment::InternalCut are const, DictTrie::Find is a const trie walk, and
// the HMM Viterbi works on a local weight vector reading const model fields),
// so concurrent calls share the dictionaries without a global mutex. All C++
// is wrapped in try/catch so a load failure or a malformed input returns
// false / an empty array to Kotlin rather than crashing the app.

#include <jni.h>
#include <android/log.h>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

#include "cppjieba/DictTrie.hpp"
#include "cppjieba/HMMModel.hpp"
#include "cppjieba/MixSegment.hpp"
#include "cppjieba/PosTagger.hpp"
#include "cppjieba/QuerySegment.hpp"

#define LOG_TAG "JiebaJNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// Owns the loaded dictionaries. Immutable after publication — segmenters are
// built per call over these shared, read-only objects.
struct JiebaState {
    std::unique_ptr<cppjieba::DictTrie> dict;
    std::unique_ptr<cppjieba::HMMModel> hmm;
};

// Guards the g_state POINTER (publication + snapshot); never held across a
// Cut. nativeInit stores a fully-built state under the lock, cut calls copy
// the shared_ptr out and then work without any lock.
std::mutex g_state_mutex;
std::shared_ptr<const JiebaState> g_state;

std::shared_ptr<const JiebaState> loadedState() {
    std::lock_guard<std::mutex> lock(g_state_mutex);
    return g_state;
}

// Join two path components with a single '/'.
std::string joinPath(const std::string& dir, const std::string& file) {
    if (dir.empty()) return file;
    if (dir.back() == '/') return dir + file;
    return dir + "/" + file;
}

// Convert a jstring to a std::string (UTF-8). Returns "" on null.
std::string toStdString(JNIEnv* env, jstring s) {
    if (s == nullptr) return std::string();
    const char* chars = env->GetStringUTFChars(s, nullptr);
    if (chars == nullptr) return std::string();
    std::string out(chars);
    env->ReleaseStringUTFChars(s, chars);
    return out;
}

// Build a Java String[] from a vector of UTF-8 words.
jobjectArray toStringArray(JNIEnv* env, const std::vector<std::string>& words) {
    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(words.size()), stringClass, nullptr);
    if (arr == nullptr) return nullptr;
    for (jsize i = 0; i < static_cast<jsize>(words.size()); ++i) {
        jstring w = env->NewStringUTF(words[i].c_str());
        env->SetObjectArrayElement(arr, i, w);
        env->DeleteLocalRef(w);
    }
    return arr;
}

// Empty String[0] — the failure / empty-input return value.
jobjectArray emptyStringArray(JNIEnv* env) {
    jclass stringClass = env->FindClass("java/lang/String");
    return env->NewObjectArray(0, stringClass, nullptr);
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeInit(
    JNIEnv* env, jobject /* thiz */, jstring dictDir) {
    // The ~5MB dictionary parse is the only serialized section: concurrent
    // inits must not double-parse, and publication must be ordered so a Cut
    // call never observes a half-built state.
    std::lock_guard<std::mutex> lock(g_state_mutex);
    if (g_state) return JNI_TRUE;  // already initialized

    const std::string dir = toStdString(env, dictDir);
    if (dir.empty()) {
        LOGE("nativeInit: empty dict dir");
        return JNI_FALSE;
    }

    try {
        auto state = std::make_shared<JiebaState>();
        const std::string dictPath = joinPath(dir, "jieba.dict.utf8");
        const std::string hmmPath = joinPath(dir, "hmm_model.utf8");

        state->dict = std::make_unique<cppjieba::DictTrie>(dictPath);
        state->hmm = std::make_unique<cppjieba::HMMModel>(hmmPath);
        // Segmenters are built per call over these (non-owning) pointers, so
        // concurrent Cuts need no lock — see the thread-safety note above.

        g_state = std::move(state);
        LOGI("nativeInit: loaded dictionaries from %s", dir.c_str());
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("nativeInit failed: %s", e.what());
        g_state.reset();
        return JNI_FALSE;
    } catch (...) {
        LOGE("nativeInit failed: unknown error");
        g_state.reset();
        return JNI_FALSE;
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeSegment(
    JNIEnv* env, jobject /* thiz */, jstring text) {
    const std::shared_ptr<const JiebaState> state = loadedState();
    if (!state) {
        LOGE("nativeSegment before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::string> words;
        // Exact mode with HMM enabled (recovers OOV words). Per-call segmenter
        // over the shared immutable dict — cheap (two pointer stores) and
        // lock-free.
        cppjieba::MixSegment seg(state->dict.get(), state->hmm.get());
        seg.Cut(sentence, words, /* hmm = */ true);
        return toStringArray(env, words);
    } catch (const std::exception& e) {
        LOGE("nativeSegment failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativeSegment failed: unknown error");
        return emptyStringArray(env);
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativeSegmentForSearch(
    JNIEnv* env, jobject /* thiz */, jstring text) {
    const std::shared_ptr<const JiebaState> state = loadedState();
    if (!state) {
        LOGE("nativeSegmentForSearch before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::string> words;
        cppjieba::QuerySegment seg(state->dict.get(), state->hmm.get());
        seg.Cut(sentence, words, /* hmm = */ true);
        return toStringArray(env, words);
    } catch (const std::exception& e) {
        LOGE("nativeSegmentForSearch failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativeSegmentForSearch failed: unknown error");
        return emptyStringArray(env);
    }
}

// [T-android-voice-correction] Part-of-speech tagging, needed by
// VocabularyFilter's admission scoring (proper-noun tags nr/ns/nz/nt earn +2).
//
// Returns a FLAT String[] of alternating token, tag, token, tag, … rather than
// an array of pairs: building a Pair[] here would mean FindClass +
// GetMethodID + NewObject per token across the JNI boundary, where the flat
// form is one NewStringUTF per element and a trivial pairwise walk in Kotlin.
//
// PosTagger is stateless and cheap to construct (its ctor is empty; it borrows
// the DictTrie from the SegmentTagged we hand it), so unlike KeywordExtractor
// it needs no extra dictionary files and is built per call rather than kept in
// JiebaState.
JNIEXPORT jobjectArray JNICALL
Java_com_openminis_app_shared_JiebaEngine_nativePosTag(
    JNIEnv* env, jobject /* thiz */, jstring text) {
    const std::shared_ptr<const JiebaState> state = loadedState();
    if (!state) {
        LOGE("nativePosTag before init");
        return emptyStringArray(env);
    }
    try {
        const std::string sentence = toStdString(env, text);
        std::vector<std::pair<std::string, std::string> > tagged;
        cppjieba::PosTagger tagger;
        // MixSegment is a SegmentTagged, so it supplies both the cut and the
        // DictTrie the tag lookup needs. Built per call over the shared
        // immutable dict (same lock-free rationale as nativeSegment).
        cppjieba::MixSegment seg(state->dict.get(), state->hmm.get());
        tagger.Tag(sentence, tagged, seg);
        std::vector<std::string> flat;
        flat.reserve(tagged.size() * 2);
        for (size_t i = 0; i < tagged.size(); ++i) {
            flat.push_back(tagged[i].first);
            flat.push_back(tagged[i].second);
        }
        return toStringArray(env, flat);
    } catch (const std::exception& e) {
        LOGE("nativePosTag failed: %s", e.what());
        return emptyStringArray(env);
    } catch (...) {
        LOGE("nativePosTag failed: unknown error");
        return emptyStringArray(env);
    }
}

}  // extern "C"
