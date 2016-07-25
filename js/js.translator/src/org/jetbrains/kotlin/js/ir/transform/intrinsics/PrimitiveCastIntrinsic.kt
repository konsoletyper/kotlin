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
import org.jetbrains.kotlin.js.ir.JsirExpression
import org.jetbrains.kotlin.js.ir.JsirType
import org.jetbrains.kotlin.js.ir.getPrimitiveType
import org.jetbrains.kotlin.js.ir.receiverClass
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameSafe

class PrimitiveCastIntrinsic : Intrinsic {
    override fun isApplicable(descriptor: FunctionDescriptor, asStatement: Boolean): Boolean {
        if (extractSourceAndTarget(descriptor) == null) return false

        return when (descriptor.name.asString()) {
            "toBoolean",
            "toByte",
            "toChar",
            "toShort",
            "toInt",
            "toLong",
            "toFloat",
            "toDouble" -> true
            else -> false
        }
    }

    override fun apply(invocation: JsirExpression.Invocation): JsirExpression {
        val (source, target) = extractSourceAndTarget(invocation.function)!!
        return JsirExpression.PrimitiveCast(invocation.receiver!!, source, target)
    }

    override fun applyAsStatement(invocation: JsirExpression.Invocation) = null

    private fun extractSourceAndTarget(descriptor: FunctionDescriptor): Pair<JsirType, JsirType>? {
        val receiverType = descriptor.receiverClass?.fqNameSafe?.asString() ?: return null
        val source = getPrimitiveType(receiverType)
        if (source == JsirType.ANY) return null

        val returnType = descriptor.returnType?.constructor?.declarationDescriptor?.fqNameSafe?.asString() ?: return null
        val target = getPrimitiveType(returnType)
        if (target == JsirType.ANY) return null

        return Pair(source, target)
    }
}
