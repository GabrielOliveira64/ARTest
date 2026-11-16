package com.example.arfixo

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.sqrt

class MainActivity : ComponentActivity(), SensorEventListener {

    private lateinit var previewView: PreviewView
    private lateinit var arView: OverlayView
    private lateinit var sensorManager: SensorManager
    private var rotSensor: Sensor? = null
    private val matrix = FloatArray(9)
    private val prefs by lazy { getSharedPreferences("ar_fixo", Context.MODE_PRIVATE) }

    private val permLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
            if (ok) startCamera()
            else Toast.makeText(this, "Preciso da permissão da câmera", Toast.LENGTH_LONG).show()
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotSensor == null) {
            Toast.makeText(this, "Este aparelho não tem sensor de rotação", Toast.LENGTH_LONG).show()
        }

        val dp = resources.displayMetrics.density
        val root = FrameLayout(this)

        previewView = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        root.addView(
            previewView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        arView = OverlayView(this)
        arView.focalFraction = computeFocalFraction()
        root.addView(
            arView,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        )

        val seek = SeekBar(this)
        seek.max = 100
        seek.progress = prefs.getInt("cal", 50)
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                arView.focalScale = 0.7f + p * 0.006f
                prefs.edit().putInt("cal", p).apply()
            }
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        arView.focalScale = 0.7f + seek.progress * 0.006f

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.setBackgroundColor(Color.argb(140, 0, 0, 0))
        panel.setPadding((16 * dp).toInt(), (8 * dp).toInt(), (16 * dp).toInt(), (8 * dp).toInt())

        val label = TextView(this)
        label.text = "Calibração: ajuste até o ícone parar de \"deslizar\" sobre o cenário"
        label.setTextColor(Color.WHITE)
        label.textSize = 12f
        panel.addView(label)
        panel.addView(seek)

        val lp = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            Gravity.BOTTOM
        )
        lp.bottomMargin = (40 * dp).toInt()
        root.addView(panel, lp)

        setContentView(root)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED
        ) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)
    }

    private fun computeFocalFraction(): Float {
        try {
            val cm = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            for (id in cm.cameraIdList) {
                val c = cm.getCameraCharacteristics(id)
                if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
                val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
                val size = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
                return f / max(size.width, size.height)
            }
        } catch (e: Exception) {
        }
        return 0.8f
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview)
        }, ContextCompat.getMainExecutor(this))
    }

    override fun onResume() {
        super.onResume()
        rotSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_FASTEST) }
    }

    override fun onPause() {
        super.onPause()
        sensorManager.unregisterListener(this)
    }

    override fun onSensorChanged(event: SensorEvent) {
        val v = if (event.values.size > 4) event.values.copyOf(4) else event.values
        SensorManager.getRotationMatrixFromVector(matrix, v)
        arView.updateRotation(matrix)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}

/**
 * Toque na tela = o ícone é fixado naquela direção do mundo e fica ali.
 * Tocar de novo move o ícone para o novo ponto.
 */
class OverlayView(context: Context) : View(context) {

    // Matriz de rotação aparelho -> mundo
    private val r = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
    private var hasRot = false

    var focalFraction = 0.8f
    var focalScale = 1f

    // Direção do ícone no mundo
    private var dx = 0f
    private var dy = 0f
    private var dz = 0f
    private var placed = false
    private var rollDeg = 0f

    private var sx = 0f
    private var sy = 0f

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 20, 24, 40); style = Paint.Style.FILL
    }
    private val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE
    }
    private val lens = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 200, 255); style = Paint.Style.FILL
    }
    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 48f
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }
    private val rect = RectF()

    fun updateRotation(m: FloatArray) {
        System.arraycopy(m, 0, r, 0, 9)
        hasRot = true
    }

    private fun focalPx(): Float =
        focalFraction * focalScale * max(height.toFloat(), width * 4f / 3f)

    /** Converte o ponto tocado em direção no mundo e fixa o ícone lá. */
    private fun place(tx: Float, ty: Float) {
        if (!hasRot || width == 0) return
        val f = focalPx()
        val vx = (tx - width / 2f) / f
        val vy = -(ty - height / 2f) / f
        val n = sqrt(vx * vx + vy * vy + 1f)
        val ax = vx / n
        val ay = vy / n
        val az = -1f / n
        // mundo = R * aparelho
        dx = r[0] * ax + r[1] * ay + r[2] * az
        dy = r[3] * ax + r[4] * ay + r[5] * az
        dz = r[6] * ax + r[7] * ay + r[8] * az
        placed = true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.actionMasked == MotionEvent.ACTION_UP) place(e.x, e.y)
        return true
    }

    private fun project(fpx: Float): Boolean {
        val vx = r[0] * dx + r[3] * dy + r[6] * dz
        val vy = r[1] * dx + r[4] * dy + r[7] * dz
        val vz = r[2] * dx + r[5] * dy + r[8] * dz
        if (vz > -0.05f) return false
        sx = width / 2f + fpx * vx / (-vz)
        sy = height / 2f - fpx * vy / (-vz)
        return true
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        postInvalidateOnAnimation()
        if (!hasRot || width == 0 || height == 0) return

        if (!placed) {
            canvas.drawText("Toque na tela para fixar o ícone", width / 2f, height * 0.15f, hint)
            return
        }

        val fpx = focalPx()
        if (!project(fpx)) return

        val upLen = sqrt(r[6] * r[6] + r[7] * r[7])
        if (upLen > 0.25f) rollDeg = atan2(r[6], r[7]) * 180f / Math.PI.toFloat()

        val s = 0.08f * fpx
        edge.strokeWidth = s * 0.08f
        canvas.save()
        canvas.translate(sx, sy)
        canvas.rotate(rollDeg)
        rect.set(-s, -s * 0.55f, s, s * 0.55f)
        canvas.drawRoundRect(rect, s * 0.32f, s * 0.32f, body)
        canvas.drawRoundRect(rect, s * 0.32f, s * 0.32f, edge)
        canvas.drawCircle(-s * 0.4f, 0f, s * 0.3f, lens)
        canvas.drawCircle(s * 0.4f, 0f, s * 0.3f, lens)
        canvas.drawCircle(-s * 0.4f, 0f, s * 0.3f, edge)
        canvas.drawCircle(s * 0.4f, 0f, s * 0.3f, edge)
        canvas.restore()
    }
}
