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

package org.jetbrains.kotlin.js.ir.transform.optimize

import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.ir.generate.negate
import kotlin.reflect.KClass

class CompareToElimination : Optimization {
    override fun apply(parameters: List<JsirVariable>, body: MutableList<JsirStatement>): List<KClass<out Optimization>> {
        body.replace { expression -> replaceCompareTo(expression) ?: expression }
        return emptyList()
    }

    private fun replaceCompareTo(expression: JsirExpression): JsirExpression? {
        if (expression !is JsirExpression.Binary) return null

        val operation = expression.operation
        val negated = expression.operation.negate() ?: return null

        return when {
            expression.left.isComparison() && expression.right.isZero() -> {
                val comparison = expression.left as JsirExpression.Binary
                JsirExpression.Binary(operation, expression.type, comparison.left, comparison.right)
            }
            expression.right.isComparison() && expression.left.isZero() -> {
                val comparison = expression.left as JsirExpression.Binary
                JsirExpression.Binary(negated, expression.type, comparison.left, comparison.right)
            }
            else -> null
        }
    }

    private fun JsirExpression.isComparison() = when (this) {
        is JsirExpression.Binary -> operation == JsirBinaryOperation.COMPARE
        else -> false
    }

    private fun JsirExpression.isZero() = when (this) {
        is JsirExpression.Constant -> {
            val value = this.value
            when (value) {
                is Byte -> value.toInt() == 0
                is Short -> value.toInt() == 0
                is Char -> value.toInt() == 0
                is Int -> value == 0
                is Long -> value == 0L
                is Float -> value == 0F
                is Double -> value == 0.0
                else -> false
            }
        }
        else -> false
    }
}
