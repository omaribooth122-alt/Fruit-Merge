package com.example

import android.content.Context
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.compose.ui.platform.LocalContext
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.MyApplicationTheme
import kotlin.random.Random

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MyApplicationTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color(0xFFFFF3E0)
                ) { innerPadding ->
                    GameScreen(Modifier.padding(innerPadding))
                }
            }
        }
    }
}

data class FruitType(val id: Int, val radiusRatio: Float, val color: Color, val emoji: String, val score: Int)

val FRUITS = listOf(
    FruitType(0, 0.04f, Color(0xFFE53935), "🍒", 2),
    FruitType(1, 0.06f, Color(0xFFD81B60), "🍓", 4),
    FruitType(2, 0.08f, Color(0xFF8E24AA), "🍇", 8),
    FruitType(3, 0.10f, Color(0xFFFFD54F), "🍋", 16),
    FruitType(4, 0.13f, Color(0xFFFF9800), "🍊", 32),
    FruitType(5, 0.16f, Color(0xFF4CAF50), "🍏", 64),
    FruitType(6, 0.20f, Color(0xFFF06292), "🍑", 128),
    FruitType(7, 0.25f, Color(0xFF795548), "🥥", 256),
    FruitType(8, 0.31f, Color(0xFF8BC34A), "🍈", 512),
    FruitType(9, 0.38f, Color(0xFF2E7D32), "🍉", 1024)
)

fun randomInitialFruit(score: Int): FruitType {
    val maxIdx = when {
        score > 5000 -> 2
        score > 2000 -> 3
        else -> 4
    }
    return FRUITS[Random.nextInt(maxIdx + 1)]
}

class FruitBody(
    var x: Float,
    var y: Float,
    var vx: Float = 0f,
    var vy: Float = 0f,
    var type: FruitType,
    val id: Int = Random.nextInt()
)

class Particle(
    var x: Float,
    var y: Float,
    var vx: Float,
    var vy: Float,
    val color: Color,
    var life: Float = 1f,
    val initialLife: Float = 1f,
    val size: Float = 10f
)

class FloatingText(
    var x: Float,
    var y: Float,
    val text: String,
    val color: Color,
    var life: Float = 1f,
    val initialLife: Float = 1f
)

@Stable
class GameState(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("FruitMergePrefs", Context.MODE_PRIVATE)
    var bestScore by mutableIntStateOf(prefs.getInt("best_score", 0))

    var toneGen: ToneGenerator? = null
    init {
        try {
            toneGen = ToneGenerator(AudioManager.STREAM_MUSIC, 100)
        } catch (e: Exception) {
            // Ignore
        }
    }

    fun checkBestScore() {
        if (score > bestScore) {
            bestScore = score
            prefs.edit().putInt("best_score", bestScore).apply()
        }
    }

    var tick by mutableIntStateOf(0)
    var score by mutableIntStateOf(0)
    var timeElapsed by mutableFloatStateOf(0f)
    val particles = mutableListOf<Particle>()
    val floatingTexts = mutableListOf<FloatingText>()
    var lastMergeTime by mutableFloatStateOf(0f)
    var comboCount by mutableIntStateOf(1)
    var isGameOver by mutableStateOf(false)
    var width = 0f
    var height = 0f
    
    val bodies = mutableListOf<FruitBody>()
    
    var currentFruitType by mutableStateOf(randomInitialFruit(0))
    var nextFruitType by mutableStateOf(randomInitialFruit(0))
    var currentX by mutableFloatStateOf(0f)
    var isDropping by mutableStateOf(false)
    
    var dropCooldown by mutableFloatStateOf(0f)
    var gameOverTimer by mutableFloatStateOf(0f)
    var initialized = false
    
    val gravity = 3000f

    fun updateDropX(x: Float) {
        if (isDropping || width == 0f) return
        val r = currentFruitType.radiusRatio * width
        currentX = x.coerceIn(r, width - r)
    }

    fun drop() {
        if (isDropping || isGameOver || width == 0f) return
        val dropLimitY = width * 0.15f
        val r = currentFruitType.radiusRatio * width
        val yPos = dropLimitY - r
        bodies.add(FruitBody(currentX, yPos, 0f, 0f, currentFruitType))
        isDropping = true
        dropCooldown = 1.0f
        toneGen?.startTone(ToneGenerator.TONE_CDMA_KEYPAD_VOLUME_KEY_LITE, 30)
    }

    fun reset() {
        bodies.clear()
        particles.clear()
        floatingTexts.clear()
        score = 0
        timeElapsed = 0f
        isGameOver = false
        gameOverTimer = 0f
        isDropping = false
        dropCooldown = 0f
        comboCount = 1
        lastMergeTime = 0f
        currentFruitType = randomInitialFruit(0)
        nextFruitType = randomInitialFruit(0)
        if (width > 0f) currentX = width / 2f
    }

    fun update(dt: Float) {
        if (isGameOver || width == 0f || height == 0f) return
        timeElapsed += dt
        
        if (!initialized) {
            initialized = true
            currentX = width / 2f
        }

        val pIter = particles.iterator()
        while (pIter.hasNext()) {
            val p = pIter.next()
            p.x += p.vx * dt
            p.y += p.vy * dt
            p.life -= dt
            if (p.life <= 0f) pIter.remove()
        }

        val ftIter = floatingTexts.iterator()
        while (ftIter.hasNext()) {
            val ft = ftIter.next()
            ft.y -= 50f * dt
            ft.life -= dt
            if (ft.life <= 0f) ftIter.remove()
        }

        if (dropCooldown > 0f) {
            dropCooldown -= dt
            if (dropCooldown <= 0f) {
                isDropping = false
                currentFruitType = nextFruitType
                nextFruitType = randomInitialFruit(score)
                updateDropX(currentX) // Re-clamp based on new radius
            }
        }

        if (bodies.size > 3) {
            var isStableOverLine = false
            val dropLimitY = width * 0.15f
            for (b in bodies) {
                val r = b.type.radiusRatio * width
                if (b.y - r < dropLimitY) {
                    if (kotlin.math.abs(b.vx) < 20f && kotlin.math.abs(b.vy) < 20f) {
                        isStableOverLine = true
                    }
                }
            }
            if (isStableOverLine) {
                gameOverTimer += dt
                if (gameOverTimer > 2f) {
                    isGameOver = true
                }
            } else {
                gameOverTimer = 0f
            }
        }

        val iterations = 8
        val subDt = dt / iterations
        val toRmv = mutableSetOf<FruitBody>()
        val toAdd = mutableListOf<FruitBody>()

        for (iter in 0 until iterations) {
            for (b in bodies) {
                if (toRmv.contains(b)) continue
                b.vy += gravity * subDt
                b.x += b.vx * subDt
                b.y += b.vy * subDt

                val r = b.type.radiusRatio * width
                if (b.y + r > height) {
                    b.y = height - r
                    b.vy = -b.vy * 0.2f
                    b.vx *= 0.8f
                }
                if (b.x - r < 0) {
                    b.x = r
                    b.vx = -b.vx * 0.2f
                    b.vy *= 0.98f
                }
                if (b.x + r > width) {
                    b.x = width - r
                    b.vx = -b.vx * 0.2f
                    b.vy *= 0.98f
                }
            }

            for (i in 0 until bodies.size) {
                for (j in i + 1 until bodies.size) {
                    val b1 = bodies[i]
                    val b2 = bodies[j]
                    if (toRmv.contains(b1) || toRmv.contains(b2)) continue

                    val r1 = b1.type.radiusRatio * width
                    val r2 = b2.type.radiusRatio * width
                    val dx = b2.x - b1.x
                    val dy = b2.y - b1.y
                    val distSq = dx * dx + dy * dy
                    val radSum = r1 + r2

                    if (distSq < radSum * radSum) {
                        if (b1.type.id == b2.type.id) {
                            if (b1.type.id < FRUITS.last().id) {
                                toRmv.add(b1)
                                toRmv.add(b2)
                                val newType = FRUITS[b1.type.id + 1]
                                val midX = (b1.x + b2.x) / 2
                                val midY = (b1.y + b2.y) / 2
                                toAdd.add(FruitBody(midX, midY, 0f, -150f, newType))
                                
                                val timeSinceLastMerge = timeElapsed - lastMergeTime
                                if (timeSinceLastMerge < 3f && lastMergeTime > 0f) {
                                    comboCount++
                                } else {
                                    comboCount = 1
                                }
                                lastMergeTime = timeElapsed
                                
                                val addedScore = newType.score * comboCount
                                score += addedScore
                                checkBestScore()
                                
                                val floatText = if (comboCount > 1) "+$addedScore (${comboCount}x COMBO!)" else "+$addedScore"
                                floatingTexts.add(FloatingText(midX, midY, floatText, Color.White, 1.5f, 1.5f))
                                
                                val toneType = when (newType.id % 4) {
                                    0 -> ToneGenerator.TONE_PROP_BEEP
                                    1 -> ToneGenerator.TONE_PROP_BEEP2
                                    2 -> ToneGenerator.TONE_PROP_ACK
                                    else -> ToneGenerator.TONE_PROP_PROMPT
                                }
                                toneGen?.startTone(toneType, 50)
                                
                                for (k in 0 until 15) {
                                    val angle = Random.nextFloat() * 2 * kotlin.math.PI.toFloat()
                                    val speed = Random.nextFloat() * 200f + 50f
                                    particles.add(
                                        Particle(
                                            x = midX,
                                            y = midY,
                                            vx = kotlin.math.cos(angle) * speed,
                                            vy = kotlin.math.sin(angle) * speed,
                                            color = newType.color,
                                            life = Random.nextFloat() * 0.5f + 0.5f,
                                            size = Random.nextFloat() * 15f + 5f
                                        )
                                    )
                                }
                                continue
                            }
                        }

                        val dist = kotlin.math.sqrt(distSq.toDouble()).toFloat()
                        if (dist == 0f) {
                            b1.x -= 0.1f
                            b2.x += 0.1f
                            continue
                        }
                        val overlap = radSum - dist
                        val nx = dx / dist
                        val ny = dy / dist

                        val m1 = r1 * r1
                        val m2 = r2 * r2
                        val invM1 = 1f / m1
                        val invM2 = 1f / m2
                        val totalInvM = invM1 + invM2

                        val percent = 0.8f
                        val slop = 0.05f
                        val correctionMag = (overlap - slop).coerceAtLeast(0f) / totalInvM * percent
                        val cx = nx * correctionMag
                        val cy = ny * correctionMag
                        b1.x -= cx * invM1
                        b1.y -= cy * invM1
                        b2.x += cx * invM2
                        b2.y += cy * invM2

                        val rvx = b2.vx - b1.vx
                        val rvy = b2.vy - b1.vy
                        val velAlongNormal = rvx * nx + rvy * ny

                        if (velAlongNormal < 0) {
                            val e = 0.05f
                            var jNorm = -(1 + e) * velAlongNormal
                            jNorm /= totalInvM

                            val impX = jNorm * nx
                            val impY = jNorm * ny
                            b1.vx -= impX * invM1
                            b1.vy -= impY * invM1
                            b2.vx += impX * invM2
                            b2.vy += impY * invM2

                            val tx = -ny
                            val ty = nx
                            val tangVel = rvx * tx + rvy * ty
                            var jt = -tangVel
                            jt /= totalInvM

                            val mu = 0.4f
                            if (jt > jNorm * mu) jt = jNorm * mu
                            if (jt < -jNorm * mu) jt = -jNorm * mu

                            b1.vx -= jt * tx * invM1
                            b1.vy -= jt * ty * invM1
                            b2.vx += jt * tx * invM2
                            b2.vy += jt * ty * invM2
                        }
                    }
                }
            }
        }

        if (toRmv.isNotEmpty()) {
            bodies.removeAll(toRmv)
            bodies.addAll(toAdd)
        }

        tick++
    }
}

@Composable
fun GameScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val gameState = remember { GameState(context) }

    DisposableEffect(Unit) {
        onDispose {
            gameState.toneGen?.release()
        }
    }

    LaunchedEffect(Unit) {
        var lastTime = withFrameNanos { it }
        while (true) {
            val time = withFrameNanos { it }
            val dt = (time - lastTime) / 1_000_000_000f
            lastTime = time
            gameState.update(dt.coerceAtMost(0.032f))
        }
    }

    Column(modifier.fillMaxSize()) {
        GameTopBar(
            score = gameState.score, 
            bestScore = gameState.bestScore,
            nextFruit = gameState.nextFruitType,
            timeElapsed = gameState.timeElapsed
        )

        val textMeasurer = rememberTextMeasurer()

        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color(0xFFFFF3E0))
                .onSizeChanged {
                    gameState.width = it.width.toFloat()
                    gameState.height = it.height.toFloat()
                }
                .pointerInput(gameState.isDropping) {
                    if (!gameState.isDropping) {
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            gameState.updateDropX(down.position.x)
                            do {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull()
                                if (change != null && change.positionChange() != Offset.Zero) {
                                    gameState.updateDropX(change.position.x)
                                }
                            } while (event.changes.any { it.pressed })
                            gameState.drop()
                        }
                    }
                }
        ) {
            val tick = gameState.tick
            val width = gameState.width

            Canvas(Modifier.fillMaxSize()) {
                val _req = tick 

                if (width > 0f) {
                    val dropLimitY = width * 0.15f

                    drawLine(
                        color = Color.Red.copy(alpha = 0.3f),
                        start = Offset(0f, dropLimitY),
                        end = Offset(width, dropLimitY),
                        strokeWidth = 6f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(30f, 20f))
                    )

                    if (!gameState.isDropping) {
                        val rPx = gameState.currentFruitType.radiusRatio * width
                        val yPos = dropLimitY - rPx

                        drawCircle(
                            color = gameState.currentFruitType.color,
                            radius = rPx,
                            center = Offset(gameState.currentX, yPos)
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.4f),
                            radius = rPx,
                            center = Offset(gameState.currentX, yPos),
                            style = Stroke(width = 4.dp.toPx())
                        )
                        val textSize = (rPx * 1.2f).toSp()
                        val style = TextStyle(fontSize = textSize)
                        val textLayoutResult = textMeasurer.measure(gameState.currentFruitType.emoji, style)
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(
                                gameState.currentX - textLayoutResult.size.width / 2f,
                                yPos - textLayoutResult.size.height / 2f
                            )
                        )
                    }

                    for (body in gameState.bodies) {
                        val rPx = body.type.radiusRatio * width
                        drawCircle(
                            color = body.type.color,
                            radius = rPx,
                            center = Offset(body.x, body.y)
                        )
                        drawCircle(
                            color = Color.Black.copy(alpha = 0.1f),
                            radius = rPx,
                            center = Offset(body.x, body.y),
                            style = Stroke(width = 3.dp.toPx())
                        )
                        val textSize = (rPx * 1.2f).toSp()
                        val style = TextStyle(fontSize = textSize)
                        val textLayoutResult = textMeasurer.measure(body.type.emoji, style)
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(
                                body.x - textLayoutResult.size.width / 2f,
                                body.y - textLayoutResult.size.height / 2f
                            )
                        )
                    }

                    for (p in gameState.particles) {
                        val alpha = (p.life / p.initialLife).coerceIn(0f, 1f)
                        drawCircle(
                            color = p.color.copy(alpha = alpha),
                            radius = p.size,
                            center = Offset(p.x, p.y)
                        )
                    }

                    for (ft in gameState.floatingTexts) {
                        val alpha = (ft.life / ft.initialLife).coerceIn(0f, 1f)
                        val textLayoutResult = textMeasurer.measure(
                            ft.text,
                            TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, color = ft.color.copy(alpha = alpha))
                        )
                        drawText(
                            textLayoutResult = textLayoutResult,
                            topLeft = Offset(
                                ft.x - textLayoutResult.size.width / 2f,
                                ft.y - textLayoutResult.size.height / 2f
                            ),
                            shadow = androidx.compose.ui.graphics.Shadow(
                                color = Color.Black.copy(alpha = alpha * 0.8f),
                                offset = Offset(2f, 2f),
                                blurRadius = 4f
                            )
                        )
                    }
                }
            }

            if (gameState.isGameOver) {
                GameOverOverlay(score = gameState.score, bestScore = gameState.bestScore, onRestart = { gameState.reset() })
            }
        }
    }
}

@Composable
fun GameTopBar(score: Int, bestScore: Int, nextFruit: FruitType, timeElapsed: Float) {
    val minutes = (timeElapsed / 60).toInt()
    val seconds = (timeElapsed % 60).toInt()
    val timeString = "${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFFFFB74D))
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("NEXT", fontWeight = FontWeight.Bold, color = Color(0xFF5D4037))
            Spacer(Modifier.height(8.dp))
            Box(
                Modifier
                    .size(56.dp)
                    .background(nextFruit.color, CircleShape)
                    .border(3.dp, Color.White.copy(alpha = 0.5f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Text(nextFruit.emoji, fontSize = 28.sp)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TIME", fontWeight = FontWeight.Bold, color = Color(0xFF5D4037))
            Text(timeString, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Color.White)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SCORE", fontWeight = FontWeight.Bold, color = Color(0xFF5D4037))
            Text("$score", fontSize = 42.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            Text("BEST: $bestScore", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color(0xFF8D6E63))
        }
    }
}

@Composable
fun GameOverOverlay(score: Int, bestScore: Int, onRestart: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f)),
        contentAlignment = Alignment.Center
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            shape = RoundedCornerShape(24.dp)
        ) {
            Column(
                Modifier.padding(48.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Game Over!", fontSize = 36.sp, fontWeight = FontWeight.Bold, color = Color(0xFFE53935))
                Spacer(Modifier.height(16.dp))
                Text("Score: $score", fontSize = 28.sp, fontWeight = FontWeight.SemiBold)
                Text("Best: $bestScore", fontSize = 20.sp, fontWeight = FontWeight.Medium, color = Color(0xFF8D6E63))
                Spacer(Modifier.height(32.dp))
                Button(
                    onClick = onRestart,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50)),
                    contentPadding = PaddingValues(horizontal = 32.dp, vertical = 16.dp)
                ) {
                    Text("Play Again", fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}
