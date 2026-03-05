package com.termux.artifactcalc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Backspace
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CalculatorApp() }
    }
}

@Composable
private fun CalculatorApp() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFFF6F7FB)) {
            val background = Brush.verticalGradient(listOf(Color(0xFFE9F3FF), Color(0xFFF9F9FC)))
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(background)
                    .padding(16.dp)
            ) {
                CalculatorScreen()
            }
        }
    }
}

@Composable
private fun CalculatorScreen() {
    var expression by rememberSaveable { mutableStateOf("") }
    var result by rememberSaveable { mutableStateOf("0") }
    var status by rememberSaveable { mutableStateOf("Ready") }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp),
            shape = RoundedCornerShape(28.dp),
            color = Color.White
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Compose M3 Calculator",
                    color = Color(0xFF62708A),
                    fontSize = 14.sp
                )
                Text(
                    text = if (expression.isBlank()) "0" else expression,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = Color(0xFF43506B),
                    fontSize = 24.sp
                )
                Text(
                    text = result,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = Color(0xFF0F172A),
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = status,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.End,
                    color = Color(0xFF7C8EA8),
                    fontSize = 13.sp
                )
            }
        }

        val rows = listOf(
            listOf("C", "(", ")", "⌫"),
            listOf("7", "8", "9", "÷"),
            listOf("4", "5", "6", "×"),
            listOf("1", "2", "3", "-"),
            listOf("0", ".", "=", "+")
        )

        rows.forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                row.forEach { token ->
                    CalcButton(
                        modifier = Modifier.weight(1f),
                        token = token,
                        onClick = {
                            when (token) {
                                "C" -> {
                                    expression = ""
                                    result = "0"
                                    status = "Cleared"
                                }
                                "⌫" -> {
                                    expression = expression.dropLast(1)
                                    if (expression.isBlank()) {
                                        result = "0"
                                        status = "Ready"
                                    }
                                }
                                "=" -> {
                                    if (expression.isBlank()) return@CalcButton
                                    runCatching {
                                        val value = ExpressionParser(expression).parse()
                                        result = prettyNumber(value)
                                        status = "Computed"
                                    }.onFailure {
                                        status = "Invalid expression"
                                    }
                                }
                                else -> {
                                    expression += token
                                    runCatching {
                                        val value = ExpressionParser(expression).parse()
                                        result = prettyNumber(value)
                                        status = "Live preview"
                                    }.onFailure {
                                        status = "Typing..."
                                    }
                                }
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun CalcButton(modifier: Modifier, token: String, onClick: () -> Unit) {
    val isOp = token in setOf("+", "-", "×", "÷")
    val isEq = token == "="
    val container = when {
        isEq -> Color(0xFF1D4ED8)
        isOp -> Color(0xFFDBEAFE)
        token == "C" -> Color(0xFFFEE2E2)
        else -> Color.White
    }
    val content = when {
        isEq -> Color.White
        isOp -> Color(0xFF1E3A8A)
        token == "C" -> Color(0xFF991B1B)
        else -> Color(0xFF1F2937)
    }

    Button(
        modifier = modifier.height(64.dp),
        onClick = onClick,
        shape = RoundedCornerShape(18.dp),
        contentPadding = PaddingValues(0.dp),
        colors = ButtonDefaults.buttonColors(containerColor = container, contentColor = content)
    ) {
        if (token == "⌫") {
            androidx.compose.material3.Icon(
                imageVector = Icons.Rounded.Backspace,
                contentDescription = "Backspace",
                modifier = Modifier.size(22.dp)
            )
        } else {
            Text(token, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

private fun prettyNumber(v: Double): String {
    val roundedInt = v.toLong()
    if (abs(v - roundedInt.toDouble()) < 1e-10) return roundedInt.toString()
    return "%.8f".format(v).trimEnd('0').trimEnd('.')
}

private class ExpressionParser(rawInput: String) {
    private val text = rawInput.replace("×", "*").replace("÷", "/").replace(" ", "")
    private var index = 0

    fun parse(): Double {
        if (text.isBlank()) return 0.0
        val value = parseExpression()
        if (index != text.length) throw IllegalArgumentException("Unexpected char at $index")
        return value
    }

    private fun parseExpression(): Double {
        var value = parseTerm()
        while (index < text.length) {
            val op = text[index]
            if (op != '+' && op != '-') break
            index++
            val right = parseTerm()
            value = if (op == '+') value + right else value - right
        }
        return value
    }

    private fun parseTerm(): Double {
        var value = parseFactor()
        while (index < text.length) {
            val op = text[index]
            if (op != '*' && op != '/') break
            index++
            val right = parseFactor()
            value = if (op == '*') value * right else value / right
        }
        return value
    }

    private fun parseFactor(): Double {
        if (index >= text.length) throw IllegalArgumentException("Unexpected end")

        val c = text[index]
        if (c == '+') {
            index++
            return parseFactor()
        }
        if (c == '-') {
            index++
            return -parseFactor()
        }
        if (c == '(') {
            index++
            val value = parseExpression()
            if (index >= text.length || text[index] != ')') {
                throw IllegalArgumentException("Missing )")
            }
            index++
            return value
        }
        return parseNumber()
    }

    private fun parseNumber(): Double {
        val start = index
        var hasDot = false
        while (index < text.length) {
            val c = text[index]
            if (c == '.') {
                if (hasDot) break
                hasDot = true
                index++
                continue
            }
            if (c !in '0'..'9') break
            index++
        }
        if (start == index) throw IllegalArgumentException("Expected number at $index")
        return text.substring(start, index).toDouble()
    }
}
