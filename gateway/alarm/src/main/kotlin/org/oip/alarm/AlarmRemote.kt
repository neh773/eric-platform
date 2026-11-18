package org.oip.alarm

import java.time.Duration

/**
 * Live alarms read from a peer gateway.
 *
 * Event ids and sources use the local remote-provider name as a prefix, the same way a
 * remote tag provider appears as `[name]path`. An acknowledgement or shelf is sent to the
 * gateway that owns the alarm.
 */
/** The person on the requesting gateway. Empty roles and zone mean the caller named none. */
data class RemoteCaller(
    val user: String = "",
    val roles: Set<String> = emptySet(),
    val zone: String = "",
)

interface AlarmRemote {
    fun query(filter: AlarmFilter): List<AlarmEventView>
    fun query(filter: AlarmFilter, caller: RemoteCaller): List<AlarmEventView> = query(filter)
    fun ownsEvent(eventId: String): Boolean
    fun ownsSource(source: String): Boolean
    fun acknowledge(eventIds: List<String>, user: String, notes: String?)
    fun acknowledge(eventIds: List<String>, user: String, notes: String?, caller: RemoteCaller): Unit =
        acknowledge(eventIds, user, notes)
    fun shelve(sources: List<String>, duration: Duration)
    fun shelve(sources: List<String>, duration: Duration, caller: RemoteCaller): Unit = shelve(sources, duration)
    fun unshelve(sources: List<String>)
    fun unshelve(sources: List<String>, caller: RemoteCaller): Unit = unshelve(sources)
    /** Shelves on peers that own them. Empty when no peer implements a shelved listing. */
    fun shelved(filter: AlarmFilter): List<ShelvedAlarmView> = emptyList()
    fun shelved(filter: AlarmFilter, caller: RemoteCaller): List<ShelvedAlarmView> = shelved(filter)
}
