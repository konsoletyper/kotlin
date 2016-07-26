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

package org.jetbrains.kotlin.js.ir.generate

import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.psi.KtExpression

fun JsirContext.memoize(expression: JsirExpression) = when (expression) {
    is JsirExpression.This,
    is JsirExpression.VariableReference,
    is JsirExpression.Undefined,
    is JsirExpression.Constant -> expression

    else -> {
        val temporary = newTemporary()
        assign(temporary.makeReference(), expression)
        temporary.makeReference()
    }
}

fun JsirContext.memoize(expression: KtExpression) = withSource(expression) { memoize(generate(expression)) }

fun JsirExpression.nullCheck() = when (this) {
    is JsirExpression.This,
    is JsirExpression.Undefined,
    is JsirExpression.FunctionReference -> JsirExpression.Constant(false)
    is JsirExpression.Constant -> JsirExpression.Constant(when (value) {
        null -> true
        else -> false
    })

    else -> JsirExpression.Binary(JsirBinaryOperation.REF_EQ, JsirType.ANY, this, JsirExpression.Constant(null))
}

fun JsirContext.assign(left: JsirExpression, right: JsirExpression) {
    append(JsirStatement.Assignment(left, right))
}

fun JsirContext.append(expression: JsirExpression) {
    when (expression) {
        is JsirExpression.Undefined,
        is JsirExpression.Constant,
        is JsirExpression.This,
        is JsirExpression.FunctionReference,
        is JsirExpression.VariableReference -> {}
        else -> append(JsirStatement.Assignment(null, expression))
    }
}

fun JsirExpression.negate(): JsirExpression = when (this) {
    is JsirExpression.Unary -> when (operation) {
        JsirUnaryOperation.NEGATION -> operand
        else -> negateDefault()
    }
    is JsirExpression.Constant -> {
        val value = this.value
        when (value) {
            is Boolean -> JsirExpression.Constant(!value)
            else -> negateDefault()
        }
    }
    is JsirExpression.Binary -> {
        val negatedOperation = operation.negate()
        if (negatedOperation != null) {
            JsirExpression.Binary(negatedOperation, type, left, right)
        }
        else {
            negateDefault()
        }
    }
    else -> negateDefault()
}

private fun JsirExpression.negateDefault() = JsirExpression.Unary(JsirUnaryOperation.NEGATION, JsirType.ANY, this)

fun JsirBinaryOperation.negate(): JsirBinaryOperation? = when (this) {
    JsirBinaryOperation.REF_EQ -> JsirBinaryOperation.REF_NE
    JsirBinaryOperation.REF_NE -> JsirBinaryOperation.REF_EQ
    JsirBinaryOperation.EQ -> JsirBinaryOperation.NE
    JsirBinaryOperation.NE -> JsirBinaryOperation.EQ
    JsirBinaryOperation.GT -> JsirBinaryOperation.LOE
    JsirBinaryOperation.GOE -> JsirBinaryOperation.LT
    JsirBinaryOperation.LT -> JsirBinaryOperation.GOE
    JsirBinaryOperation.LOE -> JsirBinaryOperation.GT
    else -> null
}

fun JsirContext.requireNonNull(value: JsirExpression): JsirExpression {
    val memoized = memoize(value)
    val statement = JsirStatement.If(memoized.nullCheck())
    append(statement)
    nestedBlock(statement.thenBody) {
        append(JsirStatement.Throw(JsirExpression.NewNullPointerExpression()))
    }
    return memoized
}

fun JsirContext.newTemporary() = variableContainer!!.createVariable(true)