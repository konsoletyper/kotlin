/*
 * Copyright 2010-2016 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.jetbrains.kotlin.js.ir

fun JsirFunction.prettyPrint(): String {
    val writer = SourceWriter()
    val params = "(" + parameters.asSequence().map { writer.getVariable(it) }.joinToString(" ") + ")"
    writer.block("def ${declaration.name} $params") {
        body.prettyPrint(writer)
    }
    return writer.toString()
}

fun JsirStatement.prettyPrint() = SourceWriter().apply { prettyPrint(this) }.toString()

fun JsirExpression.prettyPrint() = SourceWriter().apply { prettyPrint(this) }.toString()

private fun JsirStatement.prettyPrint(writer: SourceWriter): Unit = when (this) {
    is JsirStatement.Assignment -> {
        val left = this.left
        if (left != null) {
            writer.block("=") {
                left.prettyPrint(writer)
                right.prettyPrint(writer)
            }
        }
        else {
            writer.block("eval") {
                right.prettyPrint(writer)
            }
        }
    }
    is JsirStatement.Block -> {
        writer.block("block") {
            writer.constant("(label ${writer.getLabel(this)})")
            body.prettyPrint(writer)
        }
    }
    is JsirStatement.Break -> {
        writer.constant("(break ${writer.getLabel(target)})")
    }
    is JsirStatement.Continue -> {
        writer.constant("(continue ${writer.getLabel(target)})")
    }
    is JsirStatement.Throw -> {
        writer.block("throw") {
            exception.prettyPrint(writer)
        }
    }
    is JsirStatement.Return -> {
        val value = this.value
        if (value != null) {
            writer.block("return-value") {
                value.prettyPrint(writer)
                writer.constant(target.name.toString())
            }
        }
        else {
            writer.block("return-nothing") {
                writer.constant(target.name.toString())
            }
        }
    }
    is JsirStatement.If -> {
        writer.block("if") {
            condition.prettyPrint(writer)
            writer.block("then") {
                thenBody.prettyPrint(writer)
            }
            writer.block("else") {
                elseBody.prettyPrint(writer)
            }
        }
    }
    is JsirStatement.While -> {
        writer.block("while") {
            writer.constant("(label ${writer.getLabel(this)})")
            condition.prettyPrint(writer)
            writer.block("repeat") {
                body.prettyPrint(writer)
            }
        }
    }
    is JsirStatement.DoWhile -> {
        writer.block("do-while") {
            writer.constant("(label ${writer.getLabel(this)})")
            condition.prettyPrint(writer)
            writer.block("repeat") {
                body.prettyPrint(writer)
            }
        }
    }
    is JsirStatement.For -> {
        writer.block("for") {
            writer.constant("(label ${writer.getLabel(this)})")
            writer.block("init") {
                preAssignments.prettyPrint(writer)
            }
            writer.block("while") {
                condition.prettyPrint(writer)
            }
            writer.block("repeat") {
                body.prettyPrint(writer)
            }
            writer.block("increment") {
                postAssignments.prettyPrint(writer)
            }
        }
    }
    is JsirStatement.Switch -> {
        writer.block("switch") {
            writer.constant("(label ${writer.getLabel(this)})")
            clauses.forEach { clause ->
                writer.block("case") {
                    writer.block("when") {
                        clause.value.prettyPrint(writer)
                    }
                    writer.block("then") {
                        clause.body.prettyPrint(writer)
                    }
                }
            }
            writer.block("default") {
                defaultClause.prettyPrint(writer)
            }
        }
    }
    is JsirStatement.Try -> {
        writer.block("try") {
            writer.block("protect") {
                body.prettyPrint(writer)
            }
            catchClauses.forEach { clause ->
                writer.block("catch") {
                    writer.block("when") {
                        writer.constant(writer.getVariable(clause.catchVariable))
                        writer.constant(clause.exceptionType?.name?.toString() ?: "any")
                    }
                    writer.block("then") {
                        clause.body.prettyPrint(writer)
                    }
                }
            }
            writer.block("finally") {
                finallyClause.prettyPrint(writer)
            }
        }
    }
}

private fun List<JsirStatement>.prettyPrint(writer: SourceWriter) = forEach { it.prettyPrint(writer) }

private fun JsirExpression.prettyPrint(writer: SourceWriter): Unit = when (this) {
    is JsirExpression.Binary -> {
        writer.block(operation.getSymbol()) {
            left.prettyPrint(writer)
            right.prettyPrint(writer)
        }
    }
    is JsirExpression.Conditional -> {
        writer.block("cond") {
            condition.prettyPrint(writer)
            writer.block("then") {
                thenExpression.prettyPrint(writer)
            }
            writer.block("else") {
                elseExpression.prettyPrint(writer)
            }
        }
    }
    is JsirExpression.Negation -> {
        writer.block("-unary") {
            operand.prettyPrint(writer)
        }
    }
    is JsirExpression.NewInstance -> {
        writer.block("new") {
            writer.constant(constructor.name.toString())
            arguments.forEach { it.prettyPrint(writer) }
        }
    }
    is JsirExpression.Invocation -> {
        val receiver = this.receiver
        writer.block(if (virtual) "invoke-virtual" else if (receiver != null) "invoke-special" else "invoke-static") {
            writer.constant(function.name.toString())
            receiver?.prettyPrint(writer)
            arguments.forEach { it.prettyPrint(writer) }
        }
    }
    is JsirExpression.FieldAccess -> {
        val operation = if (receiver != null) "get" else "get-static"
        val fieldString = field.prettyPrint()
        writer.block("$operation $fieldString") {
            receiver?.prettyPrint(writer)
        }
    }
    is JsirExpression.ArrayOf -> {
        writer.block("array-of") {
            elements.forEach { it.prettyPrint(writer) }
        }
    }
    is JsirExpression.Concat -> {
        writer.block("concat") {
            parts.forEach { it.prettyPrint(writer) }
        }
    }
    is JsirExpression.ToString -> {
        writer.block("to-string") {
            value.prettyPrint(writer)
        }
    }

    is JsirExpression.Constant -> writer.constant(value.toString())
    is JsirExpression.Null -> writer.constant("null")
    is JsirExpression.This -> writer.constant("this")
    is JsirExpression.True -> writer.constant("true")
    is JsirExpression.False -> writer.constant("false")
    is JsirExpression.VariableReference -> writer.constant("(var ${writer.getVariable(variable)})")
}

private fun JsirField.prettyPrint() = when (this) {
    is JsirField.Backing -> property.name.toString()
    is JsirField.OuterClass -> "\$outer"
}

private fun JsirBinaryOperation.getSymbol() = when (this) {
    JsirBinaryOperation.ADD -> "+"
    JsirBinaryOperation.SUB -> "-"
    JsirBinaryOperation.MUL -> "*"
    JsirBinaryOperation.DIV -> "/"
    JsirBinaryOperation.REM -> "%"
    JsirBinaryOperation.AND -> "&&"
    JsirBinaryOperation.OR -> "||"
    JsirBinaryOperation.BIT_AND -> "&"
    JsirBinaryOperation.BIT_OR -> "|"
    JsirBinaryOperation.BIT_XOR -> "^"
    JsirBinaryOperation.SHL -> "<<"
    JsirBinaryOperation.LSHR -> ">>>"
    JsirBinaryOperation.ASHR -> ">>"
    JsirBinaryOperation.EQ -> "=="
    JsirBinaryOperation.NE -> "!="
    JsirBinaryOperation.GT -> ">"
    JsirBinaryOperation.GOE -> ">="
    JsirBinaryOperation.LT -> "<"
    JsirBinaryOperation.LOE -> "<="
    JsirBinaryOperation.REF_EQ -> "==="
    JsirBinaryOperation.REF_NE -> "!=="
}

private class SourceWriter {
    private val sb = StringBuilder()
    private var indentLevel = 0
    private val labelMap = mutableMapOf<JsirLabeled, String>()
    private val usedLabels = mutableSetOf<String>()
    private val variableMap = mutableMapOf<JsirVariable, String>()
    private val usedVariables = mutableSetOf<String>()

    inline fun block(name: String, body: () -> Unit) {
        sb.append("\n")
        writeIndent()
        sb.append("(").append(name)
        try {
            ++indentLevel
            body()
        }
        finally {
            --indentLevel
            sb.append(")")
        }
    }

    fun constant(value: String) {
        sb.append("\n")
        writeIndent()
        sb.append(value)
    }

    fun getLabel(labeled: JsirLabeled) = labelMap.getOrPut(labeled) {
        val labelBase = labeled.suggestedLabelName ?: "\$label"
        var index = 0
        var label = labelBase
        while (!usedLabels.add(label)) {
            label = "$labelBase ${index++}"
        }
        label
    }

    fun getVariable(variable: JsirVariable) = variableMap.getOrPut(variable) {
        val variableNameBase = variable.suggestedName ?: "\$var"
        var index = 0
        var variableName = variableNameBase
        while (!usedVariables.add(variableName)) {
            variableName = "$variableNameBase ${index++}"
        }
        variableName
    }

    override fun toString() = sb.toString()

    private fun writeIndent() = (0..indentLevel).forEach { sb.append("  ") }
}
