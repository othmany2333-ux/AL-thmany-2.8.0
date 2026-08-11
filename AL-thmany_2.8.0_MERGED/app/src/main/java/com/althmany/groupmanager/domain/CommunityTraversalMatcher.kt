package com.althmany.groupmanager.domain

import java.util.Locale

/**
 * Conservative semantic matcher for WhatsApp Community home/group-list surfaces.
 *
 * It never treats arbitrary visible text as a tappable subgroup. A candidate must have structural
 * group-row evidence (resource-id / content description / explicit group-row wording) and must not
 * look like announcements, admin actions, create/add controls, leave/report/delete or settings.
 */
object CommunityTraversalMatcher {
    private val communityMarkers = fragments(
        "community", "communities", "community info", "community groups", "groups in this community",
        "مجتمع", "المجتمع", "معلومات المجتمع", "مجموعات المجتمع", "قروبات المجتمع", "المجموعات في هذا المجتمع"
    )
    private val groupSectionMarkers = fragments(
        "groups", "view all groups", "all groups", "groups in this community", "community groups",
        "المجموعات", "القروبات", "عرض كل المجموعات", "كل المجموعات", "مجموعات المجتمع", "قروبات المجتمع"
    )
    private val announcementMarkers = fragments(
        "announcements", "announcement", "community announcement",
        "الإعلانات", "الاعلانات", "إعلانات المجتمع", "اعلانات المجتمع"
    )
    private val blockedMarkers = fragments(
        "add group", "create group", "new group", "manage groups", "community settings", "invite members",
        "leave community", "exit community", "report", "delete", "remove", "block",
        "إضافة مجموعة", "اضافة مجموعة", "إنشاء مجموعة", "انشاء مجموعة", "مجموعة جديدة", "إدارة المجموعات", "ادارة المجموعات",
        "إعدادات المجتمع", "اعدادات المجتمع", "دعوة أعضاء", "دعوة اعضاء", "مغادرة المجتمع", "الخروج من المجتمع", "إبلاغ", "ابلاغ", "حذف", "إزالة", "ازالة", "حظر"
    )
    private val openCommunityMarkers = fragments(
        "open community", "view community", "go to community", "community home",
        "فتح المجتمع", "عرض المجتمع", "الانتقال الى المجتمع", "الذهاب الى المجتمع"
    )
    private val rowIdFragments = fragments(
        "community_group", "communitygroup", "community_subgroup", "subgroup", "sub_group", "group_row", "group_item",
        "group_list_item", "community_item", "community_chat", "linked_group", "group_cell",
        "group_name", "group_title", "conversation_contact_name", "conversations_row_contact_name",
        "conversation_row", "conversations_row", "chat_name"
    )
    private val rowDescriptionFragments = fragments(
        "open group", "view group", "group chat", "community group", "linked group",
        "فتح المجموعة", "عرض المجموعة", "دردشة المجموعة", "مجموعة المجتمع", "قروب المجتمع"
    )
    private val headerOrNavigationLabels = fragments(
        "community", "communities", "community info", "groups", "community groups",
        "groups in this community", "view all groups", "all groups", "search", "back",
        "more options", "new community", "announcements",
        "المجتمع", "المجتمعات", "معلومات المجتمع", "المجموعات", "مجموعات المجتمع",
        "المجموعات في هذا المجتمع", "عرض كل المجموعات", "كل المجموعات", "بحث", "رجوع",
        "المزيد من الخيارات", "مجتمع جديد", "الإعلانات", "الاعلانات"
    )

    fun isCommunityHomeAcross(labels: Sequence<CharSequence?>): Boolean {
        val values = labels.map(::normalize).filter(String::isNotEmpty).toList()
        if (values.isEmpty()) return false
        val corpus = values.joinToString(" ")
        val community = communityMarkers.any(corpus::contains)
        val groups = groupSectionMarkers.any(corpus::contains)
        val announcements = announcementMarkers.any(corpus::contains)
        // Announcements is a strong WhatsApp Community marker. Otherwise require explicit
        // community + group-list semantics to avoid confusing a normal group info page.
        return (community && groups) || (community && announcements)
    }

    fun isAnnouncement(label: CharSequence?): Boolean {
        val value = normalize(label)
        return value.isNotEmpty() && announcementMarkers.any(value::contains)
    }

    fun isBlocked(label: CharSequence?): Boolean {
        val value = normalize(label)
        return value.isNotEmpty() && blockedMarkers.any(value::contains)
    }

    fun isOpenCommunity(label: CharSequence?): Boolean {
        val value = normalize(label)
        return value.isNotEmpty() && openCommunityMarkers.any(value::contains) && !isBlocked(value)
    }

    fun looksLikeGroupRow(
        text: CharSequence?,
        description: CharSequence?,
        viewId: CharSequence?,
        className: CharSequence?,
        clickable: Boolean
    ): Boolean {
        if (!clickable) return false
        val textValue = normalize(text)
        val descriptionValue = normalize(description)
        val idValue = normalize(viewId)
        val classValue = normalize(className)
        val combined = listOf(textValue, descriptionValue, idValue).joinToString(" ")
        if (combined.isBlank()) return false
        if (isAnnouncement(combined) || isBlocked(combined)) return false
        if (textValue in headerOrNavigationLabels || descriptionValue in headerOrNavigationLabels) return false

        val structuralId = rowIdFragments.any(idValue::contains)
        val structuralDescription = rowDescriptionFragments.any(descriptionValue::contains)
        val explicitGroupWord = containsGroupWord(textValue) || containsGroupWord(descriptionValue)
        val rowLikeClass = classValue.contains("button") || classValue.contains("textview") || classValue.contains("viewgroup")
        val titleId = idValue.contains("name") || idValue.contains("title")
        val plausibleTitle = textValue.length in 2..120 && textValue.any { it.isLetterOrDigit() }

        // The matcher is only called after the whole screen is verified as a Community home.
        // A WhatsApp title TextView whose clickable parent represents a row often exposes only a
        // generic conversation/name resource id, so accept that structure without requiring the
        // group name itself to contain the word “group”.
        return structuralId || structuralDescription ||
            (rowLikeClass && explicitGroupWord && (descriptionValue.isNotBlank() || idValue.isNotBlank())) ||
            (rowLikeClass && titleId && plausibleTitle)
    }

    fun stableGroupKey(
        text: CharSequence?,
        description: CharSequence?,
        viewId: CharSequence?
    ): String {
        val parts = listOf(normalize(viewId), normalize(description), normalize(text))
            .filter(String::isNotBlank)
        return parts.joinToString("|").take(240)
    }

    private fun containsGroupWord(value: String): Boolean =
        value.contains("group") || value.contains("مجموعه") || value.contains("مجموعة") || value.contains("قروب")

    private fun normalize(value: CharSequence?): String = value?.toString()
        ?.lowercase(Locale.ROOT)
        ?.replace('أ', 'ا')
        ?.replace('إ', 'ا')
        ?.replace('آ', 'ا')
        ?.replace('ى', 'ي')
        ?.replace('ة', 'ه')
        ?.replace(Regex("[\\u064B-\\u065F\\u0670]"), "")
        ?.replace(Regex("[^\\p{L}\\p{N}_:/.-]+"), " ")
        ?.trim()
        .orEmpty()

    private fun fragments(vararg values: String): Set<String> = values.map(::normalize).toSet()
}
