package com.lk.studyassistant.quantum.data

import android.content.Context

class FloatingProtectionStore(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getBlockedPackages(): Set<String> {
        return prefs.getStringSet(KEY_BLOCKED_PACKAGES, emptySet()).orEmpty()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toSet()
    }

    fun saveBlockedPackages(packages: Set<String>) {
        prefs.edit()
            .putStringSet(
                KEY_BLOCKED_PACKAGES,
                packages.map { it.trim() }.filter { it.isNotBlank() }.toSet()
            )
            .apply()
    }

    fun isBlocked(packageName: String?): Boolean {
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isBlank()) return false
        return pkg in getBlockedPackages()
    }

    companion object {
        private const val PREFS_NAME = "floating_privacy_protection"
        private const val KEY_BLOCKED_PACKAGES = "blockedPackages"
    }
}
