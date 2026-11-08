package com.nativeauto.trip

/**
 * Where the trip is in its life cycle.
 * - [Idle]: nothing tracked yet (or the last trip was cleared).
 * - [Running]: collecting GPS fixes.
 * - [Paused]: GPS off and the trip clock frozen; Resume continues the same trip.
 * - [Ended]: figures frozen for review; the next start begins a fresh trip.
 */
enum class TripState { Idle, Running, Paused, Ended }
