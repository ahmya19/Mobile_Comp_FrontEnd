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
import org.maplibre.android.annotations.MarkerOptions
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.module.http.HttpRequestUtil
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
                map.cameraPosition=CameraPosition.Builder().target(LatLng(13.7565,121.0583)).zoom(13.0).build()
                ready=map
            }
            map.setOnMarkerClickListener { marker ->
                latestReports.firstOrNull { it.id==marker.snippet }?.let { reportClick(it.id) }
                true
            }
            map.addOnMapLongClickListener { coordinate -> pick?.invoke(coordinate.latitude,coordinate.longitude);pick!=null }
        }
        onDispose { lifecycle.removeObserver(listener);if(!destroyed) { view.onPause();view.onStop();view.onDestroy() } }
    }
    LaunchedEffect(ready,reports) {
        ready?.let { map ->
            map.clear()
            reports.forEach { report -> report.mapCoordinate()?.let { coordinate ->
                map.addMarker(MarkerOptions().position(coordinate)
                    .title(report.title).snippet(report.id))
            } }
            if(onPick!=null && reports.size==1) reports.first().let { point ->
                point.mapCoordinate()?.let { coordinate -> map.cameraPosition=CameraPosition.Builder(map.cameraPosition)
                    .target(coordinate).build() }
            }
        }
    }
    AndroidView(factory={view},modifier=modifier)
}
