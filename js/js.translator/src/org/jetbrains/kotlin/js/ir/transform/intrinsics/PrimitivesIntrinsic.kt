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

import org.jetbrains.kotlin.descriptors.DeclarationDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.ir.*
import org.jetbrains.kotlin.js.ir.generate.negate
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class PrimitivesIntrinsic : Intrinsic {
    override fun isApplicable(descriptor: FunctionDescriptor, asStatement: Boolean): Boolean {
        if (asStatement) return false

        val className = getClass(descriptor).fqNameSafe.asString()
        return getType(className) != null && isApplicableToFunction(descriptor.name.asString())
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
        "not",
        "compareTo",
        "unaryMinus" -> true
        else -> false
    }

    override fun apply(invocation: JsirExpression.Invocation): JsirExpression {
        val functionName = invocation.function.name.asString()
        val type = getType(getClass(invocation.function).fqNameSafe.asString()) ?: return invocation
        val receiver = invocation.receiver!!

        return when (functionName) {
            "inc" -> JsirExpression.Binary(JsirBinaryOperation.ADD, type, receiver, JsirExpression.Constant(1))
            "dec" -> JsirExpression.Binary(JsirBinaryOperation.SUB, type, receiver, JsirExpression.Constant(1))
            "not" -> invocation.receiver!!.negate()
            "unaryMinus" -> JsirExpression.Unary(JsirUnaryOperation.MINUS, type, receiver)
            else -> {
                operation(functionName)?.let {
                    JsirExpression.Binary(it, type, receiver, invocation.arguments[0])
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
        "compareTo" -> JsirBinaryOperation.COMPARE
        else -> null
    }

    override fun applyAsStatement(invocation: JsirExpression.Invocation) = null

    private fun getType(className: String) = when (className) {
        "kotlin.String" -> JsirType.ANY
        else -> {
            val result = getPrimitiveType(className)
            if (result != JsirType.ANY) result else null
        }
    }

    private fun getClass(function: FunctionDescriptor): DeclarationDescriptor {
        return function.extensionReceiverParameter?.containingDeclaration ?: function.containingDeclaration
    }
}