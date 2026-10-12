// Android implementation of Slic3r::PluginAuditManager (src/slic3r/plugin/PluginAuditManager.hpp).
//
// Upstream installs a CPython audit hook that asks the user (wxWidgets dialogs) before a plugin
// touches files outside its roots, opens sockets or starts processes. That hook is not installed
// here yet: plugins run with the app's own Android sandbox only. What is kept is the bookkeeping
// the plugin host relies on: which plugin/capability is running on this thread (plugin.storage(),
// the trampolines) and the scoped roots granted for a call.
#include "PluginAuditManager.hpp"

#include <boost/filesystem.hpp>
#include <boost/log/trivial.hpp>

namespace Slic3r {

bool is_inside_allowed_root(const boost::filesystem::path& candidate, const boost::filesystem::path& allowed_root)
{
    namespace fs = boost::filesystem;
    boost::system::error_code ec;
    fs::path c = fs::weakly_canonical(candidate, ec);
    if (ec)
        c = fs::absolute(candidate).lexically_normal();
    fs::path r = fs::weakly_canonical(allowed_root, ec);
    if (ec)
        r = fs::absolute(allowed_root).lexically_normal();
    auto ci = c.begin();
    auto ri = r.begin();
    while (ri != r.end() && ci != c.end() && *ri == *ci) {
        ++ri;
        ++ci;
    }
    if (ri != r.end())
        return false;
    for (; ci != c.end(); ++ci)
        if (*ci == "..")
            return false;
    return true;
}

thread_local std::string              PluginAuditManager::m_current_plugin_key;
thread_local std::string              PluginAuditManager::m_current_capability_name;
thread_local std::vector<AllowedRoot> PluginAuditManager::m_scoped_allowed_roots;
thread_local bool                     PluginAuditManager::m_audit_denial_pending = false;
thread_local bool                     PluginAuditManager::m_has_last_violation   = false;
thread_local AuditViolation           PluginAuditManager::m_last_violation;

ScopedPluginAuditContext::ScopedPluginAuditContext(const std::string& plugin_key, const std::string& capability_name)
    : m_previous_id(PluginAuditManager::instance().current_plugin())
    , m_previous_capability(PluginAuditManager::instance().current_capability())
    , m_previous_scoped_roots(PluginAuditManager::m_scoped_allowed_roots)
{
    PluginAuditManager::instance().set_current_plugin(plugin_key);
    PluginAuditManager::instance().set_current_capability(capability_name);
    PluginAuditManager::m_scoped_allowed_roots.clear();
}

ScopedPluginAuditContext::~ScopedPluginAuditContext()
{
    PluginAuditManager::instance().set_current_plugin(m_previous_id);
    PluginAuditManager::instance().set_current_capability(m_previous_capability);
    PluginAuditManager::m_scoped_allowed_roots = std::move(m_previous_scoped_roots);
}

PluginAuditManager& PluginAuditManager::instance()
{
    static PluginAuditManager mgr;
    return mgr;
}

void PluginAuditManager::install_hook() { BOOST_LOG_TRIVIAL(info) << "[AUDIT] Python audit hook not installed on Android"; }

void        PluginAuditManager::set_current_plugin(const std::string& plugin_key) { m_current_plugin_key = plugin_key; }
std::string PluginAuditManager::current_plugin() const { return m_current_plugin_key; }
void        PluginAuditManager::clear_current_plugin() { m_current_plugin_key.clear(); }
void        PluginAuditManager::set_current_capability(const std::string& name) { m_current_capability_name = name; }
std::string PluginAuditManager::current_capability() const { return m_current_capability_name; }
void        PluginAuditManager::clear_current_capability() { m_current_capability_name.clear(); }

void PluginAuditManager::add_global_allowed_root(const boost::filesystem::path& root, bool allow_write)
{
    if (root.empty())
        return;
    std::lock_guard<std::mutex> lock(m_mutex);
    m_global_allowed_roots.push_back({root, allow_write});
}

void PluginAuditManager::add_scoped_allowed_root(const boost::filesystem::path& root, bool allow_write)
{
    if (!root.empty())
        m_scoped_allowed_roots.push_back({root, allow_write});
}

bool PluginAuditManager::request_filesystem_read_permissions(const std::string& plugin_key, const std::vector<std::string>& paths)
{
    for (const std::string& path : paths)
        BOOST_LOG_TRIVIAL(info) << "[AUDIT] Plugin " << plugin_key << " requested read access to " << path;
    return true;
}

void PluginAuditManager::report_violation(const AuditViolation& violation)
{
    m_last_violation       = violation;
    m_has_last_violation   = true;
    m_audit_denial_pending = true;
}

bool PluginAuditManager::audit_denial_pending() const { return m_audit_denial_pending; }
void PluginAuditManager::clear_audit_denial() { m_audit_denial_pending = false; }
void PluginAuditManager::clear_last_violation() { m_has_last_violation = false; }

bool PluginAuditManager::last_violation(AuditViolation& violation) const
{
    if (m_has_last_violation)
        violation = m_last_violation;
    return m_has_last_violation;
}

} // namespace Slic3r
