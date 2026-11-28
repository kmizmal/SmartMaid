package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.JsonObject
import com.oyxdsg.smartmaid.entity.SmartMaidEntity

interface Resumable {
    fun saveState(): JsonObject
    fun restoreState(state: JsonObject?)
    fun stateVersion(): Int
    fun validateState(maid: SmartMaidEntity?, state: JsonObject?): Boolean
}
