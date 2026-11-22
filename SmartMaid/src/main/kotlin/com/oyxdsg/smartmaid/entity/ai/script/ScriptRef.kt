package com.oyxdsg.smartmaid.entity.ai.script

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive

/** 脚本变量、条件和整数算术解析。 */
object ScriptRef {
    @JvmStatic
    fun resolve(params: JsonObject?, ctx: ScriptContext): JsonObject {
        val out = JsonObject()
        if (params == null) return out
        for ((key, value) in params.entrySet()) out.add(key, resolveValue(value, ctx))
        return out
    }

    @JvmStatic
    fun resolveValue(element: JsonElement?, ctx: ScriptContext): JsonElement? {
        if (element == null || element.isJsonNull) return element
        if (element.isJsonPrimitive && element.asJsonPrimitive.isString) {
            val ref = resolveRef(element.asString, ctx)
            return ref ?: element
        }
        if (element.isJsonArray) {
            val out = JsonArray()
            for (x in element.asJsonArray) out.add(resolveValue(x, ctx))
            return out
        }
        if (element.isJsonObject) {
            val out = JsonObject()
            for ((key, value) in element.asJsonObject.entrySet()) out.add(key, resolveValue(value, ctx))
            return out
        }
        return element
    }

    private fun resolveRef(s: String?, ctx: ScriptContext): JsonElement? {
        if (s.isNullOrEmpty()) return null
        if (s.startsWith('$')) refElement(s, ctx)?.let { return it }
        if ('+' in s || '-' in s) evalInt(s, ctx)?.let { return JsonPrimitive(it) }
        return null
    }

    @JvmStatic
    fun refElement(ref: String?, ctx: ScriptContext): JsonElement? {
        if (ref == null || !ref.startsWith('$')) return null
        val path = ref.substring(1)
        if (path == "last" || path.startsWith("last.")) {
            val rest = if (path == "last") "" else path.removePrefix("last.")
            return descend(ctx.lastResult, rest)
        }
        val dot = path.indexOf('.')
        val key = if (dot < 0) path else path.substring(0, dot)
        val value = ctx.vars[key] ?: return null
        val rest = if (dot < 0) "" else path.substring(dot + 1)
        return descend(value, rest)
    }

    private fun descend(initial: JsonElement?, rest: String): JsonElement? {
        if (rest.isEmpty()) return initial?.deepCopy()
        var cur = initial
        for (part in rest.split('.')) {
            if (cur == null || !cur.isJsonObject) return null
            cur = cur.asJsonObject[part]
        }
        return cur?.deepCopy()
    }

    @JvmStatic
    fun evalInt(expr: String?, ctx: ScriptContext): Int? {
        if (expr.isNullOrEmpty()) return null
        val sb = StringBuilder()
        var i = 0
        while (i < expr.length) {
            val c = expr[i]
            if (c == '$') {
                var j = i + 1
                while (j < expr.length && (expr[j].isLetterOrDigit() || expr[j] == '_' || expr[j] == '.')) j++
                val value = refElement(expr.substring(i, j), ctx)
                if (value == null || !value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
                sb.append(value.asBigDecimal.toBigInteger())
                i = j
            } else {
                sb.append(c)
                i++
            }
        }
        val text = sb.toString()
        var value = 0
        var sign = 1
        var hasNumber = false
        var k = 0
        while (k < text.length) {
            when (val c = text[k]) {
                ' ', '\t' -> k++
                '+' -> { sign = 1; k++ }
                '-' -> { sign = -1; k++ }
                else -> if (c.isDigit()) {
                    var number = 0L
                    while (k < text.length && text[k].isDigit()) {
                        number = number * 10 + (text[k] - '0')
                        k++
                    }
                    value += sign * number.toInt()
                    hasNumber = true
                    sign = 1
                } else return null
            }
        }
        return if (hasNumber) value else null
    }

    @JvmStatic
    fun evalCond(cond: JsonObject?, ctx: ScriptContext): Boolean {
        if (cond == null) return true
        val varSpec = if (cond.has("var")) cond["var"].asString else ""
        val op = if (cond.has("op")) cond["op"].asString else "exists"
        val value = if (varSpec.startsWith('$')) refElement(varSpec, ctx) else refElement("$" + varSpec, ctx)
        val expected = if (cond.has("val")) resolveValue(cond["val"], ctx) else null
        return when (op) {
            "exists" -> value != null
            "eq" -> jsonEquals(value, expected)
            "ne" -> !jsonEquals(value, expected)
            "gt" -> compareNumeric(value, expected) > 0
            "ge" -> compareNumeric(value, expected) >= 0
            "lt" -> compareNumeric(value, expected) < 0
            "le" -> compareNumeric(value, expected) <= 0
            else -> false
        }
    }

    private fun jsonEquals(a: JsonElement?, b: JsonElement?): Boolean {
        if (a == null || b == null) return a == b
        if (a.isJsonPrimitive && b.isJsonPrimitive) {
            val pa = a.asJsonPrimitive
            val pb = b.asJsonPrimitive
            if (pa.isBoolean || pb.isBoolean) return pa.isBoolean && pb.isBoolean && pa.asBoolean == pb.asBoolean
            if (pa.isNumber && pb.isNumber) return pa.asBigDecimal.compareTo(pb.asBigDecimal) == 0
            return pa.asString == pb.asString
        }
        return a == b
    }

    private fun compareNumeric(a: JsonElement?, b: JsonElement?): Int {
        if (a == null || b == null || !a.isJsonPrimitive || !b.isJsonPrimitive) return -2
        return try { a.asDouble.compareTo(b.asDouble) } catch (_: Exception) { -2 }
    }
}
