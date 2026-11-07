// OpenXR shim for the immersive activity. It only talks to OpenXR: Kotlin owns the EGL context, the render thread and
// all drawing (vr/VrActivity.kt), so PlateRenderer can later draw into these swapchains unchanged.
// Every call runs on the render thread, with the EGL context current.
#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES

#include <jni.h>

#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include <cstdint>
#include <cstring>
#include <vector>

#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "OrcaXr", __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "OrcaXr", __VA_ARGS__)

namespace {

constexpr int kEyes = 2;
// Layout of one view in the array handed to Kotlin: pose x y z, quaternion x y z w, fov left right up down.
constexpr int kFloatsPerView = 11;
// pollEvents() result bits, mirrored in XrShim.kt.
constexpr jint kRunning = 1;
constexpr jint kExit = 2;
constexpr int kHands = 2;
// One hand in the array handed to Kotlin, mirrored in XrShim.kt: active, pose x y z, quaternion x y z w, grip, stick x y, stick click.
constexpr int kFloatsPerHand = 12;
constexpr GLsizei kMsaaSamples = 4;
// GL_FRAMEBUFFER_SRGB_EXT, from GL_EXT_sRGB_write_control.
constexpr GLenum kFramebufferSrgb = 0x8DB9;
using FramebufferTexture2DMultisample = void(GL_APIENTRY*)(GLenum, GLenum, GLenum, GLuint, GLint, GLsizei);
using RenderbufferStorageMultisample = void(GL_APIENTRY*)(GLenum, GLsizei, GLenum, GLsizei, GLsizei);

struct EyeSwapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
    // One framebuffer per swapchain image, all sharing the eye's depth buffer.
    std::vector<GLuint> framebuffers;
    GLuint depth = 0;
    uint32_t acquired = 0;
    int32_t width = 0;
    int32_t height = 0;
};

struct Shim {
    jobject activity = nullptr;
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false;
    bool exitRequested = false;
    bool srgbControl = false;
    XrTime displayTime = 0;
    EyeSwapchain eyes[kEyes];
    XrView views[kEyes] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    // XR_FB_passthrough: listed by the runtime, and the entry points once enabled.
    bool passthroughListed = false;
    bool refreshRatesListed = false;
    PFN_xrEnumerateDisplayRefreshRatesFB enumerateRefreshRates = nullptr;
    PFN_xrGetDisplayRefreshRateFB getRefreshRate = nullptr;
    PFN_xrRequestDisplayRefreshRateFB requestRefreshRate = nullptr;
    XrDuration displayPeriod = 0;
    PFN_xrCreatePassthroughFB createPassthrough = nullptr;
    PFN_xrDestroyPassthroughFB destroyPassthrough = nullptr;
    PFN_xrPassthroughStartFB startPassthrough = nullptr;
    PFN_xrCreatePassthroughLayerFB createPassthroughLayer = nullptr;
    PFN_xrDestroyPassthroughLayerFB destroyPassthroughLayer = nullptr;
    XrPassthroughFB passthrough = XR_NULL_HANDLE;
    XrPassthroughLayerFB passthroughLayer = XR_NULL_HANDLE;
    // Controllers. VR still runs without them if any of this fails (inputReady stays false).
    bool inputReady = false;
    XrActionSet actionSet = XR_NULL_HANDLE;
    XrAction aim = XR_NULL_HANDLE;
    XrAction grip = XR_NULL_HANDLE;
    XrAction stick = XR_NULL_HANDLE;
    XrAction stickClick = XR_NULL_HANDLE;
    XrPath handPaths[kHands] = {XR_NULL_PATH, XR_NULL_PATH};
    XrSpace aimSpaces[kHands] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
};

Shim g;

const char* stateName(XrSessionState s) {
    switch (s) {
        case XR_SESSION_STATE_IDLE: return "XR_SESSION_STATE_IDLE";
        case XR_SESSION_STATE_READY: return "XR_SESSION_STATE_READY";
        case XR_SESSION_STATE_SYNCHRONIZED: return "XR_SESSION_STATE_SYNCHRONIZED";
        case XR_SESSION_STATE_VISIBLE: return "XR_SESSION_STATE_VISIBLE";
        case XR_SESSION_STATE_FOCUSED: return "XR_SESSION_STATE_FOCUSED";
        case XR_SESSION_STATE_STOPPING: return "XR_SESSION_STATE_STOPPING";
        case XR_SESSION_STATE_LOSS_PENDING: return "XR_SESSION_STATE_LOSS_PENDING";
        case XR_SESSION_STATE_EXITING: return "XR_SESSION_STATE_EXITING";
        default: return "XR_SESSION_STATE_UNKNOWN";
    }
}

// Logs a failed call and returns whether it succeeded.
bool ok(XrResult r, const char* what) {
    if (XR_SUCCEEDED(r)) return true;
    char text[XR_MAX_RESULT_STRING_SIZE] = "";
    if (g.instance != XR_NULL_HANDLE) xrResultToString(g.instance, r, text);
    LOGE("%s failed: %d %s", what, static_cast<int>(r), text);
    return false;
}

// Logs what the runtime offers (the M0 question) and returns whether both required extensions are there.
bool logExtensions() {
    uint32_t count = 0;
    if (!ok(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr), "xrEnumerateInstanceExtensionProperties")) return false;
    std::vector<XrExtensionProperties> props(count, {XR_TYPE_EXTENSION_PROPERTIES});
    if (!ok(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, props.data()), "xrEnumerateInstanceExtensionProperties")) return false;
    bool gles = false;
    bool android = false;
    g.passthroughListed = false;
    g.refreshRatesListed = false;
    for (const auto& p : props) {
        LOGI("XR_EXTENSION %s v%u", p.extensionName, p.extensionVersion);
        if (!std::strcmp(p.extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME)) gles = true;
        if (!std::strcmp(p.extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) android = true;
        if (!std::strcmp(p.extensionName, XR_FB_PASSTHROUGH_EXTENSION_NAME)) g.passthroughListed = true;
        if (!std::strcmp(p.extensionName, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME)) g.refreshRatesListed = true;
    }
    if (!gles) LOGE("The runtime has no %s", XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
    if (!android) LOGE("The runtime has no %s", XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME);
    return gles && android;
}

template <typename T>
bool loadFunction(const char* name, T& out) {
    return XR_SUCCEEDED(xrGetInstanceProcAddr(g.instance, name, reinterpret_cast<PFN_xrVoidFunction*>(&out))) && out;
}

// What VR itself can run at, which may differ from Android's display modes. Empty when the runtime doesn't say.
std::vector<float> refreshRates() {
    if (!g.enumerateRefreshRates) return {};
    uint32_t count = 0;
    if (!ok(g.enumerateRefreshRates(g.session, 0, &count, nullptr), "xrEnumerateDisplayRefreshRatesFB")) return {};
    std::vector<float> rates(count);
    if (!ok(g.enumerateRefreshRates(g.session, count, &count, rates.data()), "xrEnumerateDisplayRefreshRatesFB")) return {};
    return rates;
}

// Leaves all three null on any miss, so VR runs at whatever the runtime picks.
void loadRefreshFunctions() {
    if (!g.refreshRatesListed) return;
    if (!loadFunction("xrEnumerateDisplayRefreshRatesFB", g.enumerateRefreshRates) ||
        !loadFunction("xrGetDisplayRefreshRateFB", g.getRefreshRate) ||
        !loadFunction("xrRequestDisplayRefreshRateFB", g.requestRefreshRate)) {
        LOGE("VR_REFRESH entry points missing");
        g.enumerateRefreshRates = nullptr;
        g.getRefreshRate = nullptr;
        g.requestRefreshRate = nullptr;
        return;
    }
    for (float rate : refreshRates()) LOGI("VR_REFRESH available %.2f", rate);
}

// Leaves createPassthrough null on any miss, so the session stays plain.
void loadPassthroughFunctions() {
    const bool loaded = loadFunction("xrCreatePassthroughFB", g.createPassthrough) &&
                        loadFunction("xrDestroyPassthroughFB", g.destroyPassthrough) &&
                        loadFunction("xrPassthroughStartFB", g.startPassthrough) &&
                        loadFunction("xrCreatePassthroughLayerFB", g.createPassthroughLayer) &&
                        loadFunction("xrDestroyPassthroughLayerFB", g.destroyPassthroughLayer);
    if (!loaded) {
        LOGE("VR_PASSTHROUGH unavailable: entry points missing, plain background");
        g.createPassthrough = nullptr;
    }
}

void destroyPassthrough() {
    if (g.passthroughLayer != XR_NULL_HANDLE) g.destroyPassthroughLayer(g.passthroughLayer);
    if (g.passthrough != XR_NULL_HANDLE) g.destroyPassthrough(g.passthrough);
    g.passthroughLayer = XR_NULL_HANDLE;
    g.passthrough = XR_NULL_HANDLE;
}

// The camera feed behind the projection layer. Any failure leaves the plain opaque background; the session goes on.
void createPassthrough() {
    if (!g.createPassthrough) return;
    XrPassthroughCreateInfoFB info{XR_TYPE_PASSTHROUGH_CREATE_INFO_FB};
    bool started = ok(g.createPassthrough(g.session, &info, &g.passthrough), "xrCreatePassthroughFB") &&
                   ok(g.startPassthrough(g.passthrough), "xrPassthroughStartFB");
    if (started) {
        XrPassthroughLayerCreateInfoFB layerInfo{XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB};
        layerInfo.passthrough = g.passthrough;
        layerInfo.flags = XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB;
        layerInfo.purpose = XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB;
        started = ok(g.createPassthroughLayer(g.session, &layerInfo, &g.passthroughLayer), "xrCreatePassthroughLayerFB");
    }
    if (started) {
        LOGI("VR_PASSTHROUGH on");
    } else {
        LOGE("VR_PASSTHROUGH unavailable: creation failed, plain background");
        destroyPassthrough();
    }
}

bool createInstance(JavaVM* vm, bool wantPassthrough) {
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    if (!ok(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", reinterpret_cast<PFN_xrVoidFunction*>(&initLoader)),
            "xrGetInstanceProcAddr(xrInitializeLoaderKHR)") || !initLoader) return false;
    XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loaderInfo.applicationVM = vm;
    loaderInfo.applicationContext = g.activity;
    if (!ok(initLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)), "xrInitializeLoaderKHR")) return false;

    if (!logExtensions()) return false;

    // Passthrough is optional: it is only listed with the manifest feature, and without it the session stays opaque.
    const bool passthrough = wantPassthrough && g.passthroughListed;
    if (!wantPassthrough) LOGI("VR_PASSTHROUGH off: plain background requested");
    else if (!g.passthroughListed) LOGI("VR_PASSTHROUGH unavailable: %s not listed by the runtime, plain background", XR_FB_PASSTHROUGH_EXTENSION_NAME);
    std::vector<const char*> extensions = {XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    if (passthrough) extensions.push_back(XR_FB_PASSTHROUGH_EXTENSION_NAME);
    if (g.refreshRatesListed) extensions.push_back(XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME);
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = g.activity;
    XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};
    info.next = &androidInfo;
    info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
    info.enabledExtensionNames = extensions.data();
    std::strncpy(info.applicationInfo.applicationName, "OrcaStudio VR", XR_MAX_APPLICATION_NAME_SIZE - 1);
    info.applicationInfo.apiVersion = XR_API_VERSION_1_0;
    if (!ok(xrCreateInstance(&info, &g.instance), "xrCreateInstance")) return false;

    XrInstanceProperties props{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(g.instance, &props))) {
        LOGI("XR_RUNTIME %s %u.%u.%u", props.runtimeName, XR_VERSION_MAJOR(props.runtimeVersion),
             XR_VERSION_MINOR(props.runtimeVersion), XR_VERSION_PATCH(props.runtimeVersion));
    }
    XrSystemGetInfo systemInfo{XR_TYPE_SYSTEM_GET_INFO};
    systemInfo.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    if (!ok(xrGetSystem(g.instance, &systemInfo, &g.system), "xrGetSystem")) return false;
    if (passthrough) loadPassthroughFunctions();
    return true;
}

// OpenXR requires this call before xrCreateSession.
bool checkGlesRequirements() {
    PFN_xrGetOpenGLESGraphicsRequirementsKHR getRequirements = nullptr;
    if (!ok(xrGetInstanceProcAddr(g.instance, "xrGetOpenGLESGraphicsRequirementsKHR", reinterpret_cast<PFN_xrVoidFunction*>(&getRequirements)),
            "xrGetInstanceProcAddr(xrGetOpenGLESGraphicsRequirementsKHR)") || !getRequirements) return false;
    XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    if (!ok(getRequirements(g.instance, g.system, &req), "xrGetOpenGLESGraphicsRequirementsKHR")) return false;
    LOGI("XR_GLES min %u.%u max %u.%u", XR_VERSION_MAJOR(req.minApiVersionSupported), XR_VERSION_MINOR(req.minApiVersionSupported),
         XR_VERSION_MAJOR(req.maxApiVersionSupported), XR_VERSION_MINOR(req.maxApiVersionSupported));
    return true;
}

int64_t pickFormat() {
    uint32_t count = 0;
    if (!ok(xrEnumerateSwapchainFormats(g.session, 0, &count, nullptr), "xrEnumerateSwapchainFormats")) return 0;
    std::vector<int64_t> formats(count);
    if (!ok(xrEnumerateSwapchainFormats(g.session, count, &count, formats.data()), "xrEnumerateSwapchainFormats")) return 0;
    for (int64_t wanted : {static_cast<int64_t>(GL_SRGB8_ALPHA8), static_cast<int64_t>(GL_RGBA8)}) {
        for (int64_t f : formats) {
            if (f == wanted) return f;
        }
    }
    LOGE("No usable swapchain format among %u", count);
    return 0;
}

bool hasGlExtension(const char* name) {
    GLint count = 0;
    glGetIntegerv(GL_NUM_EXTENSIONS, &count);
    for (GLint i = 0; i < count; i++) {
        const auto* e = reinterpret_cast<const char*>(glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i)));
        if (e && !std::strcmp(e, name)) return true;
    }
    return false;
}

// The app's shaders write display-encoded colour, so GL must not encode it again on the way into an sRGB swapchain.
void disableSrgbEncoding() {
    g.srgbControl = hasGlExtension("GL_EXT_sRGB_write_control");
    LOGI("VR_SRGB write control %s", g.srgbControl ? "present, encoding off" : "absent, colours will look washed out");
}

// Depth and framebuffers of every eye, with 4x MSAA that resolves on flush where the GPU offers it (else single sample).
bool createEyeTargets() {
    FramebufferTexture2DMultisample attachMsaa = nullptr;
    RenderbufferStorageMultisample storageMsaa = nullptr;
    GLsizei samples = 0;
    if (hasGlExtension("GL_EXT_multisampled_render_to_texture")) {
        attachMsaa = reinterpret_cast<FramebufferTexture2DMultisample>(eglGetProcAddress("glFramebufferTexture2DMultisampleEXT"));
        storageMsaa = reinterpret_cast<RenderbufferStorageMultisample>(eglGetProcAddress("glRenderbufferStorageMultisampleEXT"));
        GLint most = 0;
        glGetIntegerv(GL_MAX_SAMPLES, &most);
        if (attachMsaa && storageMsaa && most > 1) samples = most < kMsaaSamples ? most : kMsaaSamples;
    }
    LOGI("VR_MSAA samples %d", static_cast<int>(samples));
    for (EyeSwapchain& s : g.eyes) {
        glGenRenderbuffers(1, &s.depth);
        glBindRenderbuffer(GL_RENDERBUFFER, s.depth);
        if (samples > 0) storageMsaa(GL_RENDERBUFFER, samples, GL_DEPTH_COMPONENT24, s.width, s.height);
        else glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH_COMPONENT24, s.width, s.height);
        s.framebuffers.resize(s.images.size());
        glGenFramebuffers(static_cast<GLsizei>(s.framebuffers.size()), s.framebuffers.data());
        for (size_t i = 0; i < s.images.size(); i++) {
            glBindFramebuffer(GL_FRAMEBUFFER, s.framebuffers[i]);
            if (samples > 0) attachMsaa(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s.images[i].image, 0, samples);
            else glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, s.images[i].image, 0);
            glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_RENDERBUFFER, s.depth);
            const GLenum status = glCheckFramebufferStatus(GL_FRAMEBUFFER);
            if (status != GL_FRAMEBUFFER_COMPLETE) {
                LOGE("Eye framebuffer incomplete: 0x%x", status);
                glBindFramebuffer(GL_FRAMEBUFFER, 0);
                return false;
            }
        }
    }
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glBindRenderbuffer(GL_RENDERBUFFER, 0);
    return true;
}

bool createSwapchains() {
    uint32_t count = kEyes;
    XrViewConfigurationView config[kEyes] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
    if (!ok(xrEnumerateViewConfigurationViews(g.instance, g.system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, kEyes, &count, config),
            "xrEnumerateViewConfigurationViews")) return false;
    const int64_t format = pickFormat();
    if (!format) return false;
    for (int eye = 0; eye < kEyes; eye++) {
        XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        info.format = format;
        info.sampleCount = 1;
        info.width = config[eye].recommendedImageRectWidth;
        info.height = config[eye].recommendedImageRectHeight;
        info.faceCount = 1;
        info.arraySize = 1;
        info.mipCount = 1;
        EyeSwapchain& s = g.eyes[eye];
        if (!ok(xrCreateSwapchain(g.session, &info, &s.handle), "xrCreateSwapchain")) return false;
        s.width = static_cast<int32_t>(info.width);
        s.height = static_cast<int32_t>(info.height);
        uint32_t images = 0;
        if (!ok(xrEnumerateSwapchainImages(s.handle, 0, &images, nullptr), "xrEnumerateSwapchainImages")) return false;
        s.images.assign(images, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        if (!ok(xrEnumerateSwapchainImages(s.handle, images, &images, reinterpret_cast<XrSwapchainImageBaseHeader*>(s.images.data())),
                "xrEnumerateSwapchainImages")) return false;
        LOGI("XR_SWAPCHAIN eye %d %dx%d format 0x%llx images %u", eye, s.width, s.height, static_cast<long long>(format), images);
    }
    disableSrgbEncoding();
    return createEyeTargets();
}

XrPath toPath(const char* text) {
    XrPath path = XR_NULL_PATH;
    ok(xrStringToPath(g.instance, text, &path), text);
    return path;
}

bool createAction(const char* name, const char* label, XrActionType type, XrAction* out) {
    XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
    info.actionType = type;
    std::strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
    std::strncpy(info.localizedActionName, label, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
    info.countSubactionPaths = kHands;
    info.subactionPaths = g.handPaths;
    return ok(xrCreateAction(g.actionSet, &info, out), name);
}

// Needs the session for attaching and for the aim spaces. The Touch profile also covers Touch Plus (compatibility mode).
bool createInput() {
    g.handPaths[0] = toPath("/user/hand/left");
    g.handPaths[1] = toPath("/user/hand/right");
    XrActionSetCreateInfo setInfo{XR_TYPE_ACTION_SET_CREATE_INFO};
    std::strncpy(setInfo.actionSetName, "orca", XR_MAX_ACTION_SET_NAME_SIZE - 1);
    std::strncpy(setInfo.localizedActionSetName, "OrcaStudio", XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE - 1);
    if (!ok(xrCreateActionSet(g.instance, &setInfo, &g.actionSet), "xrCreateActionSet")) return false;
    if (!createAction("aim", "Aim", XR_ACTION_TYPE_POSE_INPUT, &g.aim) ||
        !createAction("grip", "Grab the plate", XR_ACTION_TYPE_BOOLEAN_INPUT, &g.grip) ||
        !createAction("stick", "Distance and zoom", XR_ACTION_TYPE_VECTOR2F_INPUT, &g.stick) ||
        !createAction("stick_click", "True size", XR_ACTION_TYPE_BOOLEAN_INPUT, &g.stickClick)) return false;

    const XrActionSuggestedBinding bindings[] = {
        {g.aim, toPath("/user/hand/left/input/aim/pose")},
        {g.aim, toPath("/user/hand/right/input/aim/pose")},
        {g.grip, toPath("/user/hand/left/input/squeeze/value")},
        {g.grip, toPath("/user/hand/right/input/squeeze/value")},
        {g.stick, toPath("/user/hand/left/input/thumbstick")},
        {g.stick, toPath("/user/hand/right/input/thumbstick")},
        {g.stickClick, toPath("/user/hand/left/input/thumbstick/click")},
        {g.stickClick, toPath("/user/hand/right/input/thumbstick/click")},
    };
    XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    suggested.interactionProfile = toPath("/interaction_profiles/oculus/touch_controller");
    suggested.countSuggestedBindings = sizeof(bindings) / sizeof(bindings[0]);
    suggested.suggestedBindings = bindings;
    if (!ok(xrSuggestInteractionProfileBindings(g.instance, &suggested), "xrSuggestInteractionProfileBindings")) return false;

    XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    attach.countActionSets = 1;
    attach.actionSets = &g.actionSet;
    if (!ok(xrAttachSessionActionSets(g.session, &attach), "xrAttachSessionActionSets")) return false;

    for (int hand = 0; hand < kHands; hand++) {
        XrActionSpaceCreateInfo info{XR_TYPE_ACTION_SPACE_CREATE_INFO};
        info.action = g.aim;
        info.subactionPath = g.handPaths[hand];
        info.poseInActionSpace.orientation.w = 1.0f;
        if (!ok(xrCreateActionSpace(g.session, &info, &g.aimSpaces[hand]), "xrCreateActionSpace")) return false;
    }
    return true;
}

void logInteractionProfiles() {
    for (int hand = 0; hand < kHands; hand++) {
        XrInteractionProfileState state{XR_TYPE_INTERACTION_PROFILE_STATE};
        if (!ok(xrGetCurrentInteractionProfile(g.session, g.handPaths[hand], &state), "xrGetCurrentInteractionProfile")) continue;
        char text[XR_MAX_PATH_LENGTH] = "none";
        uint32_t length = 0;
        if (state.interactionProfile != XR_NULL_PATH) xrPathToString(g.instance, state.interactionProfile, sizeof(text), &length, text);
        LOGI("VR_INPUT %s profile %s", hand == 0 ? "left" : "right", text);
    }
}

// One hand's state into out; leaves it all zero unless the aim pose is usable.
void readHand(int hand, jfloat* out) {
    XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};
    get.subactionPath = g.handPaths[hand];
    get.action = g.aim;
    XrActionStatePose pose{XR_TYPE_ACTION_STATE_POSE};
    if (XR_FAILED(xrGetActionStatePose(g.session, &get, &pose)) || !pose.isActive) return;
    XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
    if (XR_FAILED(xrLocateSpace(g.aimSpaces[hand], g.space, g.displayTime, &location))) return;
    const XrSpaceLocationFlags valid = XR_SPACE_LOCATION_POSITION_VALID_BIT | XR_SPACE_LOCATION_ORIENTATION_VALID_BIT;
    if ((location.locationFlags & valid) != valid) return;
    out[0] = 1.0f;
    out[1] = location.pose.position.x;
    out[2] = location.pose.position.y;
    out[3] = location.pose.position.z;
    out[4] = location.pose.orientation.x;
    out[5] = location.pose.orientation.y;
    out[6] = location.pose.orientation.z;
    out[7] = location.pose.orientation.w;
    XrActionStateBoolean pressed{XR_TYPE_ACTION_STATE_BOOLEAN};
    get.action = g.grip;
    if (XR_SUCCEEDED(xrGetActionStateBoolean(g.session, &get, &pressed)) && pressed.isActive && pressed.currentState) out[8] = 1.0f;
    XrActionStateVector2f axes{XR_TYPE_ACTION_STATE_VECTOR2F};
    get.action = g.stick;
    if (XR_SUCCEEDED(xrGetActionStateVector2f(g.session, &get, &axes)) && axes.isActive) {
        out[9] = axes.currentState.x;
        out[10] = axes.currentState.y;
    }
    pressed = {XR_TYPE_ACTION_STATE_BOOLEAN};
    get.action = g.stickClick;
    if (XR_SUCCEEDED(xrGetActionStateBoolean(g.session, &get, &pressed)) && pressed.isActive && pressed.currentState) out[11] = 1.0f;
}

bool createSession(EGLDisplay display, EGLConfig config, EGLContext context) {
    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = display;
    binding.config = config;
    binding.context = context;
    XrSessionCreateInfo info{XR_TYPE_SESSION_CREATE_INFO};
    info.next = &binding;
    info.systemId = g.system;
    if (!ok(xrCreateSession(g.instance, &info, &g.session), "xrCreateSession")) return false;

    XrReferenceSpaceCreateInfo spaceInfo{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    spaceInfo.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    spaceInfo.poseInReferenceSpace.orientation.w = 1.0f;
    if (!ok(xrCreateReferenceSpace(g.session, &spaceInfo, &g.space), "xrCreateReferenceSpace")) return false;
    createPassthrough();
    loadRefreshFunctions();
    g.inputReady = createInput();
    LOGI("VR_INPUT %s", g.inputReady ? "ready" : "unavailable, the plate stays where it is");
    return createSwapchains();
}

void onStateChanged(XrSessionState state) {
    g.state = state;
    LOGI("%s", stateName(state));
    switch (state) {
        case XR_SESSION_STATE_READY: {
            XrSessionBeginInfo info{XR_TYPE_SESSION_BEGIN_INFO};
            info.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
            if (ok(xrBeginSession(g.session, &info), "xrBeginSession")) g.running = true;
            break;
        }
        case XR_SESSION_STATE_STOPPING:
            g.running = false;
            ok(xrEndSession(g.session), "xrEndSession");
            break;
        case XR_SESSION_STATE_EXITING:
        case XR_SESSION_STATE_LOSS_PENDING:
            g.running = false;
            g.exitRequested = true;
            break;
        default:
            break;
    }
}

void destroyAll(JNIEnv* env) {
    destroyPassthrough();
    for (EyeSwapchain& s : g.eyes) {
        if (s.handle != XR_NULL_HANDLE) xrDestroySwapchain(s.handle);
    }
    for (XrSpace space : g.aimSpaces) {
        if (space != XR_NULL_HANDLE) xrDestroySpace(space);
    }
    if (g.actionSet != XR_NULL_HANDLE) xrDestroyActionSet(g.actionSet);
    if (g.space != XR_NULL_HANDLE) xrDestroySpace(g.space);
    if (g.session != XR_NULL_HANDLE) xrDestroySession(g.session);
    if (g.instance != XR_NULL_HANDLE) xrDestroyInstance(g.instance);
    if (g.activity) env->DeleteGlobalRef(g.activity);
    g = Shim{};
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_app_orcaandroid_vr_XrShim_nativeCreate(JNIEnv* env, jclass, jobject activity, jlong display, jlong config, jlong context, jboolean passthrough) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return JNI_FALSE;
    g.activity = env->NewGlobalRef(activity);
    const bool created = createInstance(vm, passthrough == JNI_TRUE) && checkGlesRequirements() &&
                         createSession(reinterpret_cast<EGLDisplay>(display), reinterpret_cast<EGLConfig>(config), reinterpret_cast<EGLContext>(context));
    if (!created) destroyAll(env);
    return created ? JNI_TRUE : JNI_FALSE;
}

// True when the passthrough layer runs: the renderer then clears with alpha 0.
JNIEXPORT jboolean JNICALL Java_app_orcaandroid_vr_XrShim_nativePassthroughActive(JNIEnv*, jclass) {
    return g.passthroughLayer != XR_NULL_HANDLE ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jfloatArray JNICALL Java_app_orcaandroid_vr_XrShim_nativeRefreshRates(JNIEnv* env, jclass) {
    const std::vector<float> rates = refreshRates();
    jfloatArray out = env->NewFloatArray(static_cast<jsize>(rates.size()));
    if (out) env->SetFloatArrayRegion(out, 0, static_cast<jsize>(rates.size()), rates.data());
    return out;
}

// 0 when the runtime doesn't say.
JNIEXPORT jfloat JNICALL Java_app_orcaandroid_vr_XrShim_nativeCurrentRefreshRate(JNIEnv*, jclass) {
    float rate = 0;
    if (!g.getRefreshRate || !ok(g.getRefreshRate(g.session, &rate), "xrGetDisplayRefreshRateFB")) return 0;
    return rate;
}

// The rate implied by the last xrWaitFrame, to compare with the rate the runtime reports. 0 before the first frame.
JNIEXPORT jfloat JNICALL Java_app_orcaandroid_vr_XrShim_nativeFramePeriodHz(JNIEnv*, jclass) {
    return g.displayPeriod > 0 ? static_cast<jfloat>(1e9 / static_cast<double>(g.displayPeriod)) : 0;
}

// 0 asks for no preference. Only starts the change: XR_TYPE_EVENT_DATA_DISPLAY_REFRESH_RATE_CHANGED_FB reports it.
JNIEXPORT jboolean JNICALL Java_app_orcaandroid_vr_XrShim_nativeRequestRefreshRate(JNIEnv*, jclass, jfloat rate) {
    return g.requestRefreshRate && ok(g.requestRefreshRate(g.session, rate), "xrRequestDisplayRefreshRateFB") ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_app_orcaandroid_vr_XrShim_nativePollEvents(JNIEnv*, jclass) {
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(g.instance, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            onStateChanged(reinterpret_cast<const XrEventDataSessionStateChanged*>(&event)->state);
        } else if (event.type == XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED) {
            if (g.inputReady) logInteractionProfiles();
        } else if (event.type == XR_TYPE_EVENT_DATA_DISPLAY_REFRESH_RATE_CHANGED_FB) {
            const auto* changed = reinterpret_cast<const XrEventDataDisplayRefreshRateChangedFB*>(&event);
            LOGI("VR_REFRESH changed %.2f to %.2f", changed->fromDisplayRefreshRate, changed->toDisplayRefreshRate);
        } else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
            LOGE("XR instance loss pending");
            g.running = false;
            g.exitRequested = true;
        }
        event = {XR_TYPE_EVENT_DATA_BUFFER};
    }
    return (g.running ? kRunning : 0) | (g.exitRequested ? kExit : 0);
}

JNIEXPORT void JNICALL Java_app_orcaandroid_vr_XrShim_nativeSwapchainSize(JNIEnv* env, jclass, jint eye, jintArray out) {
    const jint size[2] = {g.eyes[eye].width, g.eyes[eye].height};
    env->SetIntArrayRegion(out, 0, 2, size);
}

// Waits for and begins the next frame; true when the caller should draw. nativeEndFrame must always follow.
JNIEXPORT jboolean JNICALL Java_app_orcaandroid_vr_XrShim_nativeBeginFrame(JNIEnv* env, jclass, jfloatArray outViews) {
    XrFrameState frame{XR_TYPE_FRAME_STATE};
    if (!ok(xrWaitFrame(g.session, nullptr, &frame), "xrWaitFrame")) return JNI_FALSE;
    g.displayTime = frame.predictedDisplayTime;
    g.displayPeriod = frame.predictedDisplayPeriod;
    if (!ok(xrBeginFrame(g.session, nullptr), "xrBeginFrame")) return JNI_FALSE;
    if (!frame.shouldRender) return JNI_FALSE;

    XrViewLocateInfo locate{XR_TYPE_VIEW_LOCATE_INFO};
    locate.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    locate.displayTime = frame.predictedDisplayTime;
    locate.space = g.space;
    XrViewState viewState{XR_TYPE_VIEW_STATE};
    uint32_t count = kEyes;
    if (!ok(xrLocateViews(g.session, &locate, &viewState, kEyes, &count, g.views), "xrLocateViews")) return JNI_FALSE;
    const XrViewStateFlags valid = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT;
    if ((viewState.viewStateFlags & valid) != valid) return JNI_FALSE;

    jfloat out[kEyes * kFloatsPerView];
    for (int eye = 0; eye < kEyes; eye++) {
        const XrView& v = g.views[eye];
        jfloat* o = out + eye * kFloatsPerView;
        o[0] = v.pose.position.x;
        o[1] = v.pose.position.y;
        o[2] = v.pose.position.z;
        o[3] = v.pose.orientation.x;
        o[4] = v.pose.orientation.y;
        o[5] = v.pose.orientation.z;
        o[6] = v.pose.orientation.w;
        o[7] = v.fov.angleLeft;
        o[8] = v.fov.angleRight;
        o[9] = v.fov.angleUp;
        o[10] = v.fov.angleDown;
    }
    env->SetFloatArrayRegion(outViews, 0, kEyes * kFloatsPerView, out);
    return JNI_TRUE;
}

// Controller state at the predicted display time of the frame just begun. All zero while unfocused or untracked.
JNIEXPORT void JNICALL Java_app_orcaandroid_vr_XrShim_nativeInput(JNIEnv* env, jclass, jfloatArray outHands) {
    jfloat out[kHands * kFloatsPerHand] = {};
    if (g.inputReady) {
        XrActiveActionSet active{g.actionSet, XR_NULL_PATH};
        XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
        sync.countActiveActionSets = 1;
        sync.activeActionSets = &active;
        // XR_SESSION_NOT_FOCUSED is a success code, and every action is inactive then.
        const XrResult synced = xrSyncActions(g.session, &sync);
        if (ok(synced, "xrSyncActions") && synced == XR_SUCCESS) {
            for (int hand = 0; hand < kHands; hand++) readHand(hand, out + hand * kFloatsPerHand);
        }
    }
    env->SetFloatArrayRegion(outHands, 0, kHands * kFloatsPerHand, out);
}

// Returns the framebuffer to draw this eye into, or 0 when no image could be acquired.
JNIEXPORT jint JNICALL Java_app_orcaandroid_vr_XrShim_nativeAcquireImage(JNIEnv*, jclass, jint eye) {
    EyeSwapchain& s = g.eyes[eye];
    uint32_t index = 0;
    if (!ok(xrAcquireSwapchainImage(s.handle, nullptr, &index), "xrAcquireSwapchainImage")) return 0;
    XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wait.timeout = XR_INFINITE_DURATION;
    if (!ok(xrWaitSwapchainImage(s.handle, &wait), "xrWaitSwapchainImage")) {
        // Must not stay acquired: the next frame would fail too.
        ok(xrReleaseSwapchainImage(s.handle, nullptr), "xrReleaseSwapchainImage");
        return 0;
    }
    // Every eye, in case the runtime changes the state in the shared context.
    if (g.srgbControl) glDisable(kFramebufferSrgb);
    s.acquired = index;
    return static_cast<jint>(s.framebuffers[index]);
}

JNIEXPORT void JNICALL Java_app_orcaandroid_vr_XrShim_nativeReleaseImage(JNIEnv*, jclass, jint eye) {
    const EyeSwapchain& s = g.eyes[eye];
    glBindFramebuffer(GL_FRAMEBUFFER, s.framebuffers[s.acquired]);
    // Nobody reads this eye's depth again: dropping it saves writing it out to memory.
    const GLenum depth = GL_DEPTH_ATTACHMENT;
    glInvalidateFramebuffer(GL_FRAMEBUFFER, 1, &depth);
    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glFlush();
    ok(xrReleaseSwapchainImage(s.handle, nullptr), "xrReleaseSwapchainImage");
}

JNIEXPORT void JNICALL Java_app_orcaandroid_vr_XrShim_nativeEndFrame(JNIEnv*, jclass, jboolean rendered) {
    const bool passthrough = g.passthroughLayer != XR_NULL_HANDLE;
    XrCompositionLayerProjectionView projected[kEyes] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    // Passthrough first, as layers composite in array order. Both use the texture alpha, premultiplied (no UNPREMULTIPLIED bit).
    XrCompositionLayerPassthroughFB background{XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB};
    background.flags = XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT;
    background.space = XR_NULL_HANDLE;
    background.layerHandle = g.passthroughLayer;
    const XrCompositionLayerBaseHeader* layers[2] = {reinterpret_cast<const XrCompositionLayerBaseHeader*>(&background),
                                                      reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer)};
    XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
    end.displayTime = g.displayTime;
    end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    end.layers = layers + (passthrough ? 0 : 1);
    if (rendered) {
        for (int eye = 0; eye < kEyes; eye++) {
            const EyeSwapchain& s = g.eyes[eye];
            projected[eye].pose = g.views[eye].pose;
            projected[eye].fov = g.views[eye].fov;
            projected[eye].subImage.swapchain = s.handle;
            projected[eye].subImage.imageRect.extent = {s.width, s.height};
        }
        layer.layerFlags = passthrough ? XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT : 0;
        layer.space = g.space;
        layer.viewCount = kEyes;
        layer.views = projected;
        end.layerCount = passthrough ? 2 : 1;
    } else if (passthrough) {
        // Nothing drawn this frame: keep the passthrough up.
        end.layerCount = 1;
    }
    ok(xrEndFrame(g.session, &end), "xrEndFrame");
}

JNIEXPORT void JNICALL Java_app_orcaandroid_vr_XrShim_nativeDestroy(JNIEnv* env, jclass) {
    destroyAll(env);
}

}  // extern "C"
