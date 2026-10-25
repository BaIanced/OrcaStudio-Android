package app.orcaandroid.ui.scad

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VerticalAlignBottom
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.orcaandroid.R
import app.orcaandroid.scad.ScadHints
import app.orcaandroid.scad.ScadParam
import app.orcaandroid.scad.ScadSnippets
import app.orcaandroid.ui.AppViewModel
import app.orcaandroid.ui.ScadEditorState
import app.orcaandroid.ui.components.NumberField
import app.orcaandroid.ui.components.PickerField
import app.orcaandroid.ui.components.PickerItem
import app.orcaandroid.ui.components.appIsDark
import kotlin.math.roundToInt

private val CODE_STYLE = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp)
private val LINE_REF = Regex("""line (\d+)""")

/**
 * The OpenSCAD editor panel: code and Customizer tabs, the console and the render actions. It sits
 * beside the 3D view (tablets) or over it (phones, [onHide] shows the plate), which shows the result.
 */
@Composable
fun ScadPanel(s: ScadEditorState, vm: AppViewModel, onHide: (() -> Unit)? = null, embeddedPreview: Boolean = true) {
    var tab by rememberSaveable { mutableStateOf(0) }
    var jump by remember { mutableStateOf<Int?>(null) }
    var previewOpen by rememberSaveable { mutableStateOf(true) }
    var templates by remember { mutableStateOf(false) }
    var addSetting by remember { mutableStateOf(false) }
    // A change for the code editor to apply to its text and cursor (Insert, Add a slider).
    var codeEdit by remember { mutableStateOf<((TextFieldValue) -> TextFieldValue)?>(null) }
    val context = LocalContext.current
    // A design with sliders opens on the Customizer: changing it needs no code.
    var shownLoad by rememberSaveable { mutableStateOf(-1) }
    if (shownLoad != s.loadId) {
        shownLoad = s.loadId
        tab = if (s.shapes != null) 0 else if (s.source.contains(Regex("""//\s*\["""))) 1 else 0
    }
    // Shape designs get a Shapes tab first; the code is generated (read only).
    val builder = s.shapes != null
    val tabs = if (builder) listOf("shapes", "code", "custom") else listOf("code", "custom")
    val current = tabs.getOrElse(tab) { tabs[0] }
    val codeTab = tabs.indexOf("code")
    val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/x-openscad")) { it?.let(vm.scad::save) }
    val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { it?.let(vm.scad::openUri) }
    // Android adds no extension for an OpenSCAD MIME type, so a shape design ("Shape design") gets one here.
    val saveName = if (s.name.endsWith(".scad", ignoreCase = true)) s.name else s.name + ".scad"
    BoxWithConstraints(Modifier.fillMaxSize()) {
    // The preview gets a share of the panel, so the code / Customizer keep most of a short one.
    val previewHeight = minOf(maxWidth * 0.62f, maxHeight * 0.3f).coerceIn(110.dp, 300.dp)
    Column(
        Modifier.fillMaxSize().onPreviewKeyEvent { e ->
            if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
            when {
                e.key == Key.F5 || (e.isCtrlPressed && e.key == Key.Enter) -> { vm.scad.preview(); true }
                e.key == Key.F6 -> { vm.scad.render(); true }
                e.isCtrlPressed && e.key == Key.S -> { save.launch(saveName); true }
                else -> false
            }
        },
    ) {
        Header(s, vm, onHide, onOpen = { open.launch(arrayOf("*/*")) }, onSave = { save.launch(saveName) }, onNew = { templates = true })
        // Without the embedded preview (the design is on the main canvas or the plate) only the auto-render switch stays.
        PreviewBar(s, vm, previewOpen, toggle = embeddedPreview) { previewOpen = !previewOpen }
        if (embeddedPreview && previewOpen) ScadPreviewPane(s, vm, Modifier.fillMaxWidth().height(previewHeight))
        PrimaryTabRow(selectedTabIndex = tabs.indexOf(current)) {
            tabs.forEachIndexed { i, t ->
                Tab(current == t, { tab = i }, text = {
                    Text(when (t) {
                        "shapes" -> stringResource(R.string.shape_tab)
                        "code" -> stringResource(R.string.scad_code)
                        else -> stringResource(R.string.scad_customizer) + if (s.params.isNotEmpty()) " · ${s.params.size}" else ""
                    })
                })
            }
        }
        val paneName = stringResource(when (current) {
            "shapes" -> R.string.shape_tab
            "code" -> R.string.scad_code
            else -> R.string.scad_customizer
        })
        Column(Modifier.weight(1f).fillMaxWidth().semantics { paneTitle = paneName }) {
            if (current == "shapes") ShapesTab(s, vm)
            else if (current == "code" && builder) {
                GeneratedNotice(onDetach = vm.scad::detachShapes)
                CodeEditor(s, vm, jump, onJumped = { jump = null }, edit = null, onEdited = {}, readOnly = true)
            } else if (current == "code") {
                InsertBar(
                    onInsert = { sn ->
                        codeEdit = { v ->
                            val (t, at) = ScadSnippets.insert(v.text, v.selection.start, v.selection.end, sn)
                            TextFieldValue(t, TextRange(at))
                        }
                    },
                    onAddSetting = { addSetting = true },
                )
                CodeEditor(s, vm, jump, onJumped = { jump = null }, edit = codeEdit, onEdited = { codeEdit = null })
            } else Customizer(s, vm, onAddSetting = { addSetting = true })
        }
        HorizontalDivider()
        HintCards(remember(s.log) { ScadHints.explain(s.log) }, onLine = { tab = codeTab; jump = it })
        Console(s, onLine = { tab = codeTab; jump = it })
        Actions(s, vm)
    }
    }
    if (templates) TemplateGallery(onDismiss = { templates = false }, onPick = { templates = false; vm.scad.loadExample(it) },
        onShapes = { templates = false; vm.scad.newShapes() })
    if (addSetting) AddSettingDialog(onDismiss = { addSetting = false }) { label, mm, value, min, max, step ->
        addSetting = false
        // Shape designs regenerate their code: the slider becomes a dimension of the design.
        if (builder) {
            vm.shapes.addDim(label, value, min, max, step)?.let { name ->
                android.widget.Toast.makeText(context, context.getString(R.string.shape_dim_added, name), android.widget.Toast.LENGTH_LONG).show()
            }
            return@AddSettingDialog
        }
        val name = ScadSnippets.identifier(label)
        val apply = { text: String -> ScadSnippets.addParameter(text, label, mm, value, min, max, step) }
        if (current == "code" && !builder) codeEdit = { v -> apply(v.text).let { (t, at, _) -> TextFieldValue(t, TextRange(at)) } }
        else vm.scad.edit(apply(s.source).first)
        android.widget.Toast.makeText(context, context.getString(R.string.scad_setting_added, name), android.widget.Toast.LENGTH_LONG).show()
    }
}

/** "Preview" header: live preview switch and show / hide. */
@Composable
private fun PreviewBar(s: ScadEditorState, vm: AppViewModel, open: Boolean, toggle: Boolean = true, onToggle: () -> Unit) {
    Row(Modifier.fillMaxWidth().then(if (toggle) Modifier.clickable(onClick = onToggle) else Modifier).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (toggle) Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
            stringResource(if (open) R.string.scad_preview_hide else R.string.scad_preview_show),
            tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.scad_preview), style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(start = 8.dp).weight(1f))
        Text(stringResource(R.string.scad_auto_render), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Switch(s.autoRender, vm.scad::setAutoRender, Modifier.padding(start = 8.dp).scale(0.8f))
    }
}

@Composable
private fun Header(s: ScadEditorState, vm: AppViewModel, onHide: (() -> Unit)?, onOpen: () -> Unit, onSave: () -> Unit, onNew: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.Code, null, tint = MaterialTheme.colorScheme.primary)
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            Text(if (s.shapes != null) stringResource(R.string.shape_builder) else "OpenSCAD", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(s.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onNew) { Icon(Icons.Default.Add, stringResource(R.string.scad_new)) }
        IconButton(onClick = onOpen) { Icon(Icons.Default.FolderOpen, stringResource(R.string.scad_open)) }
        IconButton(onClick = onSave) { Icon(Icons.Default.Save, stringResource(R.string.scad_save)) }
        if (onHide != null) IconButton(onClick = onHide) { Icon(Icons.Default.VerticalAlignBottom, stringResource(R.string.scad_hide_editor)) }
        IconButton(onClick = vm.scad::close) { Icon(Icons.Default.Close, stringResource(R.string.scad_close)) }
    }
}

// --- Code ----------------------------------------------------------------------------------------

/** Above generated code: where it comes from, and the way out to plain code. */
@Composable
private fun GeneratedNotice(onDetach: () -> Unit) {
    var confirm by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.secondaryContainer).padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.shape_generated), style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f))
        TextButton(onClick = { confirm = true }) { Text(stringResource(R.string.shape_edit_as_code)) }
    }
    if (confirm) androidx.compose.material3.AlertDialog(
        onDismissRequest = { confirm = false },
        title = { Text(stringResource(R.string.shape_edit_as_code)) },
        text = { Text(stringResource(R.string.shape_edit_as_code_desc)) },
        confirmButton = { TextButton(onClick = { confirm = false; onDetach() }) { Text(stringResource(R.string.shape_edit_as_code)) } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(android.R.string.cancel)) } },
    )
}

@Composable
private fun CodeEditor(
    s: ScadEditorState, vm: AppViewModel, jump: Int?, onJumped: () -> Unit,
    edit: ((TextFieldValue) -> TextFieldValue)?, onEdited: () -> Unit, readOnly: Boolean = false,
) {
    // Generated code follows the design; typed code only changes on a new load.
    var value by remember(s.loadId, if (readOnly) s.source else null) { mutableStateOf(TextFieldValue(s.source)) }
    val colors = scadColors()
    val highlight = remember(colors) { ScadHighlight(colors) }
    val vScroll = rememberScrollState()
    val focus = remember { FocusRequester() }
    val lineHeight = with(LocalDensity.current) { CODE_STYLE.lineHeight.toPx() }
    val lines = remember(value.text) { value.text.count { it == '\n' } + 1 }
    val textColor = MaterialTheme.colorScheme.onSurface

    LaunchedEffect(jump) {
        val line = jump ?: return@LaunchedEffect
        var at = 0
        repeat(line - 1) { at = value.text.indexOf('\n', at).let { i -> if (i < 0) value.text.length else i + 1 } }
        value = value.copy(selection = TextRange(at.coerceAtMost(value.text.length)))
        vScroll.animateScrollTo(((line - 4) * lineHeight).roundToInt().coerceAtLeast(0))
        runCatching { focus.requestFocus() }
        onJumped()
    }

    fun change(next: TextFieldValue) {
        val v = autoIndent(value, next)
        value = v
        vm.scad.edit(v.text)
    }

    LaunchedEffect(edit) {
        val f = edit ?: return@LaunchedEffect
        val v = f(value)
        value = v
        vm.scad.edit(v.text)
        onEdited()
        // Show the inserted lines.
        val line = v.text.take(v.selection.start).count { it == '\n' }
        vScroll.animateScrollTo(((line - 6) * lineHeight).roundToInt().coerceAtLeast(0))
        runCatching { focus.requestFocus() }
    }

    Row(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLowest).verticalScroll(vScroll)) {
        // Line numbers: one Text with the same style, so the rows line up with the code.
        Text(
            (1..lines).joinToString("\n"),
            style = CODE_STYLE.copy(color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f), textAlign = TextAlign.End),
            modifier = Modifier.background(MaterialTheme.colorScheme.surfaceContainer).padding(start = 8.dp, end = 8.dp, top = 8.dp, bottom = 48.dp)
                .width(with(LocalDensity.current) { (lines.toString().length * 8.5f).sp.toDp() }.coerceAtLeast(20.dp)),
        )
        // No soft wrap: the horizontally scrolling box gives the field unbounded width.
        Box(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
            BasicTextField(
                value = value,
                onValueChange = ::change,
                readOnly = readOnly,
                textStyle = CODE_STYLE.copy(color = textColor),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                visualTransformation = highlight,
                modifier = Modifier.padding(start = 10.dp, end = 24.dp, top = 8.dp, bottom = 48.dp).heightIn(min = 200.dp)
                    .focusRequester(focus)
                    .onPreviewKeyEvent { e ->
                        // Tab indents (4 spaces) instead of moving the focus; Shift+Tab is left alone.
                        if (e.key == Key.Tab && !e.isShiftPressed && !e.isCtrlPressed) {
                            if (e.type == KeyEventType.KeyDown) {
                                val t = value.text.replaceRange(value.selection.min, value.selection.max, "    ")
                                change(TextFieldValue(t, TextRange(value.selection.min + 4)))
                            }
                            true
                        } else false
                    },
            )
        }
    }
}

/** Enter keeps the line's indentation, one level more after an opening bracket. */
private fun autoIndent(old: TextFieldValue, new: TextFieldValue): TextFieldValue {
    if (new.text.length != old.text.length + 1 || !new.selection.collapsed) return new
    val at = new.selection.start - 1
    if (at < 0 || new.text[at] != '\n' || new.text.removeRange(at, at + 1) != old.text) return new
    val lineStart = new.text.lastIndexOf('\n', at - 1) + 1
    val line = new.text.substring(lineStart, at)
    val indent = line.takeWhile { it == ' ' || it == '\t' } + if (line.trimEnd().lastOrNull() in OPENERS) "    " else ""
    if (indent.isEmpty()) return new
    return TextFieldValue(new.text.substring(0, at + 1) + indent + new.text.substring(at + 1), TextRange(at + 1 + indent.length))
}

private val OPENERS = setOf('{', '(', '[')

private data class ScadColors(
    val comment: SpanStyle, val string: SpanStyle, val number: SpanStyle,
    val keyword: SpanStyle, val builtin: SpanStyle, val special: SpanStyle,
)

@Composable
private fun scadColors(): ScadColors = if (appIsDark()) ScadColors(
    comment = SpanStyle(color = Color(0xFF8B95A3), fontStyle = FontStyle.Italic),
    string = SpanStyle(color = Color(0xFF8BD99B)),
    number = SpanStyle(color = Color(0xFFF5B971)),
    keyword = SpanStyle(color = Color(0xFFC4A7FF), fontWeight = FontWeight.SemiBold),
    builtin = SpanStyle(color = Color(0xFF7CC4FF)),
    special = SpanStyle(color = Color(0xFFF48FB1)),
) else ScadColors(
    comment = SpanStyle(color = Color(0xFF6B7280), fontStyle = FontStyle.Italic),
    string = SpanStyle(color = Color(0xFF15803D)),
    number = SpanStyle(color = Color(0xFFB45309)),
    keyword = SpanStyle(color = Color(0xFF7C3AED), fontWeight = FontWeight.SemiBold),
    builtin = SpanStyle(color = Color(0xFF0B6BCB)),
    special = SpanStyle(color = Color(0xFFBE185D)),
)

/** OpenSCAD syntax colours; the text itself is unchanged, so offsets map one to one. */
private class ScadHighlight(private val c: ScadColors) : VisualTransformation {
    private var lastText: AnnotatedString? = null
    private var lastResult: TransformedText? = null

    override fun filter(text: AnnotatedString): TransformedText {
        lastResult?.takeIf { lastText == text }?.let { return it }
        return TransformedText(highlight(text.text), OffsetMapping.Identity).also { lastText = text; lastResult = it }
    }

    private fun highlight(t: String): AnnotatedString = buildAnnotatedString {
        append(t)
        val n = t.length
        var i = 0
        var lastWord = ""
        while (i < n) {
            val ch = t[i]
            when {
                ch == '/' && i + 1 < n && t[i + 1] == '/' -> {
                    val e = t.indexOf('\n', i).let { if (it < 0) n else it }
                    addStyle(c.comment, i, e); i = e
                }
                ch == '/' && i + 1 < n && t[i + 1] == '*' -> {
                    val e = t.indexOf("*/", i + 2).let { if (it < 0) n else it + 2 }
                    addStyle(c.comment, i, e); i = e
                }
                ch == '"' -> {
                    var j = i + 1
                    while (j < n && t[j] != '"' && t[j] != '\n') { if (t[j] == '\\') j++; j++ }
                    val e = minOf(n, j + 1)
                    addStyle(c.string, i, e); i = e
                }
                ch == '<' && (lastWord == "include" || lastWord == "use") -> {
                    val e = t.indexOf('>', i).let { if (it < 0) n else it + 1 }
                    addStyle(c.string, i, e); i = e; lastWord = ""
                }
                ch.isDigit() || (ch == '.' && i + 1 < n && t[i + 1].isDigit()) -> {
                    var j = i + 1
                    while (j < n && (t[j].isDigit() || t[j] == '.' || t[j] == 'e' || t[j] == 'E' ||
                            ((t[j] == '-' || t[j] == '+') && (t[j - 1] == 'e' || t[j - 1] == 'E')))) j++
                    addStyle(c.number, i, j); i = j
                }
                ch.isLetter() || ch == '_' || ch == '$' -> {
                    var j = i + 1
                    while (j < n && (t[j].isLetterOrDigit() || t[j] == '_')) j++
                    val w = t.substring(i, j)
                    when {
                        w in KEYWORDS -> addStyle(c.keyword, i, j)
                        w in BUILTINS -> addStyle(c.builtin, i, j)
                        ch == '$' -> addStyle(c.special, i, j)
                    }
                    lastWord = w; i = j
                }
                else -> { if (!ch.isWhitespace()) lastWord = ""; i++ }
            }
        }
    }

    companion object {
        val KEYWORDS = setOf("module", "function", "if", "else", "for", "let", "each", "include", "use", "true", "false",
            "undef", "assert", "echo", "intersection_for", "assign", "PI")
        val BUILTINS = setOf("cube", "sphere", "cylinder", "polyhedron", "square", "circle", "polygon", "text", "import",
            "surface", "translate", "rotate", "scale", "mirror", "multmatrix", "color", "offset", "hull", "minkowski",
            "union", "difference", "intersection", "linear_extrude", "rotate_extrude", "projection", "render",
            "children", "resize", "roof", "abs", "sign", "sin", "cos", "tan", "asin", "acos", "atan", "atan2", "floor",
            "round", "ceil", "ln", "log", "pow", "sqrt", "exp", "min", "max", "norm", "cross", "len", "concat",
            "lookup", "str", "chr", "ord", "search", "rands", "is_undef", "is_num", "is_bool", "is_string", "is_list",
            "is_function", "version", "version_num", "parent_module")
    }
}

// --- Customizer ----------------------------------------------------------------------------------

@Composable
private fun Customizer(s: ScadEditorState, vm: AppViewModel, onAddSetting: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (s.parseError) Notice(stringResource(R.string.scad_parse_error), error = true)
        if (s.params.isEmpty()) {
            // Shape designs get sliders from the Shapes tab, not from code annotations.
            Notice(stringResource(if (s.shapes != null) R.string.shape_dims_hint else R.string.scad_no_params), error = false)
            TextButton(onClick = onAddSetting) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scad_add_setting))
            }
            return@Column
        }
        val general = stringResource(R.string.scad_parameters)
        s.params.groupBy { it.group }.forEach { (group, params) ->
            var open by rememberSaveable(group) { mutableStateOf(true) }
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(group.ifEmpty { general }, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.weight(1f))
                Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (open) params.forEach { p ->
                Param(p, s.value(p), p.name in s.values, onSlide = { vm.scad.setValue(p, it, dragging = true) }, onSlideEnd = vm.scad::endDrag) {
                    vm.scad.setValue(p, it)
                }
            }
        }
        Row {
            TextButton(onClick = onAddSetting) {
                Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scad_add_setting))
            }
            if (s.values.isNotEmpty())
                TextButton(onClick = vm.scad::resetValues) {
                    Icon(Icons.Default.RestartAlt, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.scad_reset_all))
                }
        }
    }
}

@Composable
private fun Notice(text: String, error: Boolean) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Text(text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp),
            color = if (error) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun Param(p: ScadParam, value: Any, modified: Boolean, onSlide: (Any) -> Unit = {}, onSlideEnd: () -> Unit = {}, onChange: (Any) -> Unit) {
    val label = p.caption.ifEmpty { p.name.replace('_', ' ') }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (p.kind != ScadParam.Kind.BOOLEAN && p.kind != ScadParam.Kind.CHOICE) Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
            if (modified) ModifiedDot()
        }
        when (p.kind) {
            ScadParam.Kind.NUMBER -> {
                val v = (value as Double).toFloat()
                val min = p.min; val max = p.max
                if (min != null && max != null && max > min) Row(verticalAlignment = Alignment.CenterVertically) {
                    val step = (p.step ?: 1.0).takeIf { it > 0 } ?: 1.0
                    val count = ((max - min) / step).roundToInt()
                    Slider(
                        value = v.coerceIn(min.toFloat(), max.toFloat()),
                        onValueChange = { f -> onSlide(snap(f.toDouble(), min, step)) },
                        onValueChangeFinished = onSlideEnd,
                        valueRange = min.toFloat()..max.toFloat(),
                        steps = if (count in 2..200) count - 1 else 0,
                        // Many steps would draw a solid row of tick dots; show ticks only when they're few.
                        colors = if (count > 20) SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
                            else SliderDefaults.colors(),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(12.dp))
                    NumberField(v, { onChange(it.toDouble().coerceIn(min, max)) }, Modifier.width(96.dp), decimals = p.decimals)
                } else NumberField(v, { onChange(it.toDouble()) }, Modifier.fillMaxWidth(), decimals = p.decimals)
            }
            ScadParam.Kind.VECTOR -> {
                @Suppress("UNCHECKED_CAST") val list = value as List<Double>
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEachIndexed { i, d ->
                        NumberField(d.toFloat(), { f ->
                            val x = f.toDouble().let { x -> p.min?.let { x.coerceAtLeast(it) } ?: x }.let { x -> p.max?.let { x.coerceAtMost(it) } ?: x }
                            onChange(list.toMutableList().also { it[i] = x })
                        }, Modifier.weight(1f), label = "${i + 1}", decimals = p.decimals)
                    }
                }
            }
            ScadParam.Kind.BOOLEAN -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f, fill = false))
                if (modified) ModifiedDot()
                Spacer(Modifier.weight(1f))
                Switch(value as Boolean, { onChange(it) })
            }
            ScadParam.Kind.STRING -> OutlinedTextField(
                value = value as String,
                onValueChange = { t -> onChange(p.maxLength?.let { t.take(it) } ?: t) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            ScadParam.Kind.CHOICE -> PickerField(
                label = label,
                selected = value.toString(),
                items = p.options.map { (name, v) -> PickerItem(v.toString(), name) },
                onSelect = { id -> p.options.firstOrNull { it.second.toString() == id }?.let { onChange(it.second) } },
                modified = modified,
            )
        }
    }
}

private fun snap(v: Double, min: Double, step: Double): Double {
    val s = min + ((v - min) / step).roundToInt() * step
    // Round away float noise (0.30000000000000004).
    return java.math.BigDecimal.valueOf(s).setScale(8, java.math.RoundingMode.HALF_UP).toDouble()
}

@Composable
private fun ModifiedDot() {
    Box(Modifier.padding(start = 6.dp).size(6.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape))
}

// --- Console and actions ---------------------------------------------------------------------------

@Composable
private fun Console(s: ScadEditorState, onLine: (Int) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val hasLog = s.log.isNotBlank()
    val errorLog = s.log.lineSequence().any { it.startsWith("ERROR") }
    LaunchedEffect(s.log) { if (errorLog) open = true }
    Row(
        Modifier.fillMaxWidth().clickable(enabled = hasLog) { open = !open }.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            s.rendering || s.previewing -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            s.statusError -> Icon(Icons.Default.ErrorOutline, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            s.status != null -> Icon(Icons.Outlined.CheckCircle, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
        }
        Text(
            s.status ?: stringResource(R.string.scad_console),
            style = MaterialTheme.typography.bodySmall,
            color = if (s.statusError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = if (s.rendering || s.previewing || s.status != null) 10.dp else 0.dp),
        )
        if (hasLog) Icon(if (open) Icons.Default.ExpandMore else Icons.Default.ExpandLess, stringResource(R.string.scad_console),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (open && hasLog) {
        val error = MaterialTheme.colorScheme.error
        val warning = MaterialTheme.colorScheme.tertiary
        Column(
            Modifier.fillMaxWidth().heightIn(max = 160.dp).background(MaterialTheme.colorScheme.surfaceContainerLowest)
                .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            SelectionContainer {
                Column {
                    s.log.trimEnd().lineSequence().forEach { line ->
                        val ref = LINE_REF.find(line)?.groupValues?.get(1)?.toIntOrNull()
                        Text(
                            line,
                            style = CODE_STYLE.copy(fontSize = 12.sp, lineHeight = 17.sp),
                            color = when {
                                line.startsWith("ERROR") -> error
                                line.startsWith("WARNING") -> warning
                                else -> MaterialTheme.colorScheme.onSurface
                            },
                            modifier = if (ref != null) Modifier.clickable { onLine(ref) } else Modifier,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun Actions(s: ScadEditorState, vm: AppViewModel) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp, top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (s.rendering || s.previewing) {
            OutlinedButton(onClick = vm.scad::stop, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Stop, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scad_stop))
            }
        } else {
            Button(onClick = { vm.scad.render() }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Text(stringResource(if (s.onPlate != null) R.string.scad_update else R.string.scad_render), maxLines = 1)
            }
            if (s.onPlate != null) OutlinedButton(onClick = { vm.scad.render(asNew = true) }) {
                Text(stringResource(R.string.scad_add_copy), maxLines = 1)
            }
        }
    }
}

/** Over the plate while the design shows on it: back to the design view (eye toggle). */
@Composable
fun ScadPlateToggle(onPlate: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.material3.FilledTonalIconButton(onClick = onClick, modifier = modifier) {
        Icon(if (onPlate) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            stringResource(if (onPlate) R.string.scad_off_plate else R.string.scad_on_plate))
    }
}

/** Phones: brings the editor back over the 3D view. */
@Composable
fun ScadReopenButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Button(onClick = onClick, modifier = modifier) {
        Icon(Icons.Default.Code, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text("OpenSCAD")
    }
}
