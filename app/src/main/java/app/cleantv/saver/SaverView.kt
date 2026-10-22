package app.cleantv.saver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import kotlin.math.max
import kotlin.random.Random

/**
 * Cheap canvas screensaver. Modes: "mystify", "starfield", "pipes".
 * All state is preallocated; the frame loop allocates nothing.
 */
class SaverView(context: Context, private val mode: String, t0: Long = 30L * 60_000L) : View(context) {

    companion object {
        const val MODE_MYSTIFY = "mystify"
        const val MODE_STARFIELD = "starfield"
        const val MODE_PIPES = "pipes"
        const val MODE_MATRIX = "matrix"
        private const val FRAME_MS = 33L
        private const val PIPE_FRAME_MS = 40L
    }

    private val density = resources.displayMetrics.density
    private val rnd = Random(System.nanoTime())
    private var running = false
    private var sized = false

    private val blackPaint = Paint().apply { color = Color.BLACK; style = Paint.Style.FILL }

    // ---- Mystify state ----
    private val polyCount = 2
    private val pointCount = 4
    private val trailLen = 14
    private val px = Array(polyCount) { FloatArray(pointCount) }
    private val py = Array(polyCount) { FloatArray(pointCount) }
    private val vx = Array(polyCount) { FloatArray(pointCount) }
    private val vy = Array(polyCount) { FloatArray(pointCount) }
    private val hue = FloatArray(polyCount)
    // ring buffer of trail positions: [poly][slot][point]
    private val trailX = Array(polyCount) { Array(trailLen) { FloatArray(pointCount) } }
    private val trailY = Array(polyCount) { Array(trailLen) { FloatArray(pointCount) } }
    private val trailCount = IntArray(polyCount)
    private val trailHead = IntArray(polyCount)
    private val mystifyPath = Path()
    private val mystifyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeJoin = Paint.Join.ROUND
    }
    private val hsv = FloatArray(3)

    // ---- Starfield state ----
    private val starCount = 260
    private val sx = FloatArray(starCount)
    private val sy = FloatArray(starCount)
    private val sz = FloatArray(starCount)
    private val fadePaint = Paint().apply { color = Color.argb(153, 0, 0, 0); style = Paint.Style.FILL }
    private val starPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }

    // ---- Pipes state ----
    private var cell = 0f
    private var cols = 0
    private var rows = 0
    private var used: BooleanArray = BooleanArray(0)
    private var usedCount = 0
    private val pipeCount = 3
    private val pipeX = IntArray(pipeCount)
    private val pipeY = IntArray(pipeCount)
    private val pipeDx = IntArray(pipeCount)
    private val pipeDy = IntArray(pipeCount)
    private val pipeColor = IntArray(pipeCount)
    private val pipeLen = IntArray(pipeCount)
    private var pipeSerial = 0
    private val pipeColors = intArrayOf(
        0xFF2F9E6A.toInt(), 0xFFE0B23A.toInt(), 0xFF3B82F6.toInt(),
        0xFFD9534F.toInt(), 0xFF9B59B6.toInt(), 0xFFEEF1F4.toInt()
    )
    private val pipePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
    }
    private val jointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private var pipeBitmap: Bitmap? = null
    private var pipeCanvas: Canvas? = null

    // ---- Matrix state: one falling head per column drawn into a persistent bitmap that fades each frame ----
    private var glyph = 0f
    private var mCols = 0
    private var mRows = 0
    private var headY: FloatArray = FloatArray(0)
    private var headSpeed: FloatArray = FloatArray(0)
    private var matrixBitmap: Bitmap? = null
    private var matrixCanvas: Canvas? = null
    private val matrixFade = Paint().apply { color = Color.argb(48, 0, 0, 0); style = Paint.Style.FILL }
    private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFE8FFEC.toInt(); typeface = android.graphics.Typeface.DEFAULT_BOLD; textAlign = Paint.Align.CENTER }
    private val trailPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF22E85A.toInt(); typeface = android.graphics.Typeface.DEFAULT; textAlign = Paint.Align.CENTER }
    // half-width katakana, a handful of Chinese characters, digits and a few symbols
    private val glyphs = buildString {
        for (c in 0xFF66..0xFF9D) append(c.toChar())
        append("\u65E5\u6708\u706B\u6C34\u6728\u91D1\u571F\u5C71\u5DDD\u7530\u4EBA\u53E3\u529B\u5927\u4E2D\u5C0F\u4E0A\u4E0B\u5929\u5730\u5149\u96FB\u6570\u5B57\u9F8D\u9580\u6C17\u9053")
        append("0123456789")
        append("<>+-*=:")
    }
    private val oneChar = CharArray(1)
    private val edge = EdgeLayer(resources.displayMetrics.density, t0, java.util.Random())
    private var lastChar: CharArray = CharArray(0)
    private var mTrail: IntArray = IntArray(0)

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            invalidate()
            postOnAnimationDelayed(this, if (mode == MODE_PIPES) PIPE_FRAME_MS else FRAME_MS)
        }
    }

    init {
        setBackgroundColor(Color.BLACK)
        mystifyPaint.strokeWidth = 2f * density
        pipePaint.strokeWidth = 8f * density
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        sized = true
        edge.onSize(w, h)
        when (mode) {
            MODE_MYSTIFY -> initMystify(w, h)
            MODE_STARFIELD -> initStarfield(w, h)
            MODE_PIPES -> initPipes(w, h)
            MODE_MATRIX -> initMatrix(w, h)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        pipeBitmap?.recycle()
        pipeBitmap = null
        pipeCanvas = null
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) start() else stop()
    }

    private fun start() {
        if (running || !isAttachedToWindow || windowVisibility != VISIBLE) return
        running = true
        removeCallbacks(frame)
        postOnAnimation(frame)
    }

    private fun stop() {
        running = false
        removeCallbacks(frame)
    }

    private fun step() {
        if (!sized) return
        when (mode) {
            MODE_MYSTIFY -> stepMystify()
            MODE_STARFIELD -> stepStarfield()
            MODE_PIPES -> stepPipes()
            MODE_MATRIX -> stepMatrix()
        }
        edge.step()
    }

    override fun onDraw(canvas: Canvas) {
        if (!sized) {
            canvas.drawColor(Color.BLACK)
            return
        }
        when (mode) {
            MODE_MYSTIFY -> drawMystify(canvas)
            MODE_STARFIELD -> drawStarfield(canvas)
            MODE_PIPES -> drawPipes(canvas)
            MODE_MATRIX -> drawMatrix(canvas)
        }
        edge.draw(canvas)
    }

    // ---------------- Mystify ----------------

    private fun initMystify(w: Int, h: Int) {
        val speedScale = density * 0.75f
        for (p in 0 until polyCount) {
            for (i in 0 until pointCount) {
                px[p][i] = rnd.nextFloat() * w
                py[p][i] = rnd.nextFloat() * h
                vx[p][i] = (rnd.nextFloat() * 4f + 2f) * speedScale * if (rnd.nextBoolean()) -1f else 1f
                vy[p][i] = (rnd.nextFloat() * 4f + 2f) * speedScale * if (rnd.nextBoolean()) -1f else 1f
            }
            hue[p] = if (p == 0) 320f else 200f
            trailCount[p] = 0
            trailHead[p] = 0
        }
    }

    private fun stepMystify() {
        val w = width.toFloat()
        val h = height.toFloat()
        for (p in 0 until polyCount) {
            for (i in 0 until pointCount) {
                px[p][i] += vx[p][i]
                py[p][i] += vy[p][i]
                if (px[p][i] < 0f || px[p][i] > w) vx[p][i] = -vx[p][i]
                if (py[p][i] < 0f || py[p][i] > h) vy[p][i] = -vy[p][i]
            }
            val slot = trailHead[p]
            for (i in 0 until pointCount) {
                trailX[p][slot][i] = px[p][i]
                trailY[p][slot][i] = py[p][i]
            }
            trailHead[p] = (slot + 1) % trailLen
            if (trailCount[p] < trailLen) trailCount[p]++
            hue[p] = (hue[p] + 0.6f) % 360f
        }
    }

    private fun drawMystify(canvas: Canvas) {
        canvas.drawColor(Color.BLACK)
        for (p in 0 until polyCount) {
            val n = trailCount[p]
            // oldest first so the newest draws on top
            for (k in 0 until n) {
                val slot = (trailHead[p] - n + k + trailLen * 2) % trailLen
                val xs = trailX[p][slot]
                val ys = trailY[p][slot]
                mystifyPath.rewind()
                mystifyPath.moveTo(xs[0], ys[0])
                for (i in 1 until pointCount) mystifyPath.lineTo(xs[i], ys[i])
                mystifyPath.close()
                hsv[0] = (hue[p] + k * 4f) % 360f
                hsv[1] = 1f
                hsv[2] = 0.95f
                val alpha = ((k + 1) * 255 / n).coerceIn(0, 255)
                mystifyPaint.color = Color.HSVToColor(alpha, hsv)
                canvas.drawPath(mystifyPath, mystifyPaint)
            }
        }
    }

    // ---------------- Starfield ----------------

    private fun initStarfield(w: Int, h: Int) {
        for (i in 0 until starCount) resetStar(i, w.toFloat(), h.toFloat(), rnd.nextFloat() * w)
    }

    private fun resetStar(i: Int, w: Float, h: Float, z: Float) {
        sx[i] = (rnd.nextFloat() - 0.5f) * w
        sy[i] = (rnd.nextFloat() - 0.5f) * h
        sz[i] = z
    }

    private fun stepStarfield() {
        val w = width.toFloat()
        val h = height.toFloat()
        val speed = 9f * density * 0.75f
        for (i in 0 until starCount) {
            sz[i] -= speed
            if (sz[i] <= 1f) resetStar(i, w, h, w)
        }
    }

    private fun drawStarfield(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, h, fadePaint)
        val focal = 300f * density * 0.75f
        for (i in 0 until starCount) {
            val k = focal / sz[i]
            val x = sx[i] * k + w / 2f
            val y = sy[i] * k + h / 2f
            if (x < 0f || x > w || y < 0f || y > h) continue
            val depth = 1f - sz[i] / w
            val r = max(0.5f, depth * 3f * density)
            starPaint.alpha = (depth * 255f).toInt().coerceIn(0, 255)
            canvas.drawCircle(x, y, r, starPaint)
        }
    }

    // ---------------- Pipes ----------------

    private fun initPipes(w: Int, h: Int) {
        cell = 18f * density
        cols = (w / cell).toInt().coerceAtLeast(1)
        rows = (h / cell).toInt().coerceAtLeast(1)
        used = BooleanArray(cols * rows)
        usedCount = 0
        pipeBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        pipeBitmap = bmp
        pipeCanvas = Canvas(bmp).also { it.drawColor(Color.BLACK) }
        pipeSerial = 0
        for (i in 0 until pipeCount) newPipe(i)
    }

    private fun newPipe(i: Int) {
        var x = 0
        var y = 0
        var tries = 0
        do {
            x = rnd.nextInt(cols)
            y = rnd.nextInt(rows)
            tries++
        } while (used[y * cols + x] && tries < 50)
        markUsed(x, y)
        pipeX[i] = x
        pipeY[i] = y
        pipeDx[i] = 1
        pipeDy[i] = 0
        pipeColor[i] = pipeColors[pipeSerial % pipeColors.size]
        pipeSerial++
        pipeLen[i] = 0
    }

    private fun markUsed(x: Int, y: Int) {
        val idx = y * cols + x
        if (!used[idx]) {
            used[idx] = true
            usedCount++
        }
    }

    private fun clearGrid() {
        java.util.Arrays.fill(used, false)
        usedCount = 0
        pipeCanvas?.drawColor(Color.BLACK)
    }

    private fun stepPipes() {
        val c = pipeCanvas ?: return
        val half = cell / 2f
        for (i in 0 until pipeCount) {
            if (rnd.nextFloat() < 0.18f || pipeLen[i] == 0) {
                when (rnd.nextInt(4)) {
                    0 -> { pipeDx[i] = 1; pipeDy[i] = 0 }
                    1 -> { pipeDx[i] = -1; pipeDy[i] = 0 }
                    2 -> { pipeDx[i] = 0; pipeDy[i] = 1 }
                    else -> { pipeDx[i] = 0; pipeDy[i] = -1 }
                }
            }
            val nx = pipeX[i] + pipeDx[i]
            val ny = pipeY[i] + pipeDy[i]
            val blocked = nx < 0 || ny < 0 || nx >= cols || ny >= rows || used[ny * cols + nx] || pipeLen[i] > 90
            if (blocked) {
                if (usedCount > cols * rows * 0.55f) clearGrid()
                newPipe(i)
                continue
            }
            pipePaint.color = pipeColor[i]
            jointPaint.color = pipeColor[i]
            c.drawLine(pipeX[i] * cell + half, pipeY[i] * cell + half, nx * cell + half, ny * cell + half, pipePaint)
            c.drawCircle(nx * cell + half, ny * cell + half, 5f * density, jointPaint)
            pipeX[i] = nx
            pipeY[i] = ny
            pipeLen[i]++
            markUsed(nx, ny)
        }
    }

    private fun drawPipes(canvas: Canvas) {
        val bmp = pipeBitmap
        if (bmp == null) {
            canvas.drawColor(Color.BLACK)
            return
        }
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }

    // ---------------- Matrix ----------------

    private fun initMatrix(w: Int, h: Int) {
        glyph = 16f * density
        mCols = (w / glyph).toInt().coerceAtLeast(1)
        mRows = (h / glyph).toInt().coerceAtLeast(1)
        headY = FloatArray(mCols) { -rnd.nextInt(mRows * 2).toFloat() }
        headSpeed = FloatArray(mCols) { 0.2f + rnd.nextFloat() * 0.35f }
        lastChar = CharArray(mCols) { glyphs[rnd.nextInt(glyphs.length)] }
        mTrail = IntArray(mCols) { 8 + rnd.nextInt(16) }
        headPaint.textSize = glyph
        trailPaint.textSize = glyph
        matrixBitmap?.recycle()
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        matrixBitmap = bmp
        matrixCanvas = Canvas(bmp).also { it.drawColor(Color.BLACK) }
    }

    private fun stepMatrix() {
        val c = matrixCanvas ?: return
        c.drawRect(0f, 0f, width.toFloat(), height.toFloat(), matrixFade)
        for (i in 0 until mCols) {
            val prev = headY[i]
            headY[i] += headSpeed[i]
            val row = headY[i].toInt()
            if (row != prev.toInt() && row >= 0 && row < mRows) {
                val x = i * glyph + glyph / 2f
                // the previous head cools to trail green, the new head is near white
                if (row - 1 >= 0) {
                    oneChar[0] = lastChar[i]
                    c.drawRect(x - glyph / 2f, (row - 1) * glyph, x + glyph / 2f, row * glyph, blackPaint)
                    c.drawText(oneChar, 0, 1, x, row * glyph - glyph * 0.15f, trailPaint)
                }
                val ch = glyphs[rnd.nextInt(glyphs.length)]
                lastChar[i] = ch
                oneChar[0] = ch
                c.drawText(oneChar, 0, 1, x, (row + 1) * glyph - glyph * 0.15f, headPaint)
            }
            if (row > mRows + rnd.nextInt(8)) {
                headY[i] = -rnd.nextInt(mRows).toFloat()
                headSpeed[i] = 0.2f + rnd.nextFloat() * 0.35f
                mTrail[i] = 8 + rnd.nextInt(16)
            }
        }
    }

    private fun drawMatrix(canvas: Canvas) {
        val bmp = matrixBitmap
        if (bmp == null) { canvas.drawColor(Color.BLACK); return }
        canvas.drawBitmap(bmp, 0f, 0f, null)
    }
}
