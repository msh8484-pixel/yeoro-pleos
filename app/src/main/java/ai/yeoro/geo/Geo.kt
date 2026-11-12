package ai.yeoro.geo
import kotlin.math.*
fun haversineKm(a: Pair<Double, Double>, b: Pair<Double, Double>): Double {
    val r = 6371.0; val dLat = Math.toRadians(b.first - a.first); val dLon = Math.toRadians(b.second - a.second)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.first)) * cos(Math.toRadians(b.first)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
