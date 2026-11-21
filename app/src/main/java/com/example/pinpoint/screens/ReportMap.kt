package com.example.pinpoint.screens

import android.os.Bundle
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.example.pinpoint.data.Report
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File

private object MapNetwork {
    private var initialized=false
    @Synchronized fun initialize(context: android.content.Context) {
        if(initialized) return
        HttpRequestUtil.setOkHttpClient(OkHttpClient.Builder()
            .cache(Cache(File(context.cacheDir,"map_http"),20L*1024*1024))
            .addInterceptor { chain -> chain.proceed(chain.request().newBuilder()
                .header("User-Agent","PinPointCitizen/1.0 (+https://github.com/ahmya19/Mobile_Comp_FrontEnd)").build()) }.build())
        initialized=true
    }
}

private const val REPORT_SOURCE="reports"
private const val REPORT_LAYER="reports-layer"

private fun List<Report>.toFeatures(): FeatureCollection = FeatureCollection.fromFeatures(mapNotNull { report ->
    report.mapCoordinate()?.let { Feature.fromGeometry(Point.fromLngLat(it.longitude,it.latitude)).apply { addStringProperty("id",report.id) } }
})

private fun Report.mapCoordinate(): LatLng? {
    val lat=latitude ?: return null
    val lon=longitude ?: return null
    return if(lat.isFinite() && lon.isFinite() && lat in -90.0..90.0 && lon in -180.0..180.0) LatLng(lat,lon) else null
}

/** Free raster map. Only visible tiles are fetched; no bulk/offline downloads. */
@Composable
fun ReportMap(reports: List<Report>,modifier: Modifier=Modifier,
    onReportClick: (String)->Unit={},onPick: ((Double,Double)->Unit)?=null) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestReports by rememberUpdatedState(reports)
    val reportClick by rememberUpdatedState(onReportClick)
    val pick by rememberUpdatedState(onPick)
    val view=remember(context) { MapLibre.getInstance(context);MapNetwork.initialize(context.applicationContext); MapView(context) }
    var ready by remember { mutableStateOf<MapLibreMap?>(null) }
    var framed by remember { mutableStateOf(false) }
    DisposableEffect(view,lifecycle) {
        view.onCreate(Bundle())
        var destroyed=false
        val listener=LifecycleEventObserver { _,event -> when(event) {
            Lifecycle.Event.ON_START->view.onStart()
            Lifecycle.Event.ON_RESUME->view.onResume()
            Lifecycle.Event.ON_PAUSE->view.onPause()
            Lifecycle.Event.ON_STOP->view.onStop()
            Lifecycle.Event.ON_DESTROY->{ view.onDestroy();destroyed=true }
            else->Unit
        } }
        lifecycle.addObserver(listener)
        view.getMapAsync { map ->
            map.setStyle(Style.Builder().fromJson("""{"version":8,"sources":{"osm":{"type":"raster","tiles":["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],"tileSize":256,"attribution":"© OpenStreetMap contributors"}},"layers":[{"id":"osm","type":"raster","source":"osm"}]}""")) {
                // Core style APIs (source + layer) replace the deprecated marker annotations.
                it.addSource(GeoJsonSource(REPORT_SOURCE,latestReports.toFeatures()))
                it.addLayer(CircleLayer(REPORT_LAYER,REPORT_SOURCE).withProperties(
                    circleColor("#7C4DCC"),circleRadius(9f),circleStrokeColor("#FFFFFF"),circleStrokeWidth(2f)))
                map.cameraPosition=CameraPosition.Builder().target(LatLng(13.7565,121.0583)).zoom(13.0).build()
                ready=map
            }
            map.addOnMapClickListener { coordinate ->
                val hits=map.queryRenderedFeatures(map.projection.toScreenLocation(coordinate),REPORT_LAYER)
                val id=hits.firstOrNull()?.getStringProperty("id")
                if(id!=null && latestReports.any { it.id==id }) { reportClick(id);true } else false
            }
            map.addOnMapLongClickListener { coordinate -> pick?.invoke(coordinate.latitude,coordinate.longitude);pick!=null }
        }
        onDispose { lifecycle.removeObserver(listener);if(!destroyed) { view.onPause();view.onStop();view.onDestroy() } }
    }
    LaunchedEffect(ready,reports) {
        ready?.let { map ->
            map.style?.getSourceAs<GeoJsonSource>(REPORT_SOURCE)?.setGeoJson(reports.toFeatures())
            // Frame the community's reports once, so pins are visible without manual panning.
            val points=reports.mapNotNull { it.mapCoordinate() }
            if(onPick==null && !framed && points.isNotEmpty()) {
                framed=true
                if(points.size==1) map.cameraPosition=CameraPosition.Builder().target(points.first()).zoom(15.0).build()
                else map.easeCamera(CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.Builder().includes(points).build(),80),600)
            }
            if(onPick!=null && reports.size==1) reports.first().let { point ->
                point.mapCoordinate()?.let { coordinate -> map.cameraPosition=CameraPosition.Builder(map.cameraPosition)
                    .target(coordinate).build() }
            }
        }
    }
    AndroidView(factory={view},modifier=modifier)
}
