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

fun JsirExpression.nullCheck() = when (this) {
    is JsirExpression.Null -> JsirExpression.True

    is JsirExpression.This,
    is JsirExpression.True,
    is JsirExpression.False,
    is JsirExpression.Constant -> JsirExpression.False

    else -> JsirExpression.Binary(JsirBinaryOperation.REF_EQ, this, JsirExpression.Null)
}

fun JsirContext.translateMemoized(expression: KtExpression) = memoize(translate(expression))

fun JsirContext.assign(left: JsirExpression, right: JsirExpression) {
    append(JsirStatement.Assignment(left, right))
}

fun JsirContext.append(expression: JsirExpression) {
    append(JsirStatement.Assignment(null, expression))
}