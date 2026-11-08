package com.duolauncher.ui

import android.os.Build
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView

/**
 * 搜索框控制器。
 *
 * 为什么不用 Compose 的 `BasicTextField`：
 * `BasicTextField` 的输入连接挂在整块 `AndroidComposeView` 上，它"关闭输入"时只会
 * 停掉自己的 TextInputSession，**从不调用 `InputMethodManager.hide`**。所以在部分
 * 输入法（例如全键盘类的大脚输入法）上，键盘窗口虽然不见了，输入法会话仍然活着，
 * 屏幕底部那条「输入状态 / 双拼 / 麦克风」工具栏就一直赖在桌面上——之前换着花样
 * 调 `clearFocus` / `view.clearFocus` / `hide()` 都治不好，就是这个原因。
 *
 * 换成真实 `EditText` 之后，`clearFocus()` 会让 ViewRootImpl 认为窗口里已经没有
 * 可输入控件，框架自己就会 `finishInput` 并把输入法窗口（连同工具栏）一起收掉；
 * 再叠一次 `hideSoftInputFromWindow` 只是兜底，同一个事件不会播两遍动画。
 */
class DuoFieldController {
    private var editRef: EditText? = null

    internal fun attach(e: EditText?) {
        editRef = e
    }

    /** 结束输入：先失焦（框架据此收键盘），再显式 hide 兜底。可重复调用，无副作用。 */
    fun dismissInput() {
        val e = editRef ?: return
        runCatching { e.clearFocus() }
        runCatching {
            (e.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                as InputMethodManager).hideSoftInputFromWindow(e.windowToken, 0)
        }
    }

    /** 重新进入输入态并弹出键盘 */
    fun requestInput() {
        val e = editRef ?: return
        runCatching {
            e.requestFocus()
            e.post {
                runCatching {
                    (e.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                        as InputMethodManager).showSoftInput(e, InputMethodManager.SHOW_IMPLICIT)
                }
            }
        }
    }
}

/**
 * 磨砂玻璃风格的搜索输入框（真实 EditText 实现）。
 *
 * - [value] 是唯一数据源；外部改动（比如点 × 清空）会同步进 EditText，输入也会回调出去，
 *   双向同步做了比较，不会来回抖动、不会把光标顶到开头。
 * - 聚焦/失焦通过 [onFocusChange] 上报，调用方据此决定是否退回"折叠态"。
 */
@Composable
fun DuoSearchField(
    controller: DuoFieldController,
    value: String,
    textColor: Color,
    hintColor: Color,
    cursorColor: Color,
    hint: String = "",
    autoFocus: Boolean = false,
    onValueChange: (String) -> Unit,
    onFocusChange: (Boolean) -> Unit = {},
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier
) {
    // 回调会被 factory 捕获，用 rememberUpdatedState 保证永远是最新的闭包
    val onValue by rememberUpdatedState(onValueChange)
    val onFocus by rememberUpdatedState(onFocusChange)
    val submit by rememberUpdatedState(onSubmit)
    val hintNow by rememberUpdatedState(hint)
    val valueNow by rememberUpdatedState(value)
    val colorNow by rememberUpdatedState(textColor)
    val hintColorNow by rememberUpdatedState(hintColor)
    val cursorNow by rememberUpdatedState(cursorColor)
    // autoFocus 只在**本次挂载**里生效一次：否则每次重绘都会把焦点抢回来，
    // 用户想关掉键盘就永远关不掉了
    var autoDone by remember { mutableStateOf(false) }

    AndroidView(
        modifier = modifier.fillMaxWidth(),
        factory = { c ->
            EditText(c).apply {
                background = null
                setPadding(0, 0, 0, 0)
                minimumHeight = 0
                includeFontPadding = false
                isSingleLine = true
                gravity = Gravity.CENTER_VERTICAL or Gravity.START
                textSize = 15f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                // NO_EXTRACT_UI：全屏输入模式会把启动器整页顶走，这里一律禁止
                imeOptions = EditorInfo.IME_ACTION_SEARCH or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                setTextColor(colorNow.toArgb())
                setHintTextColor(hintColorNow.toArgb())
                runCatching {
                    if (Build.VERSION.SDK_INT >= 29) {
                        textCursorDrawable = textCursorDrawable?.apply { setTint(cursorNow.toArgb()) }
                    }
                }
                setOnFocusChangeListener { _, has -> onFocus(has) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun afterTextChanged(s: Editable?) {
                        val t = s?.toString().orEmpty()
                        // 由 update 同步进来的文本不要再回调出去，避免循环
                        if (t != valueNow) onValue(t)
                    }
                })
                setOnEditorActionListener { _, _, _ -> submit(); true }
            }.also { controller.attach(it) }
        },
        update = { et ->
            controller.attach(et)
            if (et.text?.toString() != value) {
                et.setText(value)
                et.setSelection(value.length.coerceAtMost(et.text?.length ?: 0))
            }
            if (et.hint?.toString() != hintNow) et.hint = hintNow
            et.setTextColor(textColor.toArgb())
            et.setHintTextColor(hintColor.toArgb())
            runCatching {
                if (Build.VERSION.SDK_INT >= 29) {
                    et.textCursorDrawable = et.textCursorDrawable?.apply { setTint(cursorColor.toArgb()) }
                }
            }
            if (autoFocus && !autoDone) {
                autoDone = true
                et.requestFocus()
                et.post {
                    runCatching {
                        (et.context.getSystemService(android.content.Context.INPUT_METHOD_SERVICE)
                            as InputMethodManager).showSoftInput(et, InputMethodManager.SHOW_IMPLICIT)
                    }
                }
            }
        }
    )

    // 输入框从组合里摘掉时解绑，避免控制器持有已经销毁的 View
    DisposableEffect(controller) {
        onDispose { controller.attach(null) }
    }
}
