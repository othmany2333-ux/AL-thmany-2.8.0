package com.althmany.groupmanager.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ShizukuUiDumpParserTest {
    @Test
    fun `parses a bounded join action from uiautomator xml`() {
        val xml = """<hierarchy rotation="0"><node text="Group invite" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[0,0][500,100]"/><node text="Join group" resource-id="com.whatsapp:id/join_button" class="android.widget.Button" package="com.whatsapp" content-desc="" clickable="true" enabled="true" bounds="[100,1200][900,1320]"/></hierarchy>"""
        val snapshot = ShizukuUiDumpParser.parse(xml)
        assertEquals(AutomationScreenKind.JOIN_ACTION, snapshot.screenKind)
        val node = snapshot.actionNode(AccessibilityJoinAction.JOIN)
        assertNotNull(node)
        assertEquals(500, node!!.bounds!!.centerX)
        assertEquals(1260, node.bounds!!.centerY)
    }

    @Test
    fun `never returns a destructive cancel request node as join action`() {
        val xml = """<hierarchy rotation="0"><node text="إلغاء الطلب" resource-id="com.whatsapp:id/cancel_join_button" class="android.widget.Button" package="com.whatsapp" content-desc="" clickable="true" enabled="true" bounds="[100,1200][900,1320]"/></hierarchy>"""
        val snapshot = ShizukuUiDumpParser.parse(xml)
        assertNull(snapshot.actionNode(AccessibilityJoinAction.JOIN))
        assertNull(snapshot.actionNode(AccessibilityJoinAction.REQUEST))
    }

    @Test
    fun `keeps an exact flattened work profile join label as a guarded target`() {
        val xml = """<hierarchy rotation="0"><node text="دعوة المجموعة" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[0,0][500,100]"/><node text="الانضمام إلى المجموعة" resource-id="" class="android.widget.TextView" package="com.whatsapp" content-desc="" clickable="false" enabled="true" bounds="[100,1200][900,1320]"/></hierarchy>"""
        val snapshot = ShizukuUiDumpParser.parse(xml)

        val node = snapshot.actionNode(AccessibilityJoinAction.JOIN, "com.whatsapp")
        assertNotNull(node)
        assertEquals(false, node!!.clickable)
        assertEquals(500, node.bounds!!.centerX)
    }
}
