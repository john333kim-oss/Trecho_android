package app.trecho.run.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import app.trecho.run.core.TrackPoint
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.JointType
import com.google.android.gms.maps.model.LatLng
import com.google.android.gms.maps.model.LatLngBounds
import com.google.android.gms.maps.model.RoundCap
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.Polyline
import com.google.maps.android.compose.rememberCameraPositionState

/**
 * Google 지도에 경로를 그린다. follow=true면 마지막 위치를 따라가고(러닝 중),
 * false면 전체 경로가 보이게 맞춘다(기록 상세).
 */
@Composable
fun GoogleRouteMap(points: List<TrackPoint>, follow: Boolean, lineColor: Color, modifier: Modifier = Modifier, interactive: Boolean = true) {
    val camera = rememberCameraPositionState()
    var loaded by remember { mutableStateOf(false) }
    val latLngs = remember(points.size) { points.map { LatLng(it.lat, it.lon) } }

    GoogleMap(
        modifier = modifier,
        cameraPositionState = camera,
        uiSettings = MapUiSettings(
            zoomControlsEnabled = false, mapToolbarEnabled = false, compassEnabled = false,
            scrollGesturesEnabled = interactive, zoomGesturesEnabled = interactive,
            rotationGesturesEnabled = interactive, tiltGesturesEnabled = interactive,
        ),
        onMapLoaded = { loaded = true },
    ) {
        if (latLngs.size >= 2) {
            Polyline(
                points = latLngs,
                color = lineColor,
                width = 14f,
                jointType = JointType.ROUND,
                startCap = RoundCap(),
                endCap = RoundCap(),
            )
        }
    }

    LaunchedEffect(loaded, latLngs.size, follow) {
        if (!loaded || latLngs.isEmpty()) return@LaunchedEffect
        if (follow || latLngs.size < 2) {
            val zoom = if (camera.position.zoom < 12f) 16.5f else camera.position.zoom
            camera.move(CameraUpdateFactory.newLatLngZoom(latLngs.last(), zoom))
        } else {
            val b = LatLngBounds.builder().apply { latLngs.forEach { include(it) } }.build()
            camera.move(CameraUpdateFactory.newLatLngBounds(b, 80))
        }
    }
}
