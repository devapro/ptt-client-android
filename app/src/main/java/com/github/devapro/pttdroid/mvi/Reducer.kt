package com.github.devapro.pttdroid.mvi

import kotlin.reflect.KClass

interface Reducer<ACTION : NEXT, STATE, NEXT : Any, EVENT> {

    val actionClass: KClass<ACTION>

    @Throws(ClassCastException::class)
    suspend fun reduce(action: ACTION, state: STATE): Result<STATE, NEXT, EVENT?>

    infix fun <STATE, NEXT> STATE.to(that: NEXT?): Result<STATE, NEXT, EVENT> = Result(this, that)

    data class Result<out STATE, out NEXT_ACTION, out EVENT>(
        val state: STATE,
        val action: NEXT_ACTION? = null,
        val event: EVENT? = null
    )
}