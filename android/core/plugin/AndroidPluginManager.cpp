// Android implementation of the parts of Slic3r::PluginManager (src/slic3r/plugin/PluginManager.hpp)
// that the plugin host and the app use. Upstream's PluginManager.cpp is bound to the wxWidgets GUI
// (notifications, the Plater, the plugin dialogs); discovery, loading and installing reuse the same
// GUI-free helpers it uses (PluginFsUtils, plugin_loader). Plugins are discovered and loaded
// synchronously on the caller's thread, which on Android is the plugin runtime's worker.
#include "PluginManager.hpp"

#include "PluginHooks.hpp"
#include "PluginLoader.hpp"
#include "PythonInterpreter.hpp"
#include "PythonPluginBridge.hpp"
#include "pluginTypes/script/ScriptPluginCapability.hpp"

#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>

namespace Slic3r {

void Plugin::release_module()
{
    if (module == nullptr && plugin_sys_paths.empty() && plugin_modules.empty())
        return;
    PythonInterpreter::instance().unload_module(module, module_name, plugin_sys_paths, plugin_modules);
    module = nullptr;
    module_name.clear();
    plugin_sys_paths.clear();
    plugin_modules.clear();
}

PluginManager& PluginManager::instance()
{
    // The interpreter's static must outlive this one: ~Plugin releases modules through it.
    PythonInterpreter::instance();
    static PluginManager inst;
    return inst;
}

PluginManager::~PluginManager() { shutdown(); }

bool PluginManager::initialize()
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (m_initialized)
            return true;
        m_shutting_down.store(false, std::memory_order_release);
    }
    PythonInterpreter& interpreter = PythonInterpreter::instance();
    if (!interpreter.is_initialized() && !interpreter.initialize()) {
        BOOST_LOG_TRIVIAL(error) << "Failed to initialize Python: " << interpreter.last_error();
        return false;
    }
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_initialized = true;
    }
    m_config.load();
    plugin_hooks::install();
    return true;
}

void PluginManager::set_shutting_down() { m_shutting_down.store(true, std::memory_order_release); }

void PluginManager::shutdown()
{
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        if (!m_initialized)
            return;
    }
    plugin_hooks::uninstall();
    set_shutting_down();
    unload_all_plugins();
    PythonPluginBridge::instance().clear_pending_captures();
    if (m_config.dirty())
        m_config.save();
    std::lock_guard<std::mutex> lock(m_mutex);
    m_initialized = false;
}

Plugin* PluginManager::find_plugin_locked(const std::string& plugin_key)
{
    for (Plugin& plugin : m_plugins)
        if (plugin.descriptor.plugin_key == plugin_key)
            return &plugin;
    return nullptr;
}

const Plugin* PluginManager::find_plugin_locked(const std::string& plugin_key) const
{
    for (const Plugin& plugin : m_plugins)
        if (plugin.descriptor.plugin_key == plugin_key)
            return &plugin;
    return nullptr;
}

void PluginManager::set_cloud_user(const std::string& user_id)
{
    std::lock_guard<std::mutex> lock(m_mutex);
    m_cloud_user_id = user_id;
}

void PluginManager::discover_plugins(bool /*async*/, bool clear)
{
    std::string cloud_user_id;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        cloud_user_id          = m_cloud_user_id;
        m_discovery_in_progress = true;
    }
    std::string                   error;
    std::vector<PluginDescriptor> discovered = discover_plugin_packages(get_plugin_directories(cloud_user_id), error);
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        for (PluginDescriptor& descriptor : discovered) {
            if (descriptor.plugin_key.empty())
                continue;
            if (Plugin* existing = find_plugin_locked(descriptor.plugin_key)) {
                if (!existing->is_loaded())
                    existing->descriptor = std::move(descriptor);
                continue;
            }
            Plugin plugin;
            plugin.descriptor = std::move(descriptor);
            m_plugins.push_back(std::move(plugin));
        }
        (void) clear;
        m_discovery_error       = error;
        m_discovery_complete    = true;
        m_discovery_in_progress = false;
    }
    m_discovery_cv.notify_all();
}

std::vector<PluginDescriptor> PluginManager::get_plugin_descriptors(bool include_invalid) const
{
    std::lock_guard<std::mutex>   lock(m_mutex);
    std::vector<PluginDescriptor> out;
    for (const Plugin& plugin : m_plugins)
        if (include_invalid || plugin.descriptor.is_metadata_valid())
            out.push_back(plugin.descriptor);
    return out;
}

bool PluginManager::try_get_plugin_descriptor(const std::string& plugin_key, PluginDescriptor& out) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    if (const Plugin* plugin = find_plugin_locked(plugin_key)) {
        out = plugin->descriptor;
        return true;
    }
    return false;
}

bool PluginManager::try_get_valid_plugin_descriptor(const std::string& plugin_key, PluginDescriptor& out) const
{
    return try_get_plugin_descriptor(plugin_key, out) && out.is_metadata_valid();
}

bool PluginManager::try_get_plugin_descriptor_for_capability(const std::string& capability_name,
                                                             PluginCapabilityType type,
                                                             PluginDescriptor& out) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    for (const Plugin& plugin : m_plugins)
        for (const auto& cap : plugin.capabilities)
            if (cap && cap->name() == capability_name && (type == PluginCapabilityType::Unknown || cap->type() == type)) {
                out = plugin.descriptor;
                return true;
            }
    return false;
}

std::string PluginManager::get_storage_dir(const std::string& plugin_key) const
{
    namespace fs = boost::filesystem;
    PluginDescriptor descriptor;
    if (!try_get_plugin_descriptor(plugin_key, descriptor))
        throw std::runtime_error("The current plugin is not registered");
    fs::path dir = fs::path(get_orca_plugins_dir()) / PLUGIN_DATA_DIR;
    if (descriptor.is_cloud_plugin()) {
        std::string user_id;
        {
            std::lock_guard<std::mutex> lock(m_mutex);
            user_id = m_cloud_user_id;
        }
        if (user_id.empty())
            throw std::runtime_error("Cloud plugin storage is unavailable without a logged-in user");
        if (!is_valid_plugin_id(plugin_key))
            throw std::runtime_error("The current cloud plugin key is not a valid folder name");
        dir = dir / PLUGIN_SUBSCRIBED_DIR / user_id / plugin_key;
    } else {
        dir /= plugin_key;
    }
    fs::create_directories(dir);
    return dir.string();
}

std::vector<std::shared_ptr<PluginCapabilityInterface>> PluginManager::get_plugin_capabilities(const std::string& plugin_key,
                                                                                               PluginCapabilityType type,
                                                                                               bool only_enabled) const
{
    std::lock_guard<std::mutex>                             lock(m_mutex);
    std::vector<std::shared_ptr<PluginCapabilityInterface>> out;
    for (const Plugin& plugin : m_plugins) {
        if (!plugin_key.empty() && plugin.descriptor.plugin_key != plugin_key)
            continue;
        for (const auto& cap : plugin.capabilities)
            if (cap && (type == PluginCapabilityType::Unknown || cap->type() == type) && (!only_enabled || cap->is_enabled()))
                out.push_back(cap);
    }
    return out;
}

std::shared_ptr<PluginCapabilityInterface> PluginManager::get_plugin_capability(const PluginCapabilityId& id, bool only_enabled) const
{
    for (auto& cap : get_plugin_capabilities(id.plugin_key, id.type, only_enabled))
        if (cap->name() == id.name)
            return cap;
    return nullptr;
}

std::shared_ptr<PluginCapabilityInterface> PluginManager::get_plugin_capability(const std::string& capability_name,
                                                                                PluginCapabilityType type,
                                                                                bool only_enabled) const
{
    for (auto& cap : get_plugin_capabilities({}, type, only_enabled))
        if (cap->name() == capability_name)
            return cap;
    return nullptr;
}

void PluginManager::load_plugin(const std::string& plugin_key, bool skip_deps, std::vector<std::string> capabilities_to_enable)
{
    load_plugin_impl(plugin_key, skip_deps, capabilities_to_enable);
}

void PluginManager::load_plugin_impl(const std::string& plugin_key, bool skip_deps, const std::vector<std::string>& capabilities_to_enable)
{
    PluginDescriptor descriptor;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        const Plugin* plugin = find_plugin_locked(plugin_key);
        if (plugin == nullptr || plugin->is_loaded() || m_shutting_down.load(std::memory_order_acquire))
            return;
        descriptor = plugin->descriptor;
        m_load_in_progress.insert(plugin_key);
    }
    Plugin      loaded;
    std::string error;
    const bool  ok = plugin_loader::load(descriptor, skip_deps, capabilities_to_enable, {}, loaded, error);
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        m_load_in_progress.erase(plugin_key);
        if (Plugin* plugin = find_plugin_locked(plugin_key)) {
            if (ok) {
                *plugin = std::move(loaded);
                m_load_errors.erase(plugin_key);
            } else {
                plugin->descriptor.set_error(error);
                m_load_errors[plugin_key] = error;
            }
        }
    }
    m_load_cv.notify_all();
    if (!ok)
        BOOST_LOG_TRIVIAL(error) << "Plugin " << plugin_key << " failed to load: " << error;
}

void PluginManager::unload_all_plugins()
{
    std::vector<Plugin> plugins;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        for (Plugin& plugin : m_plugins)
            if (plugin.is_loaded()) {
                Plugin moved;
                moved.descriptor = plugin.descriptor;
                std::swap(moved, plugin);
                plugin.descriptor = moved.descriptor;
                plugins.push_back(std::move(moved));
            }
    }
    for (Plugin& plugin : plugins)
        plugin_loader::unload(plugin);
}

bool PluginManager::is_plugin_loaded(const std::string& plugin_key) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    const Plugin*               plugin = find_plugin_locked(plugin_key);
    return plugin != nullptr && plugin->is_loaded();
}

std::string PluginManager::get_plugin_load_error(const std::string& plugin_key) const
{
    std::lock_guard<std::mutex> lock(m_mutex);
    auto                        it = m_load_errors.find(plugin_key);
    return it == m_load_errors.end() ? std::string() : it->second;
}

void PluginManager::wait_for_all_plugin_loads() const
{
    std::unique_lock<std::mutex> lock(m_mutex);
    m_load_cv.wait(lock, [this] { return m_load_in_progress.empty(); });
}

bool PluginManager::wait_for_all_plugin_loads(std::chrono::milliseconds timeout) const
{
    std::unique_lock<std::mutex> lock(m_mutex);
    return m_load_cv.wait_for(lock, timeout, [this] { return m_load_in_progress.empty(); });
}

void PluginManager::set_capability_enabled(const PluginCapabilityId& id, bool enabled)
{
    if (auto cap = get_plugin_capability(id, /*only_enabled=*/false))
        cap->set_enabled(enabled);
}

bool PluginManager::install_plugin(const boost::filesystem::path& filepath, PluginDescriptor& plugin_descriptor, std::string& error)
{
    std::string cloud_user_id;
    {
        std::lock_guard<std::mutex> lock(m_mutex);
        cloud_user_id = m_cloud_user_id;
    }
    return plugin_loader::install_plugin(filepath, cloud_user_id, plugin_descriptor, error);
}

ExecutionResult PluginManager::run_script_capability(const std::string& plugin_key, const std::string& capability_name, std::string& error)
{
    auto cap = std::dynamic_pointer_cast<ScriptPluginCapability>(
        get_plugin_capability({PluginCapabilityType::Script, capability_name, plugin_key}));
    if (!cap) {
        error = "No such script capability";
        return {};
    }
    try {
        PythonGILState gil;
        if (!gil) {
            error = "Python interpreter is shutting down";
            return {};
        }
        return cap->execute();
    } catch (const std::exception& ex) {
        error = ex.what();
    }
    return {};
}

void PluginManager::dispatch_lifecycle_event(LifecycleEvent evt, const LifecycleEventContext& ctx)
{
    for (const auto& cap : get_plugin_capabilities()) {
        if (ctx.cancellation_check && ctx.cancellation_check())
            break;
        try {
            cap->on_lifecycle_event(evt, ctx);
        } catch (const std::exception& ex) {
            BOOST_LOG_TRIVIAL(warning) << "Plugin " << cap->audit_plugin_key() << "/" << cap->name()
                                       << " on_lifecycle_event threw: " << ex.what();
        }
    }
}

} // namespace Slic3r
