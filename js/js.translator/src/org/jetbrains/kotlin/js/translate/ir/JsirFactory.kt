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

package org.jetbrains.kotlin.js.translate.ir

import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.psi.KtExpression

fun JsirContext.memoize(expression: JsirExpression) = when (expression) {
    is JsirExpression.Null,
    is JsirExpression.This,
    is JsirExpression.VariableReference,
    is JsirExpression.Constant -> expression

    else -> {
        val temporary = JsirVariable()
        assign(temporary.makeReference(), expression)
        temporary.makeReference()
    }
}

fun JsirContext.memoize(expression: KtExpression) = memoize(generate(expression))

fun JsirExpression.nullCheck() = when (this) {
    is JsirExpression.Null -> JsirExpression.True

    is JsirExpression.This,
    is JsirExpression.True,
    is JsirExpression.False,
    is JsirExpression.Constant -> JsirExpression.False

    else -> JsirExpression.Binary(JsirBinaryOperation.REF_EQ, this, JsirExpression.Null)
}

fun JsirContext.assign(left: JsirExpression, right: JsirExpression) {
    append(JsirStatement.Assignment(left, right))
}

fun JsirContext.append(expression: JsirExpression) {
    when (expression) {
        is JsirExpression.Null,
        is JsirExpression.True,
        is JsirExpression.False,
        is JsirExpression.Constant,
        is JsirExpression.VariableReference -> {}
        else -> append(JsirStatement.Assignment(null, expression))
    }
}

fun JsirExpression.negate(): JsirExpression = when (this) {
    is JsirExpression.Negation -> operand
    is JsirExpression.True -> JsirExpression.False
    is JsirExpression.False -> JsirExpression.True
    is JsirExpression.Binary -> {
        val negatedOperation = operation.negate()
        if (negatedOperation != null) {
            JsirExpression.Binary(negatedOperation, left, right)
        }
        else {
            JsirExpression.Negation(this)
        }
    }
    else -> JsirExpression.Negation(this)
}

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