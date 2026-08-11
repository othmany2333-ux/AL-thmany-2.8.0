package com.althmany.groupmanager

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import com.althmany.groupmanager.data.AppPreferences
import com.althmany.groupmanager.data.GroupLinkDatabase
import com.althmany.groupmanager.data.GroupLinkRepository
import com.althmany.groupmanager.util.QuickJoinNotification
import com.althmany.groupmanager.util.nightMode

class GroupManagerApp : Application() {
    val preferences: AppPreferences by lazy { AppPreferences(this) }
    val database: GroupLinkDatabase by lazy { GroupLinkDatabase(this) }
    val repository: GroupLinkRepository by lazy {
        GroupLinkRepository(database = database, preferences = preferences)
    }

    override fun onCreate() {
        super.onCreate()
        preferences.applyExecutableRuntimeMigration()
        AppCompatDelegate.setDefaultNightMode(preferences.themeMode.nightMode)
        QuickJoinNotification.createChannel(this)
    }
}
