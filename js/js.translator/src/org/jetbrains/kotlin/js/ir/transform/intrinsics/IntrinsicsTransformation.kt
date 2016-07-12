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

package org.jetbrains.kotlin.js.ir.transform.intrinsics

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.ir.transform.Transformation

class IntrinsicsTransformation : Transformation {
    private val intrinsics = listOf(
            PrimitivesIntrinsic(),
            ArraysIntrinsic(),
            InvokeIntrinsic()
    )
    private val intrinsicCache = mutableMapOf<Pair<FunctionDescriptor, Boolean>, Intrinsic?>()

    override fun apply(function: JsirFunction) {
        function.body.replace(object : JsirMapper {
            override fun map(statement: JsirStatement, canChangeType: Boolean) = when (statement) {
                is JsirStatement.Assignment -> {
                    val right = statement.right
                    if (statement.left == null && right is JsirExpression.Invocation) {
                        getIntrinsic(right.function, true)?.applyAsStatement(right) ?: statement
                    }
                    else {
                        statement
                    }
                }
                else -> statement
            }

            override fun map(expression: JsirExpression) = when (expression) {
                is JsirExpression.Invocation -> tryApplyIntrinsic(expression)
                else -> expression
            }
        })
    }

    private fun tryApplyIntrinsic(invocation: JsirExpression.Invocation): JsirExpression {
        return getIntrinsic(invocation.function, false)?.apply(invocation) ?: invocation
    }

    private fun getIntrinsic(function: FunctionDescriptor, asStatement: Boolean) = intrinsicCache.getOrPut(Pair(function, asStatement)) {
        intrinsics.firstOrNull { it.isApplicable(function, asStatement) }
    }
}
