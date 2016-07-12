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

package org.jetbrains.kotlin.js.ir.intrinsics

import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.JsirBinaryOperation
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.translate.negate
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class PrimitivesIntrinsic : Intrinsic {
    override fun isApplicable(descriptor: FunctionDescriptor, asStatement: Boolean): Boolean {
        if (asStatement) return false

        val cls = descriptor.extensionReceiverParameter?.containingDeclaration ?: descriptor.containingDeclaration
        val className = cls.fqNameSafe.asString()
        return isApplicableToClass(className) && isApplicableToFunction(descriptor.name.asString())
    }

    private fun isApplicableToClass(className: String) = when (className) {
        "kotlin.Boolean",
        "kotlin.Byte",
        "kotlin.Short",
        "kotlin.Int",
        "kotlin.Long",
        "kotlin.Float",
        "kotlin.Double",
        "kotlin.Character",
        "kotlin.String" -> true
        else -> false
    }

    private fun isApplicableToFunction(functionName: String) = when(functionName) {
        "or",
        "and",
        "xor",
        "shr",
        "ushr",
        "shl",
        "plus",
        "minus",
        "times",
        "div",
        "mod",
        "inc",
        "dec",
        "equals",
        "not" -> true
        else -> false
    }


    override fun apply(invocation: JsirExpression.Invocation): JsirExpression {
        val functionName = invocation.function.name.asString()
        return when (functionName) {
            "inc" -> JsirExpression.Binary(JsirBinaryOperation.ADD, invocation.receiver!!, JsirExpression.Constant(1))
            "dec" -> JsirExpression.Binary(JsirBinaryOperation.SUB, invocation.receiver!!, JsirExpression.Constant(1))
            "not" -> invocation.receiver!!.negate()
            else -> {
                operation(functionName)?.let {
                    JsirExpression.Binary(it, invocation.receiver!!, invocation.arguments[0])
                } ?: invocation
            }
        }
    }

    private fun operation(functionName: String) = when (functionName) {
        "and" -> JsirBinaryOperation.BIT_AND
        "or" -> JsirBinaryOperation.BIT_OR
        "xor" -> JsirBinaryOperation.BIT_XOR
        "shl" -> JsirBinaryOperation.SHL
        "shr" -> JsirBinaryOperation.ASHR
        "ushr" -> JsirBinaryOperation.LSHR
        "plus" -> JsirBinaryOperation.ADD
        "minus" -> JsirBinaryOperation.SUB
        "times" -> JsirBinaryOperation.MUL
        "div" -> JsirBinaryOperation.DIV
        "mod" -> JsirBinaryOperation.REM
        "equals" -> JsirBinaryOperation.REF_EQ
        else -> null
    }

    override fun applyAsStatement(invocation: JsirExpression.Invocation) = null
}