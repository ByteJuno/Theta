package io.github.bytejuno.thetacore.sample.probe

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private enum class UiCheck {
    MATERIAL,
    LAZY_LIST,
    STATE,
    ANIMATION,
    DRAWING,
    CUSTOM_LAYOUT,
    CUSTOM_MODIFIER_NODE,
    CUSTOM_SHAPE,
    CUSTOM_PAINTER,
    VECTOR,
    LOCALS,
    GESTURE,
    TEXT,
}

private class Report {
    private val results = mutableStateMapOf<String, String?>()
    private val total = libraryChecks.size + UiCheck.entries.size

    val failures get() = results.filterValues { it != null }.map { (name, reason) -> "FAIL $name: $reason" }
    val summary get() = "probe: ${results.count { it.value == null }}/$total ok"

    fun pass(name: String) {
        // 已经失败的项不能被后面的成功覆盖。
        if (name !in results) results[name] = null
    }

    fun pass(check: UiCheck) = pass(check.name)

    fun fail(name: String, reason: Throwable) {
        results[name] = reason.toString()
    }
}

@Composable
internal fun ProbeScreen() {
    val report = remember { Report() }

    LaunchedEffect(Unit) {
        libraryChecks.forEach { check ->
            try {
                check.run()
                report.pass(check.name)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                report.fail(check.name, e)
            }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(report.summary, style = MaterialTheme.typography.titleMedium)
        report.failures.forEach { Text(it, color = MaterialTheme.colorScheme.error) }
        Interaction()
        Material(report)
        LazyList(report)
        State(report)
        Animation(report)
        Drawing(report)
        CustomLayout(report)
        CustomNode(report)
        CustomShape(report)
        CustomPainter(report)
        Vector(report)
        Locals(report)
        Gesture(report)
        RichText(report)
    }
}

@Composable
private fun Interaction() {
    var clicks by remember { mutableIntStateOf(0) }
    var dialogOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { clicks++ }) { Text("Clicked $clicks") }
        Button(onClick = { dialogOpen = true }) { Text("Open dialog") }
        Box {
            Button(onClick = { menuOpen = true }) { Text("Menu") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Menu item") }, onClick = { menuOpen = false })
            }
        }
    }
    if (dialogOpen) {
        AlertDialog(
            onDismissRequest = { dialogOpen = false },
            confirmButton = { TextButton(onClick = { dialogOpen = false }) { Text("Close") } },
            text = { Text("Dialog body") },
        )
    }
}

private val UpperCase = VisualTransformation { text ->
    TransformedText(AnnotatedString(text.text.uppercase()), OffsetMapping.Identity)
}

@Composable
private fun Material(report: Report) {
    var checked by remember { mutableStateOf(true) }
    var slider by remember { mutableFloatStateOf(0.5f) }
    var text by rememberSaveable { mutableStateOf("saved") }

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = checked, onCheckedChange = { checked = it })
                Checkbox(checked = checked, onCheckedChange = { checked = it })
                CircularProgressIndicator(progress = { 0.6f }, modifier = Modifier.size(24.dp))
            }
            Slider(value = slider, onValueChange = { slider = it })
            OutlinedTextField(value = text, onValueChange = { text = it }, visualTransformation = UpperCase)
            LinearProgressIndicator(progress = { 0.4f }, modifier = Modifier.fillMaxWidth())
            HorizontalDivider()
        }
    }
    SideEffect { report.pass(UiCheck.MATERIAL) }
}

@Composable
private fun LazyList(report: Report) {
    LazyColumn(modifier = Modifier.fillMaxWidth().height(64.dp)) {
        items(listOf("Row 1", "Row 2", "Row 3")) { row ->
            Text(row, modifier = Modifier.clickable {})
            SideEffect { report.pass(UiCheck.LAZY_LIST) }
        }
    }
}

@Composable
private fun State(report: Report) {
    var source by remember { mutableIntStateOf(0) }
    val doubled by remember { derivedStateOf { source * 2 } }
    val produced by produceState(initialValue = 0) { value = 1 }
    val scope = rememberCoroutineScope()

    DisposableEffect(Unit) {
        source = 2
        onDispose {}
    }
    LaunchedEffect(Unit) {
        snapshotFlow { doubled }.first { it == 4 }
        snapshotFlow { produced }.first { it == 1 }
        check(scope.coroutineContext[kotlinx.coroutines.Job] != null)
        report.pass(UiCheck.STATE)
    }
}

@Composable
private fun Animation(report: Report) {
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0.5f, label = "alpha")
    val color by animateColorAsState(if (shown) Color.Blue else Color.Gray, label = "color")

    LaunchedEffect(Unit) { shown = true }
    Box(modifier = Modifier.size(24.dp).graphicsLayer { this.alpha = alpha }.background(color))
    AnimatedVisibility(visible = shown) {
        Text("Animated")
        SideEffect { report.pass(UiCheck.ANIMATION) }
    }
}

@Composable
private fun Drawing(report: Report) {
    Canvas(modifier = Modifier.fillMaxWidth().height(16.dp).drawBehind { drawRect(Color.LightGray) }) {
        drawLine(Color.Red, Offset.Zero, Offset(size.width, size.height))
        report.pass(UiCheck.DRAWING)
    }
}

/** 宿主的布局系统回调插件实现的 `MeasurePolicy`。 */
@Composable
private fun CustomLayout(report: Report) {
    Layout(
        content = {
            Text("Stacked 1")
            Text("Stacked 2")
        },
        modifier = Modifier.onGloballyPositioned { report.pass(UiCheck.CUSTOM_LAYOUT) },
    ) { measurables, constraints ->
        val placeables = measurables.map { it.measure(constraints) }
        layout(constraints.maxWidth, placeables.sumOf { it.height }) {
            var y = 0
            placeables.forEach { placeable ->
                placeable.placeRelative(0, y)
                y += placeable.height
            }
        }
    }
}

/** 插件继承宿主的 `Modifier.Node`，由宿主回调绘制。 */
private class StripeNode(var color: Color, val onDraw: () -> Unit) : Modifier.Node(), DrawModifierNode {
    override fun ContentDrawScope.draw() {
        drawRect(color)
        drawContent()
        onDraw()
    }
}

private data class StripeElement(val color: Color, val onDraw: () -> Unit) : ModifierNodeElement<StripeNode>() {
    override fun create() = StripeNode(color, onDraw)

    override fun update(node: StripeNode) {
        node.color = color
    }
}

@Composable
private fun CustomNode(report: Report) {
    val onDraw = remember(report) { { report.pass(UiCheck.CUSTOM_MODIFIER_NODE) } }
    Box(modifier = Modifier.fillMaxWidth().height(8.dp).then(StripeElement(Color.Green, onDraw)))
}

/** 宿主裁剪时回调插件实现的 `Shape`。 */
private object WedgeShape : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline =
        Outline.Generic(
            Path().apply {
                moveTo(0f, 0f)
                lineTo(size.width, 0f)
                lineTo(size.width, size.height)
                close()
            },
        )
}

@Composable
private fun CustomShape(report: Report) {
    Box(
        modifier = Modifier.size(24.dp).clip(WedgeShape).background(Color.Magenta)
            .clip(RoundedCornerShape(4.dp))
            .onGloballyPositioned { report.pass(UiCheck.CUSTOM_SHAPE) },
    )
}

/** 插件继承宿主的 `Painter`。 */
private class DotPainter(private val onPainted: () -> Unit) : Painter() {
    override val intrinsicSize = Size(24f, 24f)

    override fun DrawScope.onDraw() {
        drawCircle(Color.Cyan)
        onPainted()
    }
}

@Composable
private fun CustomPainter(report: Report) {
    val painter = remember(report) { DotPainter { report.pass(UiCheck.CUSTOM_PAINTER) } }
    Image(painter = painter, contentDescription = null, modifier = Modifier.size(24.dp))
}

private val TriangleVector = ImageVector.Builder(
    name = "Triangle",
    defaultWidth = 24.dp,
    defaultHeight = 24.dp,
    viewportWidth = 24f,
    viewportHeight = 24f,
).apply {
    path(fill = SolidColor(Color.Black)) {
        moveTo(12f, 2f)
        lineTo(22f, 22f)
        lineTo(2f, 22f)
        close()
    }
}.build()

@Composable
private fun Vector(report: Report) {
    Image(
        painter = rememberVectorPainter(TriangleVector),
        contentDescription = null,
        modifier = Modifier.size(24.dp).onGloballyPositioned { report.pass(UiCheck.VECTOR) },
    )
}

private val LocalLabel = compositionLocalOf { "unset" }

@Composable
private fun Locals(report: Report) {
    val context = LocalContext.current
    val density = LocalDensity.current.density
    val locale = LocalConfiguration.current.locales[0]
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    CompositionLocalProvider(LocalLabel provides "provided") {
        val label = LocalLabel.current
        SideEffect {
            check(context.packageName.isNotEmpty() && density > 0f && locale != null)
            check(lifecycle.currentState.name.isNotEmpty() && label == "provided")
            report.pass(UiCheck.LOCALS)
        }
    }
}

@Composable
private fun Gesture(report: Report) {
    BoxWithConstraints(
        modifier = Modifier.fillMaxWidth().height(16.dp).pointerInput(Unit) { detectTapGestures {} }
            .semantics { contentDescription = "Gesture area" },
    ) {
        check(maxWidth > 0.dp)
        SideEffect { report.pass(UiCheck.GESTURE) }
    }
}

@Composable
private fun RichText(report: Report) {
    val text = buildAnnotatedString {
        append("Rich ")
        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append("text") }
    }
    Text(text, onTextLayout = { report.pass(UiCheck.TEXT) })
}
