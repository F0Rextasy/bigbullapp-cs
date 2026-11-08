package com.inattv

import com.bigbull.common.BaseSettings

object Settings : BaseSettings("bigbull_inattv") {
    override fun rowEnabled(name: String): Boolean {
        InatTVPlugin.purgeLegacyInat()
        return super.rowEnabled(name)
    }
}
