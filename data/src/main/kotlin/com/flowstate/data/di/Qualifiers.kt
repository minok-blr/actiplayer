package com.flowstate.data.di

import javax.inject.Qualifier

/**
 * The application-lifetime coroutine scope. It runs on the main dispatcher on purpose:
 * sensors, engine and player commands are single-threaded by design (DECISIONS.md), and
 * the repositories only ever suspend into Room's own executors from here.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
