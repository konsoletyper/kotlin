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
import org.jetbrains.kotlin.js.ir.JsirUnaryOperation
import org.jetbrains.kotlin.resolve.descriptorUtil.fqNameUnsafe

class ArrayOfInstrinsic() : Intrinsic {
    override fun isApplicable(descriptor: FunctionDescriptor, asStatement: Boolean): Boolean {
        if (asStatement) return false

        return descriptor.fqNameUnsafe.asString() == "kotlin.arrayOf"
    }

    override fun apply(invocation: JsirExpression.Invocation): JsirExpression {
        val argument = invocation.arguments[0]
        return when (argument) {
            is JsirExpression.ArrayOf -> argument
            else -> JsirExpression.Unary(JsirUnaryOperation.ARRAY_COPY, JsirType.ANY, argument)
        }
    }

    override fun applyAsStatement(invocation: JsirExpression.Invocation) = null
}
