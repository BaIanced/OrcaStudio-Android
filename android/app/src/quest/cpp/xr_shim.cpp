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
constexpr GLsizei kMsaaSamples = 4;
// GL_FRAMEBUFFER_SRGB_EXT and GL_MAX_SAMPLES_EXT, from GL_EXT_sRGB_write_control and GL_EXT_multisampled_render_to_texture.
constexpr GLenum kFramebufferSrgb = 0x8DB9;
constexpr GLenum kMaxSamples = 0x9135;
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
    XrTime displayTime = 0;
    EyeSwapchain eyes[kEyes];
    XrView views[kEyes] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
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
    for (const auto& p : props) {
        LOGI("XR_EXTENSION %s v%u", p.extensionName, p.extensionVersion);
        if (!std::strcmp(p.extensionName, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME)) gles = true;
        if (!std::strcmp(p.extensionName, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME)) android = true;
    }
    if (!gles) LOGE("The runtime has no %s", XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
    if (!android) LOGE("The runtime has no %s", XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME);
    return gles && android;
}

bool createInstance(JavaVM* vm) {
    PFN_xrInitializeLoaderKHR initLoader = nullptr;
    if (!ok(xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", reinterpret_cast<PFN_xrVoidFunction*>(&initLoader)),
            "xrGetInstanceProcAddr(xrInitializeLoaderKHR)") || !initLoader) return false;
    XrLoaderInitInfoAndroidKHR loaderInfo{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    loaderInfo.applicationVM = vm;
    loaderInfo.applicationContext = g.activity;
    if (!ok(initLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&loaderInfo)), "xrInitializeLoaderKHR")) return false;

    if (!logExtensions()) return false;

    const char* extensions[] = {XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME};
    XrInstanceCreateInfoAndroidKHR androidInfo{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    androidInfo.applicationVM = vm;
    androidInfo.applicationActivity = g.activity;
    XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};
    info.next = &androidInfo;
    info.enabledExtensionCount = 2;
    info.enabledExtensionNames = extensions;
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
    return ok(xrGetSystem(g.instance, &systemInfo, &g.system), "xrGetSystem");
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
    const bool control = hasGlExtension("GL_EXT_sRGB_write_control");
    if (control) glDisable(kFramebufferSrgb);
    LOGI("VR_SRGB write control %s", control ? "present, encoding off" : "absent, colours will look washed out");
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
        glGetIntegerv(kMaxSamples, &most);
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
    for (EyeSwapchain& s : g.eyes) {
        if (s.handle != XR_NULL_HANDLE) xrDestroySwapchain(s.handle);
    }
    if (g.space != XR_NULL_HANDLE) xrDestroySpace(g.space);
    if (g.session != XR_NULL_HANDLE) xrDestroySession(g.session);
    if (g.instance != XR_NULL_HANDLE) xrDestroyInstance(g.instance);
    if (g.activity) env->DeleteGlobalRef(g.activity);
    g = Shim{};
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_app_orcaandroid_vr_XrShim_nativeCreate(JNIEnv* env, jclass, jobject activity, jlong display, jlong config, jlong context) {
    JavaVM* vm = nullptr;
    if (env->GetJavaVM(&vm) != JNI_OK) return JNI_FALSE;
    g.activity = env->NewGlobalRef(activity);
    const bool created = createInstance(vm) && checkGlesRequirements() &&
                         createSession(reinterpret_cast<EGLDisplay>(display), reinterpret_cast<EGLConfig>(config), reinterpret_cast<EGLContext>(context));
    if (!created) destroyAll(env);
    return created ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_app_orcaandroid_vr_XrShim_nativePollEvents(JNIEnv*, jclass) {
    XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(g.instance, &event) == XR_SUCCESS) {
        if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            onStateChanged(reinterpret_cast<const XrEventDataSessionStateChanged*>(&event)->state);
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

// Returns the framebuffer to draw this eye into, or 0 when no image could be acquired.
JNIEXPORT jint JNICALL Java_app_orcaandroid_vr_XrShim_nativeAcquireImage(JNIEnv*, jclass, jint eye) {
    EyeSwapchain& s = g.eyes[eye];
    uint32_t index = 0;
    if (!ok(xrAcquireSwapchainImage(s.handle, nullptr, &index), "xrAcquireSwapchainImage")) return 0;
    XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
    wait.timeout = XR_INFINITE_DURATION;
    if (!ok(xrWaitSwapchainImage(s.handle, &wait), "xrWaitSwapchainImage")) return 0;
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
    XrCompositionLayerProjectionView projected[kEyes] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    const XrCompositionLayerBaseHeader* layers[1] = {reinterpret_cast<const XrCompositionLayerBaseHeader*>(&layer)};
    XrFrameEndInfo end{XR_TYPE_FRAME_END_INFO};
    end.displayTime = g.displayTime;
    end.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    if (rendered) {
        for (int eye = 0; eye < kEyes; eye++) {
            const EyeSwapchain& s = g.eyes[eye];
            projected[eye].pose = g.views[eye].pose;
            projected[eye].fov = g.views[eye].fov;
            projected[eye].subImage.swapchain = s.handle;
            projected[eye].subImage.imageRect.extent = {s.width, s.height};
        }
        layer.space = g.space;
        layer.viewCount = kEyes;
        layer.views = projected;
        end.layerCount = 1;
        end.layers = layers;
    }
    ok(xrEndFrame(g.session, &end), "xrEndFrame");
}

JNIEXPORT void JNICALL Java_app_orcaandroid_vr_XrShim_nativeDestroy(JNIEnv* env, jclass) {
    destroyAll(env);
}

}  // extern "C"
