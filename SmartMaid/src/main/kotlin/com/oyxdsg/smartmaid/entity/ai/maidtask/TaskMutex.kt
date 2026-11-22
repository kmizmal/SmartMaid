package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.google.gson.*
import net.minecraft.core.BlockPos

object TaskMutex {
    const val WILDCARD = "*"
    @JvmStatic fun verb(cmd: String): String? = when (cmd) { "smelt","craft","mine","harvest","attack","guard","feed","eat","move","look","break","place","use" -> cmd; "chestopen","chestput","chesttake","cheststore" -> "chest"; "transfer","equip","store","drop","pickup" -> "carry"; "script" -> null; else -> "other:$cmd" }
    @JvmStatic fun key(cmd: String, params: JsonObject, base: BlockPos?): String? = verb(cmd)?.let { "$it:${target(it,params,base)}" }
    @JvmStatic fun possibleOverlap(a: String?, b: String?): Boolean { if (a==null||b==null||a==b) return false; val ia=a.indexOf(':'); val ib=b.indexOf(':'); return ia>=0&&ib>=0&&a.substring(0,ia)==b.substring(0,ib)&&(isGeneral(a)||isGeneral(b)) }
    private fun isGeneral(key:String)=key.substringAfter(':',"").contains(WILDCARD)
    private fun target(v:String,p:JsonObject,b:BlockPos?)=when(v){"smelt","craft","feed","eat","carry"->itemKey(p);"attack"->entityKey(p);"guard"->WILDCARD;"harvest"->produceKey(p);"mine","move","look","break","place","use","chest"->posKey(p,b);else->WILDCARD}
    private fun itemKey(p:JsonObject)=if(p.has("item")&&!p["item"].isJsonNull) normId(p["item"].asString) else WILDCARD
    private fun entityKey(p:JsonObject)=if(p.has("target")&&!p["target"].isJsonNull) normId(p["target"].asString) else WILDCARD
    private fun produceKey(p:JsonObject):String { if(p.has("produce")&&!p["produce"].isJsonNull){val x=p["produce"].asString;if(x.isNotEmpty()){val n=normId(x);return "tree:"+(if(n.startsWith("#"))n else family(n))}};return "tree:$WILDCARD" }
    @JvmStatic fun family(id:String):String { var ns=""; var s=id; val c=s.indexOf(':'); if(c>=0){ns=s.substring(0,c+1);s=s.substring(c+1)}; if(s.startsWith("stripped_"))s=s.removePrefix("stripped_"); listOf("_log","_wood","_planks","_sapling","_leaves").firstOrNull{s.endsWith(it)}?.let{s=s.removeSuffix(it)}; return ns+s }
    @JvmStatic fun normId(raw:String?):String { if(raw.isNullOrEmpty())return WILDCARD; val s=raw.trim().lowercase(); if(s.startsWith("#")){val b=s.substring(1);return "#"+(if(':' in b)b else "minecraft:$b")}; return if(':' in s)s else "minecraft:$s" }
    private fun posKey(p:JsonObject,b:BlockPos?):String { if(!p.has("pos")||!p["pos"].isJsonArray)return WILDCARD; val a=p["pos"].asJsonArray;if(a.size()<3)return WILDCARD;val x=coord(a[0],b?.x);val y=coord(a[1],b?.y);val z=coord(a[2],b?.z);return if(x==null||y==null||z==null)WILDCARD else "@$x,$y,$z" }
    private fun coord(e:JsonElement,base:Int?):Int? { if(e.isJsonPrimitive&&e.asJsonPrimitive.isNumber)return e.asInt; val s=try{e.asString}catch(_:Exception){return null};if(s.startsWith("~")){if(base==null)return null;val r=s.substring(1).trim();if(r.isEmpty())return base;return base+(r.toIntOrNull()?:return null)};return s.toIntOrNull() }
}
