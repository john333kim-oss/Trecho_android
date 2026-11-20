package app.trecho.run.ui

import android.graphics.Paint
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import app.trecho.run.core.TrackPoint
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Polyline
import java.io.File

/**
 * (Google 지도 키가 없을 때 쓰는 대체 지도) OpenStreetMap 지도에 경로를 그린다. follow=true면 마지막 위치를 따라가고(러닝 중),
 * false면 전체 경로가 보이게 맞춘다(기록 상세).
 *
 * 참고: OSM 기본 타일 서버는 개인·소량 사용만 허용한다. 서비스로 배포할 때는 상용 타일 제공자로 바꿔야 한다.
 */
@Composable
fun OsmRouteMap(points: List<TrackPoint>, follow: Boolean, lineColor: Color, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val mapView = remember {
        Configuration.getInstance().apply {
            userAgentValue = ctx.packageName
            osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
            osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        }
        MapView(ctx).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            controller.setZoom(16.5)
            isTilesScaledToDpi = true
        }
    }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> {}
            }
        }
        owner.lifecycle.addObserver(obs)
        onDispose {
            owner.lifecycle.removeObserver(obs)
            mapView.onDetach()
        }
    }
    AndroidView(
        factory = { mapView },
        modifier = modifier,
        update = { map ->
            map.overlays.removeAll { it is Polyline }
            if (points.isNotEmpty()) {
                val geo = points.map { GeoPoint(it.lat, it.lon) }
                val line = Polyline(map).apply {
                    setPoints(geo)
                    outlinePaint.color = lineColor.toArgb()
                    outlinePaint.strokeWidth = 12f
                    outlinePaint.strokeCap = Paint.Cap.ROUND
                    outlinePaint.strokeJoin = Paint.Join.ROUND
                }
                map.overlays.add(line)
                if (follow || geo.size < 2) {
                    map.controller.setCenter(geo.last())
                } else {
                    val box = BoundingBox.fromGeoPoints(geo).increaseByScale(1.25f)
                    map.post { map.zoomToBoundingBox(box, false) }
                }
            }
            map.invalidate()
        },
    )
}
