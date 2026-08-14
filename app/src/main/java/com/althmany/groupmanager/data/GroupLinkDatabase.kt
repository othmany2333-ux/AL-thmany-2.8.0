package com.althmany.groupmanager.data

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.althmany.groupmanager.domain.SessionRules
import com.althmany.groupmanager.model.GroupLink
import com.althmany.groupmanager.model.LinkResultCode
import com.althmany.groupmanager.model.LinkSource
import com.althmany.groupmanager.model.LinkStatus
import com.althmany.groupmanager.model.ParsedGroupLink
import com.althmany.groupmanager.model.SessionSnapshot
import com.althmany.groupmanager.model.SessionStats
import com.althmany.groupmanager.model.SessionStatus
import com.althmany.groupmanager.model.SessionSummary
import java.util.UUID

class GroupLinkDatabase(context: Context) :
    SQLiteOpenHelper(context.applicationContext, DATABASE_NAME, null, DATABASE_VERSION) {

    init {
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) {
        createSessionsTable(db)
        createLinksTable(db)
        createEventsTable(db)
        createIndexes(db)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if (oldVersion < 2) {
            db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions(created_at DESC)")
        }
        if (oldVersion < 3) {
            createEventsTable(db)
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_link_events_session_created " +
                    "ON link_events(session_id, created_at DESC)"
            )
        }
        if (oldVersion < 4) {
            db.execSQL("ALTER TABLE links ADD COLUMN result_code TEXT")
            db.execSQL("ALTER TABLE links ADD COLUMN result_detail TEXT")
        }
        if (oldVersion < 5) {
            db.execSQL(
                "CREATE INDEX IF NOT EXISTS index_links_session_status_position " +
                    "ON links(session_id, status, position_index)"
            )
        }
    }

    @Synchronized
    fun createSession(
        parsedLinks: List<ParsedGroupLink>,
        source: LinkSource,
        sourceLabel: String
    ): String {
        require(parsedLinks.isNotEmpty()) { "A session requires at least one link." }

        val sessionId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val database = writableDatabase

        database.beginTransaction()
        try {
            database.insertOrThrow(
                TABLE_SESSIONS,
                null,
                ContentValues().apply {
                    put("id", sessionId)
                    put("created_at", now)
                    putNull("completed_at")
                    put("source_label", sourceLabel.trim().take(120).ifBlank { "Unknown source" })
                    put("total_count", parsedLinks.size)
                    put("status", SessionStatus.ACTIVE.name)
                }
            )

            parsedLinks.forEachIndexed { index, parsed ->
                database.insertOrThrow(
                    TABLE_LINKS,
                    null,
                    ContentValues().apply {
                        put("session_id", sessionId)
                        put("position_index", index)
                        put("url", parsed.canonicalUrl)
                        put("invite_code", parsed.inviteCode)
                        put("status", LinkStatus.PENDING.name)
                        put("source", source.name)
                        put("imported_at", now)
                        putNull("opened_at")
                        putNull("completed_at")
                        put("open_attempts", 0)
                        putNull("result_code")
                        putNull("result_detail")
                    }
                )
            }

            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }

        return sessionId
    }

    @Synchronized
    fun loadSnapshot(sessionId: String): SessionSnapshot? {
        val database = readableDatabase
        val exists = database.rawQuery(
            "SELECT 1 FROM sessions WHERE id = ? LIMIT 1",
            arrayOf(sessionId)
        ).use { it.moveToFirst() }
        if (!exists) return null

        val links = queryLinks(database, sessionId)
        return SessionSnapshot(
            sessionId = sessionId,
            links = links,
            stats = SessionRules.stats(links),
            canUndoLastResult = hasUndoableResult(database, sessionId)
        )
    }

    /**
     * Lightweight dashboard snapshot: full aggregate stats plus only a small link window around
     * the currently opened/next pending item. This keeps the UI responsive with thousands of links.
     */
    @Synchronized
    fun loadDashboardSnapshot(sessionId: String, windowSize: Int = 120): SessionSnapshot? {
        val database = readableDatabase
        val exists = database.rawQuery(
            "SELECT 1 FROM sessions WHERE id = ? LIMIT 1",
            arrayOf(sessionId)
        ).use { it.moveToFirst() }
        if (!exists) return null

        val stats = querySessionStats(database, sessionId)
        val safeWindow = windowSize.coerceIn(20, 300)
        val anchor = database.rawQuery(
            """
            SELECT position_index
            FROM links
            WHERE session_id = ? AND status IN (?, ?)
            ORDER BY CASE status WHEN '${LinkStatus.OPENED.name}' THEN 0 ELSE 1 END, position_index ASC
            LIMIT 1
            """.trimIndent(),
            arrayOf(sessionId, LinkStatus.OPENED.name, LinkStatus.PENDING.name)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }

        val maxStart = (stats.total - safeWindow).coerceAtLeast(0)
        val start = (anchor - safeWindow / 2).coerceIn(0, maxStart)
        val endExclusive = start + safeWindow
        val links = database.query(
            TABLE_LINKS,
            LINK_COLUMNS,
            "session_id = ? AND position_index >= ? AND position_index < ?",
            arrayOf(sessionId, start.toString(), endExclusive.toString()),
            null,
            null,
            "position_index ASC"
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) add(cursor.toGroupLink())
            }
        }
        return SessionSnapshot(
            sessionId = sessionId,
            links = links,
            stats = stats,
            canUndoLastResult = hasUndoableResult(database, sessionId)
        )
    }

    @Synchronized
    fun getLink(linkId: Long): GroupLink? = getLinkFromDatabase(readableDatabase, linkId)

    /** Lightweight runtime lookup used by Accessibility. Avoids materializing the entire queue. */
    @Synchronized
    fun loadCurrentOpened(sessionId: String): GroupLink? = readableDatabase.query(
        TABLE_LINKS,
        LINK_COLUMNS,
        "session_id = ? AND status = ?",
        arrayOf(sessionId, LinkStatus.OPENED.name),
        null,
        null,
        "position_index ASC",
        "1"
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toGroupLink() else null
    }

    /** Returns the current opened link, otherwise the first pending link, with a single-row query. */
    @Synchronized
    fun loadNextActionable(sessionId: String): GroupLink? = readableDatabase.rawQuery(
        """
        SELECT ${LINK_COLUMNS.joinToString(", ")}
        FROM $TABLE_LINKS
        WHERE session_id = ? AND status IN (?, ?)
        ORDER BY CASE status WHEN '${LinkStatus.OPENED.name}' THEN 0 ELSE 1 END, position_index ASC
        LIMIT 1
        """.trimIndent(),
        arrayOf(sessionId, LinkStatus.OPENED.name, LinkStatus.PENDING.name)
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toGroupLink() else null
    }

    @Synchronized
    fun sessionTotalCount(sessionId: String): Int? = readableDatabase.rawQuery(
        "SELECT total_count FROM sessions WHERE id = ? LIMIT 1",
        arrayOf(sessionId)
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else null
    }

    @Synchronized
    fun isSessionComplete(sessionId: String): Boolean? = readableDatabase.rawQuery(
        "SELECT status FROM sessions WHERE id = ? LIMIT 1",
        arrayOf(sessionId)
    ).use { cursor ->
        if (!cursor.moveToFirst()) null else cursor.getString(0) == SessionStatus.COMPLETED.name
    }

    @Synchronized
    fun markOpened(linkId: Long): GroupLink? {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val target = getLinkFromDatabase(database, linkId) ?: return null
            val now = System.currentTimeMillis()

            database.update(
                TABLE_LINKS,
                ContentValues().apply {
                    put("status", LinkStatus.PENDING.name)
                    putNull("opened_at")
                    putNull("completed_at")
                    putNull("result_code")
                    putNull("result_detail")
                },
                "session_id = ? AND status = ? AND id != ?",
                arrayOf(target.sessionId, LinkStatus.OPENED.name, linkId.toString())
            )

            database.execSQL(
                """
                UPDATE links
                SET status = ?, opened_at = ?, completed_at = NULL,
                    result_code = NULL, result_detail = NULL,
                    open_attempts = open_attempts + 1
                WHERE id = ?
                """.trimIndent(),
                arrayOf<Any>(LinkStatus.OPENED.name, now, linkId)
            )

            updateSessionLifecycle(database, target.sessionId)
            database.setTransactionSuccessful()
            return getLinkFromDatabase(database, linkId)
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun markStatus(
        linkId: Long,
        status: LinkStatus,
        resultCode: LinkResultCode? = null,
        resultDetail: String? = null
    ): Boolean {
        require(status != LinkStatus.OPENED) { "Use markOpened() for OPENED status." }

        val database = writableDatabase
        database.beginTransaction()
        try {
            val target = getLinkFromDatabase(database, linkId) ?: return false
            val safeDetail = resultDetail?.trim()?.take(240)?.takeIf { it.isNotBlank() }
            if (target.status == status &&
                target.resultCode == resultCode &&
                target.resultDetail == safeDetail
            ) {
                database.setTransactionSuccessful()
                return true
            }

            val now = System.currentTimeMillis()
            val statusChanged = target.status != status
            if (statusChanged && (
                    status == LinkStatus.JOINED ||
                        status == LinkStatus.REQUESTED ||
                        status == LinkStatus.SKIPPED
                    )
            ) {
                insertResultEvent(database, target, status, now)
            }

            val values = ContentValues().apply {
                put("status", status.name)
                when (status) {
                    LinkStatus.JOINED, LinkStatus.REQUESTED, LinkStatus.SKIPPED ->
                        put("completed_at", now)
                    LinkStatus.PENDING, LinkStatus.FAILED -> putNull("completed_at")
                    LinkStatus.OPENED -> Unit
                }
                if (status != LinkStatus.OPENED) putNull("opened_at")
                if (resultCode == null) putNull("result_code") else put("result_code", resultCode.name)
                if (safeDetail == null) putNull("result_detail") else put("result_detail", safeDetail)
            }
            database.update(TABLE_LINKS, values, "id = ?", arrayOf(linkId.toString()))
            updateSessionLifecycle(database, target.sessionId)
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun undoLastResult(sessionId: String): Boolean {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val event = database.rawQuery(
                """
                SELECT id, link_id, before_status, after_status, before_opened_at,
                       before_completed_at, before_open_attempts
                FROM link_events
                WHERE session_id = ? AND action = ? AND undone = 0
                ORDER BY id DESC
                LIMIT 1
                """.trimIndent(),
                arrayOf(sessionId, EVENT_ACTION_RESULT)
            ).use { cursor ->
                if (!cursor.moveToFirst()) null else UndoEvent(
                    id = cursor.getLong(cursor.getColumnIndexOrThrow("id")),
                    linkId = cursor.getLong(cursor.getColumnIndexOrThrow("link_id")),
                    beforeStatus = enumValueOrDefault(
                        cursor.getString(cursor.getColumnIndexOrThrow("before_status")),
                        LinkStatus.OPENED
                    ),
                    afterStatus = enumValueOrDefault(
                        cursor.getString(cursor.getColumnIndexOrThrow("after_status")),
                        LinkStatus.JOINED
                    ),
                    beforeOpenedAt = cursor.getNullableLong("before_opened_at"),
                    beforeCompletedAt = cursor.getNullableLong("before_completed_at"),
                    beforeAttempts = cursor.getInt(cursor.getColumnIndexOrThrow("before_open_attempts"))
                )
            } ?: return false

            val target = getLinkFromDatabase(database, event.linkId) ?: return false
            if (target.status != event.afterStatus) return false

            if (event.beforeStatus == LinkStatus.OPENED) {
                database.update(
                    TABLE_LINKS,
                    ContentValues().apply {
                        put("status", LinkStatus.PENDING.name)
                        putNull("opened_at")
                        putNull("completed_at")
                        putNull("result_code")
                        putNull("result_detail")
                    },
                    "session_id = ? AND status = ? AND id != ?",
                    arrayOf(sessionId, LinkStatus.OPENED.name, event.linkId.toString())
                )
            }

            database.update(
                TABLE_LINKS,
                ContentValues().apply {
                    put("status", event.beforeStatus.name)
                    if (event.beforeOpenedAt == null) putNull("opened_at") else put("opened_at", event.beforeOpenedAt)
                    if (event.beforeCompletedAt == null) putNull("completed_at") else put("completed_at", event.beforeCompletedAt)
                    put("open_attempts", event.beforeAttempts)
                    putNull("result_code")
                    putNull("result_detail")
                },
                "id = ?",
                arrayOf(event.linkId.toString())
            )
            database.update(
                TABLE_EVENTS,
                ContentValues().apply { put("undone", 1) },
                "id = ?",
                arrayOf(event.id.toString())
            )
            updateSessionLifecycle(database, sessionId)
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun deleteLink(linkId: Long): Boolean {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val target = getLinkFromDatabase(database, linkId) ?: return false
            database.delete(TABLE_LINKS, "id = ?", arrayOf(linkId.toString()))
            reindexSession(database, target.sessionId)
            updateSessionLifecycle(database, target.sessionId)
            database.setTransactionSuccessful()
            return true
        } finally {
            database.endTransaction()
        }
    }

    /**
     * Re-queue only unverified failed rows.
     * Verified JOINED and REQUESTED rows are never changed.
     */
    @Synchronized
    fun requeueFailed(sessionId: String): Int {
        val database = writableDatabase
        database.beginTransaction()
        try {
            val changed = database.update(
                TABLE_LINKS,
                ContentValues().apply {
                    put("status", LinkStatus.PENDING.name)
                    putNull("opened_at")
                    putNull("completed_at")
                    putNull("result_code")
                    putNull("result_detail")
                },
                "session_id = ? AND status = ?",
                arrayOf(sessionId, LinkStatus.FAILED.name)
            )
            updateSessionLifecycle(database, sessionId)
            database.setTransactionSuccessful()
            return changed
        } finally {
            database.endTransaction()
        }
    }

    @Synchronized
    fun markSessionAbandoned(sessionId: String) {
        writableDatabase.update(
            TABLE_SESSIONS,
            ContentValues().apply {
                put("status", SessionStatus.ABANDONED.name)
                put("completed_at", System.currentTimeMillis())
            },
            "id = ? AND status = ?",
            arrayOf(sessionId, SessionStatus.ACTIVE.name)
        )
    }

    @Synchronized
    fun deleteSession(sessionId: String): Boolean =
        writableDatabase.delete(TABLE_SESSIONS, "id = ?", arrayOf(sessionId)) > 0

    @Synchronized
    fun clearHistory(activeSessionId: String?) {
        if (activeSessionId == null) {
            writableDatabase.delete(TABLE_SESSIONS, null, null)
        } else {
            writableDatabase.delete(TABLE_SESSIONS, "id != ?", arrayOf(activeSessionId))
        }
    }

    @Synchronized
    fun listSessionSummaries(activeSessionId: String?, limit: Int = 30): List<SessionSummary> {
        val sql = """
            SELECT
                s.id,
                s.created_at,
                s.completed_at,
                s.source_label,
                s.total_count,
                s.status,
                SUM(CASE WHEN l.status = '${LinkStatus.JOINED.name}'
                          AND l.result_code IN ('${LinkResultCode.JOIN_ACTION_COMPLETED.name}', '${LinkResultCode.MANUAL_JOINED.name}')
                         THEN 1 ELSE 0 END) AS joined_count,
                SUM(CASE WHEN l.status = '${LinkStatus.REQUESTED.name}'
                          AND l.result_code = '${LinkResultCode.REQUEST_SENT.name}'
                         THEN 1 ELSE 0 END) AS requested_count,
                SUM(CASE WHEN l.status = '${LinkStatus.SKIPPED.name}'
                          OR (l.status = '${LinkStatus.JOINED.name}' AND l.result_code = '${LinkResultCode.ALREADY_MEMBER.name}')
                         THEN 1 ELSE 0 END) AS skipped_count,
                SUM(CASE WHEN l.status = '${LinkStatus.FAILED.name}'
                          OR (l.status = '${LinkStatus.JOINED.name}' AND
                              (l.result_code IS NULL OR l.result_code NOT IN (
                                  '${LinkResultCode.JOIN_ACTION_COMPLETED.name}',
                                  '${LinkResultCode.MANUAL_JOINED.name}',
                                  '${LinkResultCode.ALREADY_MEMBER.name}'
                              )))
                          OR (l.status = '${LinkStatus.REQUESTED.name}' AND
                              (l.result_code IS NULL OR l.result_code != '${LinkResultCode.REQUEST_SENT.name}'))
                         THEN 1 ELSE 0 END) AS failed_count
            FROM sessions s
            LEFT JOIN links l ON l.session_id = s.id
            GROUP BY s.id
            ORDER BY s.created_at DESC
            LIMIT ?
        """.trimIndent()

        return readableDatabase.rawQuery(sql, arrayOf(limit.coerceIn(1, 100).toString())).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(cursor.getColumnIndexOrThrow("id"))
                    add(
                        SessionSummary(
                            id = id,
                            createdAt = cursor.getLong(cursor.getColumnIndexOrThrow("created_at")),
                            completedAt = cursor.getNullableLong("completed_at"),
                            sourceLabel = cursor.getString(cursor.getColumnIndexOrThrow("source_label")),
                            totalCount = cursor.getInt(cursor.getColumnIndexOrThrow("total_count")),
                            joinedCount = cursor.getInt(cursor.getColumnIndexOrThrow("joined_count")),
                            requestedCount = cursor.getInt(cursor.getColumnIndexOrThrow("requested_count")),
                            skippedCount = cursor.getInt(cursor.getColumnIndexOrThrow("skipped_count")),
                            failedCount = cursor.getInt(cursor.getColumnIndexOrThrow("failed_count")),
                            status = enumValueOrDefault(
                                cursor.getString(cursor.getColumnIndexOrThrow("status")),
                                SessionStatus.ABANDONED
                            ),
                            isActive = id == activeSessionId
                        )
                    )
                }
            }
        }
    }

    private fun createSessionsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS sessions (
                id TEXT PRIMARY KEY NOT NULL,
                created_at INTEGER NOT NULL,
                completed_at INTEGER,
                source_label TEXT NOT NULL,
                total_count INTEGER NOT NULL,
                status TEXT NOT NULL
            )
            """.trimIndent()
        )
    }

    private fun createLinksTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS links (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                position_index INTEGER NOT NULL,
                url TEXT NOT NULL,
                invite_code TEXT NOT NULL,
                status TEXT NOT NULL,
                source TEXT NOT NULL,
                imported_at INTEGER NOT NULL,
                opened_at INTEGER,
                completed_at INTEGER,
                open_attempts INTEGER NOT NULL DEFAULT 0,
                result_code TEXT,
                result_detail TEXT,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
                UNIQUE(session_id, url)
            )
            """.trimIndent()
        )
    }

    private fun createEventsTable(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE IF NOT EXISTS link_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                link_id INTEGER NOT NULL,
                action TEXT NOT NULL,
                before_status TEXT NOT NULL,
                after_status TEXT NOT NULL,
                before_opened_at INTEGER,
                before_completed_at INTEGER,
                before_open_attempts INTEGER NOT NULL,
                created_at INTEGER NOT NULL,
                undone INTEGER NOT NULL DEFAULT 0,
                FOREIGN KEY(session_id) REFERENCES sessions(id) ON DELETE CASCADE,
                FOREIGN KEY(link_id) REFERENCES links(id) ON DELETE CASCADE
            )
            """.trimIndent()
        )
    }

    private fun createIndexes(db: SQLiteDatabase) {
        db.execSQL("CREATE INDEX IF NOT EXISTS index_links_session_position ON links(session_id, position_index)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_links_session_status_position " +
                "ON links(session_id, status, position_index)"
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS index_sessions_created_at ON sessions(created_at DESC)")
        db.execSQL(
            "CREATE INDEX IF NOT EXISTS index_link_events_session_created " +
                "ON link_events(session_id, created_at DESC)"
        )
    }

    private fun insertResultEvent(
        database: SQLiteDatabase,
        target: GroupLink,
        afterStatus: LinkStatus,
        now: Long
    ) {
        database.insertOrThrow(
            TABLE_EVENTS,
            null,
            ContentValues().apply {
                put("session_id", target.sessionId)
                put("link_id", target.id)
                put("action", EVENT_ACTION_RESULT)
                put("before_status", target.status.name)
                put("after_status", afterStatus.name)
                if (target.openedAt == null) putNull("before_opened_at") else put("before_opened_at", target.openedAt)
                if (target.completedAt == null) putNull("before_completed_at") else put("before_completed_at", target.completedAt)
                put("before_open_attempts", target.openAttempts)
                put("created_at", now)
                put("undone", 0)
            }
        )
    }

    private fun hasUndoableResult(database: SQLiteDatabase, sessionId: String): Boolean =
        database.rawQuery(
            """
            SELECT 1
            FROM link_events e
            JOIN links l ON l.id = e.link_id
            WHERE e.session_id = ? AND e.action = ? AND e.undone = 0
              AND l.status = e.after_status
            ORDER BY e.id DESC
            LIMIT 1
            """.trimIndent(),
            arrayOf(sessionId, EVENT_ACTION_RESULT)
        ).use { it.moveToFirst() }

    private fun querySessionStats(database: SQLiteDatabase, sessionId: String): SessionStats =
        database.rawQuery(
            """
            SELECT COUNT(*) AS total_count,
                   SUM(CASE WHEN status = '${LinkStatus.PENDING.name}' THEN 1 ELSE 0 END) AS pending_count,
                   SUM(CASE WHEN status = '${LinkStatus.OPENED.name}' THEN 1 ELSE 0 END) AS opened_count,
                   SUM(CASE WHEN status = '${LinkStatus.JOINED.name}'
                             AND result_code IN ('${LinkResultCode.JOIN_ACTION_COMPLETED.name}', '${LinkResultCode.MANUAL_JOINED.name}')
                            THEN 1 ELSE 0 END) AS joined_count,
                   SUM(CASE WHEN status = '${LinkStatus.REQUESTED.name}'
                             AND result_code = '${LinkResultCode.REQUEST_SENT.name}'
                            THEN 1 ELSE 0 END) AS requested_count,
                   SUM(CASE WHEN status = '${LinkStatus.SKIPPED.name}'
                             OR (status = '${LinkStatus.JOINED.name}' AND result_code = '${LinkResultCode.ALREADY_MEMBER.name}')
                            THEN 1 ELSE 0 END) AS skipped_count,
                   SUM(CASE WHEN status = '${LinkStatus.FAILED.name}'
                             OR (status = '${LinkStatus.JOINED.name}' AND
                                 (result_code IS NULL OR result_code NOT IN (
                                     '${LinkResultCode.JOIN_ACTION_COMPLETED.name}',
                                     '${LinkResultCode.MANUAL_JOINED.name}',
                                     '${LinkResultCode.ALREADY_MEMBER.name}'
                                 )))
                             OR (status = '${LinkStatus.REQUESTED.name}' AND
                                 (result_code IS NULL OR result_code != '${LinkResultCode.REQUEST_SENT.name}'))
                            THEN 1 ELSE 0 END) AS failed_count
            FROM links
            WHERE session_id = ?
            """.trimIndent(),
            arrayOf(sessionId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) SessionStats(0, 0, 0, 0, 0, 0, 0)
            else SessionStats(
                total = cursor.getInt(0),
                pending = cursor.getInt(1),
                opened = cursor.getInt(2),
                joined = cursor.getInt(3),
                requested = cursor.getInt(4),
                skipped = cursor.getInt(5),
                failed = cursor.getInt(6)
            )
        }

    private fun updateSessionLifecycle(database: SQLiteDatabase, sessionId: String) {
        // Aggregate in SQLite instead of loading every link into Kotlin after each transition.
        val counts = database.rawQuery(
            """
            SELECT COUNT(*) AS total_count,
                   SUM(CASE WHEN status IN (?, ?) THEN 1 ELSE 0 END) AS remaining_count
            FROM links
            WHERE session_id = ?
            """.trimIndent(),
            arrayOf(LinkStatus.PENDING.name, LinkStatus.OPENED.name, sessionId)
        ).use { cursor ->
            if (!cursor.moveToFirst()) 0 to 0
            else cursor.getInt(0) to cursor.getInt(1)
        }
        val total = counts.first
        val remaining = counts.second
        val status = when {
            total == 0 -> SessionStatus.ABANDONED
            remaining == 0 -> SessionStatus.COMPLETED
            else -> SessionStatus.ACTIVE
        }

        database.update(
            TABLE_SESSIONS,
            ContentValues().apply {
                put("total_count", total)
                put("status", status.name)
                if (status == SessionStatus.ACTIVE) putNull("completed_at")
                else put("completed_at", System.currentTimeMillis())
            },
            "id = ?",
            arrayOf(sessionId)
        )
    }

    private fun reindexSession(database: SQLiteDatabase, sessionId: String) {
        database.query(
            TABLE_LINKS,
            arrayOf("id"),
            "session_id = ?",
            arrayOf(sessionId),
            null,
            null,
            "position_index ASC"
        ).use { cursor ->
            var position = 0
            while (cursor.moveToNext()) {
                val id = cursor.getLong(cursor.getColumnIndexOrThrow("id"))
                database.update(
                    TABLE_LINKS,
                    ContentValues().apply { put("position_index", position++) },
                    "id = ?",
                    arrayOf(id.toString())
                )
            }
        }
    }

    private fun queryLinks(database: SQLiteDatabase, sessionId: String): List<GroupLink> = database.query(
        TABLE_LINKS,
        LINK_COLUMNS,
        "session_id = ?",
        arrayOf(sessionId),
        null,
        null,
        "position_index ASC"
    ).use { cursor ->
        buildList {
            while (cursor.moveToNext()) add(cursor.toGroupLink())
        }
    }

    private fun getLinkFromDatabase(database: SQLiteDatabase, linkId: Long): GroupLink? = database.query(
        TABLE_LINKS,
        LINK_COLUMNS,
        "id = ?",
        arrayOf(linkId.toString()),
        null,
        null,
        null,
        "1"
    ).use { cursor ->
        if (cursor.moveToFirst()) cursor.toGroupLink() else null
    }

    private fun Cursor.toGroupLink(): GroupLink = GroupLink(
        id = getLong(getColumnIndexOrThrow("id")),
        sessionId = getString(getColumnIndexOrThrow("session_id")),
        position = getInt(getColumnIndexOrThrow("position_index")),
        url = getString(getColumnIndexOrThrow("url")),
        inviteCode = getString(getColumnIndexOrThrow("invite_code")),
        status = enumValueOrDefault(getString(getColumnIndexOrThrow("status")), LinkStatus.PENDING),
        source = enumValueOrDefault(getString(getColumnIndexOrThrow("source")), LinkSource.PASTE),
        importedAt = getLong(getColumnIndexOrThrow("imported_at")),
        openedAt = getNullableLong("opened_at"),
        completedAt = getNullableLong("completed_at"),
        openAttempts = getInt(getColumnIndexOrThrow("open_attempts")),
        resultCode = getNullableString("result_code")?.let {
            enumValueOrDefault(it, LinkResultCode.UNKNOWN_SCREEN)
        },
        resultDetail = getNullableString("result_detail")
    )

    private fun Cursor.getNullableString(columnName: String): String? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.getNullableLong(columnName: String): Long? {
        val index = getColumnIndexOrThrow(columnName)
        return if (isNull(index)) null else getLong(index)
    }

    private inline fun <reified T : Enum<T>> enumValueOrDefault(value: String, default: T): T =
        runCatching { enumValueOf<T>(value) }.getOrDefault(default)

    private data class UndoEvent(
        val id: Long,
        val linkId: Long,
        val beforeStatus: LinkStatus,
        val afterStatus: LinkStatus,
        val beforeOpenedAt: Long?,
        val beforeCompletedAt: Long?,
        val beforeAttempts: Int
    )

    companion object {
        private const val DATABASE_NAME = "group_link_manager.db"
        private const val DATABASE_VERSION = 5
        private const val TABLE_SESSIONS = "sessions"
        private const val TABLE_LINKS = "links"
        private const val TABLE_EVENTS = "link_events"
        private const val EVENT_ACTION_RESULT = "RESULT"

        private val LINK_COLUMNS = arrayOf(
            "id",
            "session_id",
            "position_index",
            "url",
            "invite_code",
            "status",
            "source",
            "imported_at",
            "opened_at",
            "completed_at",
            "open_attempts",
            "result_code",
            "result_detail"
        )
    }
}
