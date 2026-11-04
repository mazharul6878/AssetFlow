package com.example.data.worker

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * In-memory thread-safe coordinator tracking sync requests, generations, and worker sessions.
 * Guarantees race-safe handoff between Room persistence and WorkManager execution.
 */
object SyncCoordinator {

    private val requestGenerations = ConcurrentHashMap<String, AtomicLong>()
    private val activeWorkerSessions = ConcurrentHashMap<String, String>()
    private val isDraining = ConcurrentHashMap<String, AtomicBoolean>()
    private val coalescedRequests = ConcurrentHashMap<String, AtomicLong>()

    private fun getScopeKey(clientId: String?, cycleId: String?): String {
        return if (!clientId.isNullOrBlank() && !cycleId.isNullOrBlank()) {
            "$clientId:$cycleId"
        } else {
            "global"
        }
    }

    /**
     * Increments the sync request generation for the specified client and cycle scope.
     * Called whenever a submission is persisted to Room or an explicit sync is triggered.
     */
    fun notifySyncRequested(clientId: String? = null, cycleId: String? = null): Long {
        val key = getScopeKey(clientId, cycleId)
        val counter = requestGenerations.computeIfAbsent(key) { AtomicLong(0L) }
        return counter.incrementAndGet()
    }

    /**
     * Registers a coalesced redundant scheduling request that was suppressed from creating a new WorkManager node.
     */
    fun notifyCoalesced(clientId: String? = null, cycleId: String? = null): Long {
        val key = getScopeKey(clientId, cycleId)
        val counter = coalescedRequests.computeIfAbsent(key) { AtomicLong(0L) }
        return counter.incrementAndGet()
    }

    /**
     * Returns total number of redundant scheduling requests coalesced for the scope.
     */
    fun getCoalescedCount(clientId: String? = null, cycleId: String? = null): Long {
        val key = getScopeKey(clientId, cycleId)
        return coalescedRequests[key]?.get() ?: 0L
    }

    /**
     * Returns the current request generation for a given client and cycle scope.
     */
    fun getRequestGeneration(clientId: String? = null, cycleId: String? = null): Long {
        val key = getScopeKey(clientId, cycleId)
        return requestGenerations[key]?.get() ?: 0L
    }

    /**
     * Checks if new sync requests have been registered since the given generation baseline.
     */
    fun hasNewRequestsSince(clientId: String? = null, cycleId: String? = null, baselineGeneration: Long): Boolean {
        val key = getScopeKey(clientId, cycleId)
        val current = requestGenerations[key]?.get() ?: 0L
        return current > baselineGeneration
    }

    /**
     * Registers a running worker execution session.
     */
    fun registerWorkerStart(workerSessionId: String, clientId: String? = null, cycleId: String? = null) {
        val key = getScopeKey(clientId, cycleId)
        activeWorkerSessions[workerSessionId] = key
        isDraining.computeIfAbsent(key) { AtomicBoolean(false) }.set(true)
    }

    /**
     * Unregisters a worker execution session on completion or termination.
     */
    fun registerWorkerExit(workerSessionId: String, clientId: String? = null, cycleId: String? = null) {
        val key = getScopeKey(clientId, cycleId)
        activeWorkerSessions.remove(workerSessionId)
        isDraining[key]?.set(false)
    }

    /**
     * Checks if a worker session is currently active in this process.
     */
    fun isSessionActive(workerSessionId: String?): Boolean {
        if (workerSessionId == null) return false
        return activeWorkerSessions.containsKey(workerSessionId)
    }

    /**
     * Clears all coordinator state (used for test isolation).
     */
    fun resetForTesting() {
        requestGenerations.clear()
        activeWorkerSessions.clear()
        isDraining.clear()
        coalescedRequests.clear()
    }
}
