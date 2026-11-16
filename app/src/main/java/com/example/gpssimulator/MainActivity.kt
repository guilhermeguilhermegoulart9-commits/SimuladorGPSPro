package com.example.gpssimulator

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import kotlin.math.*

class MainActivity : Activity() {
    private lateinit var locationManager: LocationManager
    private lateinit var map: WebView
    private lateinit var status: TextView
    private lateinit var speedText: TextView
    private lateinit var speedBar: SeekBar
    private val handler = Handler(Looper.getMainLooper())
    private var route = mutableListOf<Pair<Double, Double>>()
    private var segment = 0
    private var segmentProgress = 0.0
    private var running = false
    private var paused = false
    private var speedKmh = 30
    private var providerReady = false

    private val movement = object : Runnable {
        override fun run() {
            if (!running || paused || route.size < 2) return
            advanceAlongRoute()
            publishCurrentPoint()
            handler.postDelayed(this, 1000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager
        buildUi()
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(18, 16, 18, 12)
        }
        root.addView(TextView(this).apply {
            text = "Simulador GPS Pro"
            textSize = 25f
            setPadding(0, 0, 0, 8)
        })
        root.addView(TextView(this).apply {
            text = "Pesquise um local, toque no mapa para marcar o destino e trace uma rota."
            textSize = 13f
        })
        map = WebView(this)
        map.settings.javaScriptEnabled = true
        map.settings.domStorageEnabled = true
        map.webChromeClient = WebChromeClient()
        map.webViewClient = WebViewClient()
        map.addJavascriptInterface(MapBridge(), "AndroidGPS")
        root.addView(map, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ))
        speedText = TextView(this).apply { text = "Velocidade: 30 km/h"; textSize = 14f }
        root.addView(speedText)
        speedBar = SeekBar(this).apply {
            max = 119
            progress = 29
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    speedKmh = progress + 1
                    speedText.text = "Velocidade: $speedKmh km/h"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        root.addView(speedBar)
        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        buttons.addView(Button(this).apply {
            text = "INICIAR"
            setOnClickListener { startRoute() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "PAUSAR"
            setOnClickListener { pauseRoute() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        buttons.addView(Button(this).apply {
            text = "PARAR"
            setOnClickListener { stopRoute() }
        }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(buttons)
        status = TextView(this).apply {
            text = "Status: escolha origem e destino no mapa."
            textSize = 13f
            setPadding(0, 6, 0, 0)
        }
        root.addView(status)
        setContentView(root)
        map.loadUrl("file:///android_asset/map.html")
    }

    inner class MapBridge {
        @JavascriptInterface
        fun routeReady(json: String) {
            runOnUiThread {
                try {
                    val arr = JSONArray(json)
                    route.clear()
                    for (i in 0 until arr.length()) {
                        val point = arr.getJSONArray(i)
                        route.add(Pair(point.getDouble(1), point.getDouble(0)))
                    }
                    segment = 0
                    segmentProgress = 0.0
                    status.text = "Rota carregada: ${route.size} pontos. Escolha a velocidade e inicie."
                } catch (_: Exception) {
                    status.text = "Não foi possível ler a rota."
                }
            }
        }

        @JavascriptInterface
        fun mapMessage(message: String) {
            runOnUiThread { status.text = message }
        }
    }

    private fun startRoute() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Permita a localização e tente novamente.", Toast.LENGTH_LONG).show()
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 10)
            return
        }
        if (route.size < 2) {
            Toast.makeText(this, "Pesquise dois locais e toque em Traçar rota.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            if (!providerReady) {
                try {
                    locationManager.addTestProvider(
                        LocationManager.GPS_PROVIDER, false, false, false, false,
                        true, true, true, Criteria.POWER_LOW, Criteria.ACCURACY_FINE
                    )
                } catch (_: IllegalArgumentException) { }
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
                providerReady = true
            }
            if (segment >= route.size - 1) {
                segment = 0
                segmentProgress = 0.0
            }
            running = true
            paused = false
            publishCurrentPoint()
            handler.removeCallbacks(movement)
            handler.postDelayed(movement, 1000L)
            status.text = "Simulação ativa • $speedKmh km/h"
        } catch (_: SecurityException) {
            Toast.makeText(this, "Selecione Simulador GPS nas Opções do desenvolvedor.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Falha ao iniciar: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun pauseRoute() {
        if (!running) return
        paused = !paused
        if (paused) {
            handler.removeCallbacks(movement)
            status.text = "Simulação pausada."
        } else {
            status.text = "Simulação ativa • $speedKmh km/h"
            handler.postDelayed(movement, 1000L)
        }
    }

    private fun stopRoute() {
        running = false
        paused = false
        handler.removeCallbacks(movement)
        try {
            if (providerReady) {
                locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
                locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
                providerReady = false
            }
        } catch (_: Exception) { }
        status.text = "Simulação parada."
    }

    private fun advanceAlongRoute() {
        if (segment >= route.size - 1) {
            running = false
            handler.removeCallbacks(movement)
            status.text = "Você chegou ao destino."
            return
        }
        val a = route[segment]
        val b = route[segment + 1]
        val distance = distanceMeters(a.first, a.second, b.first, b.second).coerceAtLeast(1.0)
        val step = speedKmh * 1000.0 / 3600.0
        segmentProgress += step / distance
        while (segmentProgress >= 1.0 && segment < route.size - 1) {
            segmentProgress -= 1.0
            segment++
            if (segment >= route.size - 1) {
                segment = route.size - 1
                segmentProgress = 0.0
                break
            }
        }
        map.evaluateJavascript("setSimulatorProgress($segment)", null)
    }

    private fun publishCurrentPoint() {
        if (route.isEmpty()) return
        val point = if (segment >= route.size - 1) route.last() else {
            val a = route[segment]
            val b = route[segment + 1]
            Pair(
                a.first + (b.first - a.first) * segmentProgress,
                a.second + (b.second - a.second) * segmentProgress
            )
        }
        try {
            val loc = Location(LocationManager.GPS_PROVIDER).apply {
                latitude = point.first
                longitude = point.second
                accuracy = 3f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = android.os.SystemClock.elapsedRealtimeNanos()
                speed = speedKmh / 3.6f
                bearing = 0f
            }
            locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            status.text = "Simulando: %.6f, %.6f • %d km/h".format(point.first, point.second, speedKmh)
        } catch (e: Exception) {
            status.text = "Erro ao enviar localização simulada."
            stopRoute()
        }
    }

    private fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val x = sin(dLat / 2).pow(2.0) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2.0)
        return 2 * r * asin(sqrt(x))
    }

    override fun onDestroy() {
        stopRoute()
        map.destroy()
        super.onDestroy()
    }
}
