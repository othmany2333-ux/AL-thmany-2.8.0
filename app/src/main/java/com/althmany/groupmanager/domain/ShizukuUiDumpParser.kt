package com.althmany.groupmanager.domain

import java.util.Locale

/** Pure-Kotlin parser for the XML emitted by Android's `uiautomator dump`. */
data class ShizukuUiNode(
    val text: String,
    val contentDescription: String,
    val resourceId: String,
    val className: String,
    val packageName: String,
    val clickable: Boolean,
    val enabled: Boolean,
    val scrollable: Boolean,
    val bounds: ShizukuBounds?
) {
    fun labels(): Sequence<String> = sequenceOf(text, contentDescription, resourceId).filter { it.isNotBlank() }

    fun belongsTo(targetPackage: String?): Boolean =
        targetPackage.isNullOrBlank() || packageName.isBlank() || packageName == targetPackage

    fun stableKey(prefix: String = "node"): String {
        val b = bounds
        val center = if (b == null) "-" else "${b.centerX / 12}:${b.centerY / 12}:${(b.right - b.left) / 12}:${(b.bottom - b.top) / 12}"
        return listOf(
            prefix,
            normalizeKey(packageName),
            normalizeKey(resourceId),
            normalizeKey(contentDescription),
            normalizeKey(text),
            center
        ).joinToString("|").take(360)
    }

    private fun normalizeKey(value: String): String = value.lowercase(Locale.ROOT)
        .replace(Regex("\\s+"), " ")
        .trim()
}

data class ShizukuBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val valid: Boolean get() = right > left && bottom > top
    val centerX: Int get() = left + (right - left) / 2
    val centerY: Int get() = top + (bottom - top) / 2
}

data class ShizukuActionCandidate(
    val node: ShizukuUiNode,
    val score: Int,
    val fingerprint: String
)

data class ShizukuActionSelection(
    val candidate: ShizukuActionCandidate?,
    val runnerUpScore: Int,
    val ambiguous: Boolean
)

data class ShizukuCommunityGroupCandidate(
    val key: String,
    val node: ShizukuUiNode,
    val score: Int
)

data class ShizukuUiSnapshot(
    val nodes: List<ShizukuUiNode>,
    val screenKind: AutomationScreenKind,
    val inviteContext: Boolean,
    val inviteTarget: AccessibilityInviteTarget
) {
    val labels: List<String> get() = nodes.flatMap { it.labels().toList() }

    val communityHomeSurface: Boolean
        get() = CommunityTraversalMatcher.isCommunityHomeAcross(labels.asSequence())

    val conversationSurface: Boolean
        get() {
            val values = labels.asSequence()
            val composer = values.any(AccessibilityJoinMatcher::isConversationComposer)
            val action = labels.asSequence().any(AccessibilityJoinMatcher::isConversationAction)
            return composer && action
        }

    val whatsappHomeSurface: Boolean
        get() {
            if (labels.asSequence().any(AccessibilityJoinMatcher::isWhatsAppHomeSurface)) return true
            return labels.asSequence().count(AccessibilityJoinMatcher::isWhatsAppHomeTab) >= 2
        }

    val strongPostActionSurface: Boolean
        get() = conversationSurface || communityHomeSurface || whatsappHomeSurface

    fun actionSelection(
        action: AccessibilityJoinAction,
        targetPackage: String? = null
    ): ShizukuActionSelection {
        val candidates = nodes.mapNotNull { node -> scoreActionNode(node, action, targetPackage) }
            .sortedByDescending(ShizukuActionCandidate::score)
        val best = candidates.firstOrNull()
        val runner = candidates.getOrNull(1)?.score ?: Int.MIN_VALUE
        val margin = if (best == null || runner == Int.MIN_VALUE) Int.MAX_VALUE else best.score - runner
        val ambiguous = best != null && (best.score < ShizukuRuntimePolicy.MIN_ACTION_SCORE || margin < ShizukuRuntimePolicy.MIN_SCORE_MARGIN)
        return ShizukuActionSelection(
            candidate = best?.takeUnless { ambiguous },
            runnerUpScore = runner,
            ambiguous = ambiguous
        )
    }

    fun actionNode(action: AccessibilityJoinAction, targetPackage: String? = null): ShizukuUiNode? =
        actionSelection(action, targetPackage).candidate?.node

    fun confirmationNode(targetPackage: String? = null): ShizukuUiNode? = nodes
        .asSequence()
        .filter { it.enabled && it.bounds?.valid == true && it.belongsTo(targetPackage) }
        .filter { node -> node.labels().none(AccessibilityJoinMatcher::isBlockedAction) }
        .mapNotNull { node ->
            val score = node.labels().count(AccessibilityJoinMatcher::isConfirmation) * 28 +
                (if (node.clickable) 20 else 0) +
                (if (node.className.contains("Button", ignoreCase = true)) 8 else 0)
            if (score >= 48) ShizukuActionCandidate(node, score, node.stableKey("confirm")) else null
        }
        .maxByOrNull(ShizukuActionCandidate::score)
        ?.node

    fun openCommunityNode(targetPackage: String? = null): ShizukuUiNode? = nodes
        .asSequence()
        .filter { it.enabled && it.clickable && it.bounds?.valid == true && it.belongsTo(targetPackage) }
        .filter { node -> node.labels().none(CommunityTraversalMatcher::isBlocked) }
        .firstOrNull { node -> node.labels().any(CommunityTraversalMatcher::isOpenCommunity) }

    fun communityGroupCandidates(targetPackage: String? = null): List<ShizukuCommunityGroupCandidate> {
        if (!communityHomeSurface) return emptyList()
        return nodes.asSequence()
            .filter { it.enabled && it.bounds?.valid == true && it.belongsTo(targetPackage) }
            .mapNotNull { node ->
                if (!CommunityTraversalMatcher.looksLikeGroupRow(
                        text = node.text,
                        description = node.contentDescription,
                        viewId = node.resourceId,
                        className = node.className,
                        clickable = node.clickable
                    )
                ) return@mapNotNull null
                val key = CommunityTraversalMatcher.stableGroupKey(node.text, node.contentDescription, node.resourceId)
                if (key.isBlank()) return@mapNotNull null
                var score = 60
                if (node.resourceId.isNotBlank()) score += 12
                if (node.contentDescription.isNotBlank()) score += 8
                if (node.className.contains("ViewGroup", ignoreCase = true) || node.className.contains("Button", ignoreCase = true)) score += 6
                ShizukuCommunityGroupCandidate(key, node, score)
            }
            .sortedWith(compareByDescending<ShizukuCommunityGroupCandidate> { it.score }.thenBy { it.node.bounds?.top ?: Int.MAX_VALUE })
            .toList()
    }

    fun communityScrollNode(targetPackage: String? = null): ShizukuUiNode? = nodes
        .asSequence()
        .filter { it.enabled && it.scrollable && it.bounds?.valid == true && it.belongsTo(targetPackage) }
        .maxByOrNull { node ->
            val b = node.bounds ?: return@maxByOrNull 0L
            (b.right - b.left).toLong() * (b.bottom - b.top).toLong()
        }

    private fun scoreActionNode(
        node: ShizukuUiNode,
        action: AccessibilityJoinAction,
        targetPackage: String?
    ): ShizukuActionCandidate? {
        if (!node.enabled || node.bounds?.valid != true || !node.belongsTo(targetPackage)) return null
        val labels = node.labels().toList()
        if (labels.isEmpty() || labels.any(AccessibilityJoinMatcher::isBlockedAction)) return null
        val textMatch = AccessibilityJoinMatcher.actionType(node.text, inviteContext) == action
        val descriptionMatch = AccessibilityJoinMatcher.actionType(node.contentDescription, inviteContext) == action
        val idMatch = AccessibilityJoinMatcher.actionType(node.resourceId, inviteContext) == action
        if (!textMatch && !descriptionMatch && !idMatch) return null

        var score = 0
        if (textMatch) score += 42
        if (descriptionMatch) score += 38
        if (idMatch) score += 34
        if (node.clickable) {
            score += 20
        } else if (textMatch || descriptionMatch) {
            // WhatsApp sometimes exposes the visible Join label as a non-clickable TextView inside
            // a clickable Material container. A coordinate at the exact semantic label remains
            // inside that container. Keep two-scan consensus (clickable=false) but do not discard
            // this high-confidence Work Profile fallback merely because XML flattened its parent.
            score += 16
        } else {
            score -= 10
        }
        if (node.className.contains("Button", ignoreCase = true)) score += 8
        if (targetPackage != null && node.packageName == targetPackage) score += 8
        if (node.resourceId.startsWith("${targetPackage.orEmpty()}:id/")) score += 6
        return ShizukuActionCandidate(node, score, node.stableKey(action.name))
    }
}

object ShizukuUiDumpParser {
    private val nodeRegex = Regex("<node\\s+([^>]*?)(?:/?>)", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
    private val attributeRegex = Regex("([A-Za-z0-9_:-]+)=\\\"([^\\\"]*)\\\"")
    private val boundsRegex = Regex("\\[(\\d+),(\\d+)]\\[(\\d+),(\\d+)]")

    fun parse(payload: String): ShizukuUiSnapshot {
        if (payload.startsWith("__AL_FAST_COMPACT__=")) return parseCompact(payload)
        val nodes = nodeRegex.findAll(payload).mapNotNull { match -> parseNode(match.groupValues[1]) }.toList()
        return buildSnapshot(nodes)
    }

    private fun parseCompact(payload: String): ShizukuUiSnapshot {
        val nodes = payload.lineSequence().drop(1).mapNotNull { line ->
            if (!line.startsWith("N\t")) return@mapNotNull null
            val fields = splitEscapedTabs(line.substring(2))
            if (fields.size < 10) return@mapNotNull null
            val b = fields[9].split(',').mapNotNull(String::toIntOrNull)
            ShizukuUiNode(
                text = unescapeCompact(fields[0]),
                contentDescription = unescapeCompact(fields[1]),
                resourceId = unescapeCompact(fields[2]),
                className = unescapeCompact(fields[3]),
                packageName = unescapeCompact(fields[4]),
                clickable = fields[5] == "1",
                enabled = fields[6] != "0",
                scrollable = fields[7] == "1",
                bounds = if (b.size == 4) ShizukuBounds(b[0], b[1], b[2], b[3]) else null
            )
        }.toList()
        return buildSnapshot(nodes)
    }

    private fun buildSnapshot(nodes: List<ShizukuUiNode>): ShizukuUiSnapshot {
        val labels = nodes.asSequence().flatMap { it.labels() }.toList()
        val target = AccessibilityJoinMatcher.targetTypeAcross(labels.asSequence())
        val explicitInviteContext = labels.any(AccessibilityJoinMatcher::hasInviteContext)
        val contextActions = labels.mapNotNull { AccessibilityJoinMatcher.actionType(it, explicitInviteContext) }.toSet()
        val context = explicitInviteContext || contextActions.any { it in setOf(
            AccessibilityJoinAction.PREVIEW,
            AccessibilityJoinAction.JOIN,
            AccessibilityJoinAction.REQUEST
        ) }
        var screen = AccessibilityScreenClassifier.classify(labels.asSequence())
        if (screen == AutomationScreenKind.UNKNOWN && context) {
            val actions = labels.mapNotNull { AccessibilityJoinMatcher.actionType(it, true) }.toSet()
            screen = when {
                AccessibilityJoinAction.REQUEST in actions -> AutomationScreenKind.REQUEST_ACTION
                AccessibilityJoinAction.JOIN in actions -> AutomationScreenKind.JOIN_ACTION
                AccessibilityJoinAction.PREVIEW in actions -> AutomationScreenKind.PREVIEW_ACTION
                else -> screen
            }
        }
        return ShizukuUiSnapshot(nodes, screen, context, target)
    }

    private fun splitEscapedTabs(value: String): List<String> {
        val out = ArrayList<String>(10)
        val current = StringBuilder()
        var escaped = false
        for (ch in value) {
            if (escaped) {
                current.append('\\').append(ch)
                escaped = false
            } else if (ch == '\\') {
                escaped = true
            } else if (ch == '\t') {
                out += current.toString(); current.setLength(0)
            } else current.append(ch)
        }
        if (escaped) current.append('\\')
        out += current.toString()
        return out
    }

    private fun unescapeCompact(value: String): String {
        val out = StringBuilder(value.length)
        var i = 0
        while (i < value.length) {
            val ch = value[i]
            if (ch == '\\' && i + 1 < value.length) {
                when (val n = value[i + 1]) {
                    't' -> out.append('\t')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    '\\' -> out.append('\\')
                    else -> out.append(n)
                }
                i += 2
            } else { out.append(ch); i++ }
        }
        return out.toString()
    }

    private fun parseNode(attributesText: String): ShizukuUiNode? {
        val attrs = attributeRegex.findAll(attributesText).associate { it.groupValues[1] to decodeXml(it.groupValues[2]) }
        if (attrs.isEmpty()) return null
        return ShizukuUiNode(
            text = attrs["text"].orEmpty(),
            contentDescription = attrs["content-desc"].orEmpty(),
            resourceId = attrs["resource-id"].orEmpty(),
            className = attrs["class"].orEmpty(),
            packageName = attrs["package"].orEmpty(),
            clickable = attrs["clickable"].equals("true", ignoreCase = true),
            enabled = !attrs["enabled"].equals("false", ignoreCase = true),
            scrollable = attrs["scrollable"].equals("true", ignoreCase = true),
            bounds = attrs["bounds"]?.let(::parseBounds)
        )
    }

    private fun parseBounds(value: String): ShizukuBounds? {
        val m = boundsRegex.matchEntire(value) ?: return null
        return ShizukuBounds(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), m.groupValues[4].toInt())
    }

    private fun decodeXml(value: String): String = value
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
