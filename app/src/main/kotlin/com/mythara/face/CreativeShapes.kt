package com.mythara.face

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * **Procedural shape generators.** Replaces the fixed catalogue
 * (cube / octahedron / icosahedron / torus / knot / tetrahedron)
 * with five **families** of generators that mint a new, novel shape
 * each session from random parameters. Two consecutive sessions in
 * the same family produce visually distinct geometry — the family
 * picks the *style*, the random params pick the *specific form*.
 *
 * No two minted shapes have been "seen before" — they're entirely
 * the agent's imagination, defined by:
 *   - which family rolled
 *   - the seed (random per session, persisted so the same session
 *     re-renders the same shape across recompositions)
 *
 * Families:
 *
 *   - **Supershape**       — Gielis 3D superformula, 12 random
 *                            parameters across two latitude /
 *                            longitude profiles. Produces an enormous
 *                            space of organic / mechanical forms:
 *                            spiky stars, smooth blobs, mushroom-
 *                            looking, alien crystals.
 *
 *   - **SphericalHarmonic** — random `Y_lm` coefficients summed into
 *                            a radial offset field. Smooth alien
 *                            blobs with concentric lobes.
 *
 *   - **LissajousKnot**    — generalised Lissajous knot family with
 *                            random integer frequencies + phase
 *                            offsets. Produces fresh impossible 3D
 *                            knots every time.
 *
 *   - **MetaballBlob**     — 3-7 random gaussian field centres + ray-
 *                            march for the isosurface. Goey alien
 *                            organism look.
 *
 *   - **RandomPolytope**   — 8-28 random unit-sphere vertices, k-
 *                            nearest edge graph. Crystalline cage
 *                            structures, no two ever identical.
 */
object CreativeShapes {

    enum class Family { Supershape, SphericalHarmonic, LissajousKnot, MetaballBlob, RandomPolytope }

    /** Roll a new shape. Writes [n] sample points into the provided
     *  flat arrays. Returns the family that was rolled so the engine
     *  can persist it (so the next pick avoids the same family if we
     *  want diversity — though within a family, two sessions are
     *  already visually distinct from each other).
     *
     *  Mood biases the family pick — calm leans organic, anxious /
     *  frustrated lean angular. Intensity scales overall shape size
     *  through [scaleByIntensity]. */
    fun mintRandomShape(
        seed: Long,
        n: Int,
        radius: Float,
        mood: String?,
        intensity: Float,
        xs: FloatArray,
        ys: FloatArray,
        zs: FloatArray,
    ): Family {
        require(xs.size >= n && ys.size >= n && zs.size >= n) {
            "buffers too small for $n samples"
        }
        val rnd = Random(seed)
        val family = pickFamily(mood, rnd)
        val effRadius = radius * (0.85f + intensity * 0.30f)
        when (family) {
            Family.Supershape -> sampleSupershape(rnd, effRadius, n, xs, ys, zs)
            Family.SphericalHarmonic -> sampleSphericalHarmonic(rnd, effRadius, n, xs, ys, zs)
            Family.LissajousKnot -> sampleLissajousKnot(rnd, effRadius, n, xs, ys, zs)
            Family.MetaballBlob -> sampleMetaballBlob(rnd, effRadius, n, xs, ys, zs)
            Family.RandomPolytope -> sampleRandomPolytope(rnd, effRadius, n, xs, ys, zs)
        }
        return family
    }

    /** Each family has a natural particle count for the renderer to
     *  read clean. Wireframe-heavy families (polytope) reads better
     *  sparser; surface families (supershape, harmonic, metaball)
     *  reads better dense. */
    fun particleCount(family: Family, mood: String?, intensity: Float): Int {
        val base = when (family) {
            Family.Supershape -> 1100
            Family.SphericalHarmonic -> 950
            Family.MetaballBlob -> 850
            Family.LissajousKnot -> 800
            Family.RandomPolytope -> 600
        }
        val moodMul = when (mood) {
            "excited" -> 1.40f
            "happy" -> 1.20f
            "frustrated" -> 1.15f
            "anxious" -> 1.05f
            "calm" -> 0.85f
            "sad" -> 0.75f
            else -> 1.00f
        }
        return (base * moodMul * (0.80f + intensity * 0.40f)).toInt()
            .coerceIn(180, MAX_PARTICLE_COUNT)
    }

    // ── Supershape (Gielis 3D) ────────────────────────────────────

    /** 3D superformula:
     *    r1(θ) = superR(θ, m1, n1₁, n2₁, n3₁)
     *    r2(φ) = superR(φ, m2, n1₂, n2₂, n3₂)
     *    x = r1·cos θ · r2·cos φ
     *    y = r1·sin θ
     *    z = r1·cos θ · r2·sin φ
     *
     *  Twelve random parameters across two profiles → unbounded
     *  variety. Some rolls produce smooth blobs, some produce
     *  spiked stars, some produce mushroom-like asymmetric forms. */
    private fun sampleSupershape(
        rnd: Random, R: Float, n: Int,
        xs: FloatArray, ys: FloatArray, zs: FloatArray,
    ) {
        val m1 = (1f + rnd.nextFloat() * 14f)
        val n1a = 0.3f + rnd.nextFloat() * 3.5f
        val n2a = 0.3f + rnd.nextFloat() * 3.5f
        val n3a = 0.3f + rnd.nextFloat() * 3.5f
        val m2 = (1f + rnd.nextFloat() * 14f)
        val n1b = 0.3f + rnd.nextFloat() * 3.5f
        val n2b = 0.3f + rnd.nextFloat() * 3.5f
        val n3b = 0.3f + rnd.nextFloat() * 3.5f
        // Compute a global scale so the rolled shape fits inside R
        // regardless of how spiky the random parameters made it.
        var peak = 0.001f
        for (s in 0 until 64) {
            val theta = (s / 64f - 0.5f) * PI.toFloat()
            val phi = s / 64f * 2f * PI.toFloat()
            val r = superR(theta, m1, n1a, n2a, n3a) * superR(phi, m2, n1b, n2b, n3b)
            if (r > peak) peak = r
        }
        val scale = R / peak
        for (i in 0 until n) {
            val theta = (rnd.nextFloat() - 0.5f) * PI.toFloat()
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            val r1 = superR(theta, m1, n1a, n2a, n3a)
            val r2 = superR(phi, m2, n1b, n2b, n3b)
            val ct = cos(theta); val st = sin(theta)
            val cp = cos(phi); val sp = sin(phi)
            xs[i] = r1 * ct * r2 * cp * scale
            ys[i] = r1 * st * scale
            zs[i] = r1 * ct * r2 * sp * scale
        }
    }

    private fun superR(angle: Float, m: Float, n1: Float, n2: Float, n3: Float): Float {
        val term1 = abs(cos(m * angle * 0.25f)).pow(n2)
        val term2 = abs(sin(m * angle * 0.25f)).pow(n3)
        val base = (term1 + term2).coerceAtLeast(1e-4f)
        return base.pow(-1f / n1)
    }

    // ── Spherical harmonic blob ──────────────────────────────────

    /** Sum of a handful of low-order Y_lm functions with random
     *  coefficients (taken as a simplified real-valued cos basis).
     *  Produces smooth alien-blob radial offsets. */
    private fun sampleSphericalHarmonic(
        rnd: Random, R: Float, n: Int,
        xs: FloatArray, ys: FloatArray, zs: FloatArray,
    ) {
        // Pick 5-9 random (l, m, coeff) terms.
        val numTerms = 5 + rnd.nextInt(5)
        val ls = IntArray(numTerms) { 1 + rnd.nextInt(7) }
        val ms = IntArray(numTerms) { rnd.nextInt(4) }
        val coefs = FloatArray(numTerms) { (rnd.nextFloat() - 0.5f) * 0.55f }
        for (i in 0 until n) {
            val theta = acos(2f * rnd.nextFloat() - 1f)
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            var rNorm = 1f
            for (k in 0 until numTerms) {
                rNorm += coefs[k] * cos(ls[k] * theta) * cos(ms[k] * phi)
            }
            rNorm = rNorm.coerceIn(0.40f, 1.55f)
            val r = rNorm * R * 0.75f
            xs[i] = r * sin(theta) * cos(phi)
            ys[i] = r * cos(theta)
            zs[i] = r * sin(theta) * sin(phi)
        }
    }

    // ── Generalised Lissajous knot ───────────────────────────────

    /** x = cos(a·t + φa), y = cos(b·t + φb), z = cos(c·t + φc).
     *  Random (a, b, c) ∈ {2..6}, random phase offsets, random tube
     *  radius. Trefoil + figure-eight + countless more emerge from
     *  this single family. */
    private fun sampleLissajousKnot(
        rnd: Random, R: Float, n: Int,
        xs: FloatArray, ys: FloatArray, zs: FloatArray,
    ) {
        val a = 2 + rnd.nextInt(5)
        val b = 2 + rnd.nextInt(5)
        val c = 2 + rnd.nextInt(5)
        val pa = rnd.nextFloat() * 2f * PI.toFloat()
        val pb = rnd.nextFloat() * 2f * PI.toFloat()
        val pc = rnd.nextFloat() * 2f * PI.toFloat()
        val scale = R * 0.80f
        val tubeR = R * (0.04f + rnd.nextFloat() * 0.08f)
        for (i in 0 until n) {
            val t = rnd.nextFloat() * 2f * PI.toFloat()
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            val cx = cos(a * t + pa) * scale
            val cy = cos(b * t + pb) * scale
            val cz = cos(c * t + pc) * scale
            xs[i] = cx + cos(phi) * tubeR
            ys[i] = cy + sin(phi) * tubeR
            zs[i] = cz
        }
    }

    // ── Metaball isosurface (gooey blob) ────────────────────────

    /** 3-7 random gaussian centres. For each particle we shoot a
     *  random ray from the origin and march to find the iso-distance
     *  where the field crosses threshold. Produces gooey amoeba /
     *  alien-organism shapes. */
    private fun sampleMetaballBlob(
        rnd: Random, R: Float, n: Int,
        xs: FloatArray, ys: FloatArray, zs: FloatArray,
    ) {
        val numBlobs = 3 + rnd.nextInt(5)
        val centres = Array(numBlobs) {
            floatArrayOf(
                (rnd.nextFloat() - 0.5f) * R * 0.55f,
                (rnd.nextFloat() - 0.5f) * R * 0.55f,
                (rnd.nextFloat() - 0.5f) * R * 0.55f,
            )
        }
        val radii = FloatArray(numBlobs) { R * (0.20f + rnd.nextFloat() * 0.22f) }
        val isoThreshold = 1.5f
        val marchSteps = 24
        val maxT = R * 1.4f
        for (i in 0 until n) {
            val u = rnd.nextFloat() * 2f - 1f
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - u * u)
            val dx = s * cos(phi); val dy = u; val dz = s * sin(phi)
            // March from far → near, take the first crossing where
            // the metaball field exceeds isoThreshold.
            var hitT = maxT * 0.4f
            for (step in marchSteps - 1 downTo 0) {
                val t = maxT * (step + 1) / marchSteps.toFloat()
                val px = dx * t; val py = dy * t; val pz = dz * t
                var field = 0f
                for (k in 0 until numBlobs) {
                    val c = centres[k]
                    val ax = px - c[0]; val ay = py - c[1]; val az = pz - c[2]
                    val d2 = (ax * ax + ay * ay + az * az).coerceAtLeast(1e-3f)
                    field += radii[k] * radii[k] / d2
                }
                if (field >= isoThreshold) { hitT = t; break }
            }
            xs[i] = dx * hitT
            ys[i] = dy * hitT
            zs[i] = dz * hitT
        }
    }

    // ── Random polytope (crystal cage) ──────────────────────────

    /** Generate 8-28 random vertices on the unit sphere, connect
     *  each to its k nearest neighbours, sample particles along
     *  the resulting edges. Every roll is a never-before-existed
     *  crystalline cage. */
    private fun sampleRandomPolytope(
        rnd: Random, R: Float, n: Int,
        xs: FloatArray, ys: FloatArray, zs: FloatArray,
    ) {
        val numVerts = 8 + rnd.nextInt(21)
        val verts = Array(numVerts) {
            val u = rnd.nextFloat() * 2f - 1f
            val phi = rnd.nextFloat() * 2f * PI.toFloat()
            val s = sqrt(1f - u * u)
            floatArrayOf(s * cos(phi), u, s * sin(phi))
        }
        val k = (2 + rnd.nextInt(3)).coerceAtMost(numVerts - 1)
        // For each vertex find its k nearest neighbours.
        val edgePairs = ArrayList<Int>(numVerts * k * 2)
        val dists = FloatArray(numVerts)
        for (i in 0 until numVerts) {
            for (j in 0 until numVerts) {
                if (j == i) { dists[j] = Float.MAX_VALUE; continue }
                val a = verts[i]; val b = verts[j]
                val dx = a[0] - b[0]; val dy = a[1] - b[1]; val dz = a[2] - b[2]
                dists[j] = dx * dx + dy * dy + dz * dz
            }
            // Pick the k smallest indices via simple repeated min.
            val taken = BooleanArray(numVerts)
            taken[i] = true
            for (kk in 0 until k) {
                var best = -1
                var bestD = Float.MAX_VALUE
                for (j in 0 until numVerts) {
                    if (!taken[j] && dists[j] < bestD) { best = j; bestD = dists[j] }
                }
                if (best >= 0) {
                    taken[best] = true
                    edgePairs.add(i); edgePairs.add(best)
                }
            }
        }
        val numEdges = edgePairs.size / 2
        val jitter = R * 0.014f
        for (i in 0 until n) {
            val e = rnd.nextInt(numEdges)
            val a = verts[edgePairs[e * 2]]
            val b = verts[edgePairs[e * 2 + 1]]
            val t = rnd.nextFloat()
            xs[i] = (a[0] * (1f - t) + b[0] * t) * R + gauss(rnd) * jitter
            ys[i] = (a[1] * (1f - t) + b[1] * t) * R + gauss(rnd) * jitter
            zs[i] = (a[2] * (1f - t) + b[2] * t) * R + gauss(rnd) * jitter
        }
    }

    // ── Family pick (mood-biased) ───────────────────────────────

    /** Public family-roll for [LivingShapeEngine] which wants to mint
     *  the family separately from the shape (so it can apply its own
     *  recent-family avoid list). */
    fun pickFamilyExternal(mood: String?, rnd: Random): Family = pickFamily(mood, rnd)

    private fun pickFamily(mood: String?, rnd: Random): Family {
        val weights = when (mood?.lowercase()) {
            "calm" -> floatArrayOf(0.28f, 0.32f, 0.10f, 0.22f, 0.08f)
            "happy" -> floatArrayOf(0.32f, 0.22f, 0.18f, 0.16f, 0.12f)
            "excited" -> floatArrayOf(0.22f, 0.10f, 0.32f, 0.10f, 0.26f)
            "sad" -> floatArrayOf(0.28f, 0.40f, 0.08f, 0.20f, 0.04f)
            "anxious" -> floatArrayOf(0.18f, 0.10f, 0.20f, 0.12f, 0.40f)
            "frustrated" -> floatArrayOf(0.18f, 0.08f, 0.28f, 0.10f, 0.36f)
            else -> floatArrayOf(0.26f, 0.20f, 0.20f, 0.16f, 0.18f)
        }
        val total = weights.sum()
        val r = rnd.nextFloat() * total
        var acc = 0f
        for (i in weights.indices) {
            acc += weights[i]
            if (r <= acc) return Family.entries[i]
        }
        return Family.entries.last()
    }

    private fun gauss(rnd: Random): Float {
        val u1 = rnd.nextDouble().coerceAtLeast(1e-9).toFloat()
        val u2 = rnd.nextFloat()
        return sqrt(-2f * ln(u1)) * cos(2f * PI.toFloat() * u2)
    }

    const val MAX_PARTICLE_COUNT = 1500
}
