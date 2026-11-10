#define DISCORDPP_IMPLEMENTATION
#include <discordpp.h>

#include <jni.h>
#include <android/log.h>

#include <atomic>
#include <chrono>
#include <cstdint>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <utility>

namespace {
constexpr char kLogTag[] = "GeckoPresenceNative";

std::mutex g_clientMutex;
std::shared_ptr<discordpp::Client> g_client;
std::atomic<bool> g_running{false};
std::thread g_callbackThread;
JavaVM* g_javaVm = nullptr;
jclass g_discordSdkClass = nullptr;
jmethodID g_statusMethod = nullptr;
jmethodID g_sessionMethod = nullptr;
std::uint64_t g_applicationId = 0;

void LogError(const char* message) {
    __android_log_write(ANDROID_LOG_ERROR, kLogTag, message);
}

std::string StatusName(discordpp::Client::Status status) {
    switch (status) {
        case discordpp::Client::Status::Connecting: return "connecting";
        case discordpp::Client::Status::Connected: return "connected";
        case discordpp::Client::Status::Ready: return "ready";
        case discordpp::Client::Status::Disconnected: return "disconnected";
        case discordpp::Client::Status::Reconnecting: return "reconnecting";
        case discordpp::Client::Status::Disconnecting: return "disconnecting";
        case discordpp::Client::Status::HttpWait: return "connecting";
    }
    return "unknown";
}

class ScopedJniEnv {
public:
    explicit ScopedJniEnv(JavaVM* vm) : vm_(vm), env_(nullptr), attached_(false) {
        if (vm_ == nullptr) return;
        const jint result = vm_->GetEnv(reinterpret_cast<void**>(&env_), JNI_VERSION_1_6);
        if (result == JNI_EDETACHED) {
            if (vm_->AttachCurrentThread(&env_, nullptr) == JNI_OK) {
                attached_ = true;
            } else {
                env_ = nullptr;
            }
        } else if (result != JNI_OK) {
            env_ = nullptr;
        }
    }

    ~ScopedJniEnv() {
        if (attached_ && vm_ != nullptr) {
            vm_->DetachCurrentThread();
        }
    }

    JNIEnv* get() const { return env_; }

private:
    JavaVM* vm_;
    JNIEnv* env_;
    bool attached_;
};

void NotifyStatus(const std::string& status, const std::string& error = "") {
    JavaVM* vm = g_javaVm;
    jclass callbackClass = g_discordSdkClass;
    jmethodID callbackMethod = g_statusMethod;
    if (vm == nullptr || callbackClass == nullptr || callbackMethod == nullptr) return;

    ScopedJniEnv scoped(vm);
    JNIEnv* env = scoped.get();
    if (env == nullptr) return;

    jstring javaStatus = env->NewStringUTF(status.c_str());
    jstring javaError = env->NewStringUTF(error.c_str());
    if (javaStatus != nullptr && javaError != nullptr) {
        env->CallStaticVoidMethod(callbackClass, callbackMethod, javaStatus, javaError);
    }
    if (javaStatus != nullptr) env->DeleteLocalRef(javaStatus);
    if (javaError != nullptr) env->DeleteLocalRef(javaError);
    if (env->ExceptionCheck()) env->ExceptionClear();
}

void NotifySession(const std::string& refreshToken, std::int32_t expiresInSeconds) {
    JavaVM* vm = g_javaVm;
    jclass callbackClass = g_discordSdkClass;
    jmethodID callbackMethod = g_sessionMethod;
    if (vm == nullptr || callbackClass == nullptr || callbackMethod == nullptr) return;

    ScopedJniEnv scoped(vm);
    JNIEnv* env = scoped.get();
    if (env == nullptr) return;

    jstring javaRefreshToken = env->NewStringUTF(refreshToken.c_str());
    if (javaRefreshToken != nullptr) {
        env->CallStaticVoidMethod(
            callbackClass,
            callbackMethod,
            javaRefreshToken,
            static_cast<jint>(expiresInSeconds),
        );
        env->DeleteLocalRef(javaRefreshToken);
    }
    if (env->ExceptionCheck()) env->ExceptionClear();
}

std::shared_ptr<discordpp::Client> GetClient() {
    std::lock_guard<std::mutex> lock(g_clientMutex);
    return g_client;
}

std::string FromJavaString(JNIEnv* env, jstring value) {
    if (value == nullptr) return {};
    const char* utf = env->GetStringUTFChars(value, nullptr);
    if (utf == nullptr) return {};
    std::string result(utf);
    env->ReleaseStringUTFChars(value, utf);
    return result;
}

void StartCallbackPump() {
    bool expected = false;
    if (!g_running.compare_exchange_strong(expected, true)) return;

    g_callbackThread = std::thread([] {
        while (g_running.load()) {
            try {
                discordpp::RunCallbacks();
            } catch (...) {
                NotifyStatus("error", "Discord SDK callback loop failed");
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
    });
}

bool ApplyToken(
    const std::shared_ptr<discordpp::Client>& client,
    const std::string& accessToken,
    bool connectAfterUpdate
) {
    if (!client || accessToken.empty()) return false;

    try {
        client->UpdateToken(
            discordpp::AuthorizationTokenType::Bearer,
            accessToken,
            [client, connectAfterUpdate](discordpp::ClientResult result) {
                if (!result.Successful()) {
                    NotifyStatus("error", "Discord rejected the OAuth session token");
                    return;
                }
                if (connectAfterUpdate) {
                    client->Connect();
                }
            }
        );
        return true;
    } catch (...) {
        NotifyStatus("error", "Discord token update failed");
        return false;
    }
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    g_javaVm = vm;
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeInit(
    JNIEnv* env,
    jobject,
    jlong applicationId
) {
    if (applicationId <= 0) return JNI_FALSE;

    {
        std::lock_guard<std::mutex> lock(g_clientMutex);
        if (g_client != nullptr) return JNI_TRUE;

        jclass localClass = env->FindClass("app/geckopresence/host/DiscordSdk");
        if (localClass == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            LogError("Could not find DiscordSdk callback class");
            return JNI_FALSE;
        }

        g_discordSdkClass = static_cast<jclass>(env->NewGlobalRef(localClass));
        env->DeleteLocalRef(localClass);
        if (g_discordSdkClass == nullptr) return JNI_FALSE;

        g_statusMethod = env->GetStaticMethodID(
            g_discordSdkClass,
            "onNativeStatus",
            "(Ljava/lang/String;Ljava/lang/String;)V"
        );
        g_sessionMethod = env->GetStaticMethodID(
            g_discordSdkClass,
            "onNativeSession",
            "(Ljava/lang/String;I)V"
        );
        if (g_statusMethod == nullptr || g_sessionMethod == nullptr) {
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteGlobalRef(g_discordSdkClass);
            g_discordSdkClass = nullptr;
            g_statusMethod = nullptr;
            g_sessionMethod = nullptr;
            LogError("Could not resolve DiscordSdk callback methods");
            return JNI_FALSE;
        }

        g_applicationId = static_cast<std::uint64_t>(applicationId);
        try {
            auto client = std::make_shared<discordpp::Client>();
            client->SetApplicationId(g_applicationId);
            client->SetStatusChangedCallback(
                [](discordpp::Client::Status status,
                   discordpp::Client::Error error,
                   std::int32_t errorDetail) {
                    const std::string state = StatusName(status);
                    std::string message;
                    if (error != discordpp::Client::Error::None) {
                        message = "Discord SDK error " +
                            std::to_string(static_cast<int>(error)) +
                            " (detail " + std::to_string(errorDetail) + ")";
                    }
                    NotifyStatus(state, message);
                }
            );
            g_client = std::move(client);
        } catch (...) {
            env->DeleteGlobalRef(g_discordSdkClass);
            g_discordSdkClass = nullptr;
            g_statusMethod = nullptr;
            g_sessionMethod = nullptr;
            LogError("Discord Social SDK client initialization failed");
            return JNI_FALSE;
        }
    }

    StartCallbackPump();
    NotifyStatus("initialized");
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeAuthorize(JNIEnv*, jobject) {
    auto client = GetClient();
    if (!client) return JNI_FALSE;

    try {
        auto codeVerifier = client->CreateAuthorizationCodeVerifier();
        discordpp::AuthorizationArgs args{};
        args.SetClientId(g_applicationId);
        args.SetScopes(client->GetDefaultPresenceScopes());
        args.SetCodeChallenge(codeVerifier.Challenge());

        NotifyStatus("authorizing");
        client->Authorize(
            std::move(args),
            [client, verifier = std::move(codeVerifier)](discordpp::ClientResult authorizationResult,
                                                         std::string authorizationCode,
                                                         std::string redirectUri) mutable {
                if (!authorizationResult.Successful() || authorizationCode.empty()) {
                    NotifyStatus("error", "Discord authorization was not completed");
                    return;
                }

                client->GetToken(
                    g_applicationId,
                    authorizationCode,
                    verifier.Verifier(),
                    redirectUri,
                    [client](discordpp::ClientResult tokenResult,
                             std::string accessToken,
                             std::string refreshToken,
                             discordpp::AuthorizationTokenType tokenType,
                             std::int32_t expiresIn,
                             std::string) {
                        if (!tokenResult.Successful() || accessToken.empty()) {
                            NotifyStatus("error", "Discord OAuth token exchange failed");
                            return;
                        }

                        client->UpdateToken(
                            tokenType,
                            accessToken,
                            [client,
                             refreshToken = std::move(refreshToken),
                             expiresIn](discordpp::ClientResult updateResult) {
                                if (!updateResult.Successful()) {
                                    NotifyStatus("error", "Discord rejected the OAuth token");
                                    return;
                                }
                                if (!refreshToken.empty()) {
                                    NotifySession(refreshToken, expiresIn);
                                }
                                client->Connect();
                            }
                        );
                    }
                );
            }
        );
        return JNI_TRUE;
    } catch (...) {
        NotifyStatus("error", "Discord authorization could not be started");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeConnectWithAccessToken(
    JNIEnv* env,
    jobject,
    jstring accessTokenValue
) {
    const std::string accessToken = FromJavaString(env, accessTokenValue);
    return ApplyToken(GetClient(), accessToken, true) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeRefreshAccessToken(
    JNIEnv* env,
    jobject,
    jstring accessTokenValue
) {
    const std::string accessToken = FromJavaString(env, accessTokenValue);
    return ApplyToken(GetClient(), accessToken, false) ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeSetActivity(
    JNIEnv* env,
    jobject,
    jstring nameValue,
    jstring detailsValue,
    jstring stateValue,
    jlong startTimestampSeconds
) {
    auto client = GetClient();
    if (!client) return JNI_FALSE;

    const std::string name = FromJavaString(env, nameValue);
    const std::string details = FromJavaString(env, detailsValue);
    const std::string state = FromJavaString(env, stateValue);

    try {
        discordpp::Activity activity;
        activity.SetType(discordpp::ActivityTypes::Playing);
        if (!name.empty()) activity.SetName(name);
        if (!details.empty()) activity.SetDetails(details);
        if (!state.empty()) activity.SetState(state);

        if (startTimestampSeconds > 0) {
            discordpp::ActivityTimestamps timestamps;
            timestamps.SetStart(static_cast<std::uint64_t>(startTimestampSeconds));
            activity.SetTimestamps(std::move(timestamps));
        }

        client->UpdateRichPresence(
            std::move(activity),
            [](discordpp::ClientResult result) {
                if (!result.Successful()) {
                    NotifyStatus("presence_error", "Discord did not accept the Rich Presence update");
                }
            }
        );
        return JNI_TRUE;
    } catch (...) {
        NotifyStatus("presence_error", "Rich Presence update failed");
        return JNI_FALSE;
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeClear(JNIEnv*, jobject) {
    auto client = GetClient();
    if (!client) return;
    try {
        discordpp::Activity emptyActivity;
        client->UpdateRichPresence(std::move(emptyActivity), [](discordpp::ClientResult) {});
    } catch (...) {
        NotifyStatus("presence_error", "Could not clear Rich Presence");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeDisconnect(JNIEnv*, jobject) {
    auto client = GetClient();
    if (!client) return;
    try {
        client->Disconnect();
    } catch (...) {
        NotifyStatus("error", "Discord disconnect failed");
    }
}

extern "C" JNIEXPORT void JNICALL
Java_app_geckopresence_host_DiscordSdk_nativeDestroy(JNIEnv* env, jobject) {
    g_running.store(false);
    if (g_callbackThread.joinable()) {
        g_callbackThread.join();
    }

    std::shared_ptr<discordpp::Client> client;
    {
        std::lock_guard<std::mutex> lock(g_clientMutex);
        client = std::move(g_client);
    }

    if (client) {
        try {
            client->Disconnect();
        } catch (...) {
            LogError("Discord client disconnect during destroy failed");
        }
        client.reset();
    }

    std::lock_guard<std::mutex> lock(g_clientMutex);
    if (g_discordSdkClass != nullptr) {
        env->DeleteGlobalRef(g_discordSdkClass);
        g_discordSdkClass = nullptr;
    }
    g_statusMethod = nullptr;
    g_sessionMethod = nullptr;
    g_applicationId = 0;
}
