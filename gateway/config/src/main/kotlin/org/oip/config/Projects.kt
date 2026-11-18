package org.oip.config

/**
 * Projects (PRO-001, PRO-002, PRO-004): a project is the config scope `project/<name>`, plus one settings resource
 * `org.oip.gateway/project/<name>/Project/project` that holds its title, description, parent, and flags. The
 * settings resource lives inside the project scope, so export, import, rename, and copy carry it with every other
 * resource of the project. A project that has resources but no settings resource (one made before PRO-004) reads
 * with the defaults below.
 *
 * Settings:
 * - `title`, `description`: free text shown on the admin Projects page and the home page.
 * - `parent`: the project this one inherits from, or empty for none.
 * - `enabled`: a disabled project is not launched at `/hmi` and is left off the home page.
 * - `inheritable`: only an inheritable project may be named as a parent.
 * - `runnable`: false for a project that only exists to be inherited; it is not launched at `/hmi`.
 * - `sessionRoles`: a session of this project needs one of these roles (HMP-092); empty lets any signed-in user with
 *   `tags.read` open it.
 * - `sessionIdentityProvider`: a session of this project needs a login through this identity provider (HMP-092);
 *   empty accepts any login.
 * - `sessionLevels`, `sessionRequireAll`: a session of this project needs one of these security levels, or all of
 *   them with `sessionRequireAll` (SEC-017); empty adds no level requirement.
 *
 * Inheritance (PRO-002) resolves by resource path: a read of `<module>/<kind>/<name>` in a project looks in the
 * project itself, then its parent, then the parent's parent. The first project that stores the resource wins, so
 * a child's copy overrides its parent's, and deleting the child's copy shows the parent's again. The settings
 * resource itself is never inherited. [check] refuses a parent that does not exist, is not inheritable, is the
 * project itself, or would close a cycle, and refuses clearing `inheritable` while a project still names it.
 *
 */
class ProjectService(private val store: FileConfigStore) {
    data class Settings(
        val title: String = "",
        val description: String = "",
        val parent: String = "",
        val enabled: Boolean = true,
        val inheritable: Boolean = false,
        val runnable: Boolean = true,
        val sessionRoles: List<String> = emptyList(),
        val sessionIdentityProvider: String = "",
        /** Security levels a session needs (SEC-017): any one of them, or every one with [sessionRequireAll]. */
        val sessionLevels: List<String> = emptyList(),
        val sessionRequireAll: Boolean = false,
        /** Tag provider new references start from. Empty is stored as `default`. */
        val defaultTagProvider: String = "default",
        /** Named database connection for this project. Empty means none is chosen. */
        val defaultDatabase: String = "",
        /** Session theme name. Empty means daylight. */
        val sessionTheme: String = "",
        /** Session locale. Empty means en-US. */
        val sessionLocale: String = "",
        /** Touchscreen mode (HMV-126): session inputs open an on-screen keyboard. */
        val touchscreenMode: Boolean = false,
        /**
         * Public HMI. Off by default. When on, opening `/hmi/<project>/` with no sign-in issues a session that can
         * read and write tags for that HMI only. `/admin` still requires an administrator.
         */
        val publicHmi: Boolean = false,
        /**
         * Required Designer roles (SEC-020): the caller needs one of the listed roles to view the project in the
         * Designer, save it, delete it, or protect resources and change protected ones. Empty lets every Designer user.
         */
        val designerViewRoles: List<String> = emptyList(),
        val designerSaveRoles: List<String> = emptyList(),
        val designerDeleteRoles: List<String> = emptyList(),
        val designerProtectRoles: List<String> = emptyList(),
        /** Protected resources (SEC-020) of this project, as `module/kind/name`. */
        val protectedResources: List<String> = emptyList(),
    ) {
        fun toSpec(): Map<String, Any?> = linkedMapOf(
            "title" to title,
            "description" to description,
            "parent" to parent,
            "enabled" to enabled,
            "inheritable" to inheritable,
            "runnable" to runnable,
            "sessionRoles" to sessionRoles,
            "sessionIdentityProvider" to sessionIdentityProvider,
            "sessionLevels" to sessionLevels,
            "sessionRequireAll" to sessionRequireAll,
            "defaultTagProvider" to defaultTagProvider.ifEmpty { "default" },
            "defaultDatabase" to defaultDatabase,
            "sessionTheme" to sessionTheme,
            "sessionLocale" to sessionLocale,
            "touchscreenMode" to touchscreenMode,
            "publicHmi" to publicHmi,
            "designerViewRoles" to designerViewRoles,
            "designerSaveRoles" to designerSaveRoles,
            "designerDeleteRoles" to designerDeleteRoles,
            "designerProtectRoles" to designerProtectRoles,
            "protectedResources" to protectedResources,
        )

        fun validate() {
            if (!PROVIDER.matches(defaultTagProvider.ifEmpty { "default" }))
                throw IllegalArgumentException("defaultTagProvider must be a provider name")
            if (defaultDatabase.isNotEmpty() && !DATABASE.matches(defaultDatabase))
                throw IllegalArgumentException("defaultDatabase must be a connection name")
            if (sessionTheme.isNotEmpty() && !THEME.matches(sessionTheme))
                throw IllegalArgumentException("sessionTheme must be a theme name")
            if (sessionLocale.isNotEmpty() && !LOCALE.matches(sessionLocale))
                throw IllegalArgumentException("sessionLocale must be a language tag such as en-US")
            sessionLevels.firstOrNull { !LEVEL.matches(it) }?.let { throw IllegalArgumentException("sessionLevels: '$it' is not a security level path") }
            protectedResources.firstOrNull { !resourcePath(it) }?.let {
                throw IllegalArgumentException("protectedResources: '$it' is not a module/kind/name resource path")
            }
            if (protectedResources.size > MAX_PROTECTED) throw IllegalArgumentException("protectedResources: at most $MAX_PROTECTED")
        }

        /** The required roles of a Designer [action], empty when every Designer user may take it. */
        fun designerRoles(action: DesignerAction): List<String> = when (action) {
            DesignerAction.VIEW -> designerViewRoles
            DesignerAction.SAVE -> designerSaveRoles
            DesignerAction.DELETE -> designerDeleteRoles
            DesignerAction.PROTECT -> designerProtectRoles
        }

        /** Enabled and runnable: the project opens at `/hmi` and is offered on the home page. */
        val launchable: Boolean get() = enabled && runnable

        companion object {
            val FIELDS = setOf(
                "title", "description", "parent", "enabled", "inheritable", "runnable", "sessionRoles", "sessionIdentityProvider",
                "sessionLevels", "sessionRequireAll", "defaultTagProvider", "defaultDatabase", "sessionTheme", "sessionLocale", "touchscreenMode", "publicHmi",
                "designerViewRoles", "designerSaveRoles", "designerDeleteRoles", "designerProtectRoles", "protectedResources",
            )
            /** The fields that set who may do what in the Designer (SEC-020). */
            val DESIGNER_ROLE_FIELDS = setOf("designerViewRoles", "designerSaveRoles", "designerDeleteRoles", "designerProtectRoles")
            const val MAX_PROTECTED = 10_000

            /** Whether [path] is `module/kind/name`, each part as [ResourceId] spells it. */
            fun resourcePath(path: String): Boolean {
                val parts = path.split('/')
                return parts.size == 3 && Names.MODULE.matches(parts[0]) && Names.KIND.matches(parts[1]) && Names.NAME.matches(parts[2])
            }
            private val PROVIDER = Regex("^[A-Za-z_][A-Za-z0-9_]{0,62}$")
            private val DATABASE = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?$")
            private val THEME = Regex("^[A-Za-z][A-Za-z0-9_-]{0,62}$")
            private val LOCALE = Regex("^[A-Za-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$")
            /** A security level path (SEC-017), as `org.oip.security` SecurityLevels.PATH spells it. */
            private val LEVEL = Regex("^([A-Za-z0-9][A-Za-z0-9 _.-]{0,62}[A-Za-z0-9_.-]|[A-Za-z0-9])(/([A-Za-z0-9][A-Za-z0-9 _.-]{0,62}[A-Za-z0-9_.-]|[A-Za-z0-9])){0,7}$")

            /** Role names as stored: trimmed, without blanks or repeats, in the order given. */
            fun roleList(value: Any?): List<String>? {
                if (value !is List<*>) return null
                if (value.any { it !is String }) return null
                return value.map { (it as String).trim() }.filter { it.isNotEmpty() }.distinct()
            }

            fun fromSpec(spec: Map<String, Any?>?): Settings {
                if (spec == null) return Settings()
                return Settings(
                    title = spec["title"] as? String ?: "",
                    description = spec["description"] as? String ?: "",
                    parent = spec["parent"] as? String ?: "",
                    enabled = spec["enabled"] as? Boolean ?: true,
                    inheritable = spec["inheritable"] as? Boolean ?: false,
                    runnable = spec["runnable"] as? Boolean ?: true,
                    sessionRoles = roleList(spec["sessionRoles"]) ?: emptyList(),
                    sessionIdentityProvider = spec["sessionIdentityProvider"] as? String ?: "",
                    sessionLevels = roleList(spec["sessionLevels"]) ?: emptyList(),
                    sessionRequireAll = spec["sessionRequireAll"] as? Boolean ?: false,
                    defaultTagProvider = (spec["defaultTagProvider"] as? String)?.ifEmpty { null } ?: "default",
                    defaultDatabase = spec["defaultDatabase"] as? String ?: "",
                    sessionTheme = spec["sessionTheme"] as? String ?: "",
                    sessionLocale = spec["sessionLocale"] as? String ?: "",
                    touchscreenMode = spec["touchscreenMode"] as? Boolean ?: false,
                    publicHmi = spec["publicHmi"] as? Boolean ?: false,
                    designerViewRoles = roleList(spec["designerViewRoles"]) ?: emptyList(),
                    designerSaveRoles = roleList(spec["designerSaveRoles"]) ?: emptyList(),
                    designerDeleteRoles = roleList(spec["designerDeleteRoles"]) ?: emptyList(),
                    designerProtectRoles = roleList(spec["designerProtectRoles"]) ?: emptyList(),
                    protectedResources = roleList(spec["protectedResources"]) ?: emptyList(),
                )
            }
        }
    }

    data class Info(
        val name: String,
        val settings: Settings,
        /** Resources stored in the project scope, the settings resource included. */
        val resources: Int,
        /** Version of the settings resource; 0 when the project has none. */
        val version: Long,
        /** Projects that name this one as their parent. */
        val children: List<String>,
        /** The project and its ancestors, child first. Ends early at a parent that no longer exists. */
        val chain: List<String>,
    ) {
        fun toMap(): Map<String, Any?> = linkedMapOf(
            "name" to name,
            "title" to settings.title,
            "description" to settings.description,
            "parent" to settings.parent.ifEmpty { null },
            "enabled" to settings.enabled,
            "inheritable" to settings.inheritable,
            "runnable" to settings.runnable,
            "launchable" to settings.launchable,
            "sessionRoles" to settings.sessionRoles,
            "sessionIdentityProvider" to settings.sessionIdentityProvider,
            "sessionLevels" to settings.sessionLevels,
            "sessionRequireAll" to settings.sessionRequireAll,
            "defaultTagProvider" to settings.defaultTagProvider,
            "defaultDatabase" to settings.defaultDatabase,
            "sessionTheme" to settings.sessionTheme,
            "sessionLocale" to settings.sessionLocale,
            "touchscreenMode" to settings.touchscreenMode,
            "publicHmi" to settings.publicHmi,
            "designerViewRoles" to settings.designerViewRoles,
            "designerSaveRoles" to settings.designerSaveRoles,
            "designerDeleteRoles" to settings.designerDeleteRoles,
            "designerProtectRoles" to settings.designerProtectRoles,
            "protectedResources" to settings.protectedResources,
            "resources" to resources,
            "version" to version,
            "children" to children,
            "inheritanceChain" to chain,
        )
    }

    /** A resource read through inheritance: [project] is where it is stored (the asked project, or an ancestor). */
    data class Resolved(val id: ResourceId, val resource: Resource, val project: String)

    /** Install [check] as a write guard on the store. */
    fun attach(): AutoCloseable = store.guard { writes -> check(writes) }

    fun exists(name: String): Boolean = store.list(scope = Scope.project(name)).isNotEmpty()

    fun settings(name: String): Settings = Settings.fromSpec(store.get(id(name))?.spec)

    fun names(): List<String> = projectScopes().keys.sorted()

    fun list(): List<Info> {
        val scopes = projectScopes()
        val specs = specs()
        return scopes.keys.sorted().map { info(it, scopes[it] ?: 0, specs) }
    }

    fun info(name: String): Info? {
        val count = store.list(scope = Scope.project(name)).size
        if (count == 0) return null
        return info(name, count, specs())
    }

    private fun info(name: String, count: Int, specs: Map<String, Settings>): Info {
        val stored = store.get(id(name))
        val settings = specs[name] ?: Settings()
        val children = specs.filter { it.value.parent == name }.keys.sorted()
        return Info(name, settings, count, stored?.metadata?.version ?: 0L, children, chain(name, specs))
    }

    /** [name] and its ancestors, child first. Stops at a missing parent or (defensively) a repeated name. */
    fun chain(name: String): List<String> = chain(name, specs())

    private fun chain(name: String, specs: Map<String, Settings>): List<String> {
        val out = mutableListOf(name)
        var next = specs[name]?.parent.orEmpty()
        while (next.isNotEmpty() && next !in out && specs.containsKey(next)) {
            out += next
            next = specs[next]?.parent.orEmpty()
        }
        return out
    }

    /** [id] (a project-scope resource) read child first through the parent chain, or null when no project stores it. */
    fun resolve(id: ResourceId): Resolved? {
        val project = requireProject(id.scope)
        if (isSettings(id.module, id.kind)) return store.get(id)?.let { Resolved(id, it, project) }
        for (p in chain(project)) {
            val at = id.copy(scope = Scope.project(p))
            store.get(at)?.let { return Resolved(at, it, p) }
        }
        return null
    }

    /**
     * Every [module]/[kind] resource [project] sees: its own, plus each ancestor's that no nearer project overrides.
     * A null [module] or [kind] matches all. Sorted by module, kind, name.
     */
    fun resolveList(project: String, module: String? = null, kind: String? = null): List<Resolved> {
        val seen = HashMap<Triple<String, String, String>, Resolved>()
        for ((depth, p) in chain(project).withIndex()) {
            for (stored in store.list(module, Scope.project(p), kind)) {
                if (depth > 0 && isSettings(stored.id.module, stored.id.kind)) continue
                val key = Triple(stored.id.module, stored.id.kind, stored.id.name)
                if (key !in seen) seen[key] = Resolved(stored.id, stored.resource, p)
            }
        }
        return seen.values.sortedWith(compareBy({ it.id.module }, { it.id.kind }, { it.id.name }))
    }

    /** Create [name] with [settings]. 409 when the project already has resources. */
    fun create(name: String, settings: Settings, actor: String): Info {
        val scope = Scope.project(name)
        if (exists(name)) throw ConflictException("project '$name' already exists")
        settings.validate()
        store.put(scope, Resource(KIND, Metadata(RESOURCE, GATEWAY_MODULE), settings.toSpec()), actor)
        return info(name)!!
    }

    /**
     * Change some settings of [name]. [changes] holds only the fields to change (see [Settings.FIELDS]); a field
     * of the wrong type is refused. [expectedVersion] is the settings resource version the caller read, or null.
     */
    fun update(name: String, changes: Map<String, Any?>, expectedVersion: Long?, actor: String): Info {
        if (!exists(name)) throw NoSuchElementException("project '$name' not found")
        val unknown = changes.keys - Settings.FIELDS
        require(unknown.isEmpty()) { "unknown project settings: ${unknown.sorted().joinToString()}" }
        val current = store.get(id(name))
        val version = current?.metadata?.version ?: 0L
        if (expectedVersion != null && expectedVersion != version)
            throw ConflictException("project '$name' settings version conflict: expected $expectedVersion, stored $version")
        val base = Settings.fromSpec(current?.spec).toSpec()
        val spec = LinkedHashMap(base)
        for ((key, value) in changes) {
            val expected = base[key]
            when {
                expected is String && value is String -> spec[key] = value
                expected is Boolean && value is Boolean -> spec[key] = value
                expected is List<*> && Settings.roleList(value) != null -> spec[key] = Settings.roleList(value)
                (key == "parent" || key == "sessionIdentityProvider") && value == null -> spec[key] = ""
                else -> throw IllegalArgumentException(
                    "$key must be ${when (expected) { is Boolean -> "a boolean"; is List<*> -> "a list of role names"; else -> "a string" }}",
                )
            }
        }
        Settings.fromSpec(spec).validate()
        store.put(Scope.project(name), Resource(KIND, Metadata(RESOURCE, GATEWAY_MODULE, version = version), spec), actor)
        return info(name)!!
    }

    /**
     * Copy every resource of [from] into the new project [to]. The copy keeps [from]'s settings, including its
     * parent; [title] replaces the copied title when given. Projects that inherit from [from] are not changed.
     */
    fun copy(from: String, to: String, actor: String, title: String? = null): Info {
        val source = sourceResources(from, to)
        val copies = source.map { stored ->
            val r = stored.resource
            val spec = if (title != null && isSettings(r.metadata.module, r.kind)) r.spec + ("title" to title) else r.spec
            Resource(r.kind, Metadata(r.metadata.name, r.metadata.module, r.metadata.labels), spec, r.apiVersion)
        }
        writeAll(Scope.project(to), copies, actor)
        return info(to)!!
    }

    /**
     * Move every resource of [from] to [to], point projects that inherited from [from] at [to], then remove [from].
     * Resource versions restart at 1 under the new name.
     */
    fun rename(from: String, to: String, actor: String): Info {
        val source = sourceResources(from, to)
        writeAll(Scope.project(to), source.map { stored ->
            val r = stored.resource
            Resource(r.kind, Metadata(r.metadata.name, r.metadata.module, r.metadata.labels), r.spec, r.apiVersion)
        }, actor)
        for ((child, settings) in specs()) {
            if (settings.parent != from) continue
            val current = store.get(id(child)) ?: continue
            store.put(Scope.project(child), current.copy(spec = settings.copy(parent = to).toSpec()), actor)
        }
        for (stored in source) store.delete(stored.id)
        return info(to)!!
    }

    /** Remove every resource of [name] and nothing else. 409 while another project names it as parent. */
    fun delete(name: String) {
        val resources = store.list(scope = Scope.project(name))
        if (resources.isEmpty()) throw NoSuchElementException("project '$name' not found")
        val children = specs().filter { it.value.parent == name }.keys.sorted()
        if (children.isNotEmpty())
            throw ConflictException("project '$name' is the parent of ${children.joinToString()}; change or delete them first")
        // Settings last: a failure part way leaves a project that still reads as itself.
        for (stored in resources.sortedBy { if (it.id == id(name)) 1 else 0 }) store.delete(stored.id)
    }

    /** Resources of [from] for a copy or rename to [to]. */
    fun sourceResources(from: String, to: String): List<StoredResource> {
        Scope.project(to)
        require(from != to) { "the new project name must differ from '$from'" }
        val source = store.list(scope = Scope.project(from))
        if (source.isEmpty()) throw NoSuchElementException("project '$from' not found")
        if (exists(to)) throw ConflictException("project '$to' already exists")
        return source
    }

    private fun writeAll(scope: Scope, resources: List<Resource>, actor: String) {
        // Settings first, so a parent named by the copied settings is checked before the rest is written.
        val (settings, rest) = resources.partition { isSettings(it.metadata.module, it.kind) }
        for (r in settings) store.put(scope, r, actor)
        if (rest.isNotEmpty()) store.putAll(scope, rest, actor)
    }

    /**
     * Refuse a settings write whose parent is missing, not inheritable, the project itself, or part of a cycle,
     * and a write that makes a project non-inheritable while another project inherits from it. [writes] are seen
     * together, so a batch may create a parent and its child at once.
     */
    fun check(writes: List<Pair<ResourceId, Resource>>) {
        val pending = writes.filter { (id, _) -> isSettings(id.module, id.kind) }
        if (pending.isEmpty()) return
        val specs = HashMap(specs())
        for ((id, r) in pending) {
            val project = requireProject(id.scope)
            if (id.name != RESOURCE) throw ValidationException(id.toString(), listOf("the project settings resource must be named '$RESOURCE'"))
            specs[project] = Settings.fromSpec(r.spec)
        }
        for ((id, _) in pending) {
            val project = id.scope.qualifier!!
            val settings = specs.getValue(project)
            val errors = mutableListOf<String>()
            val parent = settings.parent
            if (parent.isNotEmpty()) {
                val parentSettings = specs[parent]
                when {
                    parent == project -> errors += "a project cannot inherit from itself"
                    !runCatching { Scope.project(parent) }.isSuccess -> errors += "parent '$parent' is not a project name"
                    parentSettings == null -> errors += "parent project '$parent' does not exist"
                    !parentSettings.inheritable -> errors += "parent project '$parent' is not inheritable"
                    else -> {
                        val path = mutableListOf(project)
                        var next = parent
                        while (next.isNotEmpty()) {
                            if (next in path) {
                                errors += "inheritance cycle: ${(path + next).joinToString(" -> ")}"
                                break
                            }
                            path += next
                            next = specs[next]?.parent.orEmpty()
                        }
                    }
                }
            }
            if (!settings.inheritable) {
                val children = specs.filter { it.key != project && it.value.parent == project }.keys.sorted()
                if (children.isNotEmpty()) errors += "project is the parent of ${children.joinToString()}, so it must stay inheritable"
            }
            if (errors.isNotEmpty()) throw ValidationException(id.toString(), errors)
        }
    }

    private fun specs(): Map<String, Settings> =
        store.list(GATEWAY_MODULE, null, KIND)
            .filter { it.id.scope.type == "project" && it.id.name == RESOURCE }
            .associate { it.id.scope.qualifier!! to Settings.fromSpec(it.resource.spec) }

    private fun projectScopes(): Map<String, Int> {
        val counts = HashMap<String, Int>()
        for (stored in store.list()) {
            if (stored.id.scope.type == "project") counts.merge(stored.id.scope.qualifier!!, 1, Int::plus)
        }
        return counts
    }

    private fun requireProject(scope: Scope): String {
        require(scope.type == "project") { "not a project scope: $scope" }
        return scope.qualifier!!
    }

    companion object {
        const val KIND = "Project"
        const val RESOURCE = "project"

        fun id(name: String) = ResourceId(GATEWAY_MODULE, Scope.project(name), KIND, RESOURCE)

        fun isSettings(module: String, kind: String) = module == GATEWAY_MODULE && kind == KIND

        fun schema(): Map<String, Any?> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "title" to mapOf("type" to "string", "maxLength" to 100, "default" to ""),
                "description" to mapOf("type" to "string", "maxLength" to 2000, "default" to ""),
                "parent" to mapOf("type" to "string", "maxLength" to 63, "pattern" to "^(|${Names.NAME.pattern.removePrefix("^").removeSuffix("$")})$", "default" to ""),
                "enabled" to mapOf("type" to "boolean", "default" to true),
                "inheritable" to mapOf("type" to "boolean", "default" to false),
                "runnable" to mapOf("type" to "boolean", "default" to true),
                "sessionRoles" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 64),
                    "maxItems" to 64,
                    "default" to emptyList<String>(),
                ),
                "sessionIdentityProvider" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
                "sessionLevels" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 520),
                    "maxItems" to 64,
                    "default" to emptyList<String>(),
                ),
                "sessionRequireAll" to mapOf("type" to "boolean", "default" to false),
                "defaultTagProvider" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 63, "default" to "default"),
                "defaultDatabase" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
                "sessionTheme" to mapOf("type" to "string", "maxLength" to 63, "default" to ""),
                "sessionLocale" to mapOf("type" to "string", "maxLength" to 20, "default" to ""),
                "touchscreenMode" to mapOf("type" to "boolean", "default" to false),
                "publicHmi" to mapOf("type" to "boolean", "default" to false),
                "designerViewRoles" to roleArray(),
                "designerSaveRoles" to roleArray(),
                "designerDeleteRoles" to roleArray(),
                "designerProtectRoles" to roleArray(),
                "protectedResources" to mapOf(
                    "type" to "array",
                    "items" to mapOf("type" to "string", "minLength" to 5, "maxLength" to 260),
                    "maxItems" to Settings.MAX_PROTECTED,
                    "default" to emptyList<String>(),
                ),
            ),
        )

        private fun roleArray() = mapOf(
            "type" to "array",
            "items" to mapOf("type" to "string", "minLength" to 1, "maxLength" to 64),
            "maxItems" to 64,
            "default" to emptyList<String>(),
        )

        fun register(registry: SchemaRegistry) = registry.register(GATEWAY_MODULE, KIND, schema())
    }
}

/** What a Designer user does with a project (SEC-020); each has its own required roles in the project settings. */
enum class DesignerAction(val label: String) { VIEW("view"), SAVE("save"), DELETE("delete"), PROTECT("protect resources") }
