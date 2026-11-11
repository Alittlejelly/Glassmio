package io.github.neoruaa.glasmio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.LayerOutsets
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.constrainHeight
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle
import io.github.neoruaa.glasmio.core.LocalGlassAppearance
import io.github.neoruaa.glasmio.core.LocalGlassColors
import io.github.neoruaa.glasmio.core.GlassColors
import io.github.neoruaa.glasmio.core.GlassAppearance
import io.github.neoruaa.glasmio.glass.NavigationGlassMaterial
import io.github.neoruaa.glasmio.glass.navigationGlassBackground
import io.github.neoruaa.glasmio.glass.rememberNavigationInnerShadows
import io.github.neoruaa.glasmio.glass.render.Backdrop
import io.github.neoruaa.glasmio.glass.render.rememberGlassMaterialContext
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A single changing silhouette for the top-tabs/sidebar backplate transition.
 * [bounds] and [cornerRadius] use pixels in this surface's local viewport. The caller owns
 * timing and content; neither panel controls nor this backplate should enter [backdrop].
 * Material 0 matches the resting top tab bar; material 1 matches [GlasmioSidebar]. The latter
 * remains a design-resource adaptation of UIKit's environment-resolved sidebar material.
 */
@Composable
fun GlasmioSidebarMorphSurface(
    backdrop: Backdrop,
    bounds: () -> Rect,
    cornerRadius: () -> Float,
    sidebarMaterialProgress: () -> Float,
    modifier: Modifier = Modifier,
    sidebarBackdrop: Backdrop = backdrop,
    tabsColors: GlassColors = LocalGlassColors.current,
    tabsAppearance: GlassAppearance = LocalGlassAppearance.current,
    visible: () -> Boolean = { true },
) {
    val density = LocalDensity.current
    val shadowOutsets = remember { LayerOutsets(64.dp, 64.dp, 64.dp, 64.dp) }
    // 形变结束后继续在原几何上画两帧（外层 alpha ≈ 0.004，肉眼不可见），把采样纹理、
    // 材质滤镜和 atlas 留在热态。否则每次开合的第一帧都要把整套 RenderEffect 现建一遍，
    // 真机日志里表现为每个形变开头一次 40ms 以上的 UI 线程尖峰（layers/draw 阶段）。
    val active = visible()
    var hasMorphed by remember { mutableStateOf(false) }
    var warmFrames by remember { mutableIntStateOf(0) }
    // -1 = 未预热；0..3 = 正在沿形状范围走采样几何。
    var warmStep by remember { mutableIntStateOf(-1) }
    LaunchedEffect(active) {
        if (active) {
            hasMorphed = true
            warmFrames = 0
        } else if (hasMorphed) {
            warmFrames = 2
            while (warmFrames > 0) {
                withFrameNanos { }
                warmFrames -= 1
            }
        }
    }
    // 第一次开合之前把形变路径整体预热一遍。
    LaunchedEffect(Unit) {
        if (active) return@LaunchedEffect
        delay(300)
        // 等界面真正空闲（连续两帧都在 24ms 内）再动手：启动后那几秒整棵树还在首次绘制，
        // 这时候插进去只会把两段成本挤在同一批帧里。最多等 240 帧，等不到就放弃。
        var previous = 0L
        var idle = 0
        var waited = 0
        while (idle < 2 && waited < 240) {
            if (visible() || hasMorphed) return@LaunchedEffect
            val now = withFrameNanos { it }
            if (previous != 0L && now - previous < 24_000_000L) idle++ else idle = 0
            previous = now
            waited++
        }
        if (visible() || hasMorphed) return@LaunchedEffect
        // 先在静止几何上画两帧：把采样纹理和材质滤镜留在热态，收尾切换时才不会闪。
        warmFrames = 2
        while (warmFrames > 0) {
            withFrameNanos { }
            warmFrames -= 1
        }
        // 只在一个尺寸上预热是不够的：玻璃的 RuntimeShader、mip atlas 尺寸、材质边缘场
        // 都是"第一次遇到这个几何"时才编译/分配的，而形变途中每一帧的几何都不一样。
        // 真机日志里第一次开合是 378ms draw + 389ms measure、连续 5 秒 5fps，
        // 之后同样的代码却是 0.5ms/帧——这份成本必须提前付掉。
        // 这里沿着形状范围走 4 个采样几何，每个画两帧（外层 alpha 1/255，肉眼不可见）。
        for (step in 0..3) {
            if (visible() || hasMorphed) break
            warmStep = step
            withFrameNanos { }
            withFrameNanos { }
        }
        warmStep = -1
    }
    val placeSurface = active || warmFrames > 0 || warmStep >= 0
    // 预热时两块材质都是"半开"，否则权重为 0 的那一块会被下面的门控跳过、始终冷着。
    val warming = warmStep >= 0
    // 这个表面是随形变新建的：首帧时它的背板采样纹理、形状覆盖遮罩、材质上下文都还没产出，
    // 部分设备会把这个"未初始化"的结果直接画成整块深色（有时连形状裁剪都没生效），
    // 表现为顶栏/侧栏闪黑一到两帧。先画两帧容器色兜底，再交给玻璃材质。
    var surfaceReady by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        withFrameNanos { }
        surfaceReady = true
    }
    val shape: () -> Shape = remember(density, cornerRadius) {
        // 形变时同一个 shape 在一帧内会被采好几次（采样倍率、材质边缘场、描边、裁剪都各调一次
        // createOutline）。连续圆角的 outline 构造不便宜，按"尺寸 + 圆角"缓存最后一次结果，
        // 一帧内后续调用直接复用同一个不可变 Outline。
        val cachedShape = object : Shape {
            private var lastWidth = Float.NaN
            private var lastHeight = Float.NaN
            private var lastRadius = Float.NaN
            private var lastOutline: Outline? = null

            override fun createOutline(
                size: Size,
                layoutDirection: LayoutDirection,
                density: Density,
            ): Outline {
                // RoundedRectangle and Capsule share the same continuous-corner implementation.
                // Radius is evaluated in drawing, never stretched with an anisotropic layer scale.
                val radius = cornerRadius().coerceAtLeast(0f)
                val cached = lastOutline
                if (cached != null && size.width == lastWidth && size.height == lastHeight &&
                    radius == lastRadius) {
                    return cached
                }
                val outline = RoundedRectangle(with(density) { radius.toDp() })
                    .createOutline(size, layoutDirection, density)
                lastWidth = size.width
                lastHeight = size.height
                lastRadius = radius
                lastOutline = outline
                return outline
            }
        }
        val provider: () -> Shape = { cachedShape }
        provider
    }
    Layout(
        modifier = modifier.fillMaxSize(),
        content = {
            // Blend complete optical outputs additively on transparency, then composite once.
            // Two SrcOver half-alpha surfaces would leak 25% of the untreated backing.
            Box(Modifier.graphicsLayer {
                compositingStrategy = CompositingStrategy.Offscreen
                outsets = shadowOutsets
                // 预热帧几乎全透明；1/255 足以让 HWUI 真正跑完整条滤镜链。
                if (!active) alpha = 0.004f
            }) {
            if (!surfaceReady) {
                // 首帧兜底：与静止态顶栏同色的实心形状，形状/圆角仍然跟随形变。
                Box(Modifier.matchParentSize().clip(shape()).background(tabsColors.container))
            } else {
            CompositionLocalProvider(LocalGlassColors provides tabsColors, LocalGlassAppearance provides tabsAppearance) {
            val materialContext = rememberGlassMaterialContext()
            val innerShadows = rememberNavigationInnerShadows(tabsAppearance.usesBackdrop, renderScale = .5f)
            // 两块材质是加法混合的渐变：形变两端总有一块权重接近 0，但它仍然要跑完
            // 一整套采样 + 材质滤镜（尺寸每帧都在变，链也每帧重建）。权重低于 2% 时
            // 直接不画——混合结果变化小于 1/255，肉眼不可见，但省掉整整一层玻璃。
            Box(Modifier.matchParentSize().graphicsLayer {
                alpha = if (warming) .5f else 1f - sidebarMaterialProgress().coerceIn(0f, 1f)
                // Intermediate group opacity creates its own offscreen target. Preserve the
                // exterior shadow here before the enclosing material-mix layer composites it.
                outsets = shadowOutsets
            }.drawWithContent {
                if (warming || sidebarMaterialProgress() < .98f) drawContent()
            }.navigationGlassBackground(
                backdrop = backdrop,
                shape = shape,
                containerColor = tabsColors.container,
                navigationMaterial = tabsAppearance.usesBackdrop,
                innerShadows = innerShadows,
                isDark = tabsColors.isDark,
                appearance = tabsAppearance,
                materialContext = materialContext,
                material = NavigationGlassMaterial.RegularLarge,
                shadowAlpha = .5f,
            ))
            }
            Box(Modifier.matchParentSize().graphicsLayer {
                alpha = if (warming) .5f else sidebarMaterialProgress().coerceIn(0f, 1f)
                blendMode = BlendMode.Plus
            }.drawWithContent {
                if (warming || sidebarMaterialProgress() > .02f) drawContent()
            }.sidebarMaterialBackground(sidebarBackdrop, shape))
            }
            }
        },
    ) { measurables, constraints ->
        // 静止时仍取 bounds()：形变停住后轨道就是端点值，读它不会产生每帧失效，
        // 但拿到的正是下一次形变的起始几何，预热才有意义。不预热时完全不 place，
        // 未被放置的子节点不会被绘制，所以滚动等日常帧不会为它付任何代价。
        val resting = bounds()
        val frame = if (warmStep >= 0) warmFrame(resting, constraints, warmStep, density) else resting
        val width = frame.width.coerceAtLeast(0f).roundToInt()
        val height = frame.height.coerceAtLeast(0f).roundToInt()
        val layers = measurables.map { it.measure(Constraints.fixed(width, height)) }
        val viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth
            else constraints.constrainWidth(ceil(frame.right.coerceAtLeast(0f)).toInt())
        val viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight
            else constraints.constrainHeight(ceil(frame.bottom.coerceAtLeast(0f)).toInt())
        layout(viewportWidth, viewportHeight) {
            if (!placeSurface) return@layout
            val x = frame.left.roundToInt()
            val y = frame.top.roundToInt()
            layers.forEach { it.place(x, y) }
        }
    }
}

/**
 * 预热用的采样几何：从当前静止的顶栏矩形，按比例长到"侧栏宽度 + 整个视口高度"的形状。
 * 玻璃的 RuntimeShader、mip atlas 尺寸和材质边缘场都是按第一次遇到的几何编译/分配的，
 * 所以只要在这条路径上走几个不同的尺寸，第一次真正开合时就不用再造一遍。
 * 形状本身不需要精确——这几帧的 alpha 是 1/255。
 */
private fun warmFrame(
    resting: Rect,
    constraints: Constraints,
    step: Int,
    density: Density,
): Rect {
    val viewportWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else resting.right.roundToInt()
    val viewportHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else resting.bottom.roundToInt()
    val sidebarWidth = with(density) { 250.dp.roundToPx() }
        .coerceIn(1, viewportWidth.coerceAtLeast(1))
    val t = (step + 1) / 4f
    val width = (resting.width + (sidebarWidth - resting.width) * t).coerceAtLeast(0f)
    val height = (resting.height + (viewportHeight - resting.height) * t).coerceAtLeast(0f)
    return Rect(resting.left, resting.top, resting.left + width, resting.top + height)
}
