package com.demandmap.app.domain

enum class ServiceType { TAXI, COURIER }

data class DemandPoint(
    val lat: Double,
    val lon: Double,
    val coefficient: Double,
    val bonusRub: Int,
    val distanceM: Double,
)

data class GeoPointSimple(val lat: Double, val lon: Double)
