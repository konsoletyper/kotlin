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
import org.jetbrains.kotlin.js.ir.JsirBinaryOperation
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirStatement
import org.jetbrains.kotlin.js.ir.JsirType
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class ArraysIntrinsic : Intrinsic {
    override fun isApplicable(descriptor: FunctionDescriptor, asStatement: Boolean): Boolean {
        val cls = descriptor.extensionReceiverParameter?.containingDeclaration ?: descriptor.containingDeclaration
        val className = cls.fqNameSafe.asString()
        if (!isApplicableToClass(className)) return false

        val functionName = descriptor.name.asString()
        return if (asStatement) functionName == "set" else isApplicableToFunction(functionName)
    }

    private fun isApplicableToClass(className: String) = when (className) {
        "kotlin.Array",
        "kotlin.BooleanArray",
        "kotlin.ByteArray",
        "kotlin.ShortArray",
        "kotlin.IntArray",
        "kotlin.LongArray",
        "kotlin.FloatArray",
        "kotlin.DoubleArray" -> true
        else -> false
    }

    private fun isApplicableToFunction(functionName: String) = when(functionName) {
        "get",
        "size" -> true
        else -> false
    }

    override fun apply(invocation: JsirExpression.Invocation) = when (invocation.function.name.asString()) {
        "get" -> JsirExpression.Binary(JsirBinaryOperation.ARRAY_GET, JsirType.ANY, invocation.receiver!!, invocation.arguments[0])
        "size" -> JsirExpression.ArrayLength(invocation.receiver!!)
        else -> invocation
    }

    override fun applyAsStatement(invocation: JsirExpression.Invocation) = when (invocation.function.name.asString()) {
        "set" -> {
            val lhs = JsirExpression.Binary(JsirBinaryOperation.ARRAY_GET, JsirType.ANY, invocation.receiver!!, invocation.arguments[0])
            JsirStatement.Assignment(lhs, invocation.arguments[1])
        }
        else -> null
    }
}