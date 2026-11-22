package com.oyxdsg.smartmaid.entity.ai.maidtask

import com.oyxdsg.smartmaid.entity.ai.maidtask.QueuedTask.*

class MaidTaskQueue {
    companion object { const val SHORT_CAPACITY=16; const val LONG_CAPACITY=4 }
    private val shortQueue=ArrayList<QueuedTask>(); private val longQueue=ArrayList<QueuedTask>(); private var currentTask:QueuedTask?=null
    fun current() = currentTask
    fun hasCurrent()=currentTask!=null; fun shortSize()=shortQueue.size; fun longSize()=longQueue.size; fun isEmpty()=currentTask==null&&shortQueue.isEmpty()&&longQueue.isEmpty(); fun shortItems()=shortQueue.toList(); fun longItems()=longQueue.toList()
    fun findById(id:String)=findIn(shortQueue,id)?:findIn(longQueue,id)
    fun enqueue(task:QueuedTask)=enqueue(task,false)
    fun enqueue(task:QueuedTask,front:Boolean):Boolean { val q=list(task.queue);if(q.size>=capacity(task.queue))return false;task.state=State.QUEUED;task.pauseReason=null;if(front)q.add(0,task)else q.add(task);reindex(task.queue);return true }
    fun peekNext()=shortQueue.firstOrNull()?:longQueue.firstOrNull()
    fun startNext(tick:Long):QueuedTask? { val n=peekNext()?:return null;list(n.queue).remove(n);reindex(n.queue);n.state=State.RUNNING;n.pauseReason=null;n.startedAtTick=tick;n.lastActiveTick=tick;currentTask=n;return n }
    fun finishCurrent(end:State,result:String?) { val c=currentTask?:return;c.state=end;c.result=result;currentTask=null;if(c.queue==QueueKind.LONG&&end==State.DONE){c.state=State.QUEUED;longQueue.add(0,c);reindex(QueueKind.LONG)} }
    fun touch(tick:Long){currentTask?.lastActiveTick=tick}
    fun cancelCurrent():Boolean { val c=currentTask?:return false;c.state=State.CANCELLED;currentTask=null;return true }
    fun pauseCurrent(reason:PauseReason,degrade:Boolean):Boolean { val c=currentTask?:return false;currentTask=null;c.state=State.PAUSED;c.pauseReason=reason;c.interruptCount++;val q=list(c.queue);q.add(if(degrade)minOf(1,q.size) else 0,c);reindex(c.queue);return true }
    fun resume(id:String):Boolean { for(k in QueueKind.values()){val q=list(k);val i=q.indexOfFirst{it.id==id&&it.state==State.PAUSED};if(i>=0){val t=q.removeAt(i);t.state=State.QUEUED;t.pauseReason=null;q.add(0,t);reindex(k);return true}};return false }
    fun remove(kind:QueueKind,id:String)=list(kind).removeIf{it.id==id}.also{if(it)reindex(kind)}
    fun stop(id:String)=remove(QueueKind.SHORT,id)||remove(QueueKind.LONG,id)
    fun moveUp(k:QueueKind,id:String):Boolean {val q=list(k);val i=q.indexOfFirst{it.id==id};if(i<=0)return false;val t=q.removeAt(i);q.add(i-1,t);reindex(k);return true}
    fun move(k:QueueKind,id:String,to:Int):Boolean {val q=list(k);if(to<0||to>=q.size)return false;val i=q.indexOfFirst{it.id==id};if(i<0)return false;val t=q.removeAt(i);q.add(to,t);reindex(k);return true}
    fun promote(id:String):QueuedTask? {val t=findIn(longQueue,id)?:return null;longQueue.remove(t);reindex(QueueKind.LONG);if(currentTask!=null)pauseCurrent(PauseReason.PREEMPTED,true);t.state=State.RUNNING;t.pauseReason=null;currentTask=t;return t}
    fun clear(k:QueueKind)=list(k).clear();fun clearAll(){shortQueue.clear();longQueue.clear();currentTask=null}
    private fun list(k:QueueKind)=if(k==QueueKind.LONG)longQueue else shortQueue;private fun capacity(k:QueueKind)=if(k==QueueKind.LONG)LONG_CAPACITY else SHORT_CAPACITY;private fun findIn(q:List<QueuedTask>,id:String)=q.firstOrNull{it.id==id};private fun reindex(k:QueueKind){list(k).forEachIndexed{i,t->t.seq=i}}
}
