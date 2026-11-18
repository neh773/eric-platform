package org.oip.security

import java.util.Hashtable
import javax.naming.AuthenticationException
import javax.naming.CommunicationException
import javax.naming.Context
import javax.naming.NameNotFoundException
import javax.naming.NamingEnumeration
import javax.naming.NamingException
import javax.naming.PartialResultException
import javax.naming.SizeLimitExceededException
import javax.naming.directory.Attributes
import javax.naming.directory.SearchControls
import javax.naming.directory.SearchResult
import javax.naming.ldap.InitialLdapContext
import javax.naming.ldap.LdapContext
import javax.naming.ldap.LdapName
import javax.naming.ldap.StartTlsRequest
import javax.naming.ldap.StartTlsResponse
import javax.net.ssl.SSLException

/** A directory user found at login or by a lookup. Never carries a password. */
data class DirectoryUser(
    val dn: String,
    /** The user's name as the directory spells it ([UserSource.usernameAttribute]), or the entered name. */
    val username: String,
    val displayName: String,
    val email: String,
    /** Group names: [UserSource.groupNameAttribute] of a group found by search, or the first RDN value of a group DN. */
    val groupNames: List<String>,
    /** Group DNs, nested groups included when [UserSource.nestedGroups] is on. */
    val groupDns: List<String>,
)

/** What the directory said about one login or lookup. */
sealed interface DirectoryOutcome {
    data class Found(val user: DirectoryUser) : DirectoryOutcome
    /** Wrong password, an unknown user, or a name that matched more than one entry: one answer for all three. */
    data object BadCredentials : DirectoryOutcome
    /** A lookup (no password) found no entry. */
    data object NotFound : DirectoryOutcome
    /** The directory could not be asked: not reachable, TLS refused, or the service account bind failed. */
    data class Unavailable(val reason: String) : DirectoryOutcome
}

/**
 * The LDAP and Active Directory client of a user source (SEC-007), over the JDK's JNDI LDAP provider.
 *
 * Transport: `ldaps://` URLs use TLS from the first byte; `ldap://` URLs with [UserSource.startTls] upgrade the
 * connection with the StartTLS extended operation before any bind, and every bind (service account and user) runs on
 * that upgraded connection. Both check the server's certificate against the gateway's outbound trust (SEC-022) and its
 * host name. Plain `ldap://` is refused unless the resource says [UserSource.allowPlaintext].
 *
 * Login: with [UserSource.bindDn], the service account binds and searches [UserSource.userBaseDn] with
 * [UserSource.userFilter]; exactly one entry must match, and a second bind as that entry checks the password. Without
 * a service account, [UserSource.userBindPattern] names the bind DN (or `user@domain` name) directly and the entry is
 * then read with the user's own bind. An empty password is refused before anything is sent, because LDAP treats a
 * simple bind with no password as an anonymous bind that succeeds.
 *
 * Groups: the user entry's [UserSource.groupAttribute] (`memberOf`) and, with [UserSource.groupBaseDn], a search with
 * [UserSource.groupFilter]. With [UserSource.nestedGroups], the groups of each group are followed the same way up to
 * [UserSource.nestedGroupDepth] levels. Active Directory can also answer nested membership in one search with the
 * in-chain matching rule (`(member:1.2.840.113556.1.4.1941:={dn})` as the group filter).
 *
 * Values put into a filter are escaped as RFC 4515 says, and values put into a DN as RFC 4514 says. The bind password
 * is held only for the call and is wiped afterwards; no message or exception text this class builds carries it.
 *
 * Doc source: FEATURES SEC-007.
 */
class LdapUserSource(private val source: UserSource, private val bindPassword: () -> CharArray?) {

    /** Check [password] for [username]. [password] is not wiped here; the caller owns it. */
    fun authenticate(username: String, password: CharArray): DirectoryOutcome {
        if (password.isEmpty() || !UserSource.USERNAME.matches(username)) return DirectoryOutcome.BadCredentials
        return try {
            if (source.bindDn.isNotBlank()) viaServiceAccount(username, password) else viaBindPattern(username, password)
        } catch (e: NamingException) {
            DirectoryOutcome.Unavailable(reason(e))
        }
    }

    /** Find [username] without a password, through the service account. Needs [UserSource.bindDn]. */
    fun lookup(username: String): DirectoryOutcome {
        if (!UserSource.USERNAME.matches(username)) return DirectoryOutcome.NotFound
        if (source.bindDn.isBlank()) return DirectoryOutcome.Unavailable("a lookup without a password needs a service account (bindDn)")
        return try {
            serviceContext().use { service ->
                when (val found = findUser(service.ctx, username)) {
                    is Search.One -> DirectoryOutcome.Found(user(service.ctx, found.result, username))
                    Search.None, Search.Many -> DirectoryOutcome.NotFound
                }
            }
        } catch (e: NamingException) {
            DirectoryOutcome.Unavailable(reason(e))
        }
    }

    private fun viaServiceAccount(username: String, password: CharArray): DirectoryOutcome =
        serviceContext().use { service ->
            val entry = when (val found = findUser(service.ctx, username)) {
                is Search.One -> found.result
                Search.None, Search.Many -> return DirectoryOutcome.BadCredentials
            }
            val dn = entry.nameInNamespace
            try {
                open(dn, password).close()
            } catch (_: AuthenticationException) {
                return DirectoryOutcome.BadCredentials
            }
            DirectoryOutcome.Found(user(service.ctx, entry, username))
        }

    private fun viaBindPattern(username: String, password: CharArray): DirectoryOutcome {
        val bindName = source.userBindPattern.replace("{username}", escapeDnValue(username))
        val bound = try {
            open(bindName, password)
        } catch (_: AuthenticationException) {
            return DirectoryOutcome.BadCredentials
        }
        return bound.use { user ->
            if (source.userBaseDn.isNotBlank()) {
                when (val found = findUser(user.ctx, username)) {
                    is Search.One -> DirectoryOutcome.Found(user(user.ctx, found.result, username))
                    // The bind worked but the entry is not under the base: no entry, so no groups and no name.
                    Search.None, Search.Many -> DirectoryOutcome.BadCredentials
                }
            } else {
                val dn = runCatching { LdapName(bindName).toString() }.getOrNull() ?: return DirectoryOutcome.BadCredentials
                val attrs = user.ctx.getAttributes(LdapName(dn), userAttributeIds())
                DirectoryOutcome.Found(user(user.ctx, dn, attrs, username))
            }
        }
    }

    private sealed interface Search {
        data class One(val result: SearchResult) : Search
        data object None : Search
        data object Many : Search
    }

    private fun findUser(ctx: LdapContext, username: String): Search {
        val filter = source.userFilter.replace("{username}", escapeFilterValue(username))
        val controls = SearchControls().apply {
            searchScope = SearchControls.SUBTREE_SCOPE
            countLimit = 2L
            timeLimit = source.readTimeoutMillis
            returningAttributes = userAttributeIds()
        }
        val results = try {
            collect(ctx.search(LdapName(source.userBaseDn), filter, controls), 2)
        } catch (_: SizeLimitExceededException) {
            return Search.Many
        } catch (_: NameNotFoundException) {
            return Search.None
        }
        return when (results.size) {
            0 -> Search.None
            1 -> Search.One(results.single())
            else -> Search.Many
        }
    }

    private fun userAttributeIds(): Array<String> = listOf(
        source.usernameAttribute, source.displayNameAttribute, source.emailAttribute, source.groupAttribute,
    ).filter { it.isNotBlank() }.distinct().toTypedArray()

    private fun user(ctx: LdapContext, entry: SearchResult, entered: String): DirectoryUser =
        user(ctx, entry.nameInNamespace, entry.attributes, entered)

    private fun user(ctx: LdapContext, dn: String, attrs: Attributes, entered: String): DirectoryUser {
        val groups = groups(ctx, dn, values(attrs, source.groupAttribute), entered)
        return DirectoryUser(
            dn = dn,
            username = values(attrs, source.usernameAttribute).firstOrNull()?.takeIf { it.equals(entered, ignoreCase = true) } ?: entered,
            displayName = values(attrs, source.displayNameAttribute).firstOrNull().orEmpty(),
            email = values(attrs, source.emailAttribute).firstOrNull().orEmpty(),
            groupNames = groups.values.distinct(),
            groupDns = groups.keys.toList(),
        )
    }

    /** Group DN to group name, the user's own groups first, then each nested level in turn. */
    private fun groups(ctx: LdapContext, userDn: String, memberOf: List<String>, username: String): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        var level = direct(ctx, userDn, memberOf, username)
        level.forEach { (dn, name) -> out.putIfAbsent(dn, name) }
        if (!source.nestedGroups) return out
        repeat(source.nestedGroupDepth - 1) {
            val next = LinkedHashMap<String, String>()
            for (groupDn in level.keys) {
                if (out.size + next.size >= MAX_GROUPS) break
                val own = if (source.groupAttribute.isBlank()) emptyList()
                else runCatching { values(ctx.getAttributes(LdapName(groupDn), arrayOf(source.groupAttribute)), source.groupAttribute) }.getOrDefault(emptyList())
                for ((dn, name) in direct(ctx, groupDn, own, null)) if (dn !in out) next.putIfAbsent(dn, name)
            }
            if (next.isEmpty()) return out
            next.forEach { (dn, name) -> out.putIfAbsent(dn, name) }
            level = next
        }
        return out
    }

    /**
     * The groups [memberDn] is directly in: its [memberOf] values and, with a group base, the groups that list it. A
     * group filter by user name (`(memberUid={username})`) applies to the user only ([username] is null for a group).
     */
    private fun direct(ctx: LdapContext, memberDn: String, memberOf: List<String>, username: String?): LinkedHashMap<String, String> {
        val out = LinkedHashMap<String, String>()
        for (dn in memberOf.take(MAX_GROUPS)) out.putIfAbsent(normal(dn), rdnValue(dn))
        if (source.groupBaseDn.isBlank()) return out
        if (username == null && source.groupFilter.contains("{username}")) return out
        val filter = source.groupFilter.replace("{dn}", escapeFilterValue(memberDn)).replace("{username}", escapeFilterValue(username.orEmpty()))
        val controls = SearchControls().apply {
            searchScope = SearchControls.SUBTREE_SCOPE
            countLimit = MAX_GROUPS.toLong()
            timeLimit = source.readTimeoutMillis
            returningAttributes = arrayOf(source.groupNameAttribute)
        }
        val found = try {
            collect(ctx.search(LdapName(source.groupBaseDn), filter, controls), MAX_GROUPS)
        } catch (_: NameNotFoundException) {
            emptyList()
        } catch (_: SizeLimitExceededException) {
            emptyList()
        }
        for (r in found) {
            val dn = r.nameInNamespace
            out.putIfAbsent(normal(dn), values(r.attributes, source.groupNameAttribute).firstOrNull() ?: rdnValue(dn))
        }
        return out
    }

    private fun collect(results: NamingEnumeration<SearchResult>, max: Int): List<SearchResult> {
        val out = ArrayList<SearchResult>()
        try {
            while (out.size < max && results.hasMore()) out += results.next()
        } catch (_: PartialResultException) {
            // A referral the gateway does not follow (Active Directory returns these at the end of a domain search).
        } finally {
            runCatching { results.close() }
        }
        return out
    }

    private class Bound(val ctx: LdapContext, private val tls: StartTlsResponse?) : AutoCloseable {
        override fun close() {
            runCatching { tls?.close() }
            runCatching { ctx.close() }
        }
    }

    private fun serviceContext(): Bound {
        val secret = bindPassword() ?: CharArray(0)
        try {
            if (secret.isEmpty()) throw NamingException("the service account has no bind password (bindPasswordRef)")
            return try {
                open(source.bindDn, secret)
            } catch (_: AuthenticationException) {
                throw NamingException("the service account bind was refused; check bindDn and the bind password")
            }
        } finally {
            secret.fill('\u0000')
        }
    }

    /** A context bound as [principal]. Throws [AuthenticationException] for a refused bind. */
    private fun open(principal: String, password: CharArray): Bound {
        val env = environment()
        if (!source.startTls) {
            env[Context.SECURITY_AUTHENTICATION] = "simple"
            env[Context.SECURITY_PRINCIPAL] = principal
            env[Context.SECURITY_CREDENTIALS] = password.copyOf()
            try {
                return Bound(InitialLdapContext(env, null), null)
            } finally {
                (env[Context.SECURITY_CREDENTIALS] as CharArray).fill('\u0000')
            }
        }
        env[Context.SECURITY_AUTHENTICATION] = "none"
        val ctx = InitialLdapContext(env, null)
        var tls: StartTlsResponse? = null
        val credentials = password.copyOf()
        try {
            tls = ctx.extendedOperation(StartTlsRequest()) as StartTlsResponse
            tls.negotiate(UserSourceSocketFactory.shared())
            // The bind runs when the next operation needs it, on the connection StartTLS upgraded.
            ctx.addToEnvironment(Context.SECURITY_AUTHENTICATION, "simple")
            ctx.addToEnvironment(Context.SECURITY_PRINCIPAL, principal)
            ctx.addToEnvironment(Context.SECURITY_CREDENTIALS, credentials)
            ctx.getAttributes("", arrayOf("1.1"))
            return Bound(ctx, tls)
        } catch (e: Exception) {
            runCatching { tls?.close() }
            runCatching { ctx.close() }
            throw e
        } finally {
            credentials.fill('\u0000')
        }
    }

    private fun environment(): Hashtable<String, Any> = Hashtable<String, Any>().apply {
        put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory")
        // Several URLs are tried in turn (failover); JNDI takes them as one space-separated list.
        put(Context.PROVIDER_URL, source.ldapUrls.joinToString(" ") { it.trimEnd('/') })
        put(Context.REFERRAL, "ignore")
        put("com.sun.jndi.ldap.connect.pool", "false")
        put("com.sun.jndi.ldap.connect.timeout", source.connectTimeoutMillis.toString())
        put("com.sun.jndi.ldap.read.timeout", source.readTimeoutMillis.toString())
        put("java.naming.ldap.version", "3")
        put("java.naming.ldap.derefAliases", "never")
        if (source.ldapUrls.any { it.startsWith("ldaps://") }) put("java.naming.ldap.factory.socket", UserSourceSocketFactory::class.java.name)
    }

    companion object {
        /** Groups read for one user, nested ones included. */
        const val MAX_GROUPS = 1_000

        private fun values(attrs: Attributes?, id: String): List<String> {
            if (attrs == null || id.isBlank()) return emptyList()
            val attr = attrs.get(id) ?: return emptyList()
            val out = ArrayList<String>()
            val all = attr.all
            try {
                while (all.hasMore()) (all.next() as? String)?.let { out += it }
            } finally {
                runCatching { all.close() }
            }
            return out
        }

        private fun normal(dn: String): String = runCatching { LdapName(dn).toString() }.getOrDefault(dn)

        /** The value of a DN's first RDN (`CN=Operators,OU=Groups,...` is `Operators`). */
        fun rdnValue(dn: String): String = runCatching {
            val name = LdapName(dn)
            name.rdns.lastOrNull()?.value?.toString()
        }.getOrNull() ?: dn

        /** RFC 4515 escaping of a value placed in a search filter. */
        fun escapeFilterValue(value: String): String = buildString {
            for (c in value) when (c) {
                '\\' -> append("\\5c")
                '*' -> append("\\2a")
                '(' -> append("\\28")
                ')' -> append("\\29")
                '\u0000' -> append("\\00")
                else -> append(c)
            }
        }

        /** RFC 4514 escaping of a value placed in a DN attribute value. */
        fun escapeDnValue(value: String): String = buildString {
            value.forEachIndexed { i, c ->
                when {
                    c in ",+\"\\<>;=" -> append('\\').append(c)
                    c == '#' && i == 0 -> append("\\#")
                    c == ' ' && (i == 0 || i == value.lastIndex) -> append("\\ ")
                    c == '\u0000' -> append("\\00")
                    else -> append(c)
                }
            }
        }

        /** A reason for an administrator, never with a credential in it. */
        fun reason(e: NamingException): String {
            var cause: Throwable? = e
            while (cause != null) {
                if (cause is SSLException) return "TLS was refused: ${cause.message?.take(200) ?: cause.javaClass.simpleName}"
                cause = cause.cause
            }
            return when (e) {
                is CommunicationException -> "the directory is not reachable (${e.rootCause?.javaClass?.simpleName ?: "connection failed"})"
                is javax.naming.ServiceUnavailableException -> "the directory is unavailable"
                else -> (e.explanation ?: e.javaClass.simpleName).take(200)
            }
        }
    }
}
