package app.cleantv.sendtotv

import android.graphics.Bitmap

/**
 * QR encoder: byte mode, error correction level M, versions 1 to 10.
 * Transcribed line for line from a Python reference that was verified against the
 * python-qrcode library (24 of 24 matrices identical, including mask choice).
 */
object QrCode {
    // version -> blocks as (totalCodewords, dataCodewords)
    private val BLOCKS: Map<Int, List<IntArray>> = mapOf(
        1 to listOf(intArrayOf(26, 16)),
        2 to listOf(intArrayOf(44, 28)),
        3 to listOf(intArrayOf(70, 44)),
        4 to List(2) { intArrayOf(50, 32) },
        5 to List(2) { intArrayOf(67, 43) },
        6 to List(4) { intArrayOf(43, 27) },
        7 to List(4) { intArrayOf(49, 31) },
        8 to List(2) { intArrayOf(60, 38) } + List(2) { intArrayOf(61, 39) },
        9 to List(3) { intArrayOf(58, 36) } + List(2) { intArrayOf(59, 37) },
        10 to List(4) { intArrayOf(69, 43) } + listOf(intArrayOf(70, 44)),
    )
    private val ALIGN: Map<Int, IntArray> = mapOf(
        1 to intArrayOf(), 2 to intArrayOf(6, 18), 3 to intArrayOf(6, 22), 4 to intArrayOf(6, 26),
        5 to intArrayOf(6, 30), 6 to intArrayOf(6, 34), 7 to intArrayOf(6, 22, 38), 8 to intArrayOf(6, 24, 42),
        9 to intArrayOf(6, 26, 46), 10 to intArrayOf(6, 28, 50),
    )
    private val EXP = IntArray(256)
    private val LOG = IntArray(256)

    init {
        var x = 1
        for (i in 0 until 255) {
            EXP[i] = x; LOG[x] = i
            x = x shl 1
            if (x and 0x100 != 0) x = x xor 0x11d
        }
        EXP[255] = EXP[0]
    }

    private fun gmul(a: Int, b: Int): Int = if (a == 0 || b == 0) 0 else EXP[(LOG[a] + LOG[b]) % 255]

    private fun rsGenerator(n: Int): IntArray {
        var g = intArrayOf(1)
        for (i in 0 until n) {
            val ng = IntArray(g.size + 1)
            for (j in g.indices) {
                ng[j] = ng[j] xor g[j]
                ng[j + 1] = ng[j + 1] xor gmul(g[j], EXP[i])
            }
            g = ng
        }
        return g
    }

    private fun rsEc(data: IntArray, n: Int): IntArray {
        val g = rsGenerator(n)
        val rem = IntArray(data.size + n)
        data.copyInto(rem)
        for (i in data.indices) {
            val coef = rem[i]
            if (coef != 0) for (j in 1 until g.size) rem[i + j] = rem[i + j] xor gmul(g[j], coef)
        }
        return rem.copyOfRange(data.size, rem.size)
    }

    private fun bitLength(x: Int): Int = 32 - Integer.numberOfLeadingZeros(x)

    private fun bchTypeInfo(data: Int): Int {
        var d = data shl 10
        while (bitLength(d) - 11 >= 0) d = d xor (0x537 shl (bitLength(d) - 11))
        return ((data shl 10) or d) xor 0x5412
    }

    private fun bchTypeNumber(v: Int): Int {
        var d = v shl 12
        while (bitLength(d) - 13 >= 0) d = d xor (0x1F25 shl (bitLength(d) - 13))
        return (v shl 12) or d
    }

    private fun chooseVersion(nbytes: Int): Int {
        for (v in 1..10) {
            val cci = if (v < 10) 8 else 16
            val cap = BLOCKS.getValue(v).sumOf { it[1] } * 8
            if (4 + cci + 8 * nbytes <= cap) return v
        }
        throw IllegalArgumentException("Text too long for QR version 10")
    }

    private fun makeCodewords(data: ByteArray, v: Int): IntArray {
        val bits = ArrayList<Int>()
        fun put(value: Int, n: Int) { for (i in n - 1 downTo 0) bits.add((value shr i) and 1) }
        put(4, 4)
        put(data.size, if (v < 10) 8 else 16)
        for (b in data) put(b.toInt() and 0xff, 8)
        val blocks = BLOCKS.getValue(v)
        val limit = blocks.sumOf { it[1] } * 8
        repeat(minOf(limit - bits.size, 4)) { bits.add(0) }
        while (bits.size % 8 != 0) bits.add(0)
        var i = 0
        while (bits.size < limit) { put(if (i % 2 == 0) 0xEC else 0x11, 8); i++ }
        val cw = IntArray(bits.size / 8) { k ->
            var b = 0
            for (j in 0 until 8) b = (b shl 1) or bits[k * 8 + j]
            b
        }
        val dcs = ArrayList<IntArray>()
        val ecs = ArrayList<IntArray>()
        var off = 0
        for (blk in blocks) {
            val d = cw.copyOfRange(off, off + blk[1]); off += blk[1]
            dcs.add(d); ecs.add(rsEc(d, blk[0] - blk[1]))
        }
        val out = ArrayList<Int>()
        val maxDc = dcs.maxOf { it.size }
        for (k in 0 until maxDc) for (d in dcs) if (k < d.size) out.add(d[k])
        val maxEc = ecs.maxOf { it.size }
        for (k in 0 until maxEc) for (e in ecs) if (k < e.size) out.add(e[k])
        return out.toIntArray()
    }

    private fun maskFn(p: Int, i: Int, j: Int): Boolean = when (p) {
        0 -> (i + j) % 2 == 0
        1 -> i % 2 == 0
        2 -> j % 3 == 0
        3 -> (i + j) % 3 == 0
        4 -> (i / 2 + j / 3) % 2 == 0
        5 -> (i * j) % 2 + (i * j) % 3 == 0
        6 -> ((i * j) % 2 + (i * j) % 3) % 2 == 0
        else -> ((i * j) % 3 + (i + j) % 2) % 2 == 0
    }

    /** Builds the module matrix; -1 = unset, 0 = light, 1 = dark during construction. */
    private fun build(v: Int, cw: IntArray, mask: Int, test: Boolean): Array<BooleanArray> {
        val n = v * 4 + 17
        val m = Array(n) { IntArray(n) { -1 } }
        fun set(r: Int, c: Int, dark: Boolean) { m[r][c] = if (dark) 1 else 0 }
        fun probe(row: Int, col: Int) {
            for (r in -1..7) {
                if (row + r < 0 || row + r >= n) continue
                for (c in -1..7) {
                    if (col + c < 0 || col + c >= n) continue
                    set(row + r, col + c, (r in 0..6 && (c == 0 || c == 6)) || (c in 0..6 && (r == 0 || r == 6)) || (r in 2..4 && c in 2..4))
                }
            }
        }
        probe(0, 0); probe(n - 7, 0); probe(0, n - 7)
        val pos = ALIGN.getValue(v)
        for (row in pos) for (col in pos) {
            if (m[row][col] != -1) continue
            for (r in -2..2) for (c in -2..2) set(row + r, col + c, r == -2 || r == 2 || c == -2 || c == 2 || (r == 0 && c == 0))
        }
        for (r in 8 until n - 8) if (m[r][6] == -1) set(r, 6, r % 2 == 0)
        for (c in 8 until n - 8) if (m[6][c] == -1) set(6, c, c % 2 == 0)
        val bits = bchTypeInfo(mask) // EC level M has code 0, so format data is just the mask
        for (i in 0 until 15) {
            val mod = !test && ((bits shr i) and 1) == 1
            when {
                i < 6 -> set(i, 8, mod)
                i < 8 -> set(i + 1, 8, mod)
                else -> set(n - 15 + i, 8, mod)
            }
        }
        for (i in 0 until 15) {
            val mod = !test && ((bits shr i) and 1) == 1
            when {
                i < 8 -> set(8, n - i - 1, mod)
                i < 9 -> set(8, 15 - i, mod)
                else -> set(8, 15 - i - 1, mod)
            }
        }
        set(n - 8, 8, !test)
        if (v >= 7) {
            val vb = bchTypeNumber(v)
            for (i in 0 until 18) {
                val mod = !test && ((vb shr i) and 1) == 1
                set(i / 3, i % 3 + n - 11, mod)
                set(i % 3 + n - 11, i / 3, mod)
            }
        }
        var inc = -1
        var row = n - 1
        var bitIndex = 7
        var byteIndex = 0
        var col = n - 1
        while (col > 0) {
            var cc = col
            if (cc <= 6) cc -= 1
            while (true) {
                for (c in intArrayOf(cc, cc - 1)) {
                    if (m[row][c] == -1) {
                        var dark = false
                        if (byteIndex < cw.size) dark = ((cw[byteIndex] shr bitIndex) and 1) == 1
                        if (maskFn(mask, row, c)) dark = !dark
                        set(row, c, dark)
                        bitIndex--
                        if (bitIndex == -1) { byteIndex++; bitIndex = 7 }
                    }
                }
                row += inc
                if (row < 0 || row >= n) { row -= inc; inc = -inc; break }
            }
            col -= 2
        }
        return Array(n) { r -> BooleanArray(n) { c -> m[r][c] == 1 } }
    }

    private fun lostPoint(m: Array<BooleanArray>): Int {
        val n = m.size
        var lp = 0
        val container = IntArray(n + 1)
        for (row in 0 until n) {
            var prev = m[row][0]; var length = 0
            for (col in 0 until n) {
                if (m[row][col] == prev) length++ else {
                    if (length >= 5) container[length]++
                    length = 1; prev = m[row][col]
                }
            }
            if (length >= 5) container[length]++
        }
        for (col in 0 until n) {
            var prev = m[0][col]; var length = 0
            for (row in 0 until n) {
                if (m[row][col] == prev) length++ else {
                    if (length >= 5) container[length]++
                    length = 1; prev = m[row][col]
                }
            }
            if (length >= 5) container[length]++
        }
        for (len in 5..n) lp += container[len] * (len - 2)
        for (row in 0 until n - 1) {
            val tr = m[row]; val nr = m[row + 1]
            var col = 0
            while (col < n - 1) {
                val topRight = tr[col + 1]
                if (topRight != nr[col + 1]) { col += 2; continue }
                else if (topRight != tr[col]) { }
                else if (topRight != nr[col]) { }
                else lp += 3
                col += 1
            }
        }
        for (row in 0 until n) {
            val r = m[row]
            var col = 0
            while (col < n - 10) {
                if (!r[col + 1] && r[col + 4] && !r[col + 5] && r[col + 6] && !r[col + 9] &&
                    ((r[col] && r[col + 2] && r[col + 3] && !r[col + 7] && !r[col + 8] && !r[col + 10]) ||
                        (!r[col] && !r[col + 2] && !r[col + 3] && r[col + 7] && r[col + 8] && r[col + 10]))
                ) lp += 40
                col += if (r[col + 10]) 2 else 1
            }
        }
        for (col in 0 until n) {
            var row = 0
            while (row < n - 10) {
                if (!m[row + 1][col] && m[row + 4][col] && !m[row + 5][col] && m[row + 6][col] && !m[row + 9][col] &&
                    ((m[row][col] && m[row + 2][col] && m[row + 3][col] && !m[row + 7][col] && !m[row + 8][col] && !m[row + 10][col]) ||
                        (!m[row][col] && !m[row + 2][col] && !m[row + 3][col] && m[row + 7][col] && m[row + 8][col] && m[row + 10][col]))
                ) lp += 40
                row += if (m[row + 10][col]) 2 else 1
            }
        }
        var dark = 0
        for (r in m) for (x in r) if (x) dark++
        val percent = dark.toDouble() / (n * n)
        lp += (Math.abs(percent * 100 - 50) / 5).toInt() * 10
        return lp
    }

    /** Returns the module matrix (true = dark), without quiet zone. */
    fun encode(text: String): Array<BooleanArray> {
        val data = text.toByteArray(Charsets.UTF_8)
        val v = chooseVersion(data.size)
        val cw = makeCodewords(data, v)
        var best = 0
        var bestPenalty = 0
        for (p in 0 until 8) {
            val lp = lostPoint(build(v, cw, p, true))
            if (p == 0 || lp < bestPenalty) { best = p; bestPenalty = lp }
        }
        return build(v, cw, best, false)
    }

    /** Black modules on white, with a quiet zone, each module modulePx pixels square. */
    fun toBitmap(text: String, modulePx: Int, quietModules: Int = 2): Bitmap {
        val m = encode(text)
        val n = m.size
        val size = (n + 2 * quietModules) * modulePx
        val pixels = IntArray(size * size) { -0x1 } // white
        for (r in 0 until n) for (c in 0 until n) if (m[r][c]) {
            val y0 = (r + quietModules) * modulePx
            val x0 = (c + quietModules) * modulePx
            for (y in y0 until y0 + modulePx) {
                val base = y * size
                for (x in x0 until x0 + modulePx) pixels[base + x] = -0x1000000 // black
            }
        }
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        bmp.setPixels(pixels, 0, size, 0, 0, size, size)
        return bmp
    }
}
