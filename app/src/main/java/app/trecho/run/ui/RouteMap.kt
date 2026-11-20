package app.trecho.run.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.trecho.run.BuildConfig
import app.trecho.run.core.TrackPoint

/**
 * 경로 지도. 빌드할 때 Google Maps API 키(MAPS_API_KEY)가 들어가면 Google 지도를,
 * 없으면 OpenStreetMap 지도를 쓴다. 키가 없어도 빌드·실행은 된다.
 */
@Composable
fun RouteMap(points: List<TrackPoint>, follow: Boolean, lineColor: Color, modifier: Modifier = Modifier) {
    if (BuildConfig.HAS_MAPS_KEY) GoogleRouteMap(points, follow, lineColor, modifier)
    else OsmRouteMap(points, follow, lineColor, modifier)
}
